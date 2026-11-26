package com.assessment.controller;

import com.assessment.model.Workspace;
import com.assessment.service.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AssessmentJobWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AssessmentJobWorker.class);
    private final WorkspaceService workspaces;
    private final OcrWorker ocr;

    public AssessmentJobWorker(WorkspaceService workspaces, OcrWorker ocr) {
        this.workspaces = workspaces;
        this.ocr = ocr;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void tick() {
        try {
            for (String tenant : workspaces.tenants()) processNext(workspaces.readWithoutAudit(tenant));
        } catch (Exception ex) {
            if (!(ex instanceof ResponseStatusException))
                LOG.warn("Assessment queue temporarily unavailable ({})", ex.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    void processNext(Workspace workspace) throws IOException {
        var queued = workspace.jobs.stream().filter(OcrWorker::claimable).findFirst();
        if (queued.isEmpty()) return;
        var job = queued.get();
        String tenant = workspace.id;
        String id = job.get("id").toString(), scriptId = job.get("scriptId").toString();
        var script = WorkspaceService.script(workspace, scriptId);
        if (!requestStillAuthorized(job, script)) {
            workspaces.change(tenant, workspace.version, w -> stale(AssessmentJobs.find(w, id)));
            return;
        }
        int pages = ((Number) job.getOrDefault("pageCount", 0)).intValue();
        int pagesDone = ((Number) job.getOrDefault("pagesDone", 0)).intValue();
        if (pagesDone < pages) {
            processPage(workspace, script, job, pagesDone + 1, pages);
            return;
        }
        var inputs = (List<Map<String, Object>>) job.get("inputs");
        var outputs = (List<Map<String, Object>>) job.get("outputs");
        int offset = outputs.size(), attempt = ((Number) job.getOrDefault("stepAttempts", 0)).intValue();
        var input = inputs.get(offset);
        int question = ((Number) input.get("question")).intValue();
        var answer = DocumentApi.answer(script, question);
        if (!AssessmentJobs.matches(job, input, answer)) {
            workspaces.change(tenant, workspace.version, w -> {
                var current = AssessmentJobs.find(w, id);
                current.put("status", "STALE");
                current.put("completedAt", Instant.now().toString());
            });
            return;
        }
        String lease = UUID.randomUUID().toString();
        workspaces.change(tenant, workspace.version, w -> {
            var current = AssessmentJobs.find(w, id);
            current.put("lease", lease);
            current.put("status", "RUNNING");
            current.put("startedAt", Instant.now().toString());
            current.put("attempts", ((Number) current.get("attempts")).intValue() + 1);
            current.put("stepAttempts", attempt + 1);
        });
        var regions = DocumentApi.regions(answer);
        var extracted = regions.isEmpty() || Boolean.TRUE.equals(job.get("wholeScriptAnswer"))
            ? ScriptTranscription.forAnswer(ScriptTranscription.pages(script), AssessmentApi.answers(script), question)
            : attempt >= 3 ? Map.<String, Object>of("status", "MANUAL_REVIEW", "candidate", "", "message", OcrWorker.MANUAL, "sourceSpans", List.of())
            : ocr.recognize(script, regions);
        for (int retry = 0; retry < 5; retry++) {
            var latest = workspaces.readWithoutAudit(tenant);
            try {
                workspaces.change(tenant, latest.version, w -> {
                    var current = AssessmentJobs.find(w, id);
                    if (!lease.equals(current.get("lease"))) return;
                    var latestScript = WorkspaceService.script(w, scriptId);
                    if (!requestStillAuthorized(current, latestScript)) {
                        stale(current);
                        return;
                    }
                    var latestAnswer = DocumentApi.answer(latestScript, question);
                    if (!matches(current, input, latestAnswer)) {
                        stale(current);
                        return;
                    }
                    var extraction = new LinkedHashMap<>(extracted);
                    extraction.put("id", id + "-Q" + question);
                    extraction.put("completedAt", Instant.now().toString());
                    extraction.put("inputSha256", job.get("inputSha256"));
                    extraction.put("modelVersion", OcrWorker.MODEL);
                    extraction.put("rubricVersion", latestAnswer.getOrDefault("rubricVersion", "legacy"));
                    DocumentApi.preserveOcr(latestAnswer, extraction);
                    if (latestAnswer.get("ocrJob") instanceof Map<?, ?> previous) DocumentApi.preserveOcr(latestAnswer, previous);
                    latestAnswer.put("ocrJob", extraction);
                    var suggestion = Suggestions.evaluate(latestAnswer, extraction);
                    latestAnswer.put("suggestion", suggestion);
                    var results = (List<Map<String, Object>>) current.get("outputs");
                    results.add(Map.of("question", question, "answer_id", latestAnswer.getOrDefault("id", "legacy"),
                        "ocr_result_id", extraction.get("id"), "ocr_state", extraction.get("status"),
                        "suggestion_id", suggestion.get("id"), "suggestion_state", suggestion.get("status")));
                    current.put("stepAttempts", 0);
                    current.put("progress", (pages + results.size()) * 100 / (pages + inputs.size()));
                    boolean complete = results.size() == inputs.size();
                    boolean anyReadable = results.stream().anyMatch(row -> "NEEDS_REVIEW".equals(row.get("ocr_state")));
                    current.put("status", complete ? anyReadable ? "NEEDS_REVIEW" : "MANUAL_REVIEW" : "PENDING");
                    if (complete) current.put("completedAt", Instant.now().toString());
                });
                return;
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() != 409) throw ex;
            }
        }
    }

    private void processPage(Workspace workspace, Map<String, Object> script, Map<String, Object> job, int page, int count) {
        String id = job.get("id").toString(), scriptId = script.get("id").toString(), tenant = workspace.id;
        String lease = UUID.randomUUID().toString();
        int attempts = ((Number) job.getOrDefault("stepAttempts", 0)).intValue();
        workspaces.change(tenant, workspace.version, w -> {
            var current = AssessmentJobs.find(w, id);
            current.put("status", "RUNNING");
            current.put("lease", lease);
            current.put("startedAt", Instant.now().toString());
            current.put("stepAttempts", attempts + 1);
            current.put("attempts", ((Number) current.get("attempts")).intValue() + 1);
            if (page == 1) WorkspaceService.script(w, scriptId).remove("automationPages");
        });
        var result = attempts >= 3 ? Map.<String, Object>of("status", "FAILED", "candidate", "")
            : ocr.recognize(script, List.of(ScriptTranscription.pageRegion(page)));
        for (int retry = 0; retry < 5; retry++) {
            var latest = workspaces.readWithoutAudit(tenant);
            try {
                workspaces.change(tenant, latest.version, w -> {
                    var current = AssessmentJobs.find(w, id);
                    if (!lease.equals(current.get("lease"))) return;
                    var target = WorkspaceService.script(w, scriptId);
                    if (!requestStillAuthorized(current, target)) {
                        stale(current);
                        return;
                    }
                    var extracted = new ArrayList<>(ScriptTranscription.pages(target));
                    extracted.add(Map.of("page", page, "text", result.get("candidate"), "status", result.get("status")));
                    target.put("automationPages", extracted);
                    current.put("pagesDone", page);
                    int questions = AssessmentApi.answers(target).size();
                    current.put("progress", page * 100 / (count + questions));
                    current.put("stepAttempts", 0);
                    current.put("status", "PENDING");
                });
                return;
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() != 409) throw ex;
            }
        }
    }

    private static void stale(Map<String, Object> job) {
        job.put("status", "STALE");
        job.put("completedAt", Instant.now().toString());
    }

    private static boolean matches(Map<String, Object> job, Map<String, Object> input, Map<String, Object> answer) {
        try { return AssessmentJobs.matches(job, input, answer); }
        catch (IOException ex) { return false; }
    }

    private static boolean requestStillAuthorized(Map<String, Object> job, Map<String, Object> script) {
        return Objects.equals(job.getOrDefault("bindingVersion", AssessmentJobs.rubricReference(script)), AssessmentJobs.rubricReference(script))
            && !List.of("Approved", "Pending Moderation").contains(script.get("status"))
            && (!script.containsKey("marker") || Objects.equals(script.get("marker"), job.get("requestedBy")));
    }
}
