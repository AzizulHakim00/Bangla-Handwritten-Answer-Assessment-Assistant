package com.assessment.controller;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

final class ScriptTranscription {
    private static final Pattern NUMBERED = Pattern.compile(
        "(?iu)^\\s*(?:প্রশ্ন\\s*|(?:উত্তর|question|answer)\\s*[:：]?\\s*)?(\\p{Nd}{1,3})\\s*(?:[।৷.)|:ঃ]|নং|নম্বর)(?:\\s*|$)(.*)$");

    private static final Pattern TITLE = Pattern.compile(
        "^\\s*[\\p{L}\\p{Nd}>]{1,4}\\s*[।৷.)|:ঃ]\\s*(.*)$");

    private ScriptTranscription() {}

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> pages(Map<String, Object> script) {
        return (List<Map<String, Object>>) script.getOrDefault("automationPages", List.of());
    }

    static Map<String, Object> pageRegion(int page) {
        return Map.of("page", page, "x", 0, "y", 0, "width", 1, "height", 1);
    }

    static Map<String, Object> forAnswer(List<Map<String, Object>> pages, List<Map<String, Object>> answers, int question) {
        var segments = new ArrayList<Map<String, Object>>();
        var draft = new StringBuilder();
        int active = answers.size() == 1 ? 1 : 0;
        boolean incomplete = false;
        for (var page : pages) {
            int pageNumber = ((Number) page.get("page")).intValue();
            String text = Objects.toString(page.get("text"), "");
            if (text.isBlank()) {
                incomplete = true;
                continue;
            }
            int start = draft.length();
            for (String line : text.split("\\R")) {
                int heading = heading(line, answers);
                // Explicit continuation headings leave the current answer active across pages.
                if (answers.size() > 1 && heading > active) active = heading;
                if (active != question || line.isBlank()) continue;
                if (draft.length() + line.length() + 1 > 30_000) {
                    incomplete = true;
                    break;
                }
                if (!draft.isEmpty()) draft.append('\n');
                draft.append(line.strip());
            }
            if (draft.length() > start) segments.add(Map.of("start", start == 0 ? 0 : start + 1,
                "end", draft.length(), "source", pageRegion(pageNumber)));
        }
        String message = draft.isEmpty() ? "No matching answer heading was detected. Open Script OCR to inspect the extracted pages."
            : incomplete ? "Some pages had no readable text. Check the draft against the original."
            : "Automatically extracted draft. Check the question and page references before saving.";
        return Map.of("status", draft.isEmpty() ? "MANUAL_REVIEW" : "NEEDS_REVIEW",
            "candidate", draft.toString(), "sourceSpans", segments, "message", message);
    }

    private static int heading(String line, List<Map<String, Object>> answers) {
        if (line.length() > 180) return 0;
        var label = TITLE.matcher(line);
        String key = normalized(label.matches() ? label.group(1) : line);
        for (var answer : answers) {
            var candidates = new ArrayList<String>();
            candidates.add(Objects.toString(answer.get("description"), ""));
            if (answer.get("criteria") instanceof List<?> criteria && criteria.size() == 1)
                candidates.add(Objects.toString(((Map<?, ?>) criteria.getFirst()).get("text"), ""));
            for (String candidate : candidates) {
                String title = normalized(candidate);
                if (!title.isEmpty() && (key.equals(title) || label.matches() && key.startsWith(title + " ")))
                    return ((Number) answer.get("question")).intValue();
            }
        }
        var number = NUMBERED.matcher(line);
        if (number.matches()) {
            int q = Integer.parseInt(number.group(1));
            if (q >= 1 && q <= answers.size()) return q;
        }
        return 0;
    }

    private static String normalized(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\p{M}]+", " ").strip();
    }

    static boolean sameRegion(Map<?, ?> left, Map<?, ?> right) {
        for (String field : List.of("page", "x", "y", "width", "height")) {
            if (!(left.get(field) instanceof Number a) || !(right.get(field) instanceof Number b)
                || new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) != 0) return false;
        }
        return true;
    }
}
