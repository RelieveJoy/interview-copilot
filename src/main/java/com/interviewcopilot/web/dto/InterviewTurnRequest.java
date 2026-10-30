package com.interviewcopilot.web.dto;

/**
 * /api/interview/turn 请求体，三种动作互斥：
 *  - 默认：提交一轮作答（answer），面试官追问或终评
 *  - giveUp=true：放弃本题，直接终评
 *  - checkpoint=true：中途评分——看当前得分与欠缺，本题不结束
 *  - advance=true：接受当前得分，进入下一题（中途评分后无新作答则原样采用）
 */
public record InterviewTurnRequest(String sessionId, String answer, Boolean giveUp,
                                   Boolean checkpoint, Boolean advance) {
}
