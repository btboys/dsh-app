package app.dsh.mobile

import android.os.Bundle
import java.net.URI
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ApprovalsActivity : AppCompatActivity() {
    private var loadVersion = 0
    private var busy = false
    private var submissionUncertain = false
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var locks: ApprovalLocks
    private lateinit var lockPreferences: android.content.SharedPreferences

    private fun lockPrefix(computer: Computer): String {
        val uri = URI(computer.origin)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        val port = if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port
        return "$scheme://$host:$port/"
    }

    private fun lockKey(computer: Computer, id: String) = "${lockPrefix(computer)}${id.length}:$id"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_approvals)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        val computer = ComputerStore(this).active() ?: return finish()
        val prefs = getSharedPreferences("approval_locks", MODE_PRIVATE)
        lockPreferences = prefs
        locks = ApprovalLocks(object : ApprovalLocks.Storage {
            override fun contains(key: String) = prefs.getBoolean(key, false)
            override fun put(key: String) = prefs.edit().putBoolean(key, true).commit()

        })
        val list = findViewById<LinearLayout>(R.id.approvalList)
        val parent = list.parent as LinearLayout
        val index = parent.indexOfChild(list)
        status = TextView(this).apply {
            setTextColor(getColor(R.color.warn))
            visibility = View.GONE
        }
        retry = Button(this).apply {
            text = "重试刷新"
            visibility = View.GONE
            setOnClickListener { loadApprovals(computer) }
        }
        parent.addView(status, index)
        parent.addView(retry, index + 1)
        loadApprovals(computer)
    }

    private fun loadApprovals(computer: Computer) {
        val list = findViewById<LinearLayout>(R.id.approvalList)
        val empty = findViewById<View>(R.id.approvalsEmpty)
        val count = findViewById<TextView>(R.id.pendingCount)
        val version = ++loadVersion
        busy = true
        setActionsEnabled(false)
        retry.visibility = View.GONE
        count.text = "…"
        empty.visibility = View.GONE
        thread {
            val result = runCatching { GatewayClient(computer).approvals() }
            runOnUiThread {
                if (isFinishing || isDestroyed || version != loadVersion) return@runOnUiThread
                val items = result.getOrElse {
                    count.text = "—"
                    status.text = if (submissionUncertain) "提交结果未知，刷新失败；列表可能已过期，请重试刷新后操作。" else "刷新失败，列表可能已过期，请重试刷新后操作。"
                    status.visibility = View.VISIBLE
                    retry.visibility = View.VISIBLE
                    busy = false
                    return@runOnUiThread
                }
                busy = false
                val hasLocked = lockPreferences.all.keys.any { it.startsWith(lockPrefix(computer)) }
                status.visibility = if (submissionUncertain || hasLocked) View.VISIBLE else View.GONE
                if (submissionUncertain || hasLocked) status.text = "审批结果可能未知；锁定的审批不可再次操作，已消失的项目不再待处理，请在电脑端确认结果。"
                submissionUncertain = false
                list.removeAllViews()
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
                    if (locks.locked(lockKey(computer, item.id))) {
                        row.findViewById<Button>(R.id.allowOnce).isEnabled = false
                        row.findViewById<Button>(R.id.reject).isEnabled = false
                    }
                    list.addView(row)
                }
            }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        val list = findViewById<LinearLayout>(R.id.approvalList)
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            row.findViewById<Button>(R.id.allowOnce).isEnabled = enabled
            row.findViewById<Button>(R.id.reject).isEnabled = enabled
        }
    }

    private fun answer(computer: Computer, id: String, outcome: String) {
        if (busy) return
        val key = lockKey(computer, id)
        if (!locks.begin(key)) {
            status.text = "无法保存审批锁或该审批结果未知，请在电脑端确认。"
            status.visibility = View.VISIBLE
            return
        }
        busy = true
        ++loadVersion
        setActionsEnabled(false)
        status.text = "正在提交审批…"
        status.visibility = View.VISIBLE
        thread {
            val result = runCatching { GatewayClient(computer).answerApproval(id, outcome) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // The server may have processed the request before the connection failed.
                submissionUncertain = result.isFailure
                status.text = if (submissionUncertain) "提交结果未知，正在刷新确认。" else "提交成功，正在刷新列表…"
                loadApprovals(computer)
            }
        }
    }
}
