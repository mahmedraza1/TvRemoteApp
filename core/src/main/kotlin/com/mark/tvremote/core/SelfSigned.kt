package com.mark.tvremote.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Generates the client's RSA key and a self-signed X.509 certificate using only the JDK/Android
 * platform APIs (there is no public certificate builder on Android). The TV only needs a
 * well-formed certificate; trust comes from the pairing code.
 */
object SelfSigned {
    class Result(val privateKey: PrivateKey, val certificate: X509Certificate)

    fun generate(commonName: String = "atvremote"): Result {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val sha256WithRsa = der(0x30, oid("1.2.840.113549.1.1.11"), byteArrayOf(0x05, 0x00))
        val name = der(0x30, der(0x31, der(0x30, oid("2.5.4.3"), der(0x0C, commonName.toByteArray()))))
        val now = System.currentTimeMillis()
        val validity = der(0x30, utcTime(now - 24 * 3600_000L), utcTime(now + 3650L * 24 * 3600_000L))

        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))), // version: v3
            der(0x02, BigInteger.valueOf(1000).toByteArray()),
            sha256WithRsa,
            name,
            validity,
            name,
            keys.public.encoded, // SubjectPublicKeyInfo
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(tbs)
            sign()
        }
        val certificate = der(0x30, tbs, sha256WithRsa, der(0x03, byteArrayOf(0) + signature))

        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        return Result(keys.private, parsed)
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            body.size < 0x80 -> out.write(body.size)
            body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
            else -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xFF) }
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val chunks = ArrayList<Int>()
            var v = p
            do { chunks.add(0, (v and 0x7F).toInt()); v = v shr 7 } while (v > 0)
            for (i in chunks.indices) out.write(chunks[i] or if (i < chunks.size - 1) 0x80 else 0)
        }
        return der(0x06, out.toByteArray())
    }

    private fun utcTime(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(0x17, format.format(Date(millis)).toByteArray())
    }
}
