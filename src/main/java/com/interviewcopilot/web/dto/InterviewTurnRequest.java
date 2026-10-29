package com.interviewcopilot.web.dto;

/** /api/interview/turn 请求体：一轮作答（giveUp=true 表示放弃本题，直接打分） */
public record InterviewTurnRequest(String sessionId, String answer, Boolean giveUp) {
}
