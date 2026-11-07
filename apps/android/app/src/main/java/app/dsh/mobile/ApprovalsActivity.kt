package app.dsh.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import java.net.URI
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    private val submitButtons = mutableListOf<Button>()
    private var computer: Computer? = null
    private val pendingChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            computer?.let { loadApprovals(it) }
        }
    }

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
        this.computer = computer
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

    override fun onStart() {
        super.onStart()
        registerReceiver(pendingChanged, IntentFilter("app.dsh.mobile.PENDING_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(pendingChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning from the session page should show current pending items, not a stale list.
        computer?.let { loadApprovals(it) }
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
                submitButtons.clear()
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
                    val pending = item.questions.firstOrNull()
                    row.findViewById<TextView>(R.id.itemTitle).text = when {
                        pending == null -> item.title
                        pending.header.isNotBlank() -> pending.header
                        else -> getString(R.string.approval_kind_question)
                    }
                    row.findViewById<TextView>(R.id.itemDetail).apply {
                        // Questions repeat their own text and detail inside the answer form.
                        text = if (pending != null) "" else item.detail
                        visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                    }
                    val actions = row.findViewById<LinearLayout>(R.id.itemActions)
                    row.findViewById<Button>(R.id.allowOnce).setOnClickListener { answer(computer, item.id, "allowed-once") }
                    row.findViewById<Button>(R.id.reject).setOnClickListener { answer(computer, item.id, "rejected") }
                    if (item.kind == "question") {
                        renderQuestions(computer, item, row, actions)
                    } else {
                        actions.visibility = View.VISIBLE
                    }
                    if (locks.locked(lockKey(computer, item.id))) {
                        row.findViewById<Button>(R.id.allowOnce).isEnabled = false
                        row.findViewById<Button>(R.id.reject).isEnabled = false
                        submitButtons.lastOrNull()?.isEnabled = false
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
        submitButtons.forEach { it.isEnabled = enabled }
    }

    private fun answer(computer: Computer, id: String, outcome: String) =
        submit(computer, id) { GatewayClient(computer).answerApproval(id, outcome) }

    private fun submit(computer: Computer, id: String, send: () -> Unit) {
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
        status.text = "正在提交…"
        status.visibility = View.VISIBLE
        thread {
            val result = runCatching { send() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // The server may have processed the request before the connection failed.
                submissionUncertain = result.isFailure
                status.text = if (submissionUncertain) "提交结果未知，正在刷新确认。" else "提交成功，正在刷新列表…"
                loadApprovals(computer)
            }
        }
    }

    /** Renders one pending question: choices, optional free text, and its own submit button. */
    private fun renderQuestions(computer: Computer, item: PendingItem, row: View, actions: LinearLayout) {
        val allow = row.findViewById<Button>(R.id.allowOnce)
        val reject = row.findViewById<Button>(R.id.reject)
        allow.visibility = View.GONE
        reject.visibility = View.GONE
        actions.visibility = View.VISIBLE
        actions.orientation = LinearLayout.VERTICAL

        val answers = mutableMapOf<String, Questions.Answer>()
        item.questions.forEach { question ->
            if (question.header.isNotBlank()) {
                actions.addView(label(question.header, R.color.text_faint, 11f))
            }
            actions.addView(label(question.question, R.color.text_primary, 15f))
            if (question.detail.isNotBlank()) {
                actions.addView(label(question.detail, R.color.text_muted, 13f))
            }
            val options = mutableListOf<Button>()
            question.options.forEach { option ->
                val button = Button(this).apply {
                    text = if (option.description.isBlank()) option.label else "${option.label} · ${option.description}"
                    isAllCaps = false
                    setOnClickListener {
                        val current = answers[question.id] ?: Questions.Answer()
                        val selected = if (question.multiSelect) {
                            if (option.label in current.selected) current.selected - option.label else current.selected + option.label
                        } else {
                            setOf(option.label)
                        }
                        answers[question.id] = current.copy(selected = selected)
                        options.forEach { other ->
                            val label = other.text.toString().substringBefore(" · ")
                            other.alpha = if (label in selected) 1f else 0.66f
                        }
                    }
                    alpha = 0.66f
                }
                options.add(button)
                actions.addView(button)
            }
            val custom = EditText(this).apply {
                hint = if (question.options.isEmpty()) "输入你的回答" else "也可以补充说明"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(editable: android.text.Editable) {
                        val current = answers[question.id] ?: Questions.Answer()
                        answers[question.id] = current.copy(custom = editable.toString())
                    }
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                })
            }
            actions.addView(custom)
        }
        val submit = Button(this).apply {
            text = "提交回答"
            isAllCaps = false
            setOnClickListener {
                val missing = Questions.missingAnswer(item.questions, answers)
                if (missing != null) {
                    status.text = "请先回答：${missing.question}"
                    status.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val payload = Questions.payload(item.questions, answers)
                submit(computer, item.id) { GatewayClient(computer).answerQuestions(item.id, payload) }
            }
        }
        submitButtons.add(submit)
        actions.addView(submit)
    }

    private fun label(text: String, colorRes: Int, sizeSp: Float) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(colorRes))
        textSize = sizeSp
        setPadding(0, 10, 0, 4)
    }
}
