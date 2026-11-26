package com.assessment.controller;

import com.assessment.config.PilotMfa;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    private final PilotMfa mfa;

    public PageController(PilotMfa mfa) {
        this.mfa = mfa;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("authenticatorEnabled", mfa.hasConfiguredAccounts());
        return "app";
    }

    @GetMapping({"/", "/dashboard", "/exams", "/rubrics", "/scripts", "/upload", "/review", "/moderation", "/reports", "/profile", "/verify"})
    public String page() { return "app"; }
}
