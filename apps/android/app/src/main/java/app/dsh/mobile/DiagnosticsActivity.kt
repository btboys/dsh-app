package app.dsh.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class DiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val computer = ComputerStore(this).active() ?: return finish()
        val list = findViewById<LinearLayout>(R.id.checkList)
        val summary = findViewById<TextView>(R.id.diagnosticSummary)
        thread {
            val checks = runCatching { GatewayClient(computer).diagnostics() }.getOrElse {
                listOf(DiagnosticCheck("phone", false, it.message ?: "unreachable"))
            }
            runOnUiThread {
                val inflater = LayoutInflater.from(this)
                val passed = checks.count { it.ok }
                summary.text = getString(R.string.diagnostics_summary, passed, checks.size)
                checks.forEach { check ->
                    val row = inflater.inflate(R.layout.item_check, list, false)
                    row.findViewById<View>(R.id.checkDot).setBackgroundResource(if (check.ok) R.drawable.dot_ok else R.drawable.dot_fail)
                    row.findViewById<TextView>(R.id.checkName).text = check.name
                    row.findViewById<TextView>(R.id.checkDetail).apply {
                        text = check.detail
                        visibility = if (check.detail.isBlank()) View.GONE else View.VISIBLE
                    }
                    row.findViewById<TextView>(R.id.checkState).apply {
                        text = getString(if (check.ok) R.string.diagnostics_ok else R.string.diagnostics_fail)
                        setTextColor(getColor(if (check.ok) R.color.accent else R.color.danger))
                    }
                    list.addView(row)
                }
            }
        }
    }
}
