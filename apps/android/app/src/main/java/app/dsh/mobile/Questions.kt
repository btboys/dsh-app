package app.dsh.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Reads the `ask_user_question` payload the gateway forwards, and builds the answer batch. */
object Questions {
    /** One in-progress answer: chosen option labels plus optional free text. */
    data class Answer(val selected: Set<String> = emptySet(), val custom: String = "")

    fun parse(questions: JSONArray?): List<PendingQuestion> {
        if (questions == null) return emptyList()
        return List(questions.length()) { index ->
            val item = questions.getJSONObject(index)
            PendingQuestion(
                id = item.optString("id"),
                question = item.optString("question"),
                detail = item.optString("detail"),
                header = item.optString("header"),
                options = parseOptions(item.optJSONArray("options")),
                multiSelect = item.optBoolean("multiSelect", false),
            )
        }.filter { it.id.isNotEmpty() }
    }

    private fun parseOptions(options: JSONArray?): List<QuestionOption> {
        if (options == null) return emptyList()
        return List(options.length()) { index ->
            val option = options.getJSONObject(index)
            QuestionOption(option.optString("label"), option.optString("description"))
        }.filter { it.label.isNotEmpty() }
    }

    /** The first unanswered question, or null when every question has a choice or free text. */
    fun missingAnswer(questions: List<PendingQuestion>, answers: Map<String, Answer>): PendingQuestion? =
        questions.firstOrNull { question ->
            val answer = answers[question.id] ?: Answer()
            answer.selected.isEmpty() && answer.custom.isBlank()
        }

    /**
     * Builds the batch the host expects: one entry per question, with the chosen labels and the
     * free text only when the user typed something.
     */
    fun payload(questions: List<PendingQuestion>, answers: Map<String, Answer>): JSONObject {
        val entries = JSONArray()
        for (question in questions) {
            val answer = answers[question.id] ?: Answer()
            val entry = JSONObject()
                .put("id", question.id)
                .put("selected", JSONArray(answer.selected.toList()))
            val custom = answer.custom.trim()
            if (custom.isNotEmpty()) entry.put("custom", custom)
            entries.put(entry)
        }
        return JSONObject().put("answers", entries)
    }
}
