package com.mark.tvremote.core

import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/** Developer CLI for exercising the protocol from a PC: `pair`, `keys`. Not shipped in the app. */

private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun loadIdentity(p12: String): ClientIdentity {
    val ks = KeyStore.getInstance("PKCS12")
    File(p12).inputStream().use { ks.load(it, "changeit".toCharArray()) }
    val alias = ks.aliases().nextElement()
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, "changeit".toCharArray())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, arrayOf<TrustManager>(TrustAll), null)
    return ClientIdentity(ctx.socketFactory, ks.getCertificate(alias) as X509Certificate)
}

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "pair" -> {
            // pair <p12> <host> <codeFile>: starts pairing, waits for the code to appear in codeFile.
            val identity = loadIdentity(args[1])
            val codeFile = File(args[3])
            codeFile.delete()
            PairingSession(args[2], identity, "Dev PC").use { session ->
                session.start()
                println("WAITING_FOR_CODE (TV should be showing a code)")
                while (!codeFile.exists() || codeFile.readText().isBlank()) Thread.sleep(300)
                session.finish(codeFile.readText())
                println("PAIRED")
            }
        }
        "keys" -> {
            // keys <p12> <host> <KEYCODE...>: connects and sends the given numeric key codes.
            val identity = loadIdentity(args[1])
            val latch = java.util.concurrent.CountDownLatch(1)
            val session = RemoteSession(args[2], identity, object : RemoteListener {
                override fun onReady(model: String, vendor: String) {
                    println("READY model=$model vendor=$vendor")
                    latch.countDown()
                }
                override fun onClosed(error: Exception?) {
                    println("CLOSED ${error?.javaClass?.simpleName}: ${error?.message}")
                    latch.countDown()
                }
                override fun onCurrentApp(packageName: String) = println("APP $packageName")
                override fun onTextFieldChanged(focused: Boolean) = println("TEXTFIELD $focused")
            })
            session.connect()
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) println("TIMEOUT waiting for READY")
            for (k in args.drop(3)) {
                session.sendKey(k.toInt())
                Thread.sleep(700)
            }
            Thread.sleep(2000)
            session.close()
        }
        "selftest" -> {
            // selftest <host>: TLS handshake + pairing start using a freshly generated SelfSigned identity.
            val generated = SelfSigned.generate()
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            ks.setKeyEntry("c", generated.privateKey, "x".toCharArray(), arrayOf(generated.certificate))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "x".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(TrustAll), null) }
            PairingSession(args[1], ClientIdentity(ctx.socketFactory, generated.certificate), "Self-test").use {
                it.start()
                println("SELFTEST_OK handshake and pairing start accepted")
            }
        }
        "rawvol" -> {
            // rawvol <p12> <host> <fieldNumber> <direction...>: experiment with RemoteMessage volume fields.
            val identity = loadIdentity(args[1])
            val latch = java.util.concurrent.CountDownLatch(1)
            val session = RemoteSession(args[2], identity, object : RemoteListener {
                override fun onReady(model: String, vendor: String) { latch.countDown() }
                override fun onClosed(error: Exception?) { latch.countDown() }
            })
            session.connect()
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val field = args[3].toInt()
            for (d in args.drop(4)) {
                val inner = ProtoWriter()
                d.split(',').forEachIndexed { i, v -> inner.int(i + 1, v.toInt()) } // "a,b" sets field1=a, field2=b
                session.sendRaw(ProtoWriter().message(field, inner).toByteArray())
                Thread.sleep(800)
            }
            Thread.sleep(1000)
            session.close()
        }
        "text" -> {
            // text <p12> <host> <string> [keycode...]: types text via the IME channel, then sends the key codes.
            val identity = loadIdentity(args[1])
            val latch = java.util.concurrent.CountDownLatch(1)
            val session = RemoteSession(args[2], identity, object : RemoteListener {
                override fun onReady(model: String, vendor: String) { latch.countDown() }
                override fun onClosed(error: Exception?) { latch.countDown() }
                override fun onTextFieldChanged(focused: Boolean) = println("TEXTFIELD $focused")
            })
            session.connect()
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(1500)
            session.sendText(args[3])
            Thread.sleep(1500)
            for (k in args.drop(4)) { session.sendKey(k.toInt()); Thread.sleep(800) }
            Thread.sleep(1000)
            session.close()
        }
        "ime" -> {
            // ime <p12> <host> <insert> <start> <end> <value> : send a raw RemoteImeBatchEdit and print what the TV reports.
            val identity = loadIdentity(args[1])
            val latch = java.util.concurrent.CountDownLatch(1)
            val session = RemoteSession(args[2], identity, object : RemoteListener {
                override fun onReady(model: String, vendor: String) { latch.countDown() }
                override fun onClosed(error: Exception?) { latch.countDown() }
            })
            session.debug = { m ->
                val d = m.describe()
                if (!d.startsWith("8:")) println("TV> $d")
            }
            session.connect()
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(2000)
            val edit = ProtoWriter()
                .int(1, args[3].toInt())
                .message(2, ProtoWriter().int(1, args[4].toInt()).int(2, args[5].toInt()).string(3, args.getOrElse(6) { "" }))
            session.sendRaw(
                ProtoWriter().message(21, ProtoWriter().int(1, session.imeCounterForDebug).int(2, session.fieldCounterForDebug).message(3, edit)).toByteArray(),
            )
            Thread.sleep(2000)
            session.close()
        }
        "pairconnect" -> {
            // pairconnect <host> <codeFile>: fresh identity, pair, then connect immediately like the phone app does.
            val generated = SelfSigned.generate()
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            ks.setKeyEntry("c", generated.privateKey, "x".toCharArray(), arrayOf(generated.certificate))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "x".toCharArray()) }
            val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf<TrustManager>(TrustAll), null) }
            val identity = ClientIdentity(ctx.socketFactory, generated.certificate)
            val codeFile = File(args[2])
            codeFile.delete()
            val t0 = System.currentTimeMillis()
            fun at() = "+${System.currentTimeMillis() - t0}ms"
            PairingSession(args[1], identity, "Pair+Connect test").use { session ->
                session.start()
                println("${at()} WAITING_FOR_CODE")
                while (!codeFile.exists() || codeFile.readText().isBlank()) Thread.sleep(200)
                session.finish(codeFile.readText())
                println("${at()} PAIRED")
            }
            val latch = java.util.concurrent.CountDownLatch(1)
            val remote = RemoteSession(args[1], identity, object : RemoteListener {
                override fun onConnected() = println("${at()} TLS connected")
                override fun onReady(model: String, vendor: String) { println("${at()} READY $vendor $model"); latch.countDown() }
                override fun onClosed(error: Exception?) { println("${at()} CLOSED ${error?.javaClass?.simpleName}: ${error?.message}"); latch.countDown() }
            })
            try {
                remote.connect()
            } catch (e: Exception) {
                println("${at()} CONNECT FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
            if (!latch.await(12, java.util.concurrent.TimeUnit.SECONDS)) println("${at()} TIMEOUT: no READY within 12s")
            remote.close()
        }
        else -> println("usage: pair <p12> <host> <codeFile> | keys <p12> <host> <keycode...> | selftest <host> | pairconnect <host> <codeFile>")
    }
}
