package com.interviewcopilot.web.dto;

/** /api/chat 请求体；sessionId 用于 D3 的多轮记忆隔离 */
public record ChatRequest(String sessionId, String question) {
}
