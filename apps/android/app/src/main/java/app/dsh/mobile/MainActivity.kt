package app.dsh.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
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
    private lateinit var list: LinearLayout
    private lateinit var originInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ComputerStore(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply { text = "已配对电脑"; textSize = 22f })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        originInput = EditText(this).apply { hint = "http://电脑地址:3443" }
        status = TextView(this)
        val origin = originInput
        val code = EditText(this).apply { hint = "配对码" }
        root.addView(origin)
        root.addView(code)
        root.addView(Button(this).apply {
            text = "配对这台电脑"
            setOnClickListener { pair(origin.text.toString(), code.text.toString()) }
        })
        root.addView(Button(this).apply {
            text = "搜索局域网"
            setOnClickListener { discover() }
        })
        root.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        store.list().forEach { computer ->
            list.addView(Button(this).apply {
                text = "${computer.name}\n${computer.origin}"
                setOnClickListener {
                    store.activate(computer.id)
                    startActivity(Intent(this@MainActivity, SessionActivity::class.java))
                }
            })
        }
    }

    private fun report(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun pair(origin: String, code: String) {
        thread {
            try {
                val client = GatewayClient(Computer("new", "电脑", origin.trim(), "", null, 0))
                val paired = client.pair(code.trim(), android.os.Build.MODEL)
                store.save(paired)
                store.activate(paired.id)
                runOnUiThread { render() }
                report("已配对")
            } catch (error: Throwable) {
                report(error.message ?: "配对失败")
            }
        }
    }

    private fun discover() {
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
                    report("已找到电脑")
                }
            } catch (error: Throwable) {
                report(error.message ?: "没有找到电脑")
            }
        }
    }
}
