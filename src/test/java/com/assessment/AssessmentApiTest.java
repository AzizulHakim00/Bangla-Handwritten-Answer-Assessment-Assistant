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

    @Test void markerCannotOpenAppeal() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/appeals").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"reason\":\"Recheck\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void returnRequiresReasonAndPendingSubmission() throws Exception {
        mvc.perform(post("/api/scripts/SCR-1/return").with(user("moderator").roles("MODERATOR")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"reason\":\"\"}"))
            .andExpect(status().isBadRequest());
        workspace.scripts.get(0).putAll(Map.of("status","Pending Moderation","reviewedBy","marker"));
        mvc.perform(post("/api/scripts/SCR-1/return").with(user("moderator").roles("MODERATOR")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"reason\":\"Check evidence\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[0].status").value("In Review"))
            .andExpect(jsonPath("$.scripts[0].history[0].action").value("Returned for correction: Check evidence"));
    }
    @Test void appealPreservesApprovedExportWhileDraftChanges() throws Exception {
        workspace.scripts.get(0).putAll(Map.of("status","Approved","approvedBy","moderator","ocr","Original answer"));
        mvc.perform(post("/api/scripts/SCR-1/appeals").with(user("teacher").roles("TEACHER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"reason\":\"Please recheck\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[0].approvedRevisions[0].score").value(6));
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":2,\"ocr\":\"Corrected answer\",\"remarks\":\"Revised\",\"submit\":true}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/exports/marks.csv").with(user("teacher").roles("TEACHER")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(",6,7,Approved")));
        mvc.perform(post("/api/scripts/SCR-1/approve").with(user("moderator").roles("MODERATOR")).with(csrf())
            .contentType("application/json").content("{\"version\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[0].approvedRevisions[0].score").value(6))
            .andExpect(jsonPath("$.scripts[0].approvedRevisions[1].score").value(2))
            .andExpect(jsonPath("$.scripts[0].appeals[0].status").value("Resolved"));
    }
    @Test void cannotSubmitWhenAnotherQuestionIsUnreviewed() throws Exception {
        var one = new LinkedHashMap<String,Object>(Map.of("question",1,"maximum",7,"score",0,"ocr","","remarks","","reviewed",false));
        var two = new LinkedHashMap<String,Object>(Map.of("question",2,"maximum",7,"score",0,"ocr","","remarks","","reviewed",false));
        workspace.scripts.get(0).put("answers",new ArrayList<>(List.of(one,two)));
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"question\":1,\"score\":5,\"ocr\":\"Answer one\",\"remarks\":\"\",\"submit\":true}"))
            .andExpect(status().isBadRequest());
    }

    private void criterionAnswer() {
        var s=workspace.scripts.get(0);
        s.put("answers",new ArrayList<>(List.of(new LinkedHashMap<String,Object>(Map.of(
            "question",1,"maximum",7,"score",0,"ocr","","remarks","","scoringMode","CRITERIA",
            "criteria",List.of(Map.of("text","Definition","marks",7)))))));
    }
    @Test void criterionMarksNeedEvidenceFromTranscription() throws Exception {
        criterionAnswer();
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":6,\"ocr\":\"Actual answer\",\"remarks\":\"\",\"awards\":[{\"marks\":6,\"evidence\":\"Invented text\"}]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void criterionSumMustEqualQuestionTotal() throws Exception {
        criterionAnswer();
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":7,\"ocr\":\"Actual answer\",\"remarks\":\"\",\"awards\":[{\"marks\":6,\"evidence\":\"Actual answer\"}]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void criterionReviewPreservesEvidenceInHistory() throws Exception {
        criterionAnswer();
        mvc.perform(post("/api/scripts/SCR-1/review").with(user("marker").roles("MARKER")).with(csrf())
            .contentType("application/json").content("{\"version\":1,\"score\":6,\"ocr\":\"Actual answer\",\"remarks\":\"Reviewed\",\"submit\":true,\"awards\":[{\"marks\":6,\"evidence\":\"Actual answer\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[0].history[0].answers[0].awards[0].evidence").value("Actual answer"));
    }
    @Test void uploadCopiesDifferentRubricsForDifferentQuestions() throws Exception {
        workspace.exams.add(new LinkedHashMap<>(Map.of("id","EX-1","questions",2,"scoringMode","CRITERIA",
            "criteria",List.of(Map.of("text","Default","marks",2)),
            "questionRubrics",Map.of("2",List.of(Map.of("text","Second question","marks",7))))));
        var file=new org.springframework.mock.web.MockMultipartFile("files","answer.pdf","application/pdf","%PDF-test".getBytes());
        mvc.perform(multipart("/api/uploads").file(file).param("version","1").param("exam","EX-1")
            .with(user("teacher").roles("TEACHER")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scripts[1].maximum").value(9))
            .andExpect(jsonPath("$.scripts[1].answers[0].maximum").value(2))
            .andExpect(jsonPath("$.scripts[1].answers[1].maximum").value(7));
    }
}
