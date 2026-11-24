package com.assessment;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import com.assessment.controller.PageController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageController.class)
@org.springframework.security.test.context.support.WithMockUser
@org.springframework.test.context.TestPropertySource(properties={"TEACHER_PASSWORD=teacher-test-password", "MARKER_PASSWORD=marker-test-password", "MODERATOR_PASSWORD=moderator-test-password"})
class PageRoutesTest {
    @Autowired MockMvc mvc;
    @ParameterizedTest
    @ValueSource(strings={"/", "/login", "/dashboard", "/exams", "/rubrics", "/scripts", "/upload", "/review", "/moderation", "/reports", "/profile"})
    void servesFrontendWithoutDatabase(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(view().name("app"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/app.js")));
    }
}
