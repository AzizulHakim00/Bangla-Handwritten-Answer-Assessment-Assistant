package com.assessment.controller;

import com.assessment.service.Marks;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

final class Suggestions {
    private Suggestions() {}

    static Map<String, Object> evaluate(Map<String, Object> answer) {
        return evaluate(answer, answer.get("ocrJob") instanceof Map<?, ?> job ? job : Map.of());
    }

    static Map<String, Object> evaluate(Map<String, Object> answer, Map<?, ?> extraction) {
        String verifiedText = Objects.toString(answer.get("ocr"), "");
        boolean provisional = verifiedText.isBlank();
        String text = provisional ? Objects.toString(extraction.get("candidate"), "") : verifiedText;
        List<Map<String, Object>> regions = DocumentApi.regions(answer);
        var spans = extraction.get("sourceSpans") instanceof List<?> found ? found : List.of();
        var rows = new ArrayList<Map<String, Object>>();
        boolean supported = !text.isBlank() && (!regions.isEmpty() || !spans.isEmpty());
        if (answer.get("criteria") instanceof List<?> criteria) {
            for (int i = 0; i < criteria.size(); i++) {
                var criterion = (Map<?, ?>) criteria.get(i);
                var row = new LinkedHashMap<String, Object>();
                row.put("criterion", i + 1);
                row.put("label", criterion.get("text"));
                row.put("status", "ABSTAINED");
                row.put("rationale", "No rubric phrase could be linked to readable text and a source page.");
                if (supported) {
                    String hints = Objects.toString(criterion.get("hint"), "").strip();
                    if (hints.isBlank()) hints = Objects.toString(criterion.get("text"), "");
                    for (String raw : hints.split("\\|")) {
                        String hint = raw.strip();
                        if (hint.isBlank()) continue;
                        var match = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])" + Pattern.quote(hint)
                            + "(?![\\p{L}\\p{M}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
                        if (!match.find()) continue;
                        var source = source(extraction, spans, text, regions, match.start(), match.end());
                        if (source == null) continue;
                        row.put("status", "SUGGESTED");
                        row.put("score", Marks.value(criterion, "marks"));
                        row.put("evidence", match.group());
                        row.put("start", match.start());
                        row.put("end", match.end());
                        row.put("source", source);
                        row.put("rationale", provisional
                            ? "Matched in unverified OCR. Confirm the wording and its meaning against the page before awarding marks."
                            : "Rubric phrase found in the saved transcription. Check its meaning and context before awarding marks.");
                        break;
                    }
                }
                rows.add(row);
            }
        }
        boolean any = rows.stream().anyMatch(row -> row.containsKey("score"));
        return Map.of("id", "SUG-" + UUID.randomUUID(), "answerId", Objects.toString(answer.get("id"), "legacy"),
            "rubricVersion", Objects.toString(answer.get("rubricVersion"), "legacy"),
            "modelVersion", "rubric-hints-v1", "createdAt", Instant.now().toString(),
            "status", any ? "ADVISORY" : "ABSTAINED", "provisional", provisional, "criteria", rows);
    }

    private static Map<?, ?> source(Map<?, ?> extraction, List<?> spans, String text, List<Map<String, Object>> regions,
        int start, int end) {
        String candidate = Objects.toString(extraction.get("candidate"), "");
        boolean unchanged = text.equals(candidate);
        String quote = text.substring(start, end);
        int offset = unchanged ? start : candidate.indexOf(quote);
        if (offset >= 0 && (unchanged || candidate.indexOf(quote, offset + 1) < 0)) {
            for (Object raw : spans) {
                var span = (Map<?, ?>) raw;
                if (((Number) span.get("start")).intValue() <= offset && ((Number) span.get("end")).intValue() >= offset + quote.length()
                    && span.get("source") instanceof Map<?, ?> region) return region;
            }
        }
        if (regions.size() != 1) return null;
        var region = regions.getFirst();
        return spans.stream().allMatch(raw -> ((Map<?, ?>) raw).get("source") instanceof Map<?, ?> source
            && ScriptTranscription.sameRegion(region, source)) ? region : null;
    }

    static boolean overridden(Map<String, Object> answer, BigDecimal score, List<AssessmentApi.Award> awards) {
        if (score == null || awards == null || !(answer.get("suggestion") instanceof Map<?, ?> suggestion)
            || !(suggestion.get("criteria") instanceof List<?> criteria)) return false;
        for (int i = 0; i < Math.min(criteria.size(), awards.size()); i++) {
            var row = (Map<?, ?>) criteria.get(i);
            if (row.containsKey("score") && awards.get(i) != null && awards.get(i).marks() != null
                && Marks.value(row, "score").compareTo(awards.get(i).marks()) != 0) return true;
        }
        return false;
    }
}
