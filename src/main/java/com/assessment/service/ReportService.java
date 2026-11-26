package com.assessment.service;

import com.assessment.model.Workspace;
import java.io.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    private final DocumentService documents;
    private final ArtifactSigner signer;
    public ReportService(DocumentService documents) {
        this.documents = documents;
        this.signer = new ArtifactSigner(documents);
    }
    public byte[] pdf(Workspace w) throws IOException {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            List<String> lines=new ArrayList<>();lines.add("APPROVED MARK SHEET");lines.add("Issuer: " + w.id + " | Workspace version: " + w.version);
            lines.add("Generated: "+Instant.now());lines.add("Last approved scores are retained while appeals are reviewed.");lines.add("");
            for(var s:w.scripts){Map<?,?> approved=approved(s);if(approved==null)continue;
                lines.add(s.get("id").toString());lines.add("Exam: "+s.get("exam"));
                lines.add("Score: "+approved.get("score")+" / "+approved.get("maximum")+" | Approved revision");
                lines.add("Candidate: " + s.getOrDefault("candidateToken", s.get("id")));
                lines.add("Revision: " + revisionId(approved)); lines.add("");}
            if(lines.size()==5)lines.add("No approved marks available.");
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for(int start=0;start<lines.size();start+=48){var page=new PDPage(PDRectangle.A4);document.addPage(page);
                try(var stream=new PDPageContentStream(document,page)){stream.beginText();stream.setFont(font,10);stream.setLeading(15);stream.newLineAtOffset(40,790);
                    for(int i=start;i<Math.min(start+48,lines.size());i++){stream.showText(lines.get(i));stream.newLine();}stream.endText();}}
            document.save(output);return output.toByteArray();
        }
    }
    public byte[] signedBundle(Workspace w) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (var script : w.scripts) {
            var approved = approved(script);
            if (approved == null) continue;
            List<Map<String, Object>> questions = new ArrayList<>();
            if (approved.get("answers") instanceof List<?> items) {
                for (Object item : items) {
                    var answer = (Map<?, ?>) item;
                    var question = new LinkedHashMap<String, Object>();
                    question.put("question", answer.get("question"));
                    question.put("score", answer.get("score"));
                    question.put("maximum", answer.get("maximum"));
                    question.put("rubricVersion", answer.get("rubricVersion"));
                    question.put("markRevision", answer.get("markRevision"));
                    questions.add(question);
                }
            }
            results.add(Map.of("scriptId", script.get("id"), "examId", script.get("exam"),
                "candidateToken", script.getOrDefault("candidateToken", script.get("id")),
                "revisionId", revisionId(approved), "score", approved.get("score"), "maximum", approved.get("maximum"),
                "approvedBy", approved.containsKey("approvedBy") ? approved.get("approvedBy") : "legacy",
                "documentSha256", hex(documents.original(script)), "questions", questions));
        }
        if (results.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "No approved marks are available to sign");
        byte[] pdf = pdf(w);
        return ArtifactSigner.bundle("approved-marks.pdf", pdf,
            signer.sign(w.id, "MARK_SHEET", pdf, Map.of("workspaceVersion", w.version, "results", results)));
    }

    public static Map<?, ?> approved(Map<String, Object> script) {
        return script.get("approvedSnapshot") instanceof Map<?, ?> snapshot ? snapshot :
            "Approved".equals(script.get("status")) ? script : null;
    }

    public static String revisionId(Map<?, ?> approved) {
        return approved.containsKey("revisionId") ? approved.get("revisionId").toString() : "legacy";
    }

    public static String hex(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
