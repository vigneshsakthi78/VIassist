package com.example.rag.web;

import com.example.rag.Assistant;
import com.example.rag.learning.ChatLearningService;
import com.example.rag.web.dto.ChatRequest;
import com.example.rag.web.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Assistant assistant;
    private final ChatLearningService chatLearningService;

    public ChatController(Assistant assistant, ChatLearningService chatLearningService) {
        this.assistant = assistant;
        this.chatLearningService = chatLearningService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().body(new ChatResponse("Message must not be empty."));
        }

        String userMessage = request.message().trim();
        Exception last = null;
        // Single attempt on quota errors — retries burn the free-tier allowance faster.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String answer = assistant.chat(userMessage);
                chatLearningService.learnAsync(userMessage, answer);
                return ResponseEntity.ok(new ChatResponse(answer));
            } catch (Exception ex) {
                last = ex;
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                boolean rateLimited = message.contains("RESOURCE_EXHAUSTED")
                        || message.contains("429")
                        || message.toLowerCase().contains("quota")
                        || message.toLowerCase().contains("rate");
                if (rateLimited) {
                    break;
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                break;
            }
        }

        String message = last == null || last.getMessage() == null ? "" : last.getMessage();
        log.error("Chat failed: {}", message);

        if (message.toLowerCase().contains("api key") || message.contains("API_KEY") || message.contains("401")
                || message.contains("UNAUTHENTICATED")) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ChatResponse(
                    "Gemini API key missing or invalid. Set GEMINI_API_KEY then restart backend. "
                            + "Get a key at https://aistudio.google.com/apikey"
            ));
        }
        if (message.contains("RESOURCE_EXHAUSTED") || message.contains("429")
                || message.toLowerCase().contains("quota") || message.toLowerCase().contains("rate")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ChatResponse(
                    "Gemini free-tier quota exhausted for this Google AI project.\n"
                            + "This is a Google limit (RPM/RPD), not a Vicky Assist bug.\n"
                            + "Fix options:\n"
                            + "1) Wait for daily reset (midnight Pacific Time), then try again\n"
                            + "2) Check live usage: https://aistudio.google.com/usage\n"
                            + "3) Enable billing / upgrade tier for higher limits: "
                            + "https://ai.google.dev/gemini-api/docs/rate-limits\n"
                            + "4) Avoid rapid clicks; each chat + cold-start embedding uses quota"
            ));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ChatResponse("Chat failed: " + message));
    }
}
