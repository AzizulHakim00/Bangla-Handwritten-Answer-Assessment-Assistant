package com.assessment.controller;

import com.assessment.model.Workspace;
import com.assessment.service.WorkspaceService;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import java.security.Principal;
import org.springframework.beans.factory.annotation.Value;
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
    private final Path storage;
    public AssessmentApi(WorkspaceService service, @Value("${assessment.storage:./data/scripts}") String directory) throws Exception {
        this.service = service; this.storage = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(storage);
    }
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/workspace") public Map<String,Object> workspace(Authentication auth) {
        Workspace w = service.read();
        return Map.of("version", w.version, "exams", w.exams, "scripts", w.scripts, "criteria", w.criteria,
            "user", auth.getName(), "role", auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", ""));
    }
    public record ExamRequest(long version, String name, String subject, int questions, String date) {}
    @PostMapping("/exams") public Workspace exam(@RequestBody ExamRequest r) {
        check(r.questions >= 1 && r.questions <= 100, "Questions must be between 1 and 100");
        String name = text(r.name, 120), subject = text(r.subject, 80);
        try { java.time.LocalDate.parse(r.date); } catch (Exception ex) { throw bad("Choose a valid date"); }
        return service.change(r.version, w -> w.exams.add(new LinkedHashMap<>(Map.of("id", "EX-" + UUID.randomUUID(),
            "name", name, "subject", subject, "questions", r.questions, "date", r.date))));
    }
    public record Criterion(String text, int marks) {}
    public record RubricRequest(long version, String exam, List<Criterion> criteria) {}
    @PostMapping("/rubrics") public Workspace rubric(@RequestBody RubricRequest r) {
        check(r.criteria != null && !r.criteria.isEmpty() && r.criteria.size() <= 30, "Provide 1–30 criteria");
        List<Map<String,Object>> criteria = new ArrayList<>();
        for (Criterion c : r.criteria) {
            check(c.marks > 0 && c.marks <= 100, "Criterion marks must be 1–100");
            criteria.add(new LinkedHashMap<>(Map.of("text", text(c.text, 300), "marks", c.marks)));
        }
        return service.change(r.version, w -> {
            Map<String,Object> exam = exam(w, r.exam);
            // Existing scripts retain the rubric snapshot captured at upload.
            exam.put("criteria", criteria); w.criteria = criteria;
        });
    }
    @PostMapping("/uploads") public Workspace upload(@RequestParam long version, @RequestParam String exam,
            @RequestParam("files") List<MultipartFile> files, Principal principal) throws Exception {
        check(files.size() >= 1 && files.size() <= 10, "Upload 1–10 files at a time");
        List<Path> created = new ArrayList<>();
        try {
            List<Map<String,Object>> scripts = new ArrayList<>();
            for (MultipartFile file : files) {
                check(!file.isEmpty() && file.getSize() <= 20 * 1024 * 1024, "Each file must be under 20 MB");
                byte[] bytes = file.getBytes();
                String mime = detect(bytes);
                String id = "SCR-" + UUID.randomUUID();
                Path path = storage.resolve(id); Files.write(path, bytes, StandardOpenOption.CREATE_NEW); created.add(path);
                Map<String,Object> s = new LinkedHashMap<>();
                s.put("id", id); s.put("exam", exam); s.put("filename", text(file.getOriginalFilename(), 200));
                s.put("mime", mime); s.put("status", "To Review"); s.put("score", 0);
                s.put("remarks", ""); s.put("ocr", ""); s.put("history", new ArrayList<>());
                s.put("updated", Instant.now().toString()); s.put("uploadedBy", principal.getName()); scripts.add(s);
            }
            return service.change(version, w -> {
                Map<String,Object> selected = exam(w, exam);
                Object criteria = selected.get("criteria");
                check(criteria instanceof List<?> list && !list.isEmpty(), "Save an exam rubric before uploading");
                scripts.forEach(s -> { s.put("criteria", criteria); s.put("maximum", maximum(criteria)); });
                w.scripts.addAll(scripts);
            });
        } catch (Exception ex) { for (Path p : created) Files.deleteIfExists(p); throw ex; }
    }
    @GetMapping("/scripts/{id}/document") public ResponseEntity<byte[]> document(@PathVariable String id) throws Exception {
        Map<String,Object> s = WorkspaceService.script(service.read(), id);
        Path path = storage.resolve(id).normalize();
        check(path.getParent().equals(storage), "Invalid document path");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)s.get("mime")))
            .header("Content-Disposition", "inline; filename=answer-script")
            .cacheControl(CacheControl.noStore()).body(Files.readAllBytes(path));
    }
    public record ReviewRequest(long version, String ocr, String remarks, Integer score, boolean submit) {}
    @PostMapping("/scripts/{id}/review") public Workspace review(@PathVariable String id, @RequestBody ReviewRequest r, Principal principal) {
        return service.change(r.version, w -> {
            Map<String,Object> s = WorkspaceService.script(w, id);
            check(!List.of("Approved", "Pending Moderation").contains(s.get("status")), "Submitted or approved marks cannot be edited");
            int max = ((Number)s.get("maximum")).intValue();
            check(r.score != null && r.score >= 0 && r.score <= max, "Score exceeds rubric limits");
            String transcription = bounded(r.ocr, 30000);
            check(!r.submit || !transcription.isBlank(), "Transcribe the answer before submitting. Unreadable answers require manual review.");
            s.put("ocr", transcription); s.put("remarks", bounded(r.remarks, 2000)); s.put("score", r.score);
            s.put("status", r.submit ? "Pending Moderation" : "In Review"); s.put("reviewedBy", principal.getName());
            event(s, r.submit ? "Submitted for moderation" : "Draft saved", principal.getName());
        });
    }
    public record VersionRequest(long version) {}
    @PostMapping("/scripts/{id}/approve") public Workspace approve(@PathVariable String id, @RequestBody VersionRequest r, Principal principal) {
        return service.change(r.version, w -> {
            Map<String,Object> s = WorkspaceService.script(w, id);
            check("Pending Moderation".equals(s.get("status")), "Only submitted marks can be approved");
            check(!principal.getName().equals(s.get("reviewedBy")), "A different moderator must approve");
            s.put("status", "Approved"); s.put("approvedBy", principal.getName()); event(s, "Approved final marks", principal.getName());
        });
    }
    @GetMapping(value="/exports/marks.csv", produces="text/csv;charset=UTF-8") public ResponseEntity<String> export() {
        StringBuilder csv = new StringBuilder("\ufeffScript ID,Exam ID,Score,Maximum,Status\r\n");
        for (var s : service.read().scripts) if ("Approved".equals(s.get("status")))
            csv.append(s.get("id")).append(',').append(s.get("exam")).append(',').append(s.get("score"))
                .append(',').append(s.get("maximum")).append(",Approved\r\n");
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=approved-mark-sheet.csv")
            .cacheControl(CacheControl.noStore()).body(csv.toString());
    }
    @SuppressWarnings("unchecked") private static void event(Map<String,Object> s, String action, String user) {
        String time = Instant.now().toString(); s.put("updated", time);
        ((List<Map<String,Object>>)s.get("history")).add(new LinkedHashMap<>(Map.of("action", action, "time", time,
            "user", user, "score", s.get("score"), "ocr", s.get("ocr"), "remarks", s.get("remarks"))));
    }
    private static Map<String,Object> exam(Workspace w, String id) {
        return w.exams.stream().filter(e -> Objects.equals(id, e.get("id"))).findFirst().orElseThrow(() -> bad("Exam not found"));
    }
    private static int maximum(Object criteria) {
        int sum = 0;
        for (Object c : (List<?>)criteria) sum += ((Number)((Map<?,?>)c).get("marks")).intValue();
        return sum;
    }
    private static String detect(byte[] b) {
        if (b.length > 5 && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (b.length > 8 && b[0] == (byte)0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47) return "image/png";
        if (b.length > 3 && b[0] == (byte)0xff && b[1] == (byte)0xd8 && b[2] == (byte)0xff) return "image/jpeg";
        throw bad("Only PDF, PNG and JPEG documents are accepted");
    }
    private static String text(String s, int max) { String value = bounded(s, max).strip(); check(!value.isEmpty(), "Required text is missing"); return value; }
    private static String bounded(String s, int max) { check(s != null && s.length() <= max, "Text is missing or too long"); return s; }
    private static void check(boolean condition, String message) { if (!condition) throw bad(message); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
