package app.dsh.mobile

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class SessionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = ComputerStore(this).active() ?: return finish()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : PinnedWebViewClient(computer) {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return !request.url.toString().startsWith(computer.origin)
                }
            }
        }
        val secure = if (computer.origin.startsWith("https://")) "; Secure" else ""
        CookieManager.getInstance().setCookie(computer.origin, "dsh_app=${computer.token}; HttpOnly; SameSite=Strict$secure")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this)
        bar.addView(action("审批") { startActivity(Intent(this, ApprovalsActivity::class.java)) })
        bar.addView(action("文件") { startActivity(Intent(this, FilesActivity::class.java)) })
        bar.addView(action("诊断") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })
        bar.addView(action("电脑") { finish() })
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        thread {
            runCatching { GatewayClient(computer).openSession() }
            runOnUiThread { web.loadUrl(computer.origin) }
        }
        startForegroundService(Intent(this, LinkService::class.java))
    }

    private fun action(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
