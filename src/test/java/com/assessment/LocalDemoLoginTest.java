package com.assessment;

import com.assessment.config.SecurityConfig;
import com.assessment.controller.PageController;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"TEACHER_PASSWORD=teacher-test-password", "MARKER_PASSWORD=marker-test-password",
    "MODERATOR_PASSWORD=moderator-test-password", "ASSESSMENT_LOCAL_DEMO=true"})
class LocalDemoLoginTest {
    @Autowired MockMvc mvc;

    @Test void localhostShowsDemoSignIn() throws Exception {
        mvc.perform(get("http://127.0.0.1:8085/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("<meta name=\"local-demo-enabled\" content=\"true\">")));
    }

    @ParameterizedTest
    @ValueSource(strings={"TEACHER", "MARKER", "MODERATOR"})
    void roleButtonSignsInAndKeepsSession(String role) throws Exception {
        String username = role.toLowerCase(Locale.ROOT) + "@school.local";
        var signIn = mvc.perform(post("http://127.0.0.1:8085/login").param("demoRole", role).with(csrf()))
            .andExpect(authenticated().withUsername(username)).andExpect(redirectedUrl("/dashboard")).andReturn();
        var session = (MockHttpSession) signIn.getRequest().getSession(false);
        mvc.perform(get("/dashboard").session(session)).andExpect(status().isOk())
            .andExpect(authenticated().withUsername(username));
        mvc.perform(get("/rubrics").session(session))
            .andExpect(status().is("TEACHER".equals(role) ? 200 : 403));
    }

    @Test void demoSignInRequiresCsrfAndKnownRole() throws Exception {
        mvc.perform(post("/login").param("demoRole", "TEACHER"))
            .andExpect(status().isForbidden()).andExpect(unauthenticated());
        mvc.perform(post("/login").param("demoRole", "ADMIN").with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(unauthenticated());
    }

    @Test void proxiedAndNonLocalRequestsCannotUseDemoSignIn() throws Exception {
        mvc.perform(get("/login").header("X-Assessment-Proxy", "1")).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("name=\"local-demo-enabled\""))));
        mvc.perform(post("/login").header("X-Assessment-Proxy", "1").param("demoRole", "TEACHER").with(csrf()))
            .andExpect(status().isNotFound()).andExpect(unauthenticated());
        mvc.perform(post("http://school.example/login").param("demoRole", "TEACHER").with(csrf()))
            .andExpect(status().isNotFound()).andExpect(unauthenticated());
    }
}
