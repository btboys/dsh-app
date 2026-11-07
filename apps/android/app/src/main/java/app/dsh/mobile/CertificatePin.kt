package app.dsh.mobile

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for one paired computer.
 *
 * A pin is the base64 SHA-256 of the leaf certificate's DER encoding, the same value the
 * computer shows next to its pairing code. A pinned connection accepts exactly that leaf and
 * nothing else: the pair is trusted because the user compared the fingerprint out of band,
 * not because some certificate authority signed it.
 */
object CertificatePin {
    private const val DIGEST = "SHA-256"
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /** The pin for a certificate as base64 SHA-256 of its DER encoding. */
    fun of(certificate: X509Certificate): String =
        encoder.encodeToString(MessageDigest.getInstance(DIGEST).digest(certificate.encoded))

    /** True when the pin is a SHA-256 digest in the format this app stores. */
    fun isValidFormat(pin: String): Boolean = decode(pin)?.size == 32

    /** Compares two pins without leaking, through timing, where they first differ. */
    fun matches(expected: String, actual: String): Boolean {
        val left = decode(expected) ?: return false
        val right = decode(actual) ?: return false
        return MessageDigest.isEqual(left, right)
    }

    fun matches(expected: String, certificate: X509Certificate): Boolean = matches(expected, of(certificate))

    /**
     * Pins the leaf certificate. Hostname checking is skipped on purpose: the pin identifies
     * the computer, and a LAN address changes without the identity changing.
     */
    fun socketFactory(expected: String): SSLSocketFactory =
        context(expected).socketFactory

    fun trustManager(expected: String): X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            if (!matches(expected, leaf)) throw CertificateException("certificate_mismatch")
        }
    }

    private fun context(expected: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager(expected)), SecureRandom())
    }

    /** Accepts standard base64 and its URL-safe form, so a pasted pin works either way. */
    private fun decode(pin: String): ByteArray? {
        val compact = pin.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        val standard = try {
            decoder.decode(compact)
        } catch (error: IllegalArgumentException) {
            null
        }
        if (standard != null) return standard
        return try {
            urlDecoder.decode(compact)
        } catch (error: IllegalArgumentException) {
            null
        }
    }
}
