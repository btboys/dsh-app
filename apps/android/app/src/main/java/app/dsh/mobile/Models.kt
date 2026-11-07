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
    val questions: List<PendingQuestion> = emptyList(),
)

data class PendingQuestion(
    val id: String,
    val question: String,
    val detail: String = "",
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class QuestionOption(
    val label: String,
    val description: String = "",
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
