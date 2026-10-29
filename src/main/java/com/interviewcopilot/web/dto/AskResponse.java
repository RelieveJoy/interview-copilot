package com.interviewcopilot.web.dto;

/** /api/ask 响应体 */
public record AskResponse(String answer, String model, long latencyMs) {
}
