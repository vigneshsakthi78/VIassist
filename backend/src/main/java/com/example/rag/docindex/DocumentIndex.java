package com.example.rag.docindex;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class DocumentIndex {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndex.class);
    private static final Pattern DOCID = Pattern.compile("DMSDOC\\d+", Pattern.CASE_INSENSITIVE);
    private static final String INDEX = "rag/doc-index.json";

    private final Map<String, StoredDocument> byId;

    public DocumentIndex(ObjectMapper objectMapper) {
        this.byId = load(objectMapper);
    }

    public String enrich(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return userMessage;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher matcher = DOCID.matcher(userMessage);
        while (matcher.find()) {
            ids.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        if (ids.isEmpty()) {
            return userMessage;
        }

        StringBuilder prompt = new StringBuilder(userMessage.trim());
        prompt.append("\n\nDocument lookup instruction: summarize each document's content in plain language, ");
        prompt.append("then list its attachments. Use only the records below. ");
        prompt.append("If content is empty, say there is no page content. ");
        prompt.append("If there are no attachments, say there are no attachments. ");
        prompt.append("Do not invent files. Do not use the DMS how-to screenshot format for this lookup.\n");
        for (String id : ids) {
            StoredDocument document = byId.get(id);
            prompt.append('\n');
            if (document == null) {
                prompt.append(id).append(" is not in the day-by-day extract.\n");
                continue;
            }
            prompt.append("Doc id: ").append(document.docid).append('\n');
            prompt.append("Title: ").append(blank(document.title)).append('\n');
            prompt.append("Reference: ").append(blank(document.refno)).append('\n');
            prompt.append("Version: ").append(blank(document.version)).append('\n');
            prompt.append("Status: ").append(blank(document.status)).append('\n');
            prompt.append("Published day: ").append(blank(document.day)).append('\n');
            prompt.append("Document file: ").append(blank(document.documentFile)).append('\n');
            prompt.append("Content:\n").append(blank(document.contentText)).append('\n');
            prompt.append("Attachments:\n");
            if (document.attachments == null || document.attachments.isEmpty()) {
                prompt.append("(none)\n");
            } else {
                for (StoredAttachment attachment : document.attachments) {
                    prompt.append("- ")
                            .append(blank(attachment.filename))
                            .append(" | status ")
                            .append(blank(attachment.status))
                            .append(" | size ")
                            .append(blank(attachment.size))
                            .append('\n');
                }
            }
        }
        return prompt.toString();
    }

    private static Map<String, StoredDocument> load(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(INDEX);
        if (!resource.exists()) {
            log.warn("No day-by-day document index at classpath:{}. Docid search has nothing to summarize.", INDEX);
            return Map.of();
        }
        try (InputStream input = resource.getInputStream()) {
            IndexFile file = objectMapper.readValue(input, IndexFile.class);
            if (file.documents == null) {
                return Map.of();
            }
            Map<String, StoredDocument> loaded = file.documents.stream()
                    .filter(document -> document.docid != null && !document.docid.isBlank())
                    .collect(Collectors.toMap(
                            document -> document.docid.toUpperCase(Locale.ROOT),
                            document -> document,
                            (left, right) -> left));
            log.info("Loaded {} day-by-day documents for docid search", loaded.size());
            return Map.copyOf(loaded);
        } catch (IOException ex) {
            log.error("Could not read {}: {}", INDEX, ex.getMessage());
            return Map.of();
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class IndexFile {
        public List<StoredDocument> documents;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StoredDocument {
        public String docid;
        public String title;
        public String refno;
        public String version;
        public String status;
        public String day;
        public String published;
        public String documentFile;
        public String contentText;
        public List<StoredAttachment> attachments;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class StoredAttachment {
        public String filename;
        public String status;
        public String size;
        public String source;
        public String published;
    }
}
