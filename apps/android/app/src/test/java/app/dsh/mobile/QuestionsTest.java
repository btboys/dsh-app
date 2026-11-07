package app.dsh.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QuestionsTest {
    private static JSONArray sample() throws Exception {
        return new JSONArray("[{\"id\":\"q1\",\"question\":\"用哪种方式？\",\"header\":\"部署\","
            + "\"options\":[{\"label\":\"A\",\"description\":\"第一种\"},{\"label\":\"B\"}]},"
            + "{\"id\":\"q2\",\"question\":\"补充说明\",\"multiSelect\":true}]");
    }

    @Test
    public void parsesQuestionsAndTheirOptions() throws Exception {
        List<PendingQuestion> questions = Questions.INSTANCE.parse(sample());
        assertEquals(2, questions.size());
        assertEquals("q1", questions.get(0).getId());
        assertEquals("部署", questions.get(0).getHeader());
        assertEquals(2, questions.get(0).getOptions().size());
        assertEquals("第一种", questions.get(0).getOptions().get(0).getDescription());
        assertTrue(questions.get(1).getMultiSelect());
        assertTrue(questions.get(1).getOptions().isEmpty());
    }

    @Test
    public void questionsWithoutAnIdAreDropped() throws Exception {
        assertTrue(Questions.INSTANCE.parse(new JSONArray("[{\"question\":\"no id\"}]")).isEmpty());
        assertTrue(Questions.INSTANCE.parse(null).isEmpty());
    }

    @Test
    public void anUnansweredQuestionIsReported() throws Exception {
        List<PendingQuestion> questions = Questions.INSTANCE.parse(sample());
        assertNotNull(Questions.INSTANCE.missingAnswer(questions, new HashMap<>()));
        Map<String, Questions.Answer> answers = new HashMap<>();
        answers.put("q1", new Questions.Answer(new HashSet<>(Arrays.asList("A")), ""));
        assertEquals("q2", Questions.INSTANCE.missingAnswer(questions, answers).getId());
        answers.put("q2", new Questions.Answer(new HashSet<>(), "  补充  "));
        assertNull(Questions.INSTANCE.missingAnswer(questions, answers));
    }

    @Test
    public void thePayloadCarriesEveryQuestionOnce() throws Exception {
        List<PendingQuestion> questions = Questions.INSTANCE.parse(sample());
        Map<String, Questions.Answer> answers = new HashMap<>();
        answers.put("q1", new Questions.Answer(new HashSet<>(Arrays.asList("A", "B")), ""));
        answers.put("q2", new Questions.Answer(new HashSet<>(), "自定义文本"));
        JSONObject payload = Questions.INSTANCE.payload(questions, answers);
        JSONArray batch = payload.getJSONArray("answers");
        assertEquals(2, batch.length());
        JSONObject first = batch.getJSONObject(0);
        assertEquals("q1", first.getString("id"));
        assertEquals(2, first.getJSONArray("selected").length());
        assertTrue(!first.has("custom"));
        JSONObject second = batch.getJSONObject(1);
        assertEquals("q2", second.getString("id"));
        assertEquals(0, second.getJSONArray("selected").length());
        assertEquals("自定义文本", second.getString("custom"));
    }

    @Test
    public void blankCustomTextIsOmitted() throws Exception {
        List<PendingQuestion> questions = Questions.INSTANCE.parse(sample());
        Map<String, Questions.Answer> answers = new HashMap<>();
        answers.put("q1", new Questions.Answer(new HashSet<>(Arrays.asList("B")), "   "));
        JSONObject payload = Questions.INSTANCE.payload(questions, answers);
        assertTrue(!payload.getJSONArray("answers").getJSONObject(0).has("custom"));
    }
}
