package com.interviewcopilot.web.dto;

import java.util.List;

/** /api/chat 响应体：答案 + 引用来源列表 */
public record ChatResponse(String answer, String model, long latencyMs, boolean knowledgeHit, List<SourceRef> sources) {
}
