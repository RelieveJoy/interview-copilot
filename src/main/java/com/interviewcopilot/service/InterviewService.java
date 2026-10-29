package com.interviewcopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试模式：LLM 出题 → 候选人作答 → 基于 RAG 知识库的评分 + 追问。
 *
 * 出题：按主题（从知识库文件名前缀提取，如 JVM/Redis/并发）让 LLM 生成拷问题。
 * 评分：检索知识库命中段作为评分基准注入提示词，要求严格 JSON 输出。
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private final OpenAiChatModel chatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** sessionId → 面试会话 */
    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();

    public record InterviewSession(String sessionId, String topic, List<String> questions) {
    }

    public InterviewService(OpenAiChatModel chatModel, KnowledgeService knowledgeService) {
        this.chatModel = chatModel;
        this.knowledgeService = knowledgeService;
    }

    /** 知识库覆盖的主题（文件名 "-" 前缀；台账类文件无前缀，排除）。 */
    public List<String> topics() {
        return knowledgeService.importedFileNames().stream()
                .filter(n -> n.contains("-"))
                .map(n -> n.substring(0, n.indexOf('-')))
                .distinct()
                .sorted()
                .toList();
    }

    /** 开始一场面试：LLM 按主题生成 N 道题，失败时回退到模板题。 */
    public InterviewSession start(String topic, int count) {
        String safeTopic = (topic == null || topic.isBlank()) ? "Java 后端" : topic.trim();
        int n = Math.max(1, Math.min(10, count));

        List<String> questions;
        try {
            String prompt = """
                    你是资深 Java 面试官。围绕主题「%s」出 %d 道高频面试拷问题，要求：
                    由浅入深、可口头作答、覆盖该主题核心考点，不要和别的主题混杂。
                    只输出一个 JSON 字符串数组，形如 ["问题1","问题2"]，不要任何多余文字。"""
                    .formatted(safeTopic, n);
            questions = parseArray(chatModel.chat(prompt));
            if (questions.size() > n) {
                questions = questions.subList(0, n);
            }
        } catch (Exception e) {
            log.warn("LLM 出题失败, 使用模板题: {}", e.getMessage());
            questions = List.of(
                    "讲一讲「" + safeTopic + "」这个主题下你最熟悉的一个核心知识点，讲透它。",
                    "「" + safeTopic + "」相关的内容在实际项目中怎么用？举一个你处理过的场景。",
                    "关于「" + safeTopic + "」，面试官常追问的坑/误区是什么？正确的理解是什么？",
                    "把「" + safeTopic + "」和一个相近的技术概念对比着讲一下。",
                    "如果系统出问题，你会怎么用「" + safeTopic + "」的知识去排查？").subList(0, n);
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        InterviewSession session = new InterviewSession(sessionId, safeTopic, questions);
        sessions.put(sessionId, session);
        return session;
    }

    public InterviewSession session(String sessionId) {
        return sessions.get(sessionId);
    }

    /** 评分：检索知识库作为基准 → LLM 严格 JSON 评价。 */
    public Map<String, Object> evaluate(String question, String userAnswer) {
        List<KnowledgeService.KnowledgeHit> hits = knowledgeService.search(question);

        StringBuilder sb = new StringBuilder("""
                你是严格的 Java 面试官。请评价候选人的回答。

                面试题：%s

                候选人回答：%s
                """.formatted(question, userAnswer));
        if (hits.isEmpty()) {
            sb.append("\n（知识库未检索到相关资料，按通用标准评价）");
        } else {
            sb.append("\n评分参考资料（候选人看不到，作为评分基准）：");
            for (int i = 0; i < hits.size(); i++) {
                sb.append("\n[").append(i + 1).append("] 来源: ").append(hits.get(i).file())
                  .append("\n").append(hits.get(i).text(), 0, Math.min(500, hits.get(i).text().length()));
            }
        }
        sb.append("""

                只输出严格 JSON（不要 markdown 代码块、不要多余文字），结构：
                {"score": 0到10的整数, "highlights": ["回答亮点", ...], "gaps": ["欠缺/错误", ...],
                 "followUp": "一个更深层的追问", "reference": "基于参考资料的答题要点, 120字内"}""");

        String raw = chatModel.chat(sb.toString());
        Map<String, Object> result = parseObject(raw);
        result.put("question", question);
        result.put("sources", hits.stream().map(h -> Map.of(
                "file", h.file(), "score", h.score())).toList());
        return result;
    }

    // ---------- JSON 提取（容忍 LLM 输出前后缀杂质） ----------

    private List<String> parseArray(String raw) throws Exception {
        int l = raw.indexOf('[');
        int r = raw.lastIndexOf(']');
        if (l < 0 || r <= l) {
            throw new IllegalStateException("LLM 未返回 JSON 数组: " + raw);
        }
        return mapper.readValue(raw.substring(l, r + 1),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private Map<String, Object> parseObject(String raw) {
        try {
            int l = raw.indexOf('{');
            int r = raw.lastIndexOf('}');
            if (l >= 0 && r > l) {
                return mapper.readValue(raw.substring(l, r + 1),
                        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            }
        } catch (Exception e) {
            log.warn("评分 JSON 解析失败: {}", e.getMessage());
        }
        // 兜底：不让前端拿到解析错误
        return new ConcurrentHashMap<>(Map.of(
                "score", -1,
                "highlights", List.of(),
                "gaps", List.of("评分结果解析失败，可重试一次"),
                "followUp", "",
                "reference", raw.length() > 300 ? raw.substring(0, 300) : raw));
    }
}
