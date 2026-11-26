package com.assessment.controller;

import com.assessment.model.Workspace;
import com.assessment.service.*;
import com.assessment.config.InstitutionAccounts;
import java.math.BigDecimal;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import java.security.Principal;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AssessmentApi {
    private final WorkspaceService service;
    private final DocumentService documents;
    private final ArtifactSigner signer;
    private final InstitutionAccounts accounts;

    public AssessmentApi(WorkspaceService service, DocumentService documents, ArtifactSigner signer,
        InstitutionAccounts accounts) {
        this.service = service;
        this.documents = documents;
        this.signer = signer;
        this.accounts = accounts;
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/workspace") public Map<String,Object> workspace(Authentication auth) {
        Workspace w = AccessScope.view(service.readWithoutAudit());
        var assignments = accounts.members().stream().filter(m -> m.active() && m.tenant().equals(w.id)
            && !m.role().equals("TEACHER")).map(m -> Map.of("email", m.email(), "role", m.role())).toList();
        var rubricHistory = teacherHistory(w);
        var scriptJobs = w.jobs.stream()
            .map(job -> Map.of("script_id", job.get("scriptId"), "job_id", job.get("id"),
                "state", job.get("status"), "progress", job.get("progress"))).toList();
        return Map.of("version", w.version, "exams", w.exams, "scripts", w.scripts, "criteria", w.criteria,
            "user", auth.getName(), "role", AccessScope.role(), "tenant", w.id,
            "assignees", "TEACHER".equals(AccessScope.role()) ? assignments : List.of(),
            "rubricHistory", rubricHistory, "jobs", scriptJobs);
    }
    private static List<Map<String, Object>> teacherHistory(Workspace w) {
        return w.rubrics.stream().map(row -> Map.of("id", row.get("id"), "exam", row.get("exam"),
            "question", row.getOrDefault("question", 0), "publishedAt", row.getOrDefault("publishedAt", ""))).toList();
    }

    public record Item(String description, BigDecimal maximum) {}
    public record ExamRequest(long version, String name, String subject, int questions, String date,
        String reference, List<Item> items) {}

    @PostMapping("/exams") public Workspace exam(@RequestBody ExamRequest r) {
        return saveExam(null, r);
    }

    @PostMapping("/exams/{id}") public Workspace updateExam(@PathVariable String id, @RequestBody ExamRequest r) {
        return saveExam(id, r);
    }

    private Workspace saveExam(String existingId, ExamRequest r) {
        check(r.questions >= 1 && r.questions <= 100, "Questions must be between 1 and 100");
        String name = text(r.name, 120), subject = text(r.subject, 80);
        String id = existingId == null ? "EX-" + UUID.randomUUID() : existingId;
        String reference = text(r.reference, 64);
        check(reference.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), "Use letters, digits, hyphens or underscores in the exam reference");
        try { java.time.LocalDate.parse(r.date); } catch (Exception ex) { throw bad("Choose a valid date"); }
        List<Map<String, Object>> items = new ArrayList<>();
        check(r.items != null && r.items.size() == r.questions, "Provide a description and maximum for each question");
        for (int i = 0; i < r.items.size(); i++) {
            Item item = r.items.get(i);
            check(item != null, "Question is required");
            var maximum = Marks.validate(item.maximum, new BigDecimal("3000"), "Invalid question maximum");
            check(maximum.signum() > 0, "Question maximum must be positive");
            items.add(Map.of("question", i + 1, "description", text(item.description, 1000), "maximum", maximum));
        }
        return service.change(r.version, w -> {
            if (w.exams.stream().anyMatch(e -> !id.equals(e.get("id")) && reference.equalsIgnoreCase(e.getOrDefault("reference", e.get("id")).toString())))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The examination reference is already in use");
            var exam = existingId == null ? new LinkedHashMap<String, Object>() : exam(w, id);
            exam.putAll(Map.of("id", id, "reference", reference, "name", name, "subject", subject,
                "questions", r.questions, "date", r.date, "items", items, "updatedAt", Instant.now().toString()));
            if (existingId == null) w.exams.add(exam);
            else {
                for (String field : List.of("questionRubrics", "rubricPointers")) {
                    if (exam.get(field) instanceof Map<?, ?> values) {
                        var remaining = new LinkedHashMap<>(values);
                        remaining.keySet().removeIf(key -> Integer.parseInt(key.toString()) > r.questions);
                        exam.put(field, remaining);
                    }
                }
            }
            audit(w, existingId == null ? "EXAM_CREATED" : "EXAM_UPDATED", id, reference);
        });
    }

    public record Criterion(String text, BigDecimal marks, String hint) {}
    public record RubricRequest(long version, String exam, List<Criterion> criteria, Integer question) {}

    @PostMapping("/rubrics") public Workspace rubric(@RequestBody RubricRequest r) {
        check(r.criteria != null && !r.criteria.isEmpty() && r.criteria.size() <= 30, "Provide 1–30 criteria");
        List<Map<String,Object>> criteria = new ArrayList<>();
        for (Criterion criterion : r.criteria) {
            check(criterion != null, "Criterion is required");
            var marks = Marks.validate(criterion.marks, new BigDecimal("100"), "Criterion marks must be positive and at most 100");
            check(marks.signum() > 0, "Criterion marks must be positive");
            criteria.add(Map.of("text", text(criterion.text, 300), "marks", marks,
                "hint", bounded(Objects.toString(criterion.hint, ""), 300).strip()));
        }
        return service.change(r.version, w -> {
            var exam = exam(w, r.exam);
            int question = r.question == null ? 0 : r.question;
            int count = ((Number) exam.get("questions")).intValue();
            check(question >= 0 && question <= count, "Question is outside the exam");
            if (exam.get("items") instanceof List<?> items && !items.isEmpty()) {
                for (int q = 1; q <= count; q++) {
                    if (question != 0 && question != q) continue;
                    if (question == 0 && exam.get("questionRubrics") instanceof Map<?, ?> overrides &&
                        overrides.containsKey(Integer.toString(q))) continue;
                    check(maximum(criteria).compareTo(Marks.value((Map<?, ?>) items.get(q - 1), "maximum")) == 0,
                        "Criterion maximums must add up to the question maximum");
                }
            }
            String id = "RUB-" + UUID.randomUUID();
            var artifact = Map.of("id", id, "exam", r.exam, "question", question, "criteria", criteria,
                "publishedBy", AccessScope.user(), "publishedAt", Instant.now().toString());
            try {
                byte[] bytes = ArtifactSigner.canonical(artifact);
                var seal = signer.sign(w.id, "RUBRIC", bytes, Map.of("revisionId", id, "examId", r.exam, "question", question));
                w.rubrics.add(Map.of("id", id, "exam", r.exam, "question", question, "publishedAt", artifact.get("publishedAt"), "artifact", new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                    "manifest", seal.manifest(), "signature", seal.signature(), "publicKey", seal.publicKey(), "keyId", seal.keyId()));
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Rubric signature could not be created", ex);
            }
            if (question == 0) exam.put("criteria", criteria);
            else nested(exam, "questionRubrics").put(Integer.toString(question), criteria);
            nested(exam, "rubricPointers").put(Integer.toString(question), id);
            exam.put("scoringMode", "CRITERIA");
            w.criteria = criteria;
            audit(w, "RUBRIC_PUBLISHED", r.exam, id);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> item, String key) {
        return (Map<String, Object>) item.computeIfAbsent(key, k -> new LinkedHashMap<>());
    }

    static String rubricVersion(Map<String, Object> exam, int question) {
        if (!(exam.get("rubricPointers") instanceof Map<?, ?> pointers)) return "legacy";
        Object id = pointers.get(Integer.toString(question));
        if (id == null && exam.get("questionRubrics") instanceof Map<?, ?> criteria &&
            criteria.containsKey(Integer.toString(question))) return "legacy";
        if (id == null) id = pointers.get("0");
        return Objects.toString(id, "legacy");
    }

    @PostMapping("/uploads") public Workspace upload(@RequestParam long version, @RequestParam String exam,
        @RequestParam("files") List<MultipartFile> files, @RequestParam(required = false) String[] candidateTokens,
        @RequestParam(required = false) String marker, @RequestParam(required = false) String moderator,
        @RequestParam(required = false) Integer rubricQuestion, @RequestParam(required = false) String rubricVersion,
        Principal principal) throws Exception {
        check(files.size() >= 1 && files.size() <= 10, "Upload 1–10 files at a time");
        boolean automaticTokens = candidateTokens == null || candidateTokens.length == 0;
        check(automaticTokens || candidateTokens.length == files.size(), "Provide one candidate token per file");
        String tenant = AccessScope.tenant();
        String assignedMarker = assignee(marker, tenant, "MARKER");
        String assignedModerator = assignee(moderator, tenant, "MODERATOR");
        var created = new ArrayList<Path>();
        var scripts = new ArrayList<Map<String, Object>>();
        boolean databaseStarted = false;
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                if (file.isEmpty()) throw DocumentApi.invalid("The document is empty");
                if (file.getSize() > 20L * 1024 * 1024)
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Each file must be under 20 MB");
                byte[] bytes = file.getBytes();
                String mime = detect(bytes);
                String suppliedMime = file.getContentType();
                if (suppliedMime != null && !suppliedMime.equals("application/octet-stream") && !mime.equals(suppliedMime))
                    throw DocumentApi.invalid("The declared file type does not match the document");
                int pages = documents.validate(bytes, mime);
                String id = "SCR-" + UUID.randomUUID();
                String inputToken = automaticTokens ? "" : candidateTokens[i].strip();
                String token = inputToken.isEmpty() ? "C-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)
                    : inputToken.toUpperCase(Locale.ROOT);
                check(token.matches("[A-Z0-9][A-Z0-9_-]{0,39}"), "Candidate token must be a code of up to 40 letters, digits, hyphens or underscores");
                var script = new LinkedHashMap<String, Object>();
                script.put("id", id);
                script.put("tenant", tenant);
                script.put("exam", exam);
                script.put("candidateToken", token);
                script.put("marker", assignedMarker);
                script.put("moderator", assignedModerator);
                script.put("sha256", ReportService.hex(bytes));
                script.put("encryptionVersion", 1);
                script.put("mime", mime);
                script.put("pages", pages);
                script.put("size", bytes.length);
                script.put("status", "To Review");
                script.put("remarks", "");
                script.put("ocr", "");
                script.put("history", new ArrayList<>());
                script.put("updated", Instant.now().toString());
                script.put("uploadedBy", principal.getName());
                created.add(documents.store(script, bytes));
                scripts.add(script);
            }
            databaseStarted = true;
            return service.change(version, w -> {
                var selected = exam(w, exam);
                var rubric = uploadRubric(selected, rubricQuestion, rubricVersion);
                var submittedTokens = new HashSet<>();
                for (var script : scripts) {
                    uniqueToken(w, exam, script);
                    if (!submittedTokens.add(script.get("candidateToken")))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "The candidate code is duplicated in this upload");
                }
                for (var script : scripts) {
                    bindRubric(script, selected, rubric);
                    w.scripts.add(script);
                    audit(w, "SCRIPT_UPLOADED", script.get("id").toString(), script.get("sha256").toString());
                }
            });
        } catch (Exception ex) {
            // A connection failure during commit can leave an accepted database reference.
            if (!databaseStarted || ex instanceof ResponseStatusException response && response.getStatusCode().is4xxClientError()) {
                for (Path path : created) Files.deleteIfExists(path);
            }
            throw ex;
        }
    }

    private record UploadRubric(List<Map<String, Object>> answers, int scope) {}

    private static UploadRubric uploadRubric(Map<String, Object> exam, Integer selected, String expectedRubric) {
        int count = ((Number) exam.get("questions")).intValue();
        check(selected == null || selected >= 0 && selected <= count, "Select a rubric within this exam");
        List<Map<String, Object>> answers = new ArrayList<>();
        int first = selected != null && selected > 0 ? selected : 1;
        int last = selected == null ? count : first;
        for (int question = first; question <= last; question++) {
            int scope = selected == null ? question : selected;
            Object criteria = questionCriteria(exam, scope);
            String version = rubricVersion(exam, scope);
            check(criteria instanceof List<?> list && !list.isEmpty() && !"legacy".equals(version),
                "Publish the selected rubric before uploading");
            if (selected != null && !version.equals(expectedRubric))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected rubric changed. Reload and select it again.");
            var items = exam.get("items") instanceof List<?> rows ? rows : List.of();
            Map<?, ?> item = question <= items.size() ? (Map<?, ?>) items.get(question - 1) : Map.of();
            if (scope > 0 && item.containsKey("maximum")) {
                check(maximum(criteria).compareTo(Marks.value(item, "maximum")) == 0,
                    "Update the rubric for question " + question + " to match the exam maximum before uploading");
            }
            var answer = new LinkedHashMap<String, Object>(Map.of(
                "question", answers.size() + 1, "examQuestion", scope, "page", 1, "ocr", "", "remarks", "",
                "reviewed", false, "maximum", maximum(criteria), "criteria", copy(criteria), "rubricVersion", version));
            answer.put("description", Objects.toString(item.get("description"), ""));
            answers.add(answer);
        }
        return new UploadRubric(answers, selected == null ? -1 : selected);
    }

    private static void bindRubric(Map<String, Object> script, Map<String, Object> exam, UploadRubric rubric) {
        List<Map<String, Object>> answers = new ArrayList<>();
        for (var source : rubric.answers()) {
            var answer = new LinkedHashMap<>(source);
            answer.put("id", "ANS-" + UUID.randomUUID());
            if ("CRITERIA".equals(exam.get("scoringMode"))) answer.put("scoringMode", "CRITERIA");
            answers.add(answer);
        }
        script.put("exam", exam.get("id"));
        script.put("rubricScope", rubric.scope());
        script.put("criteria", answers.getFirst().get("criteria"));
        script.put("answers", answers);
        script.put("rubricSetVersion", "RSET-" + UUID.randomUUID());
        script.put("maximum", answers.stream().map(a -> Marks.value(a, "maximum")).reduce(BigDecimal.ZERO, BigDecimal::add));
        script.remove("automationPages");
    }

    private static void uniqueToken(Workspace w, String exam, Map<String, Object> script) {
        if (script.get("candidateToken") != null && w.scripts.stream().anyMatch(other -> exam.equals(other.get("exam"))
            && !script.get("id").equals(other.get("id")) && script.get("candidateToken").equals(other.get("candidateToken"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The candidate code is already used in that exam");
    }

    public record Binding(long version, String exam, Integer rubricQuestion, String rubricVersion) {}

    @PostMapping("/scripts/{id}/binding") public Workspace binding(@PathVariable String id, @RequestBody Binding request) {
        return service.change(request.version, w -> {
            var script = WorkspaceService.script(w, id);
            check("To Review".equals(script.get("status")) && answers(script).stream().noneMatch(answer ->
                Boolean.TRUE.equals(answer.get("reviewed")) || answer.containsKey("markRevision") ||
                !Objects.toString(answer.get("ocr"), "").isBlank() || answer.get("score") != null &&
                (script.containsKey("rubricScope") || Marks.value(answer, "score").signum() != 0)),
                "Only scripts without saved marker review can change exam or rubric");
            var exam = exam(w, request.exam);
            var rubric = uploadRubric(exam, request.rubricQuestion, request.rubricVersion);
            uniqueToken(w, request.exam, script);
            bindRubric(script, exam, rubric);
            audit(w, "SCRIPT_RUBRIC_CHANGED", id, script.get("rubricSetVersion").toString());
        });
    }

    public record Assignment(long version, String marker, String moderator) {}

    @PostMapping("/scripts/{id}/assignment") public Workspace assign(@PathVariable String id, @RequestBody Assignment r) {
        String marker = assignee(r.marker, AccessScope.tenant(), "MARKER");
        String moderator = assignee(r.moderator, AccessScope.tenant(), "MODERATOR");
        return service.change(r.version, w -> {
            var script = WorkspaceService.script(w, id);
            script.put("marker", marker);
            script.put("moderator", moderator);
            audit(w, "ASSIGNMENT_CHANGED", id, marker + "|" + moderator);
        });
    }

    private String assignee(String email, String tenant, String role) {
        var candidates = accounts.members().stream()
            .filter(m -> m.active() && m.tenant().equals(tenant) && m.role().equals(role)).toList();
        if ((email == null || email.isBlank()) && candidates.size() == 1) return candidates.getFirst().email();
        return candidates.stream().filter(m -> m.email().equals(email)).map(InstitutionAccounts.Member::email)
            .findFirst().orElseThrow(() -> bad("Select an active " + role.toLowerCase(Locale.ROOT) + " in your institution"));
    }

    @GetMapping("/scripts/{id}/document") public ResponseEntity<byte[]> document(@PathVariable String id) throws Exception {
        var script = WorkspaceService.script(service.read(), id);
        boolean pdf = "application/pdf".equals(script.get("mime"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(script.get("mime").toString()))
            .header("Content-Disposition", (pdf ? "attachment" : "inline") + "; filename=answer-script." + (pdf ? "pdf" :
                "image/png".equals(script.get("mime")) ? "png" : "jpg"))
            .cacheControl(CacheControl.noStore()).body(documents.original(script));
    }

    public record Award(BigDecimal marks, String evidence, Integer region) {}
    public record ReviewRequest(long version, String ocr, String remarks, BigDecimal score, boolean submit,
        Integer question, Integer page, List<Award> awards, Boolean manualReview) {}

    @PostMapping("/scripts/{id}/review") public Workspace review(@PathVariable String id, @RequestBody ReviewRequest r, Principal principal) {
        return service.change(r.version, w -> {
            Map<String,Object> s = WorkspaceService.script(w, id);
            check(!List.of("Approved", "Pending Moderation").contains(s.get("status")), "Submitted or approved marks cannot be edited");
            List<Map<String,Object>> answers = answers(s);
            int q = r.question == null ? 1 : r.question;
            check(q >= 1 && q <= answers.size(), "Question is outside this script");
            Map<String,Object> answer = answers.get(q - 1);
            BigDecimal max = Marks.value(answer, "maximum");
            var score = r.score == null && !r.submit ? null : Marks.validate(r.score, max, "Score exceeds rubric limits");
            String transcription = bounded(r.ocr, 30000);
            String remarks = bounded(r.remarks, 2000);
            boolean manual = Boolean.TRUE.equals(r.manualReview);
            check(!manual || !remarks.isBlank(), "Give a reason for manual review");
            if ("CRITERIA".equals(answer.get("scoringMode")) && score != null) {
                var criteria = (List<?>) answer.get("criteria");
                check(r.awards != null && r.awards.size() == criteria.size(), "Score each rubric criterion");
                BigDecimal awarded = BigDecimal.ZERO;
                List<Map<String, Object>> evidence = new ArrayList<>();
                var regions = DocumentApi.evidenceRegions(answer);
                for (int i = 0; i < criteria.size(); i++) {
                    var criterion = (Map<?, ?>) criteria.get(i);
                    var award = r.awards.get(i);
                    check(award != null, "Criterion marks are required");
                    var marks = Marks.validate(award.marks, Marks.value(criterion, "marks"), "Criterion score exceeds its maximum");
                    String quote = bounded(Objects.toString(award.evidence, ""), 2000).strip();
                    int start = quote.isBlank() ? -1 : transcription.indexOf(quote);
                    boolean needsRegion = award.region != null || !regions.isEmpty();
                    int regionIndex = award.region == null ? 0 : award.region;
                    check(!needsRegion || regionIndex >= 0 && regionIndex < regions.size(), "Evidence region does not exist");
                    check(marks.signum() == 0 || start >= 0 || (manual && needsRegion),
                        "Positive marks need an exact excerpt or a manually inspected source region");
                    var entry = new LinkedHashMap<String, Object>(Map.of("marks", marks, "evidence", quote));
                    if (start >= 0) { entry.put("start", start); entry.put("end", start + quote.length()); }
                    if (needsRegion) entry.put("source", copy(regions.get(regionIndex)));
                    if (start < 0 || !needsRegion) entry.put("page", r.page == null ? answer.getOrDefault("page", 1) : r.page);
                    evidence.add(entry);
                    awarded = awarded.add(marks);
                }
                check(awarded.compareTo(score) == 0, "Question score must equal the sum of criterion marks");
                answer.put("awards", evidence);
            } else if (score == null) {
                answer.remove("awards");
            }
            check(!Suggestions.overridden(answer, score, r.awards) || !remarks.isBlank(),
                "Explain any change to the suggested marks in Remarks");
            if (score == null && r.awards != null && "CRITERIA".equals(answer.get("scoringMode"))) {
                var criteria = (List<?>) answer.get("criteria");
                check(r.awards.size() == criteria.size(), "Provide one draft per criterion");
                var drafts = new ArrayList<Map<String, Object>>();
                for (int i = 0; i < criteria.size(); i++) {
                    var award = r.awards.get(i);
                    check(award != null, "Criterion draft is required");
                    var row = new LinkedHashMap<String, Object>();
                    row.put("evidence", bounded(Objects.toString(award.evidence, ""), 2000));
                    var regions = DocumentApi.evidenceRegions(answer);
                    if (award.region != null && award.region >= 0 && award.region < regions.size())
                        row.put("source", copy(regions.get(award.region)));
                    if (award.marks != null) row.put("marks", Marks.validate(award.marks,
                        Marks.value((Map<?, ?>) criteria.get(i), "marks"), "Criterion score exceeds its maximum"));
                    drafts.add(row);
                }
                answer.put("draftAwards", drafts);
            } else answer.remove("draftAwards");
            boolean reviewed = score != null && (!transcription.isBlank() || manual);
            if (r.submit) {
                check(reviewed && java.util.stream.IntStream.range(0, answers.size()).allMatch(i -> i == q - 1 ||
                    Boolean.TRUE.equals(answers.get(i).get("reviewed")) && answers.get(i).get("score") != null),
                    "Review and score every question before submitting. Unreadable answers require a manual review reason.");
            }
            if (!transcription.equals(answer.get("ocr")) &&
                !(answer.get("ocrJob") instanceof Map<?, ?> job && transcription.equals(job.get("candidate"))))
                answer.remove("suggestion");
            if (r.page != null) {
                int pages = ((Number) s.getOrDefault("pages", 500)).intValue();
                check(r.page >= 1 && r.page <= pages, "Page is outside the original document");
                answer.put("page", r.page);
            }
            answer.put("ocr", transcription);
            answer.put("remarks", remarks);
            if (score == null) answer.remove("score"); else answer.put("score", score);
            answer.put("reviewedBy", principal.getName());
            answer.put("reviewed", reviewed);
            answer.put("manualReview", manual);
            if (score != null) answer.put("markRevision", "MARK-" + UUID.randomUUID());
            else answer.remove("markRevision");
            s.put("ocr", transcription);
            s.put("remarks", remarks);
            if (answers.stream().anyMatch(x -> x.get("score") == null)) s.remove("score");
            else s.put("score", answers.stream().map(x -> Marks.value(x, "score")).reduce(BigDecimal.ZERO, BigDecimal::add));
            s.put("status", r.submit ? "Pending Moderation" : s.containsKey("activeAppeal") ? "Appeal Review" : "In Review");
            s.put("reviewedBy", principal.getName());
            if (r.submit) {
                var submitted = snapshot(s);
                String revisionId = "REV-" + UUID.randomUUID();
                submitted.put("revisionId", revisionId);
                submitted.put("submittedBy", principal.getName());
                submitted.put("submittedAt", Instant.now().toString());
                s.put("submittedRevisionId", revisionId);
                revisions(s, "submittedRevisions").add(submitted);
            }
            event(s, r.submit ? "Submitted for moderation" : "Draft saved", principal.getName());
            audit(w, r.submit ? "MARKS_SUBMITTED" : "MARK_DRAFT_SAVED", id,
                Objects.toString(s.get("submittedRevisionId"), Objects.toString(answer.get("markRevision"), "")));
        });
    }
    public record VersionRequest(long version) {}
    @PostMapping("/scripts/{id}/approve") public Workspace approve(@PathVariable String id, @RequestBody VersionRequest r, Principal principal) {
        return service.change(r.version, w -> {
            Map<String,Object> s = WorkspaceService.script(w, id);
            check("Pending Moderation".equals(s.get("status")), "Only submitted marks can be approved");
            check(!principal.getName().equals(s.get("reviewedBy")), "A different moderator must approve");
            check(answers(s).stream().noneMatch(a -> principal.getName().equals(a.get("reviewedBy"))), "A different moderator must approve every question");
            s.put("status", "Approved"); s.put("decisionId", "DEC-" + UUID.randomUUID()); s.put("approvedBy", principal.getName()); event(s, "Approved final marks", principal.getName());
            Map<String,Object> snapshot = snapshot(s);
            snapshot.put("revisionId", s.getOrDefault("submittedRevisionId", "REV-" + UUID.randomUUID()));
            snapshot.put("approvedBy", principal.getName());
            snapshot.put("approvedAt", Instant.now().toString());
            signApproval(w, s, snapshot);
            s.put("approvedSnapshot", snapshot);
            s.putIfAbsent("submittedRevisionId", snapshot.get("revisionId"));
            revisions(s, "approvedRevisions").add(copy(snapshot));
            audit(w, "MARKS_APPROVED", id, snapshot.get("revisionId").toString());
            if (s.containsKey("activeAppeal")) {
                Map<String,Object> appeal = activeAppeal(s); appeal.put("status", "Resolved"); appeal.put("resolvedBy", principal.getName());
                appeal.put("resolvedAt", Instant.now().toString());
                appeal.put("decision", "APPROVED_REASSESSMENT");
                appeal.put("newRevisionId", snapshot.get("revisionId"));
                s.remove("activeAppeal");
            }
        });
    }
    private void signApproval(Workspace w, Map<String, Object> script, Map<String, Object> snapshot) {
        try {
            var markRefs = answers(script).stream().map(answer -> Map.of(
                "question", answer.get("question"),
                "rubricVersion", answer.getOrDefault("rubricVersion", "legacy"),
                "markRevision", answer.getOrDefault("markRevision", "legacy"),
                "score", answer.get("score"))).toList();
            var references = Map.of("revisionId", snapshot.get("revisionId"), "scriptId", script.get("id"),
                "documentSha256", script.getOrDefault("sha256", "legacy"), "score", snapshot.get("score"),
                "approver", snapshot.get("approvedBy"), "markRevisions", markRefs);
            var seal = signer.sign(w.id, "MARK_APPROVAL", ArtifactSigner.canonical(references), references);
            snapshot.put("seal", Map.of("manifest", seal.manifest(), "signature", seal.signature(), "keyId", seal.keyId()));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The approval could not be signed", ex);
        }
    }

    public record ReasonRequest(long version, String reason) {}
    @PostMapping("/scripts/{id}/return") public Workspace returnReview(@PathVariable String id, @RequestBody ReasonRequest r, Principal principal) {
        String reason = text(r.reason, 2000);
        return service.change(r.version, w -> {
            var s = WorkspaceService.script(w, id);
            check("Pending Moderation".equals(s.get("status")), "Only submitted scripts can be returned");
            check(!principal.getName().equals(s.get("reviewedBy")), "A different moderator must return the review");
            s.put("status", s.containsKey("activeAppeal") ? "Appeal Review" : "In Review");
            s.put("decisionId", "DEC-" + UUID.randomUUID());
            event(s, "Returned for correction: " + reason, principal.getName());
            audit(w, "MARKS_RETURNED", id, Objects.toString(s.get("submittedRevisionId"), ""));
        });
    }
    @PostMapping("/scripts/{id}/appeals") public Workspace appeal(@PathVariable String id, @RequestBody ReasonRequest r, Principal principal) {
        String reason = text(r.reason, 2000);
        return service.change(r.version, w -> {
            var s = WorkspaceService.script(w, id);
            check("Approved".equals(s.get("status")), "Appeals can only be opened for approved marks");
            check(!s.containsKey("activeAppeal"), "An appeal is already open");
            if (!s.containsKey("approvedSnapshot")) {
                answers(s);
                var previous = snapshot(s); previous.put("approvedBy", s.getOrDefault("approvedBy", "Legacy approval"));
                s.put("approvedSnapshot", previous); revisions(s, "approvedRevisions").add(copy(previous));
            }
            String appealId = UUID.randomUUID().toString();
            Map<String,Object> appeal = new LinkedHashMap<>(Map.of("id", appealId, "reason", reason, "status", "Open",
                "openedBy", principal.getName(), "openedAt", Instant.now().toString(),
                "priorRevisionId", ReportService.revisionId((Map<?, ?>) s.get("approvedSnapshot"))));
            appeals(s).add(appeal); s.put("activeAppeal", appealId); s.put("status", "Appeal Review");
            event(s, "Appeal opened: " + reason, principal.getName());
            audit(w, "APPEAL_OPENED", id, appealId);
        });
    }
    @PostMapping("/scripts/{id}/appeals/resolve")
    public Workspace uphold(@PathVariable String id, @RequestBody ReasonRequest request, Principal principal) {
        String reason = text(request.reason, 2000);
        return service.change(request.version, w -> {
            var script = WorkspaceService.script(w, id);
            check(script.containsKey("activeAppeal"), "No open appeal exists");
            check(!principal.getName().equals(script.get("reviewedBy")), "A different moderator must decide");
            var previous = (Map<?, ?>) script.get("approvedSnapshot");
            check(previous != null, "The original approval is required");
            var appeal = activeAppeal(script);
            appeal.put("status", "Resolved");
            appeal.put("decision", "UPHELD_PRIOR_MARKS");
            appeal.put("decisionReason", reason);
            appeal.put("resolvedBy", principal.getName());
            appeal.put("resolvedAt", Instant.now().toString());
            for (String key : List.of("score", "maximum", "ocr", "remarks", "answers", "criteria")) {
                if (previous.containsKey(key)) script.put(key, copy(previous.get(key)));
            }
            script.put("status", "Approved");
            script.remove("activeAppeal");
            event(script, "Appeal resolved: prior approval upheld", principal.getName());
            audit(w, "APPEAL_UPHELD", id, ReportService.revisionId(previous));
        });
    }

    @SuppressWarnings("unchecked") public static List<Map<String,Object>> answers(Map<String,Object> s) {
        if (s.get("answers") instanceof List<?>) return (List<Map<String,Object>>)s.get("answers");
        var answer = new LinkedHashMap<String,Object>();
        for (String key : List.of("score", "ocr", "remarks", "maximum", "criteria", "reviewedBy"))
            if (s.containsKey(key)) answer.put(key, copy(s.get(key)));
        answer.put("question", 1); answer.put("page", 1); answer.put("reviewed", !s.getOrDefault("ocr", "").toString().isBlank());
        var list = new ArrayList<Map<String,Object>>(); list.add(answer); s.put("answers", list); return list;
    }
    @SuppressWarnings("unchecked") private static List<Object> revisions(Map<String,Object> s, String key) {
        return (List<Object>)s.computeIfAbsent(key, k -> new ArrayList<>());
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> appeals(Map<String,Object> s) {
        return (List<Map<String,Object>>)s.computeIfAbsent("appeals", k -> new ArrayList<>());
    }
    private static Map<String,Object> activeAppeal(Map<String,Object> s) {
        return appeals(s).stream().filter(a -> Objects.equals(a.get("id"), s.get("activeAppeal"))).findFirst().orElseThrow();
    }
    private static Map<String,Object> snapshot(Map<String,Object> s) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("score", "maximum", "ocr", "remarks", "answers", "criteria"))
            if (s.containsKey(key)) result.put(key, copy(s.get(key)));
        return result;
    }
    static Object copy(Object value) {
        if (value instanceof Map<?,?> map) { Map<String,Object> result = new LinkedHashMap<>(); map.forEach((k,v) -> result.put(k.toString(), copy(v))); return result; }
        if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(AssessmentApi::copy).toList());
        return value;
    }
    @GetMapping(value="/exports/marks.csv", produces="text/csv;charset=UTF-8") public ResponseEntity<String> export() {
        StringBuilder csv = new StringBuilder("\ufeffScript ID,Exam ID,Score,Maximum,Status,Candidate Token,Revision,Issuer,Approved At,Source SHA-256\r\n");
        for (var s : AccessScope.view(service.read()).scripts) {
            Map<?,?> approved = s.get("approvedSnapshot") instanceof Map<?,?> m ? m : "Approved".equals(s.get("status")) ? s : null;
            if (approved != null) csv.append(s.get("id")).append(',').append(s.get("exam")).append(',').append(approved.get("score"))
                .append(',').append(approved.get("maximum")).append(",Approved,")
                .append(s.getOrDefault("candidateToken", "")).append(',').append(ReportService.revisionId(approved)).append(',')
                .append(AccessScope.tenant()).append(',').append(Objects.toString(approved.get("approvedAt"), "")).append(',')
                .append(s.getOrDefault("sha256", "")).append("\r\n");
        }
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=approved-mark-sheet.csv")
            .cacheControl(CacheControl.noStore()).body(csv.toString());
    }
    @SuppressWarnings("unchecked") private static void event(Map<String,Object> s, String action, String user) {
        String time = Instant.now().toString(); s.put("updated", time);
        var history = (List<Map<String, Object>>) s.computeIfAbsent("history", key -> new ArrayList<>());
        var entry = new LinkedHashMap<String, Object>(Map.of("action", action, "time", time, "user", user,
            "answers", copy(s.getOrDefault("answers", List.of()))));
        if (s.containsKey("score")) entry.put("score", s.get("score"));
        history.add(entry);
    }
    private static Map<String,Object> exam(Workspace w, String id) {
        return w.exams.stream().filter(e -> Objects.equals(id, e.get("id"))).findFirst().orElseThrow(() -> bad("Exam not found"));
    }
    private static Object questionCriteria(Map<String,Object> exam,int q) {
        if(exam.get("questionRubrics") instanceof Map<?,?> rubrics&&rubrics.containsKey(Integer.toString(q))) return rubrics.get(Integer.toString(q));
        return exam.get("criteria");
    }
    private static BigDecimal maximum(Object criteria) {
        return ((List<?>) criteria).stream().map(item -> Marks.value((Map<?, ?>) item, "marks"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static void audit(Workspace w, String action, String object, String revision) {
        w.audit.add(Map.of("id", UUID.randomUUID().toString(), "actor", AccessScope.user(), "action", action,
            "object", object, "revision", revision, "version", w.version + 1, "time", Instant.now().toString()));
    }

    @GetMapping("/audit") public ResponseEntity<List<Map<String, Object>>> auditLog() {
        if (!"TEACHER".equals(AccessScope.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read().audit);
    }

    private static String detect(byte[] b) {
        if (b.length > 5 && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (b.length > 8 && b[0] == (byte)0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47) return "image/png";
        if (b.length > 3 && b[0] == (byte)0xff && b[1] == (byte)0xd8 && b[2] == (byte)0xff) return "image/jpeg";
        throw DocumentApi.invalid("Only PDF, PNG and JPEG documents are accepted");
    }
    private static String text(String s, int max) { String value = bounded(s, max).strip(); check(!value.isEmpty(), "Required text is missing"); return value; }
    private static String bounded(String s, int max) { check(s != null && s.length() <= max, "Text is missing or too long"); return s; }
    private static void check(boolean condition, String message) { if (!condition) throw bad(message); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
