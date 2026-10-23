package com.example.rag.web.dto;

public record ChatRequest(String message, String imageBase64, String imageMimeType) {
}
