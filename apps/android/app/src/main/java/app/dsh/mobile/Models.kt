package app.dsh.mobile

data class Computer(
    val id: String,
    val name: String,
    val origin: String,
    val token: String,
    val certificateSha256: String?,
    val lastSeen: Long,
)

data class PendingItem(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
)

data class FileEntry(
    val name: String,
    val type: String,
    val size: Long?,
)

data class DiagnosticCheck(
    val name: String,
    val ok: Boolean,
    val detail: String,
)
