package app.dsh.mobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class FilesActivity : AppCompatActivity() {
    private var workspace = ""
    private var path = "."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        findViewById<View>(R.id.backButton).setOnClickListener { up() }
        findViewById<View>(R.id.previewClose).setOnClickListener {
            findViewById<View>(R.id.previewPanel).visibility = View.GONE
        }
        load()
    }

    private fun up() {
        if (path == "." || path.isEmpty()) {
            finish()
            return
        }
        path = path.substringBeforeLast('/', "")
        if (path.isEmpty()) path = "."
        load()
    }

    private fun load() {
        val computer = ComputerStore(this).active() ?: return finish()
        val list = findViewById<LinearLayout>(R.id.fileList)
        val pathText = findViewById<TextView>(R.id.pathText)
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        pathText.text = if (path == ".") getString(R.string.files_root) else "${getString(R.string.files_root)} / $path"
        thread {
            if (workspace.isEmpty()) {
                val found = GatewayClient(computer).workspaces()
                if (found.isEmpty()) return@thread
                workspace = found.first().name
            }
            val entries = GatewayClient(computer).files(workspace, path)
            runOnUiThread {
                if (entries.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = getString(R.string.files_empty)
                        setTextColor(getColor(R.color.text_faint))
                        setPadding(4, 16, 4, 16)
                    })
                    return@runOnUiThread
                }
                entries.forEach { entry ->
                    val row = inflater.inflate(R.layout.item_file, list, false)
                    val directory = entry.type == "directory"
                    row.findViewById<TextView>(R.id.fileName).text = if (directory) "${entry.name}/" else entry.name
                    row.findViewById<ImageView>(R.id.fileIcon).setImageResource(if (directory) R.drawable.ic_folder else R.drawable.ic_file)
                    row.findViewById<TextView>(R.id.fileSize).text = sizeLabel(entry.size)
                    val next = if (path == ".") entry.name else "$path/${entry.name}"
                    row.setOnClickListener {
                        if (directory) {
                            path = next
                            load()
                        } else {
                            preview(computer, next)
                        }
                    }
                    list.addView(row)
                }
            }
        }
    }

    private fun sizeLabel(size: Long?): String = when {
        size == null -> ""
        size < 1_024L -> "$size B"
        size < 1_024L * 1_024L -> "${size / 1_024L} KB"
        else -> String.format("%.1f MB", size / (1_024.0 * 1_024.0))
    }

    private fun preview(computer: Computer, file: String) {
        thread {
            val text = runCatching { GatewayClient(computer).preview(workspace, file) }
                .getOrElse { getString(R.string.files_preview_unsupported) }
            runOnUiThread {
                findViewById<TextView>(R.id.previewTitle).text = file.substringAfterLast('/')
                findViewById<TextView>(R.id.previewText).text = text
                findViewById<View>(R.id.previewPanel).visibility = View.VISIBLE
            }
        }
    }
}
