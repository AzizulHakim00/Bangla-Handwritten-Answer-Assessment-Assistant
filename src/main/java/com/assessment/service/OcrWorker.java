package com.assessment.service;

import com.assessment.controller.AssessmentApi;
import com.assessment.model.Workspace;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OcrWorker {
    public static final String MODEL = "tesseract-bengali-script-psm6-180dpi-v2";
    public static final String MANUAL = "Review the original and transcribe or mark it manually.";
    private final WorkspaceService workspaces;
    private final DocumentService documents;
    private final String command;

    public OcrWorker(WorkspaceService workspaces, DocumentService documents,
        @Value("${assessment.ocr-command:tesseract}") String command) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.command = command;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void scheduledTick() {
        try {
            for (String tenant : workspaces.tenants()) {
                tick(() -> workspaces.readWithoutAudit(tenant), change -> workspaces.change(tenant, change.version, change.action));
            }
        } catch (RuntimeException ex) {
            if (!(ex instanceof ResponseStatusException))
                LoggerFactory.getLogger(OcrWorker.class).warn("OCR queue temporarily unavailable ({})", ex.getClass().getSimpleName());
        }
    }

    public void tick() {
        tick(workspaces::read, change -> workspaces.change(change.version, change.action));
    }

    @SuppressWarnings("unchecked")
    private void tick(java.util.function.Supplier<Workspace> read, Consumer<Change> save) {
        var workspace = read.get();
        for (var script : workspace.scripts) {
            for (var answer : AssessmentApi.answers(script)) {
                if (!(answer.get("ocrJob") instanceof Map<?, ?> raw)) continue;
                var job = (Map<String, Object>) raw;
                if (!claimable(job)) continue;
                if (List.of("Approved", "Pending Moderation").contains(Objects.toString(script.get("status"), "")) ||
                    script.containsKey("marker") && !Objects.equals(script.get("marker"), job.get("requestedBy"))) {
                    save.accept(new Change(workspace.version, w -> {
                        var current = matching(w, script.get("id").toString(), ((Number) answer.get("question")).intValue(),
                            job.get("id").toString());
                        current.put("status", "STALE");
                        current.remove("lease");
                    }));
                    return;
                }
                String id = script.get("id").toString(), jobId = job.get("id").toString();
                int q = ((Number) answer.get("question")).intValue();
                String lease = UUID.randomUUID().toString();
                int attempts = ((Number) job.getOrDefault("attempts", 0)).intValue();
                save.accept(new Change(workspace.version, w -> {
                    var saved = matching(w, id, q, jobId);
                    if (attempts >= 3) {
                        saved.put("status", "FAILED");
                        saved.put("message", MANUAL);
                    } else {
                        saved.put("status", "RUNNING");
                        saved.put("lease", lease);
                        saved.put("startedAt", Instant.now().toString());
                        saved.put("attempts", attempts + 1);
                    }
                }));
                if (attempts >= 3) return;
                List<Map<String, Object>> regions = job.get("regions") instanceof List<?> list ?
                    (List<Map<String, Object>>) list : List.of((Map<String, Object>) job.get("region"));
                var result = recognize(script, regions);
                for (int retry = 0; retry < 4; retry++) {
                    try {
                        var latest = read.get();
                        save.accept(new Change(latest.version, w -> {
                            var saved = matching(w, id, q, jobId);
                            if (!lease.equals(saved.get("lease"))) throw new IllegalStateException("Lease changed");
                            saved.putAll(result);
                            saved.put("modelVersion", MODEL);
                            saved.put("completedAt", Instant.now().toString());
                        }));
                        return;
                    } catch (ResponseStatusException ex) {
                        if (ex.getStatusCode().value() != 409) return;
                    } catch (IllegalStateException ex) { return; }
                }
                return;
            }
        }
    }

    private record Change(long version, Consumer<Workspace> action) {}

    public static boolean claimable(Map<String, Object> job) {
        return "PENDING".equals(job.get("status")) || "RUNNING".equals(job.get("status"))
            && Instant.parse(job.get("startedAt").toString()).isBefore(Instant.now().minusSeconds(180));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> matching(Workspace w, String id, int question, String jobId) {
        var answer = AssessmentApi.answers(WorkspaceService.script(w, id)).get(question - 1);
        var job = (Map<String, Object>) answer.get("ocrJob");
        if (job == null || !jobId.equals(job.get("id"))) throw new IllegalStateException("OCR job replaced");
        return job;
    }

    public Map<String, Object> recognize(Map<String, Object> script, List<Map<String, Object>> regions) {
        var sourceSpans = new ArrayList<Map<String, Object>>();
        var text = new StringBuilder();
        boolean failed = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (var region : regions) {
            if (System.nanoTime() >= deadline) { failed = true; break; }
            try {
                String raw = documents.recognize(script, region, command, deadline);
                if (raw.isBlank()) continue;
                if (text.length() + raw.length() + 1 > 30_000) { failed = true; break; }
                if (!text.isEmpty()) text.append('\n');
                int start = text.length();
                text.append(raw);
                sourceSpans.add(Map.of("start", start, "end", text.length(), "source", region));
            } catch (IOException | RuntimeException ex) {
                failed = true;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failed = true;
                break;
            }
        }
        String state = text.isEmpty() ? failed ? "FAILED" : "MANUAL_REVIEW" : "NEEDS_REVIEW";
        return Map.of("status", state, "candidate", text.toString(), "sourceSpans", sourceSpans,
            "message", failed || text.isEmpty() ? MANUAL : "");
    }

}
