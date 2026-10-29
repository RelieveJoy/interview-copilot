package com.interviewcopilot.web.dto;

/** /api/knowledge/import 请求体：path 为目录或单个 .md 文件 */
public record ImportRequest(String path) {
}
