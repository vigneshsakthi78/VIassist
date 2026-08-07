package com.example.rag.web;

import com.example.rag.Assistant;
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

    public ChatController(Assistant assistant) {
        this.assistant = assistant;
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
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String answer = assistant.chat(userMessage);
                return ResponseEntity.ok(new ChatResponse(answer));
            } catch (Exception ex) {
                last = ex;
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                boolean rateLimited = message.contains("RESOURCE_EXHAUSTED")
                        || message.contains("429")
                        || message.toLowerCase().contains("quota")
                        || message.toLowerCase().contains("rate");
                if (rateLimited && attempt < 3) {
                    long sleepMs = attempt * 2000L;
                    log.warn("Gemini rate/quota hit (attempt {}). Retrying in {} ms...", attempt, sleepMs);
                    try {
                        Thread.sleep(sleepMs);
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
                    "Gemini quota/rate limit hit for the current API key (any model).\n"
                            + "Try:\n"
                            + "1) Wait 1-2 minutes, then ask again\n"
                            + "2) Confirm GEMINI_MODEL on the host that serves /api/chat "
                            + "(Render for https://viassist.netlify.app — local $env only affects local runs)\n"
                            + "3) Check quota: https://aistudio.google.com/usage\n"
                            + "4) Ask shorter questions and avoid rapid clicks\n"
                            + "5) Optional: temporarily use a lighter model if free-tier RPM is exhausted"
            ));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ChatResponse("Chat failed: " + message));
    }
}
