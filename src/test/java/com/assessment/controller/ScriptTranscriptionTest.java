package com.assessment.controller;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptTranscriptionTest {
    @Test void recognizesBanglaHeadingsAndRetainsContinuationPages() {
        var first = Map.<String, Object>of("question", 1, "description", "রচনা");
        var second = Map.<String, Object>of("question", 2, "description", "ভাষণ");
        var pages = List.of(
            Map.<String, Object>of("page", 1, "text", "> | রচনা — গরু\nভূমিকা"),
            Map.<String, Object>of("page", 2, "text", "১ | রচনা (ক্রমশ)\nগরু আমাদের উপকার করে।\n২ | ভাষণ\nশিক্ষকবৃন্দ"));
        var draft = ScriptTranscription.forAnswer(pages, List.of(first, second), 1);
        assertTrue(draft.get("candidate").toString().contains("গরু আমাদের উপকার"));
        assertFalse(draft.get("candidate").toString().contains("শিক্ষকবৃন্দ"));
        var spans = (List<?>) draft.get("sourceSpans");
        assertEquals(2, spans.size());
        assertEquals(ScriptTranscription.pageRegion(2), ((Map<?, ?>) spans.get(1)).get("source"));
    }

    @Test void usesMultiPageOcrForProvisionalSuggestionsWithoutSettingVerifiedText() {
        var answer = new LinkedHashMap<String, Object>(Map.of("question", 1, "ocr", "",
            "criteria", List.of(Map.of("text", "গরু", "marks", 5))));
        var pages = List.of(Map.<String,Object>of("page", 1, "text", "রচনা"),
            Map.<String,Object>of("page", 2, "text", "গরু একটি গৃহপালিত প্রাণী।"));
        var draft = ScriptTranscription.forAnswer(pages, List.of(answer), 1);
        var result = Suggestions.evaluate(answer, draft);
        assertEquals(true, result.get("provisional"));
        var row = (Map<?, ?>) ((List<?>) result.get("criteria")).getFirst();
        assertEquals("SUGGESTED", row.get("status"));
        assertEquals(ScriptTranscription.pageRegion(2), row.get("source"));
        assertEquals("", answer.get("ocr"));
    }
}
