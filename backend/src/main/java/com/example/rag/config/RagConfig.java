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
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(RagProperties properties, EmbeddingModel embeddingModel)
            throws IOException {
        List<Document> documents = loadDocuments(properties);
        if (documents.isEmpty()) {
            throw new IllegalStateException("No documents found to ingest for RAG");
        }

        log.info("Ingesting {} document(s) with Gemini embeddings (no local ONNX model)", documents.size());
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        // Use Gemini EmbeddingModel so Render free tier does not OOM loading ONNX weights.
        EmbeddingStoreIngestor.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .documentSplitter(DocumentSplitters.recursive(500, 50))
                .build()
                .ingest(documents);

        log.info("RAG ingestion complete");
        return store;
    }

    @Bean
    ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
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
