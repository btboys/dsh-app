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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = ComputerStore(this).active() ?: return finish()
        setContentView(R.layout.activity_session)
        findViewById<TextView>(R.id.deviceName).text = computer.name
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
                return !request.url.toString().startsWith(computer.origin)
            }
        }
        val secure = if (computer.origin.startsWith("https://")) "; Secure" else ""
        CookieManager.getInstance().setCookie(computer.origin, "dsh_app=${computer.token}; HttpOnly; SameSite=Strict$secure")

        thread {
            runCatching { GatewayClient(computer).openSession() }
            runOnUiThread { web.loadUrl(computer.origin) }
        }
        startForegroundService(Intent(this, LinkService::class.java))
    }
}
