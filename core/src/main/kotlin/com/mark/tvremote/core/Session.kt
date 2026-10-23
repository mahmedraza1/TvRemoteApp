package com.mark.tvremote.core

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

const val PAIRING_PORT = 6467
const val REMOTE_PORT = 6466

/** The client's TLS identity: a factory presenting its certificate, and that certificate itself. */
class ClientIdentity(val socketFactory: SSLSocketFactory, val certificate: X509Certificate)

class PairingException(message: String) : IOException(message)

private fun openTls(host: String, port: Int, identity: ClientIdentity): SSLSocket {
    val socket = identity.socketFactory.createSocket() as SSLSocket
    try {
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.enabledProtocols = arrayOf("TLSv1.2")
        socket.soTimeout = 8_000 // bound the handshake; callers set their own read timeout after
        socket.startHandshake()
    } catch (e: IOException) {
        socket.close()
        throw e
    }
    return socket
}

private fun InputStream.readByteOrThrow(): Int {
    val b = read()
    if (b < 0) throw EOFException("Connection closed by TV")
    return b
}

/** Messages on both channels are length-prefixed with a protobuf varint. */
private fun readFrame(input: InputStream): ByteArray {
    var length = 0
    var shift = 0
    while (true) {
        val b = input.readByteOrThrow()
        length = length or ((b and 0x7F) shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw IOException("Corrupt frame length")
    }
    val data = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(data, read, length - read)
        if (n < 0) throw EOFException("Connection closed by TV")
        read += n
    }
    return data
}

private fun frame(payload: ByteArray): ByteArray {
    val length = java.io.ByteArrayOutputStream()
    var v = payload.size
    while (v and 0x7F.inv() != 0) {
        length.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    length.write(v)
    return length.toByteArray() + payload
}

/**
 * Pairing with the TV (port 6466). The TV shows a 6-character hex code; [finish] proves the
 * client knows it. Both steps block, so call them off the main thread.
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String,
) : Closeable {
    private var socket: SSLSocket? = null

    private fun send(payload: ByteArray) {
        val s = socket ?: throw PairingException("Not connected")
        s.outputStream.write(frame(payload))
        s.outputStream.flush()
    }

    private fun receive(expectedField: Int): ProtoMessage {
        val s = socket ?: throw PairingException("Not connected")
        val msg = ProtoMessage.parse(readFrame(s.inputStream))
        if (!Polo.isOk(msg)) throw PairingException("TV rejected pairing (status ${msg.int(2)})")
        if (!msg.has(expectedField)) throw PairingException("Unexpected reply from TV")
        return msg
    }

    /** Connects and runs the handshake up to the point where the TV displays the code. */
    fun start() {
        val s = openTls(host, PAIRING_PORT, identity)
        s.soTimeout = 10_000
        socket = s
        send(Polo.pairingRequest(clientName))
        receive(Polo.FIELD_PAIRING_REQUEST_ACK)
        send(Polo.options())
        receive(Polo.FIELD_OPTIONS)
        send(Polo.configuration())
        receive(Polo.FIELD_CONFIGURATION_ACK)
    }

    /** Submits the code shown on the TV. Throws [PairingException] if it is wrong. */
    fun finish(code: String) {
        val s = socket ?: throw PairingException("Pairing has not started")
        val clean = code.trim().uppercase()
        if (clean.length != 6 || !clean.all { it in "0123456789ABCDEF" }) {
            throw PairingException("The code is 6 characters (0-9, A-F)")
        }
        val serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        val clientKey = identity.certificate.publicKey as RSAPublicKey

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientKey.modulus))
        digest.update(unsigned(clientKey.publicExponent))
        digest.update(unsigned(serverKey.modulus))
        digest.update(unsigned(serverKey.publicExponent))
        digest.update(hexToBytes(clean.substring(2)))
        val secret = digest.digest()

        if (secret[0] != clean.substring(0, 2).toInt(16).toByte()) {
            throw PairingException("Wrong code")
        }
        send(Polo.secret(secret))
        try {
            receive(Polo.FIELD_SECRET_ACK)
        } catch (e: PairingException) {
            throw e // the TV explicitly rejected the code
        } catch (e: IOException) {
            // The TV can close the connection right after accepting the code, which on some
            // networks loses the final acknowledgement. Connecting afterwards confirms the result.
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun unsigned(n: java.math.BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Callbacks from a [RemoteSession]. They arrive on the session's reader thread. */
interface RemoteListener {
    /** The TLS handshake with the TV succeeded (the TV may still reject us afterwards). */
    fun onConnected() {}

    /** The TV completed its handshake and is accepting keys. */
    fun onReady(model: String, vendor: String) {}

    /** The connection ended; [error] is null for a normal [RemoteSession.close]. */
    fun onClosed(error: Exception?) {}

    /** The TV opened a text field (true) so typed text will be accepted. */
    fun onTextFieldChanged(focused: Boolean) {}

    fun onCurrentApp(packageName: String) {}
}

/** Control connection to a paired TV (port 6467). */
class RemoteSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val listener: RemoteListener,
) : Closeable {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var imeCounter = 0
    @Volatile private var fieldCounter = 0
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-remote-writer").apply { isDaemon = true } }

    /** Connects and starts the reader thread. Blocks only for the TLS handshake. */
    fun connect() {
        val s = openTls(host, REMOTE_PORT, identity)
        // The TV pings every few seconds, so silence for this long means the connection is dead.
        s.soTimeout = 20_000
        socket = s
        listener.onConnected()
        Thread({ readLoop(s) }, "tv-remote-reader").apply { isDaemon = true }.start()
    }

    fun sendKey(keyCode: Int, direction: Int = Direction.SHORT) = write(RemoteMsg.keyInject(keyCode, direction))

    /** Types text into the focused field; the TV must have a text field open. */
    fun sendText(text: String) {
        if (text.isNotEmpty()) write(RemoteMsg.text(text, imeCounter, fieldCounter))
    }

    val imeCounterForDebug get() = imeCounter
    val fieldCounterForDebug get() = fieldCounter

    /** Sends an already encoded RemoteMessage (used for protocol experiments). */
    fun sendRaw(payload: ByteArray) = write(payload)

    fun launchApp(link: String) = write(RemoteMsg.appLink(link))

    private fun write(payload: ByteArray) {
        if (closed) return
        writer.execute {
            try {
                val s = socket ?: return@execute
                synchronized(s) {
                    s.outputStream.write(frame(payload))
                    s.outputStream.flush()
                }
            } catch (e: IOException) {
                fail(e)
            }
        }
    }

    private fun readLoop(s: SSLSocket) {
        try {
            val input = s.inputStream
            while (!closed) {
                handle(ProtoMessage.parse(readFrame(input)))
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Optional hook that sees every message from the TV (protocol debugging). */
    @Volatile var debug: ((ProtoMessage) -> Unit)? = null

    private fun handle(msg: ProtoMessage) {
        debug?.invoke(msg)
        when {
            msg.has(RemoteMsg.FIELD_CONFIGURE) -> {
                val cfg = msg.message(RemoteMsg.FIELD_CONFIGURE)!!
                val info = cfg.message(2)
                deviceModel = info?.string(1).orEmpty()
                deviceVendor = info?.string(2).orEmpty()
                activeFeatures = RemoteMsg.FEATURES and cfg.int(1)
                write(RemoteMsg.configure(activeFeatures))
            }
            msg.has(RemoteMsg.FIELD_SET_ACTIVE) -> write(RemoteMsg.setActive(activeFeatures))
            msg.has(RemoteMsg.FIELD_PING_REQUEST) ->
                write(RemoteMsg.pingResponse(msg.message(RemoteMsg.FIELD_PING_REQUEST)!!.int(1)))
            msg.has(RemoteMsg.FIELD_START) -> if (!ready) {
                ready = true
                listener.onReady(deviceModel, deviceVendor)
            }
            msg.has(RemoteMsg.FIELD_IME_KEY_INJECT) -> {
                val inject = msg.message(RemoteMsg.FIELD_IME_KEY_INJECT)!!
                inject.message(1)?.string(12)?.let { listener.onCurrentApp(it) }
                listener.onTextFieldChanged(inject.has(2))
            }
            msg.has(RemoteMsg.FIELD_IME_BATCH_EDIT) -> {
                val edit = msg.message(RemoteMsg.FIELD_IME_BATCH_EDIT)!!
                imeCounter = edit.int(1)
                fieldCounter = edit.int(2)
            }
            msg.has(RemoteMsg.FIELD_IME_SHOW_REQUEST) -> listener.onTextFieldChanged(true)
            msg.has(RemoteMsg.FIELD_ERROR) -> fail(IOException("TV reported an error"))
        }
    }

    private var deviceModel = ""
    private var deviceVendor = ""
    private var activeFeatures = RemoteMsg.FEATURES

    private fun fail(error: Exception) {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        writer.shutdownNow()
        listener.onClosed(error)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        writer.shutdownNow()
        listener.onClosed(null)
    }
}
