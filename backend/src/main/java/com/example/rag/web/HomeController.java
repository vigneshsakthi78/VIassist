package com.example.rag.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
                "app", "Vicky Assist",
                "message", "Enterprise productivity API is running. Open the Angular UI for the chat coach.",
                "health", "/api/health",
                "chat", "POST /api/chat"
        );
    }
}
