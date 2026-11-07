package app.dsh.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var store: ComputerStore
    private lateinit var deviceList: LinearLayout
    private lateinit var emptyBlock: View
    private lateinit var originInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var scopeChip: TextView
    private lateinit var pairedCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ComputerStore(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContentView(R.layout.activity_main)

        deviceList = findViewById(R.id.deviceList)
        emptyBlock = findViewById(R.id.emptyBlock)
        originInput = findViewById(R.id.originInput)
        codeInput = findViewById(R.id.codeInput)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        scopeChip = findViewById(R.id.scopeChip)
        pairedCount = findViewById(R.id.pairedCount)

        findViewById<View>(R.id.pairButton).setOnClickListener {
            pair(originInput.text.toString(), codeInput.text.toString())
        }
        findViewById<View>(R.id.discoverButton).setOnClickListener { discover() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val computers = store.list()
        pairedCount.text = String.format("%02d", computers.size)
        scopeChip.setText(if (computers.any { it.origin.startsWith("https://") }) R.string.status_remote else R.string.status_local)
        deviceList.removeAllViews()
        emptyBlock.visibility = if (computers.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        computers.forEach { computer ->
            val row = inflater.inflate(R.layout.item_computer, deviceList, false)
            row.findViewById<TextView>(R.id.computerName).text = computer.name
            row.findViewById<TextView>(R.id.computerOrigin).text = computer.origin
            row.findViewById<TextView>(R.id.computerMeta).text = lastSeenLabel(computer.lastSeen)
            row.setOnClickListener {
                store.activate(computer.id)
                startActivity(Intent(this, SessionActivity::class.java))
            }
            row.setOnLongClickListener {
                store.delete(computer.id)
                render()
                report(getString(R.string.computer_removed), State.IDLE)
                true
            }
            deviceList.addView(row)
        }
        if (computers.isEmpty()) report(getString(R.string.status_idle), State.IDLE)
    }

    private fun lastSeenLabel(lastSeen: Long): String {
        if (lastSeen <= 0L) return getString(R.string.computer_never_seen)
        val minutes = ((System.currentTimeMillis() - lastSeen) / 60_000L).coerceAtLeast(0L)
        val relative = when {
            minutes < 1L -> getString(R.string.time_just_now)
            minutes < 60L -> getString(R.string.time_minutes, minutes)
            minutes < 60L * 24L -> getString(R.string.time_hours, minutes / 60L)
            else -> getString(R.string.time_days, minutes / (60L * 24L))
        }
        return getString(R.string.computer_last_seen, relative)
    }

    private enum class State { IDLE, BUSY, DONE, FAILED }

    private fun report(message: String, state: State) {
        runOnUiThread {
            statusText.text = message
            statusDot.setBackgroundResource(
                when (state) {
                    State.DONE -> R.drawable.dot_ok
                    State.FAILED -> R.drawable.dot_fail
                    else -> R.drawable.dot_idle
                }
            )
        }
    }

    private fun pair(origin: String, code: String) {
        if (origin.isBlank() || code.isBlank()) {
            report(getString(R.string.status_pair_failed), State.FAILED)
            return
        }
        report(getString(R.string.status_pairing), State.BUSY)
        thread {
            try {
                val client = GatewayClient(Computer("new", "电脑", origin.trim(), "", null, 0))
                val paired = client.pair(code.trim(), Build.MODEL)
                store.save(paired)
                store.activate(paired.id)
                runOnUiThread {
                    render()
                    codeInput.setText("")
                }
                report(getString(R.string.status_paired), State.DONE)
            } catch (error: Throwable) {
                report(error.message ?: getString(R.string.status_pair_failed), State.FAILED)
            }
        }
    }

    private fun discover() {
        report(getString(R.string.status_searching), State.BUSY)
        thread {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 2_000
                    val payload = "dsh-app-discover".toByteArray()
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), 34430))
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val body = JSONObject(String(packet.data, 0, packet.length))
                    val origin = body.getJSONArray("origins").getString(0)
                    runOnUiThread { originInput.setText(origin) }
                    report(getString(R.string.status_found), State.DONE)
                }
            } catch (error: Throwable) {
                report(error.message ?: getString(R.string.status_not_found), State.FAILED)
            }
        }
    }
}
