package app.dsh.mobile

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class DiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = ComputerStore(this).active() ?: return finish()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        root.addView(TextView(this).apply { text = "连接诊断"; textSize = 22f })
        setContentView(root)
        thread {
            val checks = runCatching { GatewayClient(computer).diagnostics() }.getOrElse {
                listOf(DiagnosticCheck("phone", false, it.message ?: "unreachable"))
            }
            runOnUiThread {
                checks.forEach { check ->
                    root.addView(TextView(this).apply {
                        text = "${if (check.ok) "正常" else "失败"} · ${check.name}\n${check.detail}"
                        setPadding(0, 24, 0, 0)
                    })
                }
            }
        }
    }
}
