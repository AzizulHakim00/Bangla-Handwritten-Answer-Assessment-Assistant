package com.assessment.controller;

import com.assessment.model.Workspace;
import com.assessment.service.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssessmentJobs {
    private final WorkspaceService workspaces;

    private final DocumentService documents;

    public AssessmentJobs(WorkspaceService workspaces, DocumentService documents) {
        this.workspaces = workspaces;
        this.documents = documents;
    }

    public Map<String, Object> start(String scriptId, String rubricVersion, String key) throws IOException {
        if (scriptId == null || rubricVersion == null || key == null || !key.matches("[A-Za-z0-9._:-]{1,100}"))
            throw DocumentApi.invalid("Provide script_id, rubric_version and a valid Idempotency-Key");
        String requester = AccessScope.user();
        for (int attempt = 0; attempt < 4; attempt++) {
            var workspace = workspaces.read();
            var script = WorkspaceService.script(workspace, scriptId);
            var existing = workspace.jobs.stream()
                .filter(job -> key.equals(job.get("key")) && requester.equals(job.get("requestedBy"))).findFirst();
            if (existing.isPresent()) {
                var job = existing.get();
                if (scriptId.equals(job.get("scriptId")) && rubricVersion.equals(job.get("rubricVersion"))) return job;
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was used with a different payload");
            }
            DocumentApi.editable(script);
            if (!rubricReference(script).equals(rubricVersion) && !uniformRubric(script, rubricVersion))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Rubric version does not match the uploaded script");
            if (workspace.jobs.stream().anyMatch(job -> scriptId.equals(job.get("scriptId")) &&
                rubricReference(script).equals(job.get("bindingVersion")) &&
                List.of("PENDING", "RUNNING").contains(job.get("status"))))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An assessment is already running for this script.");
            var answers = AssessmentApi.answers(script);
            var inputs = new ArrayList<Map<String, Object>>();
            for (var answer : answers) {
                inputs.add(Map.of("question", answer.get("question"), "hash", fingerprint(answer)));
            }
            String jobId = "JOB-" + UUID.randomUUID();
            var job = new LinkedHashMap<String, Object>();
            job.put("id", jobId);
            job.put("scriptId", scriptId);
            job.put("key", key);
            job.put("requestedBy", requester);
            job.put("createdAt", Instant.now().toString());
            job.put("rubricVersion", rubricVersion);
            job.put("bindingVersion", rubricReference(script));
            int pages = documents.pageCount(script);
            if (answers.stream().anyMatch(answer -> DocumentApi.regions(answer).isEmpty())) {
                if (pages > 100) throw DocumentApi.invalid("Automatic OCR supports up to 100 pages. For longer files, select answer regions.");
                job.put("pageCount", pages);
                job.put("pagesDone", 0);
            }
            job.put("modelVersion", OcrWorker.MODEL);
            job.put("inputSha256", ReportService.hex(ArtifactSigner.canonical(Map.of(
                "document", Objects.toString(script.get("sha256"), scriptId), "answers", inputs))));
            job.put("documentSha256", Objects.toString(script.get("sha256"), "legacy"));
            job.put("inputs", inputs);
            job.put("inputFormat", 2);
            job.put("outputs", new ArrayList<>());
            job.put("status", "PENDING");
            job.put("attempts", 0);
            job.put("progress", 0);
            try {
                workspaces.change(workspace.version, w -> {
                    WorkspaceService.script(w, scriptId).put("pages", pages);
                    w.jobs.add(job);
                    AssessmentApi.audit(w, "ASSESSMENT_REQUESTED", scriptId, jobId);
                });
                return job;
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() != 409 || attempt == 3) throw ex;
            }
        }
        throw new IllegalStateException("Job could not be queued");
    }

    public Map<String, Object> read(String id) {
        var workspace = workspaces.read();
        var job = find(workspace, id);
        WorkspaceService.script(workspace, job.get("scriptId").toString());
        return job;
    }

    public static String rubricReference(Map<String, Object> script) {
        return Objects.toString(script.get("rubricSetVersion"), "legacy");
    }

    private static boolean uniformRubric(Map<String, Object> script, String version) {
        return AssessmentApi.answers(script).stream().allMatch(a -> version.equals(Objects.toString(a.get("rubricVersion"), "legacy")));
    }

    static String fingerprint(Map<String, Object> answer) throws IOException {
        return ReportService.hex(ArtifactSigner.canonical(Map.of("regions", DocumentApi.regions(answer),
            "rubric", Objects.toString(answer.get("rubricVersion"), "legacy"))));
    }

    static boolean matches(Map<String, Object> job, Map<String, Object> input, Map<String, Object> answer) throws IOException {
        if (Integer.valueOf(2).equals(job.get("inputFormat"))) return input.get("hash").equals(fingerprint(answer));
        return input.get("hash").equals(ReportService.hex(ArtifactSigner.canonical(Map.of(
            "regions", DocumentApi.regions(answer), "text", Objects.toString(answer.get("ocr"), ""),
            "rubric", Objects.toString(answer.get("rubricVersion"), "legacy")))));
    }

    static Map<String, Object> find(Workspace workspace, String id) {
        return workspace.jobs.stream().filter(row -> id.equals(row.get("id"))).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }
}
