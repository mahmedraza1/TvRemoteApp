package com.mark.tvremote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mark.tvremote.core.ClientIdentity
import com.mark.tvremote.core.PairingException
import com.mark.tvremote.core.PairingSession
import com.mark.tvremote.core.RemoteListener
import com.mark.tvremote.core.RemoteSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

enum class State { Idle, Connecting, NeedsPairing, StartingPairing, EnterCode, FinishingPairing, Ready }

data class SavedTv(val host: String, val name: String)

/** Owns the connection to the TV and exposes its state to the UI. All state changes happen on the main thread. */
class Controller(private val context: Context) {
    var state by mutableStateOf(State.Idle)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var tvName by mutableStateOf("")
        private set
    var textFieldFocused by mutableStateOf(false)
        private set
    var reconnecting by mutableStateOf(false)
        private set
    val saved = mutableStateListOf<SavedTv>()
    val log = mutableStateListOf<String>()

    private val prefs = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-controller").apply { isDaemon = true } }
    private val identity: ClientIdentity by lazy { Identity.get(context) }

    private var host: String? = null
    private var session: RemoteSession? = null
    private var pairing: PairingSession? = null
    private var generation = 0
    private var pairedRecently = false
    private var retries = 0

    init {
        saved.addAll(loadSaved())
    }

    private fun note(text: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        main.post {
            log.add("$time  $text")
            while (log.size > 12) log.removeAt(0)
        }
    }

    val lastHost: String? get() = prefs.getString("last_host", null)

    fun connect(host: String, name: String? = null) {
        this.host = host
        tvName = name ?: saved.firstOrNull { it.host == host }?.name ?: host
        message = null
        reconnecting = false
        startConnect()
    }

    /** Reconnects to the last used TV, if any (used when the app returns to the foreground). */
    fun resume() {
        val last = lastHost ?: return
        if (state == State.Idle) connect(last)
    }

    fun disconnect(forget: Boolean = false) {
        generation++
        closeSession()
        runCatching { pairing?.close() }
        pairing = null
        textFieldFocused = false
        reconnecting = false
        state = State.Idle
        if (forget) prefs.edit().remove("last_host").apply()
    }

    private fun startConnect() {
        val gen = ++generation
        closeSession()
        state = State.Connecting
        val target = host ?: return
        note("Connecting to $target")
        io.execute {
            try {
                val ready = CountDownLatch(1)
                val listener = object : RemoteListener {
                    override fun onConnected() = note("Secure connection established")

                    override fun onReady(model: String, vendor: String) {
                        note("TV is ready ($vendor $model)")
                        main.post { if (gen == generation) this@Controller.onReady(model, vendor) }
                        ready.countDown()
                    }

                    override fun onClosed(error: Exception?) {
                        if (error != null) note("Connection closed: ${error.javaClass.simpleName}: ${error.message}")
                        ready.countDown()
                        main.post { if (gen == generation) this@Controller.onClosed(error) }
                    }

                    override fun onTextFieldChanged(focused: Boolean) {
                        main.post { if (gen == generation) textFieldFocused = focused }
                    }
                }
                val s = RemoteSession(target, identity, listener)
                session = s
                s.connect()
                if (!ready.await(8, TimeUnit.SECONDS)) {
                    note("No response from the TV within 8 seconds")
                    main.post { if (gen == generation) onTimeout() }
                }
            } catch (e: Exception) {
                note("Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                main.post { if (gen == generation) onConnectFailure(e) }
            }
        }
    }

    private fun onReady(model: String, vendor: String) {
        pairedRecently = false
        retries = 0
        state = State.Ready
        reconnecting = false
        message = null
        val h = host ?: return
        val name = saved.firstOrNull { it.host == h }?.name
            ?: tvName.takeIf { it != h }
            ?: listOf(vendor, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { h }
        tvName = name
        prefs.edit().putString("last_host", h).apply()
        if (saved.none { it.host == h }) {
            saved.add(SavedTv(h, name))
            persistSaved()
        }
    }

    private fun onClosed(error: Exception?) {
        when (state) {
            State.Ready -> {
                // Lost an established connection: try again shortly.
                reconnecting = true
                state = State.Connecting
                main.postDelayed({ if (state == State.Connecting && reconnecting) startConnect() }, 2_000)
            }
            State.Connecting -> when {
                reconnecting -> main.postDelayed({ if (state == State.Connecting && reconnecting) startConnect() }, 3_000)
                retryAfterPairing() -> {}
                else -> state = State.NeedsPairing
            }
            else -> {}
        }
    }

    /** Right after pairing the TV may need a moment before it accepts us, so retry a few times. */
    private fun retryAfterPairing(): Boolean {
        if (!pairedRecently || retries >= 4) return false
        retries++
        note("Retrying ($retries/4)…")
        val gen = generation
        main.postDelayed({ if (gen == generation && state == State.Connecting) startConnect() }, 1_500)
        return true
    }

    private fun onTimeout() {
        if (reconnecting) {
            main.postDelayed({ if (state == State.Connecting && reconnecting) startConnect() }, 3_000)
        } else if (!retryAfterPairing()) {
            fail("The TV did not respond. See the log below.")
        }
    }

    private fun onConnectFailure(e: Exception) {
        // The TV drops unknown clients during the TLS handshake, which means we are not paired yet.
        if (retryAfterPairing()) return
        if (e is SSLException) {
            reconnecting = false
            state = State.NeedsPairing
        } else if (reconnecting) {
            main.postDelayed({ if (state == State.Connecting && reconnecting) startConnect() }, 3_000)
        } else {
            fail("Can't reach the TV (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun fail(text: String) {
        closeSession()
        state = State.Idle
        message = text
    }

    fun startPairing() {
        val target = host ?: return
        val gen = ++generation
        message = null
        state = State.StartingPairing
        io.execute {
            try {
                runCatching { pairing?.close() }
                val p = PairingSession(target, identity, "TV Remote (${Build.MODEL})")
                pairing = p
                p.start()
                note("Pairing started, waiting for the code")
                main.post { if (gen == generation) state = State.EnterCode }
            } catch (e: Exception) {
                note("Pairing failed to start: ${e.javaClass.simpleName}: ${e.message}")
                main.post {
                    if (gen == generation) {
                        state = State.NeedsPairing
                        message = "Couldn't start pairing: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    fun submitCode(code: String) {
        val p = pairing ?: return
        val gen = generation
        message = null
        state = State.FinishingPairing
        io.execute {
            try {
                p.finish(code)
                p.close()
                pairing = null
                note("Code sent; connecting")
                main.post {
                    if (gen == generation) {
                        pairedRecently = true
                        retries = 0
                        state = State.Connecting
                        main.postDelayed({ if (gen == generation && state == State.Connecting) startConnect() }, 1_500)
                    }
                }
            } catch (e: PairingException) {
                main.post {
                    if (gen == generation) {
                        // Wrong code: the TV may have closed the session, so start over.
                        note("Pairing rejected: ${e.message}")
                        message = e.message
                        if (e.message?.startsWith("The code is") == true) state = State.EnterCode
                        else { state = State.NeedsPairing }
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (gen == generation) {
                        state = State.NeedsPairing
                        message = "Pairing failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
        }
    }

    fun forget(tv: SavedTv) {
        saved.remove(tv)
        persistSaved()
        if (lastHost == tv.host) prefs.edit().remove("last_host").apply()
    }

    fun key(code: Int) {
        session?.sendKey(code)
    }

    fun text(text: String) {
        session?.sendText(text)
    }

    private fun closeSession() {
        val s = session
        session = null
        if (s != null) io.execute { runCatching { s.close() } }
    }

    private fun loadSaved(): List<SavedTv> =
        prefs.getString("saved", "").orEmpty().lines().mapNotNull {
            val parts = it.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank()) SavedTv(parts[0], parts[1]) else null
        }

    private fun persistSaved() {
        prefs.edit().putString("saved", saved.joinToString("\n") { "${it.host}\t${it.name}" }).apply()
    }
}
