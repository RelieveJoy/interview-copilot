package com.interviewcopilot.web;

import com.interviewcopilot.service.KnowledgeAssistant;
import com.interviewcopilot.service.KnowledgeService;
import com.interviewcopilot.web.dto.AskRequest;
import com.interviewcopilot.web.dto.AskResponse;
import com.interviewcopilot.web.dto.ChatRequest;
import com.interviewcopilot.web.dto.ChatResponse;
import com.interviewcopilot.web.dto.SourceRef;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.service.TokenStream;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final StreamingChatModel streamingChatModel;
    private final KnowledgeAssistant knowledgeAssistant;
    private final KnowledgeService knowledgeService;
    private final String modelName;
    private final boolean apiKeyConfigured;

    /** 问候/寒暄词表 → 旁路 RAG：这类输入无检索意义，硬走向量库会捞回不相干笔记强答 */
    private static final java.util.regex.Pattern SMALL_TALK = java.util.regex.Pattern.compile(
            "(?i)(你好|您好|你们好|你好呀|你好啊|嗨|哈喽|hello|hi|hey|在吗|在么|谢谢|多谢|感谢|辛苦了|"
                    + "再见|拜拜|晚安|早安|测试|test)");

    /** 按标点/空白切成小段，每段都在寒暄词表内且 ≤6 字才算闲聊（"你好呀，在吗"✅ "你好，HashMap是什么"❌） */
    private static boolean isSmallTalk(String q) {
        String cleaned = q.replaceAll("[\\s!！。\\.?？~～…，,、；;：:]+", " ").trim();
        if (cleaned.isEmpty()) {
            return false;
        }
        for (String seg : cleaned.split("\\s+")) {
            if (seg.length() > 6 || !SMALL_TALK.matcher(seg).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String smallTalkPrompt(String greeting) {
        return "用户向「面试拷打助手」发送了这句话（非技术问题）：「" + greeting + "」。"
                + "请以助手身份用一两句话友好回应，并用一句话说明你能基于用户导入的 Java 八股笔记"
                + "做检索问答和模拟面试拷打。不要罗列知识点、不要长篇大论、不要输出列表。";
    }

    public ChatController(OpenAiChatModel chatModel,
                          StreamingChatModel streamingChatModel,
                          KnowledgeAssistant knowledgeAssistant,
                          KnowledgeService knowledgeService,
                          @Value("${llm.model-name}") String modelName,
                          @Value("${llm.api-key:}") String apiKey) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
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
        // 纯问候：不检索不注入，简短回应（knowledgeHit=false，前端不显示引用区）
        if (isSmallTalk(question)) {
            try {
                String answer = chatModel.chat(smallTalkPrompt(question));
                return ResponseEntity.ok(new ChatResponse(
                        answer, modelName, System.currentTimeMillis() - start, false, List.of()));
            } catch (Exception e) {
                return llmError(e);
            }
        }
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

    // ---------- 流式版本（SSE 打字机效果） ----------

    /**
     * RAG 流式问答。事件序列：sources（检索命中，生成前即达）→ token*（增量文本）→ done | error。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        if (!apiKeyConfigured) {
            sseSend(emitter, "error", Map.of("error", "未配置 ZHIPUAI_API_KEY 环境变量，无法调用在线大模型"));
            emitter.complete();
            return emitter;
        }
        if (request == null || request.question() == null || request.question().isBlank()) {
            sseSend(emitter, "error", Map.of("error", "question 不能为空"));
            emitter.complete();
            return emitter;
        }
        String question = request.question().trim();
        String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
                ? "default" : request.sessionId().trim();
        long start = System.currentTimeMillis();

        // 纯问候：旁路 RAG（无 sources 事件），done 带 smallTalk 标记，前端跳过引用区
        if (isSmallTalk(question)) {
            streamDirect(emitter, smallTalkPrompt(question), start, true);
            return emitter;
        }

        knowledgeAssistant.chatStream(sessionId, question)
                .onRetrieved(contents -> sseSend(emitter, "sources", toSourceRefs(contents)))
                .onPartialResponse(token -> sseSend(emitter, "token", token))
                .onCompleteResponse(response -> {
                    sseSend(emitter, "done", Map.of("latencyMs", System.currentTimeMillis() - start));
                    emitter.complete();
                })
                .onError(t -> {
                    log.error("流式调用大模型失败", t);
                    sseSend(emitter, "error", Map.of("error", "调用大模型失败：" + t.getMessage()));
                    emitter.complete();
                })
                .start();
        return emitter;
    }

    /** 直连流式（无 RAG）。事件序列：token* → done | error */
    @PostMapping("/ask/stream")
    public SseEmitter askStream(@RequestBody AskRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        if (!apiKeyConfigured) {
            sseSend(emitter, "error", Map.of("error", "未配置 ZHIPUAI_API_KEY 环境变量，无法调用在线大模型"));
            emitter.complete();
            return emitter;
        }
        if (request == null || request.question() == null || request.question().isBlank()) {
            sseSend(emitter, "error", Map.of("error", "question 不能为空"));
            emitter.complete();
            return emitter;
        }
        long start = System.currentTimeMillis();
        streamDirect(emitter, request.question().trim(), start, false);
        return emitter;
    }

    /** 直连流式（无 RAG）。事件序列：token* → done | error */
    private void streamDirect(SseEmitter emitter, String message, long start, boolean smallTalk) {
        streamingChatModel.chat(
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(UserMessage.from(message)).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        sseSend(emitter, "token", token);
                    }

                    @Override
                    public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                        sseSend(emitter, "done", smallTalk
                                ? Map.of("latencyMs", System.currentTimeMillis() - start, "smallTalk", true)
                                : Map.of("latencyMs", System.currentTimeMillis() - start));
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式直连调用失败", error);
                        sseSend(emitter, "error", Map.of("error", "调用大模型失败：" + error.getMessage()));
                        emitter.complete();
                    }
                });
    }

    private List<SourceRef> toSourceRefs(List<Content> contents) {
        List<SourceRef> sources = new ArrayList<>();
        for (Content c : contents) {
            String file = c.textSegment().metadata().getString("source");
            Object score = c.metadata().get(ContentMetadata.SCORE);
            String snippet = c.textSegment().text().replaceAll("\\s+", " ");
            if (snippet.length() > 80) {
                snippet = snippet.substring(0, 80) + "…";
            }
            sources.add(new SourceRef(file == null ? "未知来源" : file,
                    score instanceof Double d ? d : 0.0, snippet));
        }
        return sources;
    }

    /** SSE 发送兜底：连接已断/已完成时静默忽略 */
    private void sseSend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignored) {
            // 客户端断开（点了停止按钮/关页），无需处理
        }
    }

    // ---------- 公共错误处理 ----------

    private ResponseEntity<Map<String, String>> keyMissing() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "未配置 ZHIPUAI_API_KEY 环境变量，无法调用在线大模型"));
    }

    private ResponseEntity<Map<String, String>> llmError(Exception e) {
        log.error("调用大模型失败", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "调用大模型失败：" + e.getMessage()));
    }
}
