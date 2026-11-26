package com.assessment;

import com.assessment.service.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"TEACHER_PASSWORD=teacher-test-password", "MARKER_PASSWORD=marker-test-password",
    "MODERATOR_PASSWORD=moderator-test-password", "assessment.partitioned=true", "assessment.storage=target/integration-private"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "P33_TEST_MONGO_URI", matches = ".+")
class ProductAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocumentService files;

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry settings) {
        settings.add("spring.data.mongodb.uri", () -> System.getenv("P33_TEST_MONGO_URI"));
    }

    @Test void signedAssessmentCanBeReviewedAppealedAndVerified() throws Exception {
        String suffix = Long.toUnsignedString(System.nanoTime());
        var teacher = account("teacher@school.local", "TEACHER", "school");
        var marker = account("marker@school.local", "MARKER", "school");
        var moderator = account("moderator@school.local", "MODERATOR", "school");
        var otherTenant = account("teacher@other.local", "TEACHER", "other-" + suffix);
        var unassigned = account("unassigned@school.local", "MARKER", "school");

        JsonNode created = parse(posted("/api/exams", teacher, Map.of("version", version(teacher),
            "reference", "P33-" + suffix, "name", "বাংলা", "subject", "Bangla", "questions", 1, "date", "2026-11-25",
            "items", java.util.List.of(Map.of("description", "ব্যাখ্যা করো", "maximum", 10)))).andExpect(status().isOk()));
        String exam = created.path("exams").get(created.path("exams").size() - 1).path("id").asText();
        posted("/api/rubrics", teacher, Map.of("version", version(teacher), "exam", exam, "question", 1,
            "criteria", java.util.List.of(Map.of("text", "সংজ্ঞা", "marks", 9.75)))).andExpect(status().isBadRequest());
        var rubricWorkspace = parse(posted("/api/rubrics", teacher, Map.of("version", version(teacher), "exam", exam, "question", 1,
            "criteria", java.util.List.of(Map.of("text", "সংজ্ঞা", "marks", 10, "hint", "সালোকসংশ্লেষণ"))))
            .andExpect(status().isOk()));
        String rubric = last(rubricWorkspace.path("exams")).path("rubricPointers").path("1").asText();
        byte[] signedRubric = mvc.perform(get("/api/rubrics/" + rubric + "/signed").with(teacher))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        verify(teacher, signedRubric, "VALID");

        var image = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB), "png", image);
        byte[] original = image.toByteArray();
        var upload = new MockMultipartFile("files", "identifying-name.png", "image/png", original);
        var malformed = new MockMultipartFile("files", "fake.pdf", "application/pdf", "%PDF-broken".getBytes());
        mvc.perform(multipart("/api/uploads").file(malformed).param("version", Long.toString(version(teacher)))
            .param("exam", exam).with(teacher).with(csrf())).andExpect(status().isUnprocessableEntity());
        var accepted = parse(mvc.perform(multipart("/api/uploads").file(upload).param("version", Long.toString(version(teacher)))
            .param("exam", exam).param("candidateTokens", "SCRIPT-" + suffix).with(teacher).with(csrf())).andExpect(status().isOk()));
        mvc.perform(multipart("/api/uploads").file(upload).param("version", Long.toString(version(teacher)))
            .param("exam", exam).param("candidateTokens", "SCRIPT-" + suffix).with(teacher).with(csrf()))
            .andExpect(status().isConflict());
        var script = last(accepted.path("scripts"));
        String id = script.path("id").asText();
        String answer = script.path("answers").get(0).path("id").asText();
        String rubricSet = script.path("rubricSetVersion").asText();
        assertFalse(script.has("score"));
        assertFalse(script.has("filename"));
        assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(files.path(id))));
        assertArrayEquals(original, mvc.perform(get("/api/scripts/" + id + "/document").with(marker))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/scripts/" + id + "/document").with(otherTenant)).andExpect(status().isNotFound());
        mvc.perform(get("/api/scripts/" + id + "/document").with(unassigned)).andExpect(status().isNotFound());
        workspace(otherTenant).andExpect(jsonPath("$.scripts").isEmpty());
        assertFalse(parse(workspace(unassigned)).path("scripts").toString().contains(id));

        String idem = "accept-" + suffix;
        var spec = Map.of("script_id", id, "rubric_version", rubricSet);
        String job = parse(start(marker, spec, idem).andExpect(status().isAccepted())).path("job_id").asText();
        start(marker, spec, idem).andExpect(status().isAccepted()).andExpect(jsonPath("$.job_id").value(job));
        start(marker, Map.of("script_id", id, "rubric_version", "different"), idem).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/jobs/" + job).with(unassigned)).andExpect(status().isNotFound());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(12).toNanos();
        do {
            var result = parse(mvc.perform(get("/api/v1/jobs/" + job).with(marker)).andExpect(status().isOk()));
            if ("MANUAL_REVIEW".equals(result.path("state").asText())) break;
            assertTrue(System.nanoTime() < deadline, "Durable job did not finish");
            Thread.sleep(200);
        } while (true);

        posted("/api/scripts/" + id + "/region", teacher, Map.of("version", version(teacher), "question", 1,
            "page", 1, "x", 0, "y", 0, "width", 1, "height", 1)).andExpect(status().isOk());
        posted("/api/scripts/" + id + "/review", marker, Map.of("version", version(marker), "question", 1,
            "ocr", "সালোকসংশ্লেষণ হলো খাদ্য তৈরির প্রক্রিয়া।", "remarks", ""))
            .andExpect(status().isOk());
        var suggested = parse(posted("/api/scripts/" + id + "/suggestion", marker,
            Map.of("version", version(marker), "question", 1)).andExpect(status().isOk()));
        var advice = last(suggested.path("scripts")).path("answers").get(0).path("suggestion");
        assertEquals("ADVISORY", advice.path("status").asText());
        assertEquals(10, advice.path("criteria").get(0).path("score").asInt());
        mark(marker, answer, 11, "অতিরিক্ত").andExpect(status().isUnprocessableEntity());
        String submitted = parse(mark(marker, answer, 6, "First reviewed mark").andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("Pending Moderation"))).path("submitted_revision_id").asText();
        assertTrue(submitted.startsWith("REV-"));
        long pending = version(moderator);
        posted("/api/v1/reviews", marker, Map.of("resource_id", id, "expected_version", pending, "decision", "APPROVE"))
            .andExpect(status().isForbidden());
        posted("/api/v1/reviews", moderator, Map.of("resource_id", submitted, "expected_version", pending, "decision", "APPROVE"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.approved_version").isNotEmpty());
        posted("/api/v1/reviews", moderator, Map.of("resource_id", submitted, "expected_version", pending, "decision", "APPROVE"))
            .andExpect(status().isConflict());
        byte[] oldReport = mvc.perform(get("/api/exports/marks.zip").with(moderator)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        verify(moderator, oldReport, "VALID");

        posted("/api/scripts/" + id + "/appeals", teacher, Map.of("version", version(teacher), "reason", "Recheck definition"))
            .andExpect(status().isOk());
        mark(marker, answer, 7, "Additional evidence accepted").andExpect(status().isOk());
        verify(moderator, oldReport, "VALID");
        posted("/api/v1/reviews", moderator, Map.of("resource_id", id, "expected_version", version(moderator), "decision", "APPROVE"))
            .andExpect(status().isOk());
        verify(moderator, oldReport, "SUPERSEDED");
        var current = parse(workspace(moderator)).path("scripts").findValues("approvedRevisions").stream()
            .filter(history -> history.size() == 2).findFirst().orElseThrow();
        assertEquals(6, current.get(0).path("score").asInt());
        assertEquals(7, current.get(1).path("score").asInt());
        assertFalse(parse(mvc.perform(get("/api/audit").with(teacher)).andExpect(status().isOk())).toString().contains("সালোকসংশ্লেষণ"));
        byte[] changed = Files.readAllBytes(files.path(id));
        changed[changed.length - 1] ^= 1;
        Files.write(files.path(id), changed);
        try {
            mvc.perform(get("/api/scripts/" + id + "/document").with(marker)).andExpect(status().isUnprocessableEntity());
        } finally {
            changed[changed.length - 1] ^= 1;
            Files.write(files.path(id), changed);
        }
    }

    private ResultActions mark(RequestPostProcessor user, String answer, int score, String reason) throws Exception {
        return posted("/api/v1/p33/mark-reviews", user, Map.of("answer_id", answer, "score", score, "rationale", reason,
            "expected_version", version(user), "transcription", "সালোকসংশ্লেষণ হলো খাদ্য তৈরির প্রক্রিয়া।", "submit", true,
            "awards", java.util.List.of(Map.of("marks", score, "evidence", "সালোকসংশ্লেষণ", "region", 0))));
    }

    private void verify(RequestPostProcessor user, byte[] bytes, String statusName) throws Exception {
        mvc.perform(multipart("/api/verify").file(new MockMultipartFile("file", "artifact.zip", "application/zip", bytes))
            .with(user).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(statusName));
    }

    private ResultActions start(RequestPostProcessor user, Object body, String key) throws Exception {
        return mvc.perform(post("/api/v1/p33/script-assessments").header("Idempotency-Key", key).with(user).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions posted(String path, RequestPostProcessor user, Object body) throws Exception {
        return mvc.perform(post(path).with(user).with(csrf()).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private ResultActions workspace(RequestPostProcessor user) throws Exception {
        return mvc.perform(get("/api/workspace").with(user)).andExpect(status().isOk());
    }

    private JsonNode last(JsonNode list) { return list.get(list.size() - 1); }

    private long version(RequestPostProcessor user) throws Exception { return parse(workspace(user)).path("version").asLong(); }

    private JsonNode parse(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }

    private static RequestPostProcessor account(String email, String role, String tenant) {
        return user(email).authorities(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("TENANT_" + tenant));
    }
}
