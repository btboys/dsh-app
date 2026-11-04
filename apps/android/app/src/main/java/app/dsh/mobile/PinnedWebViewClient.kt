package app.dsh.mobile

import android.net.http.SslCertificate
import android.net.http.SslError
import android.util.Base64
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedWebViewClient(private val computer: Computer) : WebViewClient() {
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val expected = computer.certificateSha256
        val bytes = SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate")
        if (expected == null || bytes == null) {
            handler.cancel()
            return
        }
        val parsed = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        val encoded = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(parsed.encoded), Base64.NO_WRAP)
        if (encoded == expected) handler.proceed() else handler.cancel()
    }
}
