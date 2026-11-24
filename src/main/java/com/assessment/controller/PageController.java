package com.assessment.controller;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class PageController {
 @GetMapping({"/", "/login", "/dashboard", "/exams", "/rubrics", "/scripts", "/upload", "/review", "/moderation", "/reports", "/profile"})
 public String page() { return "app"; }
}
