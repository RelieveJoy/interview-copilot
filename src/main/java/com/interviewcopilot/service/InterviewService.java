package com.interviewcopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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
    private final OpenAiChatModel evalChatModel;
    private final KnowledgeService knowledgeService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** sessionId → 面试会话 */
    private final Map<String, InterviewSession> sessions = new ConcurrentHashMap<>();

    /** 每题最多追问轮数（防止无限拷打） */
    private static final int MAX_FOLLOW_UPS = 3;

    /** 整句放弃信号：服务端确定性兜底，命中即强制收尾评分（提示词之外不赌模型听话） */
    private static final java.util.regex.Pattern BAIL_OUT = java.util.regex.Pattern.compile(
            "^(不知道了?|不记得|不太记得了?|想不起来了?|不清楚了?|不了解|没了解过?|没听说过?|没研究过?|"
                    + "不会|没有了?|没了|答不上来了?|答不上来了?直接打分吧?|直接打分吧?|放弃了?|我放弃了?|"
                    + "这个我不会|这题不会)$");

    private static boolean isBailOut(String answer) {
        // 剥标点/语气字 + 剥「这道题/这个/这题/我」开头，让前端按钮文案「这题我答不上来了，直接打分吧」也能命中
        String s = answer.replaceAll("[\\s!！。\\.?？~～…，,、；;：:的]", "")
                .replaceFirst("^(这道题|这个|这题|我)+", "");
        return !s.isEmpty() && s.length() <= 12 && BAIL_OUT.matcher(s).matches();
    }

    /** 一场面试：题目列表 + 逐题推进状态（当前题 / 本题追问链 / 得分台账） */
    public static final class InterviewSession {
        private final String sessionId;
        private final String topic;
        private final List<String> questions;
        private int current;                                   // 当前题下标
        private int followUps;                                 // 本题已追问次数
        private final List<String> chain = new ArrayList<>();   // 本题对话链：回答,追问,回答,追问…
        private final List<Integer> scores = new ArrayList<>(); // 已完成题的得分
        private final List<String> coveredNotes = new ArrayList<>(); // 每题已考内容留档（题干+追问），供下一题查重

        InterviewSession(String sessionId, String topic, List<String> questions) {
            this.sessionId = sessionId;
            this.topic = topic;
            this.questions = questions;
        }

        public String getSessionId() { return sessionId; }
        public String getTopic() { return topic; }
        public List<String> getQuestions() { return questions; }
    }

    public InterviewService(OpenAiChatModel chatModel,
                            @Qualifier("evalChatModel") OpenAiChatModel evalChatModel,
                            KnowledgeService knowledgeService) {
        this.chatModel = chatModel;
        this.evalChatModel = evalChatModel;
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
                    由浅入深、可口头作答、覆盖该主题核心考点，不要和别的主题混杂，各题考点相互独立不重叠。
                    题目必须是中文。只输出一个 JSON 字符串数组，形如 ["问题1","问题2"]，不要任何多余文字。"""
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
        // 拷贝成可变列表：查重闸换题时要原地替换某道题
        InterviewSession session = new InterviewSession(sessionId, safeTopic, new ArrayList<>(questions));
        sessions.put(sessionId, session);
        return session;
    }

    public InterviewSession session(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 评分：检索知识库作为基准 → LLM 严格 JSON 评价。
     * 检索按会话主题（文件名 "主题-" 前缀）限定语料——bge-small 对「锁」这类
     * 跨主题字面撞车敏感，MySQL 锁问题会被几十个 Java 并发锁段落挤出 Top 位，
     * 限定后既给模型正确基准，检索引用也对得上领域。
     */
    public Map<String, Object> evaluate(String question, String userAnswer, String topic) {
        List<KnowledgeService.KnowledgeHit> hits;
        if (topic != null && !topic.isBlank()) {
            hits = knowledgeService.searchInFiles(question, topic.trim() + "-", 4);
        } else {
            hits = List.of();
        }
        if (hits.isEmpty()) { // 主题下无命中（如「Java 后端」无此前缀）→ 退回全局检索
            hits = knowledgeService.search(question, 4);
        }

        StringBuilder sb = new StringBuilder("""
                你是严格的 Java 面试官。请评价候选人的回答。

                面试题：%s

                候选人回答：%s

                评分铁律（违反任何一条即为错误评价）：
                1. 只依据「候选人回答」本身判分。下方参考资料仅供你了解标准答案，绝不能把资料里的内容当作候选人说过的话。
                2. highlights 给 2-4 条，每条摘录候选人回答中的原话关键句（可截取），回答里没有的一个字都不许写。
                3. 候选人回答为空、仅寒暄（如"你好"）、或与题目完全无关时：score 只能给 0 或 1，highlights 必须是空数组 []，gaps 写明"未有效作答"。
                4. 吝啬给分：答对核心概念 5-6 分，补齐关键细节与深度才继续往上加。
                """.formatted(question, userAnswer));
        if (hits.isEmpty()) {
            sb.append("\n（知识库未检索到相关资料，按通用标准评价）");
        } else {
            sb.append("\n评分参考资料（仅供了解标准答案；若与题目领域不符请忽略，按通用标准评价）：");
            for (int i = 0; i < hits.size(); i++) {
                sb.append("\n[").append(i + 1).append("] 来源: ").append(hits.get(i).file())
                  .append("\n").append(hits.get(i).text(), 0, Math.min(500, hits.get(i).text().length()));
            }
        }
        sb.append("""

                只输出严格 JSON（不要 markdown 代码块、不要多余文字），结构：
                {"score": 0到10的整数, "highlights": ["回答亮点", ...], "gaps": ["欠缺/错误", ...],
                 "followUp": "一个更深层的追问", "reference": "基于参考资料的答题要点, 120字内"}""");

        String raw = evalChatModel.chat(sb.toString());
        Map<String, Object> result = parseObject(raw);
        // 硬闸：无效作答（≤1 分）不允许带任何亮点，防止模型把寒暄原话当亮点
        Object sc = result.get("score");
        if (sc instanceof Number n && n.intValue() <= 1) {
            result.put("highlights", List.of());
        }
        // 硬闸 2：亮点必须逐字来自考生原话
        result.put("highlights", honestHighlights(result.get("highlights"), List.of(userAnswer)));
        result.put("question", question);
        result.put("sources", hits.stream().map(h -> Map.of(
                "file", h.file(), "score", h.score())).toList());
        return result;
    }

    /**
     * 拷打循环的一轮：接收候选人作答，面试官要么继续追问（刨根问底），要么收尾打分进入下一题。
     * 会话状态在服务端推进（当前题 / 追问链 / 得分台账），前端只管收发。
     *
     * 返回 {"type":"followup","followUp":...,"round":n,"maxRounds":3}，或
     *      {"type":"score","score":n,"highlights":[...],"gaps":[...],"reference":...,
     *       "scores":[已完成各题得分], "nextIndex":n, "nextQuestion":"..."|"" , "done":bool}
     */
    public Map<String, Object> turn(String sessionId, String answer, boolean giveUp) {
        InterviewSession s = sessions.get(sessionId == null ? "" : sessionId.trim());
        if (s == null) {
            throw new IllegalArgumentException("面试会话不存在或已结束，请重新开始面试");
        }
        String question = s.questions.get(s.current);
        s.chain.add(answer == null || answer.isBlank() ? "（放弃作答）" : answer.trim());

        boolean bail = isBailOut(s.chain.get(s.chain.size() - 1));
        boolean mustScore = giveUp || bail || s.followUps >= MAX_FOLLOW_UPS;
        JudgeResult d = judge(s, question, mustScore);
        if (bail) {
            log.info("[面试留痕] session={} 题{} 候选人放弃信号命中，跳过追问直接终评", s.sessionId, s.current + 1);
        }
        Map<String, Object> decision = d.decision();
        log.info("[面试留痕] session={} 《{}》 题{}/{} 第{}轮 候选人作答，判定 action={}（引用：{}）",
                s.sessionId, s.topic, s.current + 1, s.questions.size(), s.followUps + 1,
                decision.getOrDefault("action", "?"),
                d.hits().stream().map(KnowledgeService.KnowledgeHit::file).distinct().toList());

        boolean followup = !mustScore && "followup".equals(decision.get("action"))
                && decision.get("followup") instanceof String fu && !fu.isBlank();
        if (followup) {
            String fu = ((String) decision.get("followup")).trim();
            s.chain.add(fu);
            s.followUps++;
            log.info("[面试留痕] session={} 题{} 追问第{}/{}轮：{}", s.sessionId, s.current + 1, s.followUps, MAX_FOLLOW_UPS, fu);
            return Map.of("type", "followup", "followUp", fu,
                    "round", s.followUps, "maxRounds", MAX_FOLLOW_UPS);
        }

        // ---- 收尾打分 ----
        int score = decision.get("score") instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1;
        if (score < 0) { // 模型该评没评（或解析失败）→ 用整条链补评一次
            Map<String, Object> ev = evaluate(question, transcript(s.chain), s.topic);
            score = ev.get("score") instanceof Number n2 ? n2.intValue() : 0;
            decision.put("highlights", ev.getOrDefault("highlights", List.of()));
            decision.put("gaps", ev.getOrDefault("gaps", List.of()));
            decision.put("reference", ev.getOrDefault("reference", ""));
        }
        // 硬闸 3（综合打分）：实测小模型会把最后的"不知道/答不上来"锚定成低分、把此前答对的内容清零——
        // 提示词叮嘱拦不住。放弃轮本身不进评分语料：剔除所有放弃性作答（含「直接打分」按钮文案），
        // 用其余各轮实质作答重新评。giveUp=true 但带实质内容的最后一轮照常计入，不丢分。
        if ((bail || giveUp) && s.chain.size() >= 3) {
            List<String> prior = new ArrayList<>();
            for (int i = 0; i < s.chain.size(); i += 2) {
                if (!isBailOut(s.chain.get(i))) {
                    prior.add(s.chain.get(i));
                }
            }
            if (!prior.isEmpty()) {
                Map<String, Object> ev = evaluate(question, String.join("\n", prior), s.topic);
                int es = ev.get("score") instanceof Number n3 ? n3.intValue() : -1;
                if (es >= 0) {
                    log.info("[面试留痕] session={} 题{} 放弃轮综合重评：judge={} → 综合={}",
                            s.sessionId, s.current + 1, score, es);
                    score = es;
                    decision.put("highlights", ev.getOrDefault("highlights", List.of()));
                    List<Object> gaps2 = new ArrayList<>();
                    if (ev.get("gaps") instanceof List<?> gl) {
                        gaps2.addAll(gl);
                    }
                    gaps2.add("追问「" + s.chain.get(s.chain.size() - 2) + "」未能作答");
                    decision.put("gaps", gaps2);
                    decision.put("reference", ev.getOrDefault("reference", ""));
                }
            }
        }
        if (score <= 1) { // 硬闸：无效作答不允许带亮点
            decision.put("highlights", List.of());
        }
        // 硬闸 2：亮点只能逐字来自考生原话（防把参考资料内容当亮点），对照考生各轮作答过滤
        List<String> said = new ArrayList<>();
        for (int i = 0; i < s.chain.size(); i += 2) {
            said.add(s.chain.get(i));
        }
        decision.put("highlights", honestHighlights(decision.get("highlights"), said));
        s.scores.add(score);

        int next = s.current + 1;
        boolean done = next >= s.questions.size();
        List<Integer> allScores = List.copyOf(s.scores);
        int rounds = s.followUps;
        log.info("[面试留痕] session={} 题{} 终评 score={}（追问{}轮）总分台账={} 欠缺={}",
                s.sessionId, s.current + 1, score, rounds, allScores, decision.get("gaps"));

        // 留档本题已考内容（题干 + 实际追问过的问题），推进下一题前据此查重
        StringBuilder covered = new StringBuilder("题").append(s.current + 1).append("「")
                .append(question).append("」");
        if (s.chain.size() > 1) {
            covered.append("（已追问：");
            for (int i = 1; i < s.chain.size(); i += 2) {
                covered.append(s.chain.get(i)).append("；");
            }
            covered.append("）");
        }
        s.coveredNotes.add(covered.toString());
        if (!done) { // 查重闸：下一题若与前题考点重复/衍生（脱裤放屁），换一个独立考点
            s.questions.set(next, dedupGuard(s, next, s.questions.get(next)));
        }

        s.current = next;
        s.followUps = 0;
        s.chain.clear();
        if (done) {
            sessions.remove(s.sessionId);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("type", "score");
        out.put("score", score);
        out.put("highlights", decision.getOrDefault("highlights", List.of()));
        out.put("gaps", decision.getOrDefault("gaps", List.of()));
        out.put("reference", decision.getOrDefault("reference", ""));
        out.put("rounds", rounds);
        out.put("scores", allScores);
        out.put("nextIndex", next);
        out.put("nextQuestion", done ? "" : s.questions.get(next));
        out.put("done", done);
        return out;
    }

    /** 单次 LLM 判定：继续追问还是收尾，顺带给出基于全链的评分字段。temperature=0 保证稳定。 */
    private JudgeResult judge(InterviewSession s, String question, boolean mustScore) {
        List<KnowledgeService.KnowledgeHit> hits;
        if (s.topic != null && !s.topic.isBlank()) {
            hits = knowledgeService.searchInFiles(question, s.topic.trim() + "-", 4);
        } else {
            hits = List.of();
        }
        if (hits.isEmpty()) {
            hits = knowledgeService.search(question, 4);
        }

        StringBuilder sb = new StringBuilder("""
                你是资深 Java 面试官，正在对候选人「刨根问底」式拷打。

                主题：%s
                面试题：%s

                对话记录：
                %s
                """.formatted(s.topic, question, transcript(s.chain)));
        if (s.followUps > 0) {
            sb.append("\n你已问过的追问（新追问严禁与其重复或仅换措辞重问同一件事）：");
            for (int i = 0; i < s.followUps; i++) {
                sb.append("\n").append(i + 1).append(". ").append(s.chain.get(i * 2 + 1));
            }
        }
        if (hits.isEmpty()) {
            sb.append("\n（知识库未检索到相关资料，按通用标准评价）");
        } else {
            sb.append("\n评分参考资料（仅供了解标准答案；若与题目领域不符请忽略）：");
            for (int i = 0; i < hits.size(); i++) {
                sb.append("\n[").append(i + 1).append("] 来源: ").append(hits.get(i).file())
                  .append("\n").append(hits.get(i).text(), 0, Math.min(500, hits.get(i).text().length()));
            }
        }
        sb.append("""

                你的任务，只输出严格 JSON（不要 markdown 代码块、不要多余文字），结构：
                {"action":"followup" 或 "score", "followup":"追问内容(仅 followup 时需要)",
                 "score":0到10整数, "highlights":["亮点"], "gaps":["欠缺/错误"], "reference":"答题要点,120字内"}

                判定规则（按顺序判定，命中即停）：
                1. 收尾信号（最优先）：候选人最新回答出现"不知道 / 不会 / 不清楚 / 没了解 / 想不起来 / 没有了"
                   等明确放弃信号，或是放弃作答、闲聊 → 立即 action="score"，不要对已放弃的候选人继续追问。
                2. 充分覆盖：候选人不仅提到了核心考点、还给出了具体的机制/流程/原因说明，且无明显薄弱点
                   → action="score"。仅罗列名词、未展开机制的不算覆盖，必须追问其展开。
                3. 其余一律 action="followup"（这是"拷打"，追问是默认动作）：针对回答中最薄弱、含糊或未展开
                   之处追问一句，每次只追一个点。若上一轮追问未得到回答，换一个更小的切入点，严禁原样重问。
                4. score/highlights/gaps 无论 action 取值都要给出，且基于对话全程评判（综合打分原则）：
                   分数衡量整条对话链的总体表现——候选人此前答对的要点、解释清楚的专业术语都计入得分，
                   最后一轮说"不知道"或放弃不把前面的贡献清零，只是封顶了深度分。
                   例：答对基本概念并解释清楚、后续追问中放弃 → 给 3-5 分而非 0-1 分。
                   highlights 摘录候选人原话中的关键句（可截取），不要写概括性评语；整体答非所问时 score 给 0 或 1 且 highlights 为空数组；
                   未能深入的追问在 gaps 中体现；吝啬给分，答对核心概念 5-6 分，补齐深度才往上加""");
        if (mustScore) {
            sb.append("\n\n注意：本题追问次数已达上限或候选人已放弃，action 必须为 \"score\"。"
                    + "评分仍基于对话全程综合给出，不因最后放弃直接打 0 分。");
        }

        return new JudgeResult(parseObject(evalChatModel.chat(sb.toString())), hits);
    }

    /**
     * 下一题查重闸：题目是开场一次性生成的，不知道前面几题的追问链实际刨过什么——
     * 题 1 追问已经覆盖的考点，题 2 又原样来问，等于"脱裤放屁"。
     * 推进前把「已考内容清单」交给模型，重复/衍生就换成主题下未覆盖的独立考点。
     * 模型失灵或解析失败时保守返回原题，绝不阻断面试流程。
     */
    private String dedupGuard(InterviewSession s, int index, String nextQuestion) {
        try {
            String prompt = """
                    你是资深 Java 面试官，正在安排「%s」主题面试的第 %d 道题。

                    本场已经考过的内容（各题题干 + 实际追问过的问题）：
                    %s

                    原定的下一题是：「%s」

                    判断：这题的考点是否与已考内容重复，或是已考内容的直接衍生
                    （例如某题的追问链已经刨过某机制的细节，这题又来问该机制）？
                    只输出严格 JSON（不要 markdown 代码块、不要多余文字）：
                    {"changed": true 或 false, "question": "..."}
                    - 不重复：changed=false，question 原样返回原题。
                    - 重复或衍生：changed=true，question 给出「%s」主题下一个尚未考过的独立考点的新题，问法与其他题一致。"""
                    .formatted(s.topic, index + 1, String.join("\n", s.coveredNotes), nextQuestion, s.topic);
            Map<String, Object> d = parseObject(evalChatModel.chat(prompt));
            boolean changed = Boolean.TRUE.equals(d.get("changed"));
            String q = d.get("question") instanceof String x && !x.isBlank() ? x.trim() : nextQuestion;
            if (changed && !q.equals(nextQuestion)) {
                log.info("[面试留痕] session={} 题{} 查重命中，换题：原「{}」→ 新「{}」",
                        s.sessionId, index + 1, nextQuestion, q);
                return q;
            }
            log.info("[面试留痕] session={} 题{} 查重通过，保留原题：{}", s.sessionId, index + 1, nextQuestion);
            return nextQuestion;
        } catch (Exception e) {
            log.warn("[面试留痕] session={} 题{} 查重调用失败，保留原题: {}", s.sessionId, index + 1, e.getMessage());
            return nextQuestion;
        }
    }

    /** 亮点可信度闸：只保留能逐字对应到考生原话的亮点（归一化去标点后做包含判断） */
    private static List<Object> honestHighlights(Object hl, List<String> candidateTexts) {
        if (!(hl instanceof List<?> list)) {
            return List.of();
        }
        String said = String.join("", candidateTexts).replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        List<Object> kept = new ArrayList<>();
        for (Object x : list) {
            String h = String.valueOf(x).replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
            if (h.length() >= 4 && said.contains(h)) {
                kept.add(x);
            }
        }
        return kept;
    }

    /** 追问链转对话文本：[答,问,答,问…] → "候选人：…\n面试官：…" */
    private String transcript(List<String> chain) {
        StringBuilder t = new StringBuilder("候选人：").append(chain.get(0));
        for (int i = 1; i < chain.size(); i++) {
            t.append("\n").append(i % 2 == 1 ? "面试官：" : "候选人：").append(chain.get(i));
        }
        return t.toString();
    }

    private record JudgeResult(Map<String, Object> decision, List<KnowledgeService.KnowledgeHit> hits) {
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
