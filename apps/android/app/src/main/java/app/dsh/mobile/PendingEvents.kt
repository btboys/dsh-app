package app.dsh.mobile

/** The gateway event fields the phone reacts to, kept free of org.json so it stays testable. */
data class GatewayEvent(
    val kind: String = "",
    val itemId: String = "",
    val status: String = "",
    val reason: String = "",
    val toolName: String = "",
)

/** Turns gateway events into the only two things the phone reacts to. */
object PendingEvents {
    const val STATE_PHONE_TIMEOUT = "phone_timeout"

    /** An approval or question needs the user, or a session went idle. */
    data class Alert(val itemId: String, val title: String, val text: String)

    /** The gateway answered, expired, or gave up on a pending item. */
    data class Settlement(val itemId: String, val state: String)

    fun alert(event: GatewayEvent): Alert? {
        if (event.kind == "approval" || event.kind == "question") {
            if (event.itemId.isEmpty()) return null
            return Alert(
                itemId = event.itemId,
                title = if (event.kind == "approval") "新的工具审批" else "新的问题",
                text = event.reason.ifEmpty { event.toolName.ifEmpty { "会话等待你的选择" } },
            )
        }
        if (event.status == "idle") {
            return Alert(itemId = "", title = "会话空闲", text = event.reason.ifEmpty { "会话状态已更新" })
        }
        return null
    }

    fun settlement(event: GatewayEvent): Settlement? {
        if (event.kind != "pending-settled" || event.itemId.isEmpty()) return null
        return Settlement(event.itemId, event.status.ifEmpty { event.reason })
    }
}
