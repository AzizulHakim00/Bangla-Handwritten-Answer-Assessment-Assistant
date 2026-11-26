package com.assessment.controller;

import com.assessment.model.Workspace;
import com.assessment.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DocumentApi {
    private final WorkspaceService workspaces;
    private final DocumentService documents;
    private final ReportService reports;
    private final ArtifactSigner signer;

    public DocumentApi(WorkspaceService workspaces, DocumentService documents, ReportService reports, ArtifactSigner signer) {
        this.workspaces = workspaces;
        this.documents = documents;
        this.reports = reports;
        this.signer = signer;
    }

    @GetMapping("/scripts/{id}/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable String id, @PathVariable int page) throws Exception {
        var image = documents.page(WorkspaceService.script(workspaces.read(), id), page);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(out.toByteArray());
    }

    @GetMapping("/scripts/{id}/document-info")
    public ResponseEntity<Map<String, Integer>> documentInfo(@PathVariable String id) throws IOException {
        var script = WorkspaceService.script(workspaces.readWithoutAudit(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("pages", documents.pageCount(script)));
    }

    public record RegionRequest(long version, int question, int page, double x, double y, double width,
        double height, Integer index, Boolean remove) {}

    @PostMapping("/scripts/{id}/region")
    public Workspace region(@PathVariable String id, @RequestBody RegionRequest r, Principal user) throws Exception {
        var script = WorkspaceService.script(workspaces.read(), id);
        var region = new LinkedHashMap<String, Object>(Map.of("page", r.page, "x", r.x, "y", r.y,
            "width", r.width, "height", r.height));
        boolean remove = Boolean.TRUE.equals(r.remove);
        if (!remove) DocumentService.crop(documents.page(script, r.page), region);
        return workspaces.change(r.version, w -> {
            var selected = WorkspaceService.script(w, id);
            editable(selected);
            var answer = answer(selected, r.question);
            var current = new ArrayList<>(regions(answer));
            int index = r.index == null ? 0 : r.index;
            if (index < 0 || index > current.size() || remove && index == current.size())
                throw invalid("Region does not exist");
            if (remove) current.remove(index);
            else if (index == current.size()) {
                if (index >= 20) throw invalid("Use at most 20 regions per question");
                current.add(region);
            } else current.set(index, region);
            answer.put("regions", current);
            answer.remove("region");
            if (!current.isEmpty()) {
                answer.put("region", current.getFirst());
                answer.put("page", current.getFirst().get("page"));
            }
            answer.put("reviewed", false);
            answer.remove("suggestion");
            if (answer.get("ocrJob") instanceof Map<?, ?> job) {
                preserveOcr(answer, job);
                answer.remove("ocrJob");
            }
            AssessmentApi.audit(w, "REGIONS_CHANGED", id, "question-" + r.question);
        });
    }

    public record OcrRequest(long version, int question) {}

    @PostMapping("/scripts/{id}/ocr")
    public Workspace ocr(@PathVariable String id, @RequestBody OcrRequest r, Principal user) {
        return workspaces.change(r.version, w -> {
            var script = WorkspaceService.script(w, id);
            editable(script);
            if (AssessmentJobs.running(w, script)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for the script assessment to finish before running region OCR.");
            }
            var answer = answer(script, r.question);
            var regions = regions(answer);
            if (regions.isEmpty()) throw invalid("Save an answer region before starting OCR");
            if (answer.get("ocrJob") instanceof Map<?, ?> job) {
                if (AssessmentJobs.active(job.get("status"))) return;
                preserveOcr(answer, job);
            }
            var job = new LinkedHashMap<String, Object>(Map.of("id", UUID.randomUUID().toString(), "status", "PENDING",
                "regions", AssessmentApi.copy(regions), "requestedBy", user.getName(), "requestedAt", Instant.now().toString(),
                "inputSha256", Objects.toString(script.get("sha256"), "legacy"),
                "rubricVersion", Objects.toString(answer.get("rubricVersion"), "legacy"), "modelVersion", OcrWorker.MODEL));
            answer.put("ocrJob", job);
            AssessmentApi.audit(w, "OCR_REQUESTED", id, job.get("id").toString());
        });
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> evidenceRegions(Map<String, Object> answer) {
        var found = new ArrayList<>(regions(answer));
        if (answer.get("ocrJob") instanceof Map<?, ?> job && job.get("sourceSpans") instanceof List<?> spans) {
            for (Object raw : spans) {
                if (((Map<?, ?>) raw).get("source") instanceof Map<?, ?> source) {
                    if (found.stream().noneMatch(region -> ScriptTranscription.sameRegion(region, source)))
                        found.add((Map<String, Object>) source);
                }
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    static void preserveOcr(Map<String, Object> answer, Map<?, ?> job) {
        var history = (List<Object>) answer.computeIfAbsent("ocrHistory", key -> new ArrayList<>());
        if (history.stream().filter(Map.class::isInstance).map(Map.class::cast)
            .noneMatch(previous -> Objects.equals(previous.get("id"), job.get("id")))) history.add(AssessmentApi.copy(job));
    }

    @PostMapping("/scripts/{id}/suggestion")
    public Workspace suggestion(@PathVariable String id, @RequestBody OcrRequest r) {
        return workspaces.change(r.version, w -> {
            var script = WorkspaceService.script(w, id);
            editable(script);
            var answer = answer(script, r.question);
            var suggestion = Suggestions.evaluate(answer);
            answer.put("suggestion", suggestion);
            AssessmentApi.audit(w, "SUGGESTION_GENERATED", id, suggestion.get("id").toString());
        });
    }

    @GetMapping("/exports/marks.pdf") public ResponseEntity<byte[]> pdf() throws Exception {
        return download(reports.pdf(AccessScope.view(workspaces.read())), "application/pdf", "approved-marks.pdf");
    }

    @GetMapping("/exports/marks.zip") public ResponseEntity<byte[]> bundle() throws Exception {
        return download(reports.signedBundle(AccessScope.view(workspaces.read())), "application/zip", "approved-marks-signed.zip");
    }

    @GetMapping("/rubrics/{id}/signed")
    public ResponseEntity<byte[]> signedRubric(@PathVariable String id) throws Exception {
        var workspace = workspaces.read();
        var rubric = workspace.rubrics.stream().filter(row -> id.equals(row.get("id"))).findFirst()
            .orElseThrow(DocumentApi::missingRubric);
        if (!"TEACHER".equals(AccessScope.role()) && workspace.scripts.stream().filter(AccessScope::visible)
            .flatMap(script -> AssessmentApi.answers(script).stream()).noneMatch(answer -> id.equals(answer.get("rubricVersion"))))
            throw missingRubric();
        var seal = new ArtifactSigner.Seal(rubric.get("manifest").toString(), rubric.get("signature").toString(),
            rubric.get("publicKey").toString(), rubric.get("keyId").toString());
        byte[] artifact = rubric.get("artifact").toString().getBytes(StandardCharsets.UTF_8);
        return download(ArtifactSigner.bundle("rubric.json", artifact, seal), "application/zip", "signed-rubric.zip");
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(@RequestParam("file") MultipartFile bundle) {
        try {
            var entries = verificationEntries(bundle);
            boolean report = entries.containsKey("approved-marks.pdf");
            String name = report ? "approved-marks.pdf" : "rubric.json";
            if (!entries.keySet().containsAll(List.of(name, "manifest.json", "manifest.sig")) ||
                entries.containsKey("approved-marks.pdf") && entries.containsKey("rubric.json")) return status("ALTERED");
            var workspace = workspaces.read();
            var manifest = signer.verify(workspace.id, entries.get(name), entries.get("manifest.json"), entries.get("manifest.sig"));
            if (!manifest.path("purpose").asText().equals(report ? "MARK_SHEET" : "RUBRIC")) return status("ALTERED");
            if (report && "MARKER".equals(AccessScope.role())) return status("UNAVAILABLE");
            if (report) {
                if (!manifest.path("results").isArray() || manifest.path("results").isEmpty()) return status("ALTERED");
                boolean superseded = false;
                for (var result : manifest.path("results")) {
                    var script = WorkspaceService.script(workspace, result.path("scriptId").asText());
                    var approved = ReportService.approved(script);
                    if (approved == null || !result.path("revisionId").asText().equals(ReportService.revisionId(approved))) superseded = true;
                }
                return status(superseded ? "SUPERSEDED" : "VALID");
            }
            String id = manifest.path("revisionId").asText();
            boolean authorized = "TEACHER".equals(AccessScope.role()) || workspace.scripts.stream()
                .filter(AccessScope::visible).flatMap(script -> AssessmentApi.answers(script).stream())
                .anyMatch(answer -> id.equals(answer.get("rubricVersion")));
            if (!authorized) return status("UNAVAILABLE");
            boolean current = workspace.exams.stream().filter(exam -> Objects.equals(exam.get("id"), manifest.path("examId").asText()))
                .anyMatch(exam -> id.equals(AssessmentApi.rubricVersion(exam, manifest.path("question").asInt())));
            return status(current ? "VALID" : "SUPERSEDED");
        } catch (ArtifactSigner.VerificationException ex) {
            return status(ex.getMessage());
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 413) throw ex;
            return status("UNAVAILABLE");
        } catch (Exception ex) {
            return status("ALTERED");
        }
    }

    private static Map<String, byte[]> verificationEntries(MultipartFile bundle) throws IOException {
        int limit = 20 * 1024 * 1024;
        Map<String, byte[]> entries = new HashMap<>();
        int bytes = 0;
        try (var zip = new ZipInputStream(bundle.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!Set.of("rubric.json", "approved-marks.pdf", "manifest.json", "manifest.sig", "public-key.pem").contains(name)
                    || entries.containsKey(name)) throw new IOException("Invalid archive");
                byte[] content = zip.readNBytes(limit - bytes + 1);
                bytes += content.length;
                if (bytes > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Verification bundle is too large");
                entries.put(name, content);
            }
        }
        return entries;
    }

    private static ResponseEntity<Map<String, String>> status(String value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", value));
    }

    private static ResponseEntity<byte[]> download(byte[] bytes, String mime, String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(mime))
            .header("Content-Disposition", "attachment; filename=" + name).body(bytes);
    }

    static Map<String, Object> answer(Map<String, Object> script, int question) {
        var answers = AssessmentApi.answers(script);
        if (question < 1 || question > answers.size()) throw invalid("Question is outside this script");
        return answers.get(question - 1);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> regions(Map<String, Object> answer) {
        if (answer.get("regions") instanceof List<?> list) return (List<Map<String, Object>>) list;
        if (answer.get("region") instanceof Map<?, ?> region) return List.of((Map<String, Object>) region);
        return List.of();
    }

    static void editable(Map<String, Object> script) {
        if (List.of("Approved", "Pending Moderation").contains(script.get("status")))
            throw invalid("Submitted or approved marks cannot be changed");
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private static ResponseStatusException missingRubric() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Rubric version not found");
    }
}
