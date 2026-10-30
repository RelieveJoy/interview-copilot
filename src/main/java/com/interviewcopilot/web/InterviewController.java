package com.interviewcopilot.web;

import com.interviewcopilot.service.InterviewService;
import com.interviewcopilot.web.dto.InterviewAnswerRequest;
import com.interviewcopilot.web.dto.InterviewStartRequest;
import com.interviewcopilot.web.dto.InterviewTurnRequest;
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
 *  - POST /api/interview/turn            拷打循环：一轮作答 → 追问 或 打分进下一题
 *  - POST /api/interview/answer          单轮评分（无状态诊断用，前端拷打走 /turn）
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
                "sessionId", session.getSessionId(),
                "topic", session.getTopic(),
                "questions", session.getQuestions()));
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
        // 从面试会话取主题，评分检索按主题重排（修正跨主题字面撞车）
        String topic = null;
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            InterviewService.InterviewSession s = interviewService.session(request.sessionId().trim());
            if (s != null) {
                topic = s.getTopic();
            }
        }
        return ResponseEntity.ok(interviewService.evaluate(question, request.answer().trim(), topic));
    }

    /**
     * 拷打循环（前端面试模式走这里）：一轮作答 → 追问 或 打分进下一题。
     * giveUp=true 放弃本题直接终评；checkpoint=true 中途评分（本题不结束）；
     * advance=true 接受当前得分进入下一题。
     */
    @PostMapping("/turn")
    public ResponseEntity<?> turn(@RequestBody(required = false) InterviewTurnRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sessionId 不能为空"));
        }
        String answer = request.answer() == null ? "" : request.answer().trim();
        boolean giveUp = Boolean.TRUE.equals(request.giveUp());
        boolean checkpoint = Boolean.TRUE.equals(request.checkpoint());
        boolean advance = Boolean.TRUE.equals(request.advance());
        if (answer.isEmpty() && !giveUp && !checkpoint && !advance) {
            return ResponseEntity.badRequest().body(Map.of("error", "answer 不能为空"));
        }
        if (!apiKeyConfigured) {
            return keyMissing();
        }
        try {
            return ResponseEntity.ok(interviewService.turn(request.sessionId(), answer, giveUp, checkpoint, advance));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "调用大模型失败：" + e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, String>> keyMissing() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "未配置 ZHIPUAI_API_KEY 环境变量，无法调用在线大模型"));
    }
}
