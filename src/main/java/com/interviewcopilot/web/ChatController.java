package com.interviewcopilot.web;

import com.interviewcopilot.service.KnowledgeAssistant;
import com.interviewcopilot.service.KnowledgeService;
import com.interviewcopilot.web.dto.AskRequest;
import com.interviewcopilot.web.dto.AskResponse;
import com.interviewcopilot.web.dto.ChatRequest;
import com.interviewcopilot.web.dto.ChatResponse;
import com.interviewcopilot.web.dto.SourceRef;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话接口：
 *  - GET  /api/health      存活检查（无需 key）
 *  - GET/POST /api/ask     直连 LLM（无 RAG，兼作"关闭 RAG"基线端点）
 *  - POST /api/chat        RAG 增强 + 多轮记忆（sessionId 隔离）
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OpenAiChatModel chatModel;
    private final KnowledgeAssistant knowledgeAssistant;
    private final KnowledgeService knowledgeService;
    private final String modelName;
    private final boolean apiKeyConfigured;

    public ChatController(OpenAiChatModel chatModel,
                          KnowledgeAssistant knowledgeAssistant,
                          KnowledgeService knowledgeService,
                          @Value("${llm.model-name}") String modelName,
                          @Value("${llm.api-key:}") String apiKey) {
        this.chatModel = chatModel;
        this.knowledgeAssistant = knowledgeAssistant;
        this.knowledgeService = knowledgeService;
        this.modelName = modelName;
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "time", LocalDateTime.now().toString(),
                "model", modelName,
                "llmKeyConfigured", apiKeyConfigured,
                "knowledgeFiles", knowledgeService.importedFileCount());
    }

    // ---------- 直连模式（无 RAG） ----------

    @GetMapping("/ask")
    public ResponseEntity<?> askGet(@RequestParam("q") String question) {
        return ask(question);
    }

    @PostMapping("/ask")
    public ResponseEntity<?> askPost(@RequestBody AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        return ask(request.question());
    }

    private ResponseEntity<?> ask(String question) {
        if (!apiKeyConfigured) {
            return keyMissing();
        }
        long start = System.currentTimeMillis();
        try {
            String answer = chatModel.chat(question);
            return ResponseEntity.ok(new AskResponse(answer, modelName, System.currentTimeMillis() - start));
        } catch (Exception e) {
            return llmError(e);
        }
    }

    // ---------- RAG 模式（多轮记忆） ----------

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        if (!apiKeyConfigured) {
            return keyMissing();
        }

        String question = request.question().trim();
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? "default" : request.sessionId().trim();

        long start = System.currentTimeMillis();
        try {
            // 检索命中的真实来源（服务端事实，非模型自述）
            List<KnowledgeService.KnowledgeHit> hits = knowledgeService.search(question);

            // AiServices: 记忆(sessionId 隔离) + 检索增强 + 在线 LLM 一条龙
            String answer = knowledgeAssistant.chat(sessionId, question);

            List<SourceRef> sources = new ArrayList<>();
            for (KnowledgeService.KnowledgeHit h : hits) {
                String snippet = h.text().replaceAll("\\s+", " ");
                if (snippet.length() > 80) {
                    snippet = snippet.substring(0, 80) + "…";
                }
                sources.add(new SourceRef(h.file(), h.score(), snippet));
            }
            return ResponseEntity.ok(new ChatResponse(
                    answer, modelName, System.currentTimeMillis() - start, !hits.isEmpty(), sources));
        } catch (Exception e) {
            return llmError(e);
        }
    }

    // ---------- 公共错误处理 ----------

    private ResponseEntity<Map<String, String>> keyMissing() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "未配置 DEEPSEEK_API_KEY 环境变量，无法调用在线大模型"));
    }

    private ResponseEntity<Map<String, String>> llmError(Exception e) {
        log.error("调用大模型失败", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "调用大模型失败：" + e.getMessage()));
    }
}
