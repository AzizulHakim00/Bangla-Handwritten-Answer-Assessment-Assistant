package com.assessment.controller;

import com.assessment.config.PilotMfa;
import com.assessment.config.LocalDemoLogin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    private final PilotMfa mfa;
    private final LocalDemoLogin localDemoLogin;

    public PageController(PilotMfa mfa, LocalDemoLogin localDemoLogin) {
        this.mfa = mfa;
        this.localDemoLogin = localDemoLogin;
    }

    @GetMapping({"/login", "/review"})
    public String loginOrReview(Model model, HttpServletRequest request) {
        model.addAttribute("authenticatorEnabled", mfa.hasConfiguredAccounts());
        model.addAttribute("localDemoEnabled", localDemoLogin.available(request));
        return "app";
    }

    @GetMapping({"/", "/dashboard", "/exams", "/rubrics", "/scripts", "/upload", "/moderation", "/reports", "/profile", "/verify"})
    public String page() { return "app"; }
}
