package com.assessment.controller;

import com.assessment.service.AccessScope;
import com.assessment.service.WorkspaceService;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ProductApi {
    private final AssessmentApi assessment;
    private final AssessmentJobs jobs;
    private final WorkspaceService workspaces;

    public ProductApi(AssessmentApi assessment, AssessmentJobs jobs, WorkspaceService workspaces) {
        this.assessment = assessment;
        this.jobs = jobs;
        this.workspaces = workspaces;
    }

    public record JobRequest(@JsonProperty("script_id") String scriptId, @JsonProperty("rubric_version") String rubricVersion) {}

    @PostMapping("/p33/script-assessments")
    public ResponseEntity<Map<String, Object>> start(@RequestBody JobRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
            .body(jobView(jobs.start(request.scriptId, request.rubricVersion, key)));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Map<String, Object>> find(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobView(jobs.read(id)));
    }

    private static Map<String, Object> jobView(Map<String, Object> job) {
        return Map.of("job_id", job.get("id"), "script_id", job.get("scriptId"), "state", job.get("status"),
            "progress", job.get("progress"), "attempts", job.get("attempts"), "model_version", job.get("modelVersion"),
            "input_hash", job.get("inputSha256"), "rubric_version", job.get("rubricVersion"), "output_refs", job.get("outputs"));
    }

    public record MarkRequest(@JsonProperty("answer_id") String answerId, BigDecimal score,
        String rationale, @JsonProperty("expected_version") Long expectedVersion, String transcription,
        Integer page, List<AssessmentApi.Award> awards, Boolean submit,
        @JsonProperty("manual_review") Boolean manualReview) {}

    @PostMapping("/p33/mark-reviews")
    public Map<String, Object> mark(@RequestBody MarkRequest request, Principal actor) {
        if (request.expectedVersion == null || request.answerId == null || request.rationale == null || request.score == null)
            throw DocumentApi.invalid("Provide answer_id, score, rationale and expected_version");
        var workspace = AccessScope.view(workspaces.read());
        for (var script : workspace.scripts) {
            var answers = AssessmentApi.answers(script);
            for (int i = 0; i < answers.size(); i++) {
                var answer = answers.get(i);
                if (!request.answerId.equals(answer.get("id"))) continue;
                var revised = domain(() -> assessment.review(script.get("id").toString(), new AssessmentApi.ReviewRequest(
                    request.expectedVersion, request.transcription == null ? Objects.toString(answer.get("ocr"), "") : request.transcription,
                    request.rationale, request.score, Boolean.TRUE.equals(request.submit), ((Number) answer.get("question")).intValue(),
                    request.page, request.awards, request.manualReview), actor));
                var selected = WorkspaceService.script(revised, script.get("id").toString());
                var saved = DocumentApi.answer(selected, i + 1);
                var response = new LinkedHashMap<>(Map.of("revision_id", saved.get("markRevision"),
                    "expected_version", revised.version, "state", selected.get("status")));
                if (Boolean.TRUE.equals(request.submit)) response.put("submitted_revision_id", selected.get("submittedRevisionId"));
                return response;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found");
    }

    public record Decision(@JsonProperty("resource_id") String resourceId,
        @JsonProperty("expected_version") Long expectedVersion, String decision, String reason) {}

    @PostMapping("/reviews")
    public Map<String, Object> decide(@RequestBody Decision request, Principal actor) {
        if (request.expectedVersion == null || request.resourceId == null || request.decision == null)
            throw DocumentApi.invalid("Provide resource_id, expected_version and decision");
        String id = request.resourceId.startsWith("REV-") ? AccessScope.view(workspaces.read()).scripts.stream()
            .filter(script -> request.resourceId.equals(script.get("submittedRevisionId")))
            .map(script -> script.get("id").toString()).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submitted revision not found")) : request.resourceId;
        var revised = switch (request.decision.toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> domain(() -> assessment.approve(id, new AssessmentApi.VersionRequest(request.expectedVersion), actor));
            case "RECHECK", "RETURN" -> domain(() -> assessment.returnReview(id,
                new AssessmentApi.ReasonRequest(request.expectedVersion, request.reason), actor));
            default -> throw DocumentApi.invalid("Decision must be APPROVE or RECHECK");
        };
        var script = WorkspaceService.script(revised, id);
        var response = new LinkedHashMap<String, Object>();
        response.put("decision_id", script.get("decisionId"));
        response.put("expected_version", revised.version);
        response.put("state", script.get("status"));
        if ("Approved".equals(script.get("status"))) response.put("approved_version", script.get("submittedRevisionId"));
        return response;
    }

    private static <T> T domain(java.util.function.Supplier<T> mutation) {
        try { return mutation.get(); }
        catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 400) throw DocumentApi.invalid(ex.getReason());
            throw ex;
        }
    }
}
