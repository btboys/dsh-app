package app.dsh.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.concurrent.thread

class LinkService : Service() {
    private companion object {
        const val PENDING_ID = 8
        const val ACTION_PENDING_CHANGED = "app.dsh.mobile.PENDING_CHANGED"
    }

    @Volatile private var running = false
    private val outstanding = mutableSetOf<String>()

    @Synchronized
    private fun trackAlert(event: GatewayEvent) {
        PendingEvents.alert(event)?.takeIf { it.itemId.isNotEmpty() }?.let { outstanding.add(it.itemId) }
    }

    @Synchronized
    private fun clearSettled(event: GatewayEvent): Boolean {
        val settled = PendingEvents.settlement(event) ?: return false
        outstanding.remove(settled.itemId)
        if (settled.state == PendingEvents.STATE_PHONE_TIMEOUT) {
            notify("请求已超时", "这条请求已回到电脑端处理，手机上不再显示。")
        }
        return outstanding.isEmpty()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(7, note("已连接", getString(R.string.link_running)))
        if (!running) {
            running = true
            thread { listen() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun listen() {
        val computer = ComputerStore(this).active() ?: return stopSelf()
        var delay = 2_000L
        while (running) {
            try {
                GatewayClient(computer).eventReader().use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            val event = JSONObject(line.removePrefix("data: "))
                            handle(event)
                        }
                    }
                }
                delay = 2_000L
            } catch (error: Throwable) {
                // The stream can end while Android keeps the process alive; retry with backoff.
            }
            if (!running) return
            Thread.sleep(delay)
            delay = (delay * 2).coerceAtMost(60_000L)
        }
    }

    private fun handle(event: JSONObject) {
        val parsed = GatewayEvent(
            kind = event.optString("kind"),
            itemId = event.optString("id"),
            status = event.optString("status").ifEmpty { event.optString("state") },
            reason = event.optString("reason"),
            toolName = event.optString("toolName"),
        )
        if (clearSettled(parsed)) getSystemService(NotificationManager::class.java).cancel(PENDING_ID)
        trackAlert(parsed)
        PendingEvents.alert(parsed)?.let { notify(it.title, it.text) }
        // An open approvals screen should not keep showing items that just changed.
        if (PendingEvents.alert(parsed) != null || PendingEvents.settlement(parsed) != null) {
            sendBroadcast(Intent(ACTION_PENDING_CHANGED).setPackage(packageName))
        }
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(PENDING_ID, note(title, text))
    }

    private fun note(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ApprovalsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "link")
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("link", getString(R.string.link_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }
}
