package com.interviewcopilot.web.dto;

/** /api/interview/start 请求体 */
public record InterviewStartRequest(String topic, Integer count) {
}
