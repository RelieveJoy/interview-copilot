package com.interviewcopilot.web.dto;

/** 引用来源：来源文件 + 相似度得分 + 摘要片段 */
public record SourceRef(String file, double score, String snippet) {
}
