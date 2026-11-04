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
    @Volatile private var running = false

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
        GatewayClient(computer).eventReader().use { reader ->
            while (running) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val event = JSONObject(line.removePrefix("data: "))
                if (event.optString("kind") == "approval" || event.optString("status") == "idle") {
                    notify(event.optString("kind", "status"), event.optString("reason", "会话状态已更新"))
                }
            }
        }
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(8, note(title, text))
    }

    private fun note(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ApprovalsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "link")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
