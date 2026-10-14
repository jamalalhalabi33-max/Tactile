package com.example.tv

import android.util.Base64
import com.example.storage.TvStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object AndroidTvCertificateHelper {

    suspend fun getOrCreateSslContext(storage: TvStorage): Pair<SSLContext, X509Certificate> {
        val (certB64, keyB64) = storage.getAtvCredentials()
        val keyPair: KeyPair
        val cert: X509Certificate

        if (certB64 != null && keyB64 != null) {
            val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
            val certBytes = Base64.decode(certB64, Base64.DEFAULT)

            val kf = KeyFactory.getInstance("RSA")
            val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            val cf = CertificateFactory.getInstance("X.509")
            cert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            keyPair = KeyPair(cert.publicKey, privKey)
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            keyPair = kpg.generateKeyPair()

            cert = generateSelfSignedCertificate(keyPair)
            storage.saveAtvCredentials(
                certDerB64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP),
                keyDerB64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
            )
        }

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("atv_client", keyPair.private, "tactile".toCharArray(), arrayOf(cert))
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, "tactile".toCharArray())
        }

        // Trust manager that trusts server certificate for Android TV
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }

        return Pair(sslContext, cert)
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        // Construct standard X.509 v3 DER structure
        val tbsCert = createTbsCertificate(keyPair)
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbsCert)
        }
        val sigBytes = sig.sign()

        val certBos = ByteArrayOutputStream()
        certBos.write(0x30) // SEQUENCE
        val contentBos = ByteArrayOutputStream()
        contentBos.write(tbsCert)
        writeSha256WithRsaAlgorithmId(contentBos)
        writeBitString(contentBos, sigBytes)

        val fullBytes = contentBos.toByteArray()
        writeLength(certBos, fullBytes.size)
        certBos.write(fullBytes)

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certBos.toByteArray())) as X509Certificate
    }

    private fun createTbsCertificate(keyPair: KeyPair): ByteArray {
        val bos = ByteArrayOutputStream()
        // Version [0] EXPLICIT INTEGER 2 (v3)
        bos.write(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02))

        // Serial Number: INTEGER
        val serialBytes = BigInteger.valueOf(System.currentTimeMillis()).toByteArray()
        bos.write(0x02)
        writeLength(bos, serialBytes.size)
        bos.write(serialBytes)

        // Signature AlgorithmIdentifier (sha256WithRSAEncryption: 1.2.840.113549.1.1.11)
        writeSha256WithRsaAlgorithmId(bos)

        // Issuer: CN=atvremote
        val nameBytes = createSubjectName("atvremote")
        bos.write(nameBytes)

        // Validity: UTCTime 20240101000000Z to 20400101000000Z
        val validityBos = ByteArrayOutputStream()
        validityBos.write(byteArrayOf(0x17, 0x0D))
        validityBos.write("240101000000Z".toByteArray(Charsets.US_ASCII))
        validityBos.write(byteArrayOf(0x17, 0x0D))
        validityBos.write("400101000000Z".toByteArray(Charsets.US_ASCII))

        bos.write(0x30)
        writeLength(bos, validityBos.size())
        bos.write(validityBos.toByteArray())

        // Subject: same as Issuer
        bos.write(nameBytes)

        // SubjectPublicKeyInfo (directly from keyPair.public.encoded)
        bos.write(keyPair.public.encoded)

        val tbsFull = ByteArrayOutputStream()
        tbsFull.write(0x30)
        writeLength(tbsFull, bos.size())
        tbsFull.write(bos.toByteArray())
        return tbsFull.toByteArray()
    }

    private fun createSubjectName(cn: String): ByteArray {
        val cnBytes = cn.toByteArray(Charsets.UTF_8)
        val rdnSeq = ByteArrayOutputStream()

        // OID 2.5.4.3 (commonName)
        val oidBytes = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        rdnSeq.write(oidBytes)

        // UTF8String
        rdnSeq.write(0x0C)
        writeLength(rdnSeq, cnBytes.size)
        rdnSeq.write(cnBytes)

        val setBos = ByteArrayOutputStream()
        setBos.write(0x30) // SEQUENCE
        writeLength(setBos, rdnSeq.size())
        setBos.write(rdnSeq.toByteArray())

        val setFull = ByteArrayOutputStream()
        setFull.write(0x31) // SET
        writeLength(setFull, setBos.size())
        setFull.write(setBos.toByteArray())

        val nameBos = ByteArrayOutputStream()
        nameBos.write(0x30) // SEQUENCE
        writeLength(nameBos, setFull.size())
        nameBos.write(setFull.toByteArray())
        return nameBos.toByteArray()
    }

    private fun writeSha256WithRsaAlgorithmId(out: ByteArrayOutputStream) {
        // SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
        val seqBytes = byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00
        )
        out.write(0x30)
        writeLength(out, seqBytes.size)
        out.write(seqBytes)
    }

    private fun writeBitString(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(0x03)
        writeLength(out, bytes.size + 1)
        out.write(0x00) // unused bits
        out.write(bytes)
    }

    private fun writeLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 128) {
            out.write(length)
        } else if (length < 256) {
            out.write(0x81)
            out.write(length)
        } else {
            out.write(0x82)
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
        }
    }
}
