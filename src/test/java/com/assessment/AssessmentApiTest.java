package com.assessment;

import com.assessment.controller.AssessmentApi;
import com.assessment.config.SecurityConfig;
import com.assessment.model.Workspace;
import com.assessment.service.WorkspaceService;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@WebMvcTest(AssessmentApi.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"TEACHER_PASSWORD=teacher-test-password", "MARKER_PASSWORD=marker-test-password", "MODERATOR_PASSWORD=moderator-test-password", "assessment.storage=target/test-documents"})
class AssessmentApiTest {
    @Autowired MockMvc mvc;
    @MockBean WorkspaceService service;
    Workspace workspace;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        workspace = new Workspace(); workspace.version = 1L;
        Map<String,Object> script = new LinkedHashMap<>();
        script.putAll(Map.of("id","SCR-1","maximum",7,"score",6,"status","To Review","ocr","","remarks","","history",new ArrayList<>()));
        workspace.scripts.add(script);
        when(service.read()).thenReturn(workspace);
        when(service.change(anyLong(), any())).thenAnswer(i -> { ((Consumer<Workspace>)i.getArgument(1)).accept(workspace); return workspace; });
    }
    @Test void anonymousCannotReadScripts() throws Exception {
        mvc.perform(get("/api/workspace")).andExpect(status().isUnauthorized());
    }
    @Test void postRequiresCsrf() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER"))
            .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void moderatorCannotChangeMarkerScore() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("moderator").roles("MODERATOR")).with(csrf())
            .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
    }
    @Test void scoreCannotExceedRubric() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":8,\"ocr\":\"answer\",\"remarks\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void blankAnswerCannotBeSubmittedAsZero() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":0,\"ocr\":\"\",\"remarks\":\"\",\"submit\":true}"))
            .andExpect(status().isBadRequest());
    }
    @Test void approvedAnswerCannotBeOverwritten() throws Exception {
        workspace.scripts.get(0).put("status","Approved");
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":2,\"ocr\":\"answer\",\"remarks\":\"\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void reviewerCannotApproveOwnSubmission() throws Exception {
        workspace.scripts.get(0).putAll(Map.of("status","Pending Moderation","reviewedBy","same-person"));
        mvc.perform(post("/api/scripts/SCR-1/approve").with(user("same-person").roles("MODERATOR")).with(csrf())
            .contentType("application/json").content("{\"version\":1}" )).andExpect(status().isBadRequest());
    }
    @Test void reviewCreatesHistoricalRevision() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":6,\"ocr\":\"Verified answer\",\"remarks\":\"Reviewed\",\"submit\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[0].status").value("Pending Moderation"))
            .andExpect(jsonPath("$.scripts[0].history[0].score").value(6));
    }
}
