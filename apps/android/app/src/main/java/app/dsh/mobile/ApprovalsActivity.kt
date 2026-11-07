package app.dsh.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ApprovalsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approvals)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val computer = ComputerStore(this).active() ?: return finish()
        val list = findViewById<LinearLayout>(R.id.approvalList)
        val empty = findViewById<View>(R.id.approvalsEmpty)
        val count = findViewById<TextView>(R.id.pendingCount)
        count.text = "…"
        thread {
            val items = runCatching { GatewayClient(computer).approvals() }.getOrElse { emptyList() }
            runOnUiThread {
                count.text = String.format("%02d", items.size)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                val inflater = LayoutInflater.from(this)
                items.forEachIndexed { index, item ->
                    val row = inflater.inflate(R.layout.item_approval, list, false)
                    row.findViewById<TextView>(R.id.kindBadge).apply {
                        text = getString(if (item.kind == "approval") R.string.approval_kind_approval else R.string.approval_kind_question)
                        setTextColor(getColor(if (item.kind == "approval") R.color.accent else R.color.warn))
                    }
                    row.findViewById<TextView>(R.id.itemIndex).text = String.format("%02d", index + 1)
                    row.findViewById<TextView>(R.id.itemTitle).text = item.title
                    row.findViewById<TextView>(R.id.itemDetail).apply {
                        text = item.detail
                        visibility = if (item.detail.isBlank()) View.GONE else View.VISIBLE
                    }
                    val actions = row.findViewById<View>(R.id.itemActions)
                    actions.visibility = if (item.kind == "approval") View.VISIBLE else View.GONE
                    row.findViewById<Button>(R.id.allowOnce).setOnClickListener { answer(computer, item.id, "allowed-once") }
                    row.findViewById<Button>(R.id.reject).setOnClickListener { answer(computer, item.id, "rejected") }
                    list.addView(row)
                }
            }
        }
    }

    private fun answer(computer: Computer, id: String, outcome: String) {
        thread {
            runCatching { GatewayClient(computer).answerApproval(id, outcome) }
            runOnUiThread { finish() }
        }
    }
}
