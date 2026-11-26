package com.assessment;

import com.assessment.config.SecurityConfig;
import com.assessment.controller.PageController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PageController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"TEACHER_PASSWORD=teacher-test-password","MARKER_PASSWORD=marker-test-password","MODERATOR_PASSWORD=moderator-test-password"})
class LoginTest {
    @Autowired MockMvc mvc;
    @Test void configuredTeacherCanSignIn() throws Exception {
        mvc.perform(formLogin("/login").user("teacher@school.local").password("teacher-test-password"))
            .andExpect(authenticated().withUsername("teacher@school.local"))
            .andExpect(redirectedUrl("/dashboard"));
    }
    @Test void incorrectPasswordIsRejected() throws Exception {
        mvc.perform(formLogin("/login").user("teacher@school.local").password("incorrect-password"))
            .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
    }
    @Test void anonymousUserGetsCustomLoginPage() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("app"))
            .andExpect(content().string(not(containsString("name=\"authenticator-enabled\""))))
            .andExpect(content().string(not(containsString("name=\"local-demo-enabled\""))));
        mvc.perform(post("/login").param("demoRole", "TEACHER").with(csrf()))
            .andExpect(status().isNotFound()).andExpect(unauthenticated());
    }
}

@WebMvcTest(PageController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties={"TEACHER_PASSWORD=teacher-test-password","MARKER_PASSWORD=marker-test-password",
    "MODERATOR_PASSWORD=moderator-test-password", "ASSESSMENT_LOCAL_DEMO=true",
    "ASSESSMENT_MFA_SECRETS_JSON={\"teacher@school.local\":\"MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=\"}"})
class AuthenticatorLoginTest {
    @Autowired MockMvc mvc;

    @Test void configuredAuthenticatorIsVisibleAndEnforced() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("<meta name=\"authenticator-enabled\" content=\"true\">")))
            .andExpect(content().string(not(containsString("name=\"local-demo-enabled\""))));
        mvc.perform(post("/login").param("demoRole", "TEACHER").with(csrf()))
            .andExpect(status().isNotFound()).andExpect(unauthenticated());
        mvc.perform(formLogin("/login").user("teacher@school.local").password("teacher-test-password"))
            .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
    }
}
