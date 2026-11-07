package app.dsh.mobile

import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Guards the session WebView with the pin the user confirmed during pairing.
 *
 * Android reports a self-signed gateway certificate here as SSL_UNTRUSTED, which is the only
 * error this client may proceed past, and only when the leaf matches the pin. Other errors
 * (expired, wrong host, revoked) always cancel. This callback cannot observe connections the
 * platform already trusts, so it is not a full replacement for pinning a native connection.
 */
open class PinnedWebViewClient(private val computer: Computer) : WebViewClient() {
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val expected = computer.certificateSha256
        val bytes = SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate")
        if (expected == null || bytes == null) {
            handler.cancel()
            return
        }
        if (error.primaryError != SslError.SSL_UNTRUSTED) {
            handler.cancel()
            return
        }
        val certificate = try {
            CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        } catch (failure: Exception) {
            handler.cancel()
            return
        }
        if (CertificatePin.matches(expected, certificate)) handler.proceed() else handler.cancel()
    }
}
