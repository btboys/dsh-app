package app.dsh.mobile

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class SessionActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    private fun report(message: String) {
        statusText.text = message
        statusText.visibility = android.view.View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = ComputerStore(this).active() ?: return finish()
        setContentView(R.layout.activity_session)
        findViewById<TextView>(R.id.deviceName).text = computer.name
        statusText = findViewById(R.id.sessionStatus)
        findViewById<android.view.View>(R.id.computerAction).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.navApprovals).setOnClickListener {
            startActivity(Intent(this, ApprovalsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.navFiles).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        findViewById<android.view.View>(R.id.navDiagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        val web = findViewById<WebView>(R.id.sessionWeb)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : PinnedWebViewClient(computer) {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Leaving the paired origin would carry the session cookie somewhere else.
                return !Origins.isAllowedNavigation(computer.origin, request.url.toString())
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    runOnUiThread { report(getString(R.string.session_offline)) }
                }
            }
        }
        val secure = if (computer.origin.startsWith("https://")) "; Secure" else ""
        val token = java.net.URLEncoder.encode(computer.token, "UTF-8")
        CookieManager.getInstance().setCookie(computer.origin, "dsh_app=$token; Path=/; HttpOnly; SameSite=Strict$secure")

        thread {
            val opened = runCatching { GatewayClient(computer).openSession() }
            runOnUiThread {
                if (opened.isFailure) report(getString(R.string.session_offline))
                web.loadUrl(computer.origin)
            }
        }
        startForegroundService(Intent(this, LinkService::class.java))
    }
}
