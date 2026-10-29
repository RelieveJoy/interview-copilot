package com.interviewcopilot.web;

import com.interviewcopilot.service.InterviewService;
import com.interviewcopilot.web.dto.InterviewAnswerRequest;
import com.interviewcopilot.web.dto.InterviewStartRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 面试模式接口：
 *  - GET  /api/interview/topics          可选主题（来自知识库文件名前缀）
 *  - POST /api/interview/start           开始面试，生成题目
 *  - POST /api/interview/answer          提交回答，获得评分 + 追问
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewService interviewService;
    private final boolean apiKeyConfigured;

    public InterviewController(InterviewService interviewService,
                               @Value("${llm.api-key:}") String apiKey) {
        this.interviewService = interviewService;
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
    }

    @GetMapping("/topics")
    public Map<String, Object> topics() {
        return Map.of("topics", interviewService.topics());
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) InterviewStartRequest request) {
        if (!apiKeyConfigured) {
            return keyMissing();
        }
        String topic = request == null ? null : request.topic();
        int count = (request == null || request.count() == null) ? 5 : request.count();
        InterviewService.InterviewSession session = interviewService.start(topic, count);
        return ResponseEntity.ok(Map.of(
                "sessionId", session.sessionId(),
                "topic", session.topic(),
                "questions", session.questions()));
    }

    @PostMapping("/answer")
    public ResponseEntity<?> answer(@RequestBody InterviewAnswerRequest request) {
        if (request == null || request.answer() == null || request.answer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "answer 不能为空"));
        }
        if (!apiKeyConfigured) {
            return keyMissing();
        }
        String question = request.question() == null ? "" : request.question().trim();
        return ResponseEntity.ok(interviewService.evaluate(question, request.answer().trim()));
    }

    private ResponseEntity<Map<String, String>> keyMissing() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "未配置 DEEPSEEK_API_KEY 环境变量，无法调用在线大模型"));
    }
}
