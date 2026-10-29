package com.interviewcopilot.web.dto;

/** /api/interview/answer 请求体 */
public record InterviewAnswerRequest(String sessionId, String question, String answer) {
}
