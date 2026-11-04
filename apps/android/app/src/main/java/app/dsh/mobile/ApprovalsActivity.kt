package app.dsh.mobile

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ApprovalsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val computer = ComputerStore(this).active() ?: return finish()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        root.addView(TextView(this).apply { text = "待处理"; textSize = 22f })
        setContentView(root)
        thread {
            val items = GatewayClient(computer).approvals()
            runOnUiThread {
                if (items.isEmpty()) root.addView(TextView(this).apply { text = "没有待审批或待回答的问题。" })
                items.forEach { item ->
                    root.addView(TextView(this).apply { text = "${item.title}\n${item.detail}" })
                    if (item.kind == "approval") {
                        root.addView(decision(computer, item.id, "允许一次", "allowed-once"))
                        root.addView(decision(computer, item.id, "拒绝", "rejected"))
                    }
                }
            }
        }
    }

    private fun decision(computer: Computer, id: String, label: String, outcome: String) = Button(this).apply {
        text = label
        setOnClickListener {
            thread {
                GatewayClient(computer).answerApproval(id, outcome)
                runOnUiThread { finish() }
            }
        }
    }
}
