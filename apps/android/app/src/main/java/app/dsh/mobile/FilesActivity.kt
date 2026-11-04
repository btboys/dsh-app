package app.dsh.mobile

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class FilesActivity : AppCompatActivity() {
    private var workspace = ""
    private var path = "."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        load()
    }

    private fun load() {
        val computer = ComputerStore(this).active() ?: return finish()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        root.addView(TextView(this).apply { text = path; textSize = 18f })
        setContentView(root)
        thread {
            if (workspace.isEmpty()) {
                val found = GatewayClient(computer).workspaces()
                if (found.isEmpty()) return@thread
                workspace = found.first().name
            }
            val entries = GatewayClient(computer).files(workspace, path)
            runOnUiThread {
                entries.forEach { entry ->
                    root.addView(Button(this).apply {
                        text = if (entry.type == "directory") "${entry.name}/" else entry.name
                        setOnClickListener {
                            val next = if (path == ".") entry.name else "$path/${entry.name}"
                            if (entry.type == "directory") {
                                path = next
                                load()
                            } else preview(computer, next)
                        }
                    })
                }
            }
        }
    }

    private fun preview(computer: Computer, file: String) {
        thread {
            val text = GatewayClient(computer).preview(workspace, file)
            runOnUiThread {
                setContentView(TextView(this).apply {
                    this.text = text
                    setPadding(48, 48, 48, 48)
                })
            }
        }
    }
}
