package com.example.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * Optional filesystem folder of documents. Empty = classpath:/docs
     */
    private String docsPath = "";

    private String corsOrigins = "http://localhost:4200";

    public String getDocsPath() {
        return docsPath;
    }

    public void setDocsPath(String docsPath) {
        this.docsPath = docsPath;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(String corsOrigins) {
        this.corsOrigins = corsOrigins;
    }
}
