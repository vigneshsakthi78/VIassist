package com.example.rag.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);
    private static final String CLASSPATH_STORE = "classpath:rag/embedding-store.json";

    /** Larger chunks = fewer Gemini embed calls (free tier embed RPM is very low). */
    private static final int CHUNK_SIZE = 2500;
    private static final int CHUNK_OVERLAP = 200;

    @Bean
    InMemoryEmbeddingStore<TextSegment> embeddingStore(RagProperties properties) throws IOException {
        List<Document> documents = loadDocuments(properties);
        if (documents.isEmpty()) {
            throw new IllegalStateException("No documents found to ingest for RAG");
        }

        String docsHash = hashDocuments(documents);
        Path storePath = resolveStorePath();
        Path hashPath = Paths.get(storePath + ".hash");

        InMemoryEmbeddingStore<TextSegment> fromClasspath = loadClasspathStore(docsHash);
        if (fromClasspath != null) {
            return fromClasspath;
        }

        if (Files.isRegularFile(storePath) && Files.isRegularFile(hashPath)) {
            String previousHash = Files.readString(hashPath, StandardCharsets.UTF_8).trim();
            if (docsHash.equals(previousHash)) {
                log.info("Loading cached embedding store from {} (skip Gemini embed calls)", storePath);
                return InMemoryEmbeddingStore.fromFile(storePath);
            }
        }

        // Empty store — filled asynchronously so Tomcat can bind before embed quota work.
        log.warn("No prebuilt/cached embeddings found. App will start, then ingest in background.");
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    ApplicationRunner embeddingIngestRunner(
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            RagProperties properties) {
        AtomicBoolean started = new AtomicBoolean(false);
        return args -> {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            if (!embeddingStore.isEmpty()) {
                log.info("Embedding store already loaded ({} entries). Skipping ingest.", embeddingStore.size());
                return;
            }

            Thread t = new Thread(() -> ingestWithRateLimit(embeddingStore, embeddingModel, properties), "rag-ingest");
            t.setDaemon(true);
            t.start();
        };
    }

    @Bean
    ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(1)
                .minScore(0.4)
                .build();
    }

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.withMaxMessages(2);
    }

    @Bean
    WebMvcConfigurer corsConfigurer(RagProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                String[] origins = java.util.Arrays.stream(properties.getCorsOrigins().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toArray(String[]::new);
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(origins)
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false);
            }
        };
    }

    private void ingestWithRateLimit(
            InMemoryEmbeddingStore<TextSegment> store,
            EmbeddingModel embeddingModel,
            RagProperties properties) {
        try {
            List<Document> documents = loadDocuments(properties);
            String docsHash = hashDocuments(documents);
            Path storePath = resolveStorePath();
            Path hashPath = Paths.get(storePath + ".hash");

            log.info("Background ingest of {} document(s) with large chunks to respect embed free-tier RPM",
                    documents.size());

            // One document at a time + pause keeps us under ~100 embed RPM free tier.
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                ingestOneDocumentWithRetry(store, embeddingModel, document, i + 1, documents.size());
                Thread.sleep(1500L);
            }

            Files.createDirectories(storePath.getParent() == null ? Paths.get(".") : storePath.getParent());
            store.serializeToFile(storePath);
            Files.writeString(hashPath, docsHash, StandardCharsets.UTF_8);
            log.info("Background RAG ingest complete ({} entries). Cached at {}", store.size(), storePath);
        } catch (Exception e) {
            log.error("Background RAG ingest failed: {}", e.getMessage(), e);
        }
    }

    private void ingestOneDocumentWithRetry(
            InMemoryEmbeddingStore<TextSegment> store,
            EmbeddingModel embeddingModel,
            Document document,
            int index,
            int total) throws InterruptedException {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                EmbeddingStoreIngestor.builder()
                        .embeddingStore(store)
                        .embeddingModel(embeddingModel)
                        .documentSplitter(DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP))
                        .build()
                        .ingest(document);
                log.info("Ingested document {}/{}", index, total);
                return;
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                boolean rateLimited = message.contains("RESOURCE_EXHAUSTED")
                        || message.contains("429")
                        || message.toLowerCase().contains("quota");
                if (rateLimited && attempts <= 8) {
                    long waitMs = 65_000L;
                    log.warn("Embed quota hit on doc {}/{} (attempt {}). Waiting {}s then retrying...",
                            index, total, attempts, waitMs / 1000);
                    Thread.sleep(waitMs);
                    continue;
                }
                throw ex;
            }
        }
    }

    private InMemoryEmbeddingStore<TextSegment> loadClasspathStore(String docsHash) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource storeResource = resolver.getResource(CLASSPATH_STORE);
            Resource hashResource = resolver.getResource("classpath:rag/embedding-store.hash");
            if (!storeResource.exists() || !hashResource.exists()) {
                return null;
            }
            String previousHash = new String(hashResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!docsHash.equals(previousHash)) {
                log.info("Classpath embedding store hash mismatch; will rebuild in background");
                return null;
            }
            Path tmp = Files.createTempFile("vicky-assist-classpath-store", ".json");
            try (InputStream in = storeResource.getInputStream()) {
                Files.write(tmp, in.readAllBytes());
            }
            log.info("Loaded prebuilt classpath embedding store (no Gemini embed calls at startup)");
            InMemoryEmbeddingStore<TextSegment> loaded = InMemoryEmbeddingStore.fromFile(tmp);
            Files.deleteIfExists(tmp);
            return loaded;
        } catch (Exception e) {
            log.warn("Could not load classpath embedding store: {}", e.getMessage());
            return null;
        }
    }

    private Path resolveStorePath() {
        String configured = System.getenv("EMBEDDING_STORE_PATH");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "vicky-assist-embeddings.json");
    }

    private String hashDocuments(List<Document> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Document document : documents) {
                digest.update(document.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private List<Document> loadDocuments(RagProperties properties) throws IOException {
        String docsPath = properties.getDocsPath();
        if (docsPath != null && !docsPath.isBlank()) {
            Path path = Paths.get(docsPath);
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("rag.docs-path is not a directory: " + path.toAbsolutePath());
            }
            log.info("Loading documents from filesystem: {}", path.toAbsolutePath());
            return FileSystemDocumentLoader.loadDocuments(path);
        }

        log.info("Loading documents from classpath:/docs");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:docs/*");
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                documents.add(Document.from(text));
            }
        }
        return documents;
    }
}
