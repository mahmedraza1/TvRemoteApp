package com.mark.tvremote

import android.content.Context
import com.mark.tvremote.core.ClientIdentity
import com.mark.tvremote.core.SelfSigned
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The phone's TLS client identity. The TV remembers this certificate after pairing, so it is
 * created once and kept in the app's private storage. (A hardware Keystore key is not used:
 * the system TLS stack could not sign the handshake with it on some phones.)
 */
object Identity {
    private const val KEY_FILE = "client_key.p8"
    private const val CERT_FILE = "client_cert.der"
    private const val ALIAS = "client"

    // The TV presents a self-signed certificate. Trust comes from the pairing code, which is
    // bound to both certificates, not from the certificate chain.
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun get(context: Context): ClientIdentity {
        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)
        if (!keyFile.exists() || !certFile.exists()) {
            val generated = SelfSigned.generate()
            keyFile.writeBytes(generated.privateKey.encoded)
            certFile.writeBytes(generated.certificate.encoded)
        }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
        return build(key, cert)
    }

    private fun build(key: PrivateKey, cert: X509Certificate): ClientIdentity {
        // Always offer our certificate, whatever issuers the TV lists.
        val keyManager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: javax.net.ssl.SSLEngine?) = ALIAS
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?): String? = null
        }
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keyManager), arrayOf<TrustManager>(TrustAll), null)
        return ClientIdentity(context.socketFactory, cert)
    }
}
