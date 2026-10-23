package com.example.rag.web;

import com.example.rag.Assistant;
import com.example.rag.docindex.DocumentIndex;
import com.example.rag.learning.ChatLearningService;
import com.example.rag.web.dto.ChatRequest;
import com.example.rag.web.dto.ChatResponse;
import dev.langchain4j.data.message.ImageContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final String IMAGE_ONLY_PROMPT =
            "Identify this screenshot. If it is MACK DMS Shore, name the screen and tell me the next steps.";
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    private final Assistant assistant;
    private final ChatLearningService chatLearningService;
    private final DocumentIndex documentIndex;

    public ChatController(Assistant assistant, ChatLearningService chatLearningService, DocumentIndex documentIndex) {
        this.assistant = assistant;
        this.chatLearningService = chatLearningService;
        this.documentIndex = documentIndex;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ChatResponse("Message must not be empty."));
        }

        String userMessage = request.message() == null ? "" : request.message().trim();
        ImageContent image;
        try {
            image = decodeImage(request.imageBase64(), request.imageMimeType());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ChatResponse(ex.getMessage()));
        }

        if (userMessage.isBlank() && image == null) {
            return ResponseEntity.badRequest().body(new ChatResponse("Message must not be empty."));
        }
        if (userMessage.isBlank()) {
            userMessage = IMAGE_ONLY_PROMPT;
        }

        Exception last = null;
        // Single attempt on quota errors — retries burn the free-tier allowance faster.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String modelMessage = documentIndex.enrich(userMessage);
                String answer = image == null
                        ? assistant.chat(modelMessage)
                        : assistant.chatWithImage(modelMessage, image);
                String learnQuestion = image == null
                        ? userMessage
                        : "[User attached a screenshot] " + userMessage;
                chatLearningService.learnAsync(learnQuestion, answer);
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

    private ImageContent decodeImage(String imageBase64, String imageMimeType) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            return null;
        }

        String raw = imageBase64.trim();
        String mime = imageMimeType == null ? "" : imageMimeType.trim().toLowerCase(Locale.ROOT);
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("Image data URL is invalid.");
            }
            String header = raw.substring(5, comma).toLowerCase(Locale.ROOT);
            mime = header.split(";", 2)[0];
            raw = raw.substring(comma + 1);
        }
        raw = raw.replaceAll("\\s", "");
        if (mime.equals("image/jpg")) {
            mime = "image/jpeg";
        }
        if (!ALLOWED_IMAGE_TYPES.contains(mime)) {
            throw new IllegalArgumentException("Attach a PNG, JPEG, WebP, or GIF screenshot.");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Image data is not valid base64.");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Attached image is empty.");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image is too large. Use a cropped screenshot under 4 MB.");
        }
        return ImageContent.from(raw, mime, ImageContent.DetailLevel.HIGH);
    }
}
