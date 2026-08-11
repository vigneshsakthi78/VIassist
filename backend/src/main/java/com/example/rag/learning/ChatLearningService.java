package com.example.rag.learning;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists Q&A pairs from live chat and embeds them so the assistant improves over time.
 */
@Service
public class ChatLearningService {

    private static final Logger log = LoggerFactory.getLogger(ChatLearningService.class);
    private static final int CHUNK_SIZE = 2500;
    private static final int CHUNK_OVERLAP = 200;

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-learn");
        t.setDaemon(true);
        return t;
    });

    public ChatLearningService(
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public void learnAsync(String question, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        // Skip learning error / quota responses
        String lower = answer.toLowerCase();
        if (lower.contains("quota exhausted") || lower.contains("rate limit")
                || lower.contains("api key missing") || lower.startsWith("chat failed:")) {
            return;
        }
        executor.execute(() -> {
            try {
                learn(question.trim(), answer.trim());
            } catch (Exception e) {
                log.warn("Chat self-learning skipped: {}", e.getMessage());
            }
        });
    }

    public synchronized void learn(String question, String answer) throws IOException {
        String block = formatBlock(question, answer);
        Path file = learnedFile();
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        Files.writeString(file, block + "\n\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Document document = Document.from(block);
        EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP))
                .build()
                .ingest(document);

        // Keep disk cache in sync when possible
        Path storePath = storeFile();
        try {
            embeddingStore.serializeToFile(storePath);
        } catch (Exception e) {
            log.debug("Could not refresh embedding cache after learn: {}", e.getMessage());
        }
        log.info("Learned from chat Q&A (store size now {})", embeddingStore.size());
    }

    public List<Document> loadLearnedDocuments() {
        List<Document> documents = new ArrayList<>();
        Path file = learnedFile();
        if (!Files.isRegularFile(file)) {
            return documents;
        }
        try {
            String all = Files.readString(file, StandardCharsets.UTF_8);
            String[] blocks = all.split("\\n\\n(?=Learned chat Q&A)");
            for (String block : blocks) {
                String trimmed = block.trim();
                if (!trimmed.isEmpty()) {
                    documents.add(Document.from(trimmed));
                }
            }
            log.info("Loaded {} learned chat document(s) from {}", documents.size(), file);
        } catch (IOException e) {
            log.warn("Could not read learned chat file: {}", e.getMessage());
        }
        return documents;
    }

    public static Path learnedFile() {
        String configured = System.getenv("LEARNED_CHAT_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "vicky-assist-learned-chats.txt");
    }

    private static Path storeFile() {
        String configured = System.getenv("EMBEDDING_STORE_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "vicky-assist-embeddings.json");
    }

    private static String formatBlock(String question, String answer) {
        return "Learned chat Q&A\n"
                + "Timestamp: " + Instant.now() + "\n"
                + "Question: " + question + "\n"
                + "Answer:\n" + answer;
    }
}
