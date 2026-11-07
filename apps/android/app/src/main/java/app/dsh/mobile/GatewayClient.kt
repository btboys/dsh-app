package app.dsh.mobile

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** A computer whose certificate was not confirmed, so the request was not sent. */
class UntrustedComputerException(reason: String) : IllegalStateException(reason)

class GatewayClient(private val computer: Computer) {
    fun pair(code: String, name: String): Computer {
        val body = post("/dsh-app/pair", JSONObject().put("code", code).put("name", name), null)
        return Computer(
            id = body.getString("id"),
            name = body.getString("name"),
            origin = body.getString("origin"),
            token = body.getString("token"),
            certificateSha256 = body.optString("certificateSha256").ifEmpty { computer.certificateSha256 },
            lastSeen = System.currentTimeMillis(),
        )
    }

    fun approvals(): List<PendingItem> {
        val body = get("/dsh-app/approvals")
        val pending = body.getJSONArray("pending")
        return List(pending.length()) { index ->
            val item = pending.getJSONObject(index)
            PendingItem(
                id = item.getString("id"),
                kind = item.getString("kind"),
                title = item.optString("toolName", item.optString("kind")),
                detail = item.optString("reason", item.optJSONArray("questions")?.toString() ?: ""),
                questions = Questions.parse(item.optJSONArray("questions")),
            )
        }
    }

    fun answerApproval(id: String, outcome: String) {
        post("/dsh-app/approvals/answer", JSONObject().put("id", id).put("outcome", outcome), computer.token)
    }

    /** Sends the whole answer batch; the host settles a question call only as one batch. */
    fun answerQuestions(id: String, answers: JSONObject) {
        post("/dsh-app/questions/answer", JSONObject().put("id", id).put("answer", answers), computer.token)
    }

    fun workspaces(): List<FileEntry> {
        val body = get("/dsh-app/workspaces")
        val entries = body.getJSONArray("workspaces")
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            FileEntry(item.getString("id"), "workspace", null)
        }
    }

    fun files(workspace: String, path: String): List<FileEntry> {
        val body = get("/dsh-app/files?workspace=${encode(workspace)}&path=${encode(path)}")
        val entries = body.getJSONArray("entries")
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            FileEntry(item.getString("name"), item.getString("type"), if (item.has("size")) item.getLong("size") else null)
        }
    }

    fun preview(workspace: String, path: String): String {
        val body = get("/dsh-app/files/preview?workspace=${encode(workspace)}&path=${encode(path)}")
        if (body.optString("kind") == "unsupported") return "这个文件不能以文本预览。"
        return body.optString("text", "图片预览请在会话页打开。")
    }

    fun diagnostics(): List<DiagnosticCheck> {
        val body = get("/dsh-app/diagnostics")
        val checks = body.getJSONArray("checks")
        return List(checks.length()) { index ->
            val item = checks.getJSONObject(index)
            DiagnosticCheck(item.getString("name"), item.getBoolean("ok"), item.optString("detail"))
        }
    }

    fun openSession() {
        post("/dsh-app/session", JSONObject(), computer.token)
    }

    fun eventReader(): BufferedReader {
        val connection = open("/dsh-app/events", "GET", null, computer.token)
        connection.readTimeout = 0
        connection.setRequestProperty("accept", "text/event-stream")
        return connection.inputStream.bufferedReader()
    }

    private fun get(path: String): JSONObject = request("GET", path, null, computer.token)

    private fun post(path: String, body: JSONObject, token: String?): JSONObject = request("POST", path, body, token)

    private fun open(path: String, method: String, body: JSONObject?, token: String?): HttpURLConnection {
        val connection = URL(computer.origin + path).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            // Without a confirmed pin this computer is not identified, so refuse the connection
            // rather than trusting whatever certificate the network presents.
            val pin = computer.certificateSha256
            if (pin == null) throw UntrustedComputerException("certificate_pin_required")
            if (!CertificatePin.isValidFormat(pin)) throw UntrustedComputerException("certificate_pin_invalid")
            connection.sslSocketFactory = CertificatePin.socketFactory(pin)
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { hostname, _ -> hostname == java.net.URI(computer.origin).host }
        }
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("accept", "application/json")
        if (token != null) connection.setRequestProperty("authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return connection
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val connection = open(path, method, body, token)
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (connection.responseCode !in 200..299) error(JSONObject(text.ifEmpty { "{}" }).optString("error", "request_failed"))
        if (text.isEmpty()) return JSONObject()
        return JSONObject(text)
    }


    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
