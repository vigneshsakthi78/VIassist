package com.example.rag.learning;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves durable on-disk paths for learned chats and embedding cache.
 *
 * Priority:
 * 1) Explicit env overrides (LEARNED_CHAT_PATH / EMBEDDING_STORE_PATH)
 * 2) VICKY_DATA_DIR (or DATA_DIR) directory
 * 3) ./data under the process working directory (survives local restarts; mount this on Render)
 */
public final class DataPaths {

    private DataPaths() {
    }

    public static Path dataDir() {
        String configured = firstNonBlank(
                System.getenv("VICKY_DATA_DIR"),
                System.getenv("DATA_DIR"));
        if (configured != null) {
            return Paths.get(configured);
        }
        return Paths.get("data").toAbsolutePath().normalize();
    }

    public static Path learnedChatFile() {
        String configured = System.getenv("LEARNED_CHAT_PATH");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return dataDir().resolve("vicky-assist-learned-chats.txt");
    }

    public static Path embeddingStoreFile() {
        String configured = System.getenv("EMBEDDING_STORE_PATH");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return dataDir().resolve("vicky-assist-embeddings.json");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
