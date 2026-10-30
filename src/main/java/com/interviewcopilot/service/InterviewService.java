package com.interviewcopilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 追问轮数不设上限：拷打到底——出口 = 放弃信号 / 直接打分 / 重复追问闸 / 无欠缺收卷 */

    /** 复盘报告注册表：sessionId → 报告（收卷后异步生成，前端铃铛轮询取） */
    private final Map<String, ReportEntry> reports = new ConcurrentHashMap<>();

    /** 复盘报告后台生成：一次 LLM 调用实测 10~40s，不能阻塞收卷响应 */
    private final ExecutorService reportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "iv-report");
        t.setDaemon(true);
        return t;
    });

    /** 一份复盘报告：输入快照（失败可重试）+ 生成状态与产物 */
    public static final class ReportEntry {
        final String sessionId;
        final String topic;
        final String date;
        final List<Integer> scores;
        final List<Map<String, Object>> questionLog;
        final List<String> files;              // 本场真实检索命中的笔记（「建议复习」白名单）
        volatile String status = "generating"; // generating | ready | failed
        volatile String markdown = "";
        volatile String error = "";

        ReportEntry(String sessionId, String topic, String date,
                    List<Integer> scores, List<Map<String, Object>> questionLog, List<String> files) {
            this.sessionId = sessionId;
            this.topic = topic;
            this.date = date;
            this.scores = scores;
            this.questionLog = questionLog;
            this.files = files;
        }

        public String getSessionId() { return sessionId; }
        public String getStatus() { return status; }
        public String getMarkdown() { return markdown; }
        public String getError() { return error; }
    }

    /** 整句放弃信号：服务端确定性兜底，命中即强制收尾评分（提示词之外不赌模型听话） */
    private static final java.util.regex.Pattern BAIL_OUT = java.util.regex.Pattern.compile(
            "^(不知道了?|不记得|不太记得了?|想不起来了?|不清楚了?|不了解|没了解过?|没听说过?|没研究过?|"
                    + "不会|没有了?|没了|答不上来了?|答不上来了?直接打分吧?|直接打分吧?|放弃了?|我放弃了?|"
                    + "这个我不会|这题不会|不能了?|没法|没办法|说不上来|讲不出来|答不上|不会答|不知道怎么答)$");

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
        private Map<String, Object> checkpointDecision;               // 最近一次中途评分结果（本题未推进）
        private int checkpointChainSize = -1;                         // 中途评分时的作答轮数：一致则「下一题」原样采用
        private final List<String> checkpointGaps = new ArrayList<>(); // 中途评分的欠缺 → 后续追问锚点
        private final List<Map<String, Object>> questionLog = new ArrayList<>(); // 逐题留档（题干/得分/亮点/欠缺/追问/涉及笔记），收卷后供复盘报告用

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
                4. 吝啬给分：答对核心概念 5-6 分，补齐关键细节与深度才继续往上加；
                   若候选人已完整、准确覆盖考点（机制、流程、原因都讲清且无错误），gaps 允许为空数组并给 9-10 分——
                   满分应当罕见但可达，不许为了凑欠缺而编造欠缺。
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
        if (result.get("score") instanceof Number fm) { // 无欠缺即满分（9+空欠缺 → 10）
            result.put("score", fullMarks(fm.intValue(), result));
        }
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
     * 拷打循环的一轮，三种动作：常规作答（追问或终评）、checkpoint 中途评分（本题不结束）、
     * advance 接受得分进下一题。会话状态在服务端推进，前端只管收发。
     *
     * 返回 {"type":"followup","followUp":...,"round":n}（追问不设轮数上限，出口=放弃/判重/无欠缺），
     *      {"type":"checkpoint","score":n,"highlights":[...],"gaps":[...],"reference":...,"rounds":n}，或
     *      {"type":"score","score":n,"highlights":[...],"gaps":[...],"reference":...,
     *       "scores":[已完成各题得分], "nextIndex":n, "nextQuestion":"..."|"" , "done":bool}
     */
    public Map<String, Object> turn(String sessionId, String answer, boolean giveUp,
                                    boolean checkpoint, boolean advance) {
        InterviewSession s = sessions.get(sessionId == null ? "" : sessionId.trim());
        if (s == null) {
            throw new IllegalArgumentException("面试会话不存在或已结束，请重新开始面试");
        }
        if (checkpoint) {
            return doCheckpoint(s);
        }
        if (advance) {
            return doAdvance(s);
        }
        String question = s.questions.get(s.current);
        s.chain.add(answer == null || answer.isBlank() ? "（放弃作答）" : answer.trim());

        boolean bail = isBailOut(s.chain.get(s.chain.size() - 1));
        boolean mustScore = giveUp || bail; // 追问不上限：出口只有放弃信号 / 有欠缺继续拷 / 判重闸
        JudgeResult d = judge(s, question, mustScore);
        if (bail) {
            log.info("[面试留痕] session={} 题{} 候选人放弃信号命中，跳过追问直接终评", s.sessionId, s.current + 1);
        }
        Map<String, Object> decision = d.decision();
        log.info("[面试留痕] session={} 《{}》 题{}/{} 第{}轮 候选人作答，判定 action={}（引用：{}）",
                s.sessionId, s.topic, s.current + 1, s.questions.size(), s.followUps + 1,
                decision.getOrDefault("action", "?"),
                d.hits().stream().map(KnowledgeService.KnowledgeHit::file).distinct().toList());

        String fu = decision.get("followup") instanceof String x && !x.isBlank()
                ? ((String) decision.get("followup")).trim() : null;
        boolean followup = !mustScore && "followup".equals(decision.get("action")) && fu != null;
        if (followup && isAskedBefore(s, fu)) {
            // 硬闸：模型把问过的追问原样又问一遍（实测 glm-4-flash 无视提示词禁令）→ 没有新问题可问，强制终评
            log.info("[面试留痕] session={} 题{} 追问与已问过的问题重复，强制终评：{}", s.sessionId, s.current + 1, fu);
            followup = false;
        }
        if (!followup && !mustScore) {
            // 硬闸：有欠缺不收卷——判定模型"充分覆盖"偏宽松（一句话作答也能 9 分直接终评），
            // 只要仍有欠缺且候选人没放弃，就针对欠缺继续拷问（拷打是默认动作，无欠缺才收卷）。
            // 注意解析兜底（score=-1，gaps 是"解析失败"哨兵文案）不算真欠缺，走下方补评路径。
            boolean parseFailed = decision.get("score") instanceof Number pn && pn.intValue() < 0;
            List<?> gaps = decision.get("gaps") instanceof List<?> gl ? gl : List.of();
            if (!parseFailed && !gaps.isEmpty()) {
                if (fu == null) {
                    String g = String.valueOf(gaps.get(0));
                    fu = "你的回答里「" + (g.length() > 60 ? g.substring(0, 60) + "…" : g) + "」还没讲透，展开讲讲这部分";
                }
                followup = true;
                log.info("[面试留痕] session={} 题{} 终评判定仍有欠缺，服务端强制追问：{}", s.sessionId, s.current + 1, fu);
            }
        }
        if (followup) {
            s.chain.add(fu);
            s.followUps++;
            log.info("[面试留痕] session={} 题{} 追问第{}轮：{}", s.sessionId, s.current + 1, s.followUps, fu);
            return Map.of("type", "followup", "followUp", fu, "round", s.followUps);
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
        return finishQuestion(s, question, decision, score);
    }

    /**
     * 中途评分（checkpoint）：本题不结束、不推进状态，给当前得分 + 欠缺快照。
     * 欠缺同时记入会话，成为后续追问的锚点（针对欠缺一层层递进）；评分后无新作答就「下一题」
     * 的话，原样采用本次得分，同一内容不二次评分、不产生分数漂移。
     */
    private Map<String, Object> doCheckpoint(InterviewSession s) {
        String question = s.questions.get(s.current);
        boolean hasAnswer = false;
        for (int i = 0; i < s.chain.size(); i += 2) {
            if (!isBailOut(s.chain.get(i))) {
                hasAnswer = true;
                break;
            }
        }
        if (!hasAnswer) {
            throw new IllegalArgumentException("还没有作答，先答一版再中途评分");
        }
        Map<String, Object> decision = judge(s, question, false).decision();
        int score = decision.get("score") instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1;
        if (score < 0) { // 模型没给分 → 用整条链补评
            Map<String, Object> ev = evaluate(question, transcript(s.chain), s.topic);
            score = ev.get("score") instanceof Number n2 ? n2.intValue() : 0;
            decision.put("highlights", ev.getOrDefault("highlights", List.of()));
            decision.put("gaps", ev.getOrDefault("gaps", List.of()));
            decision.put("reference", ev.getOrDefault("reference", ""));
        }
        decision.put("highlights", honestHighlights(decision.get("highlights"), candidateTexts(s.chain)));
        score = fullMarks(score, decision); // 无欠缺即满分：9 分 + 空欠缺 → 10
        s.checkpointDecision = new HashMap<>(decision);
        s.checkpointChainSize = s.chain.size();
        s.checkpointGaps.clear();
        if (decision.get("gaps") instanceof List<?> gl) {
            for (Object g : gl) {
                s.checkpointGaps.add(String.valueOf(g));
            }
        }
        log.info("[面试留痕] session={} 题{} 中途评分 score={}（追问{}轮）欠缺={}",
                s.sessionId, s.current + 1, score, s.followUps, s.checkpointGaps);
        Map<String, Object> out = new HashMap<>();
        out.put("type", "checkpoint");
        out.put("score", score);
        out.put("highlights", decision.getOrDefault("highlights", List.of()));
        out.put("gaps", decision.getOrDefault("gaps", List.of()));
        out.put("reference", decision.getOrDefault("reference", ""));
        out.put("rounds", s.followUps);
        return out;
    }

    /** 接受得分进入下一题（advance）：中途评分后无新作答原样采用；有新作答/无中途评分则终评全链。 */
    private Map<String, Object> doAdvance(InterviewSession s) {
        String question = s.questions.get(s.current);
        Map<String, Object> decision;
        if (s.checkpointDecision != null && s.chain.size() == s.checkpointChainSize) {
            decision = s.checkpointDecision;
            log.info("[面试留痕] session={} 题{} 采用中途评分进入下一题（评分后无新作答）",
                    s.sessionId, s.current + 1);
        } else if (s.chain.isEmpty()) {
            decision = new HashMap<>(Map.of("score", 0, "highlights", List.of(),
                    "gaps", List.of("未作答，直接跳过"), "reference", ""));
        } else {
            decision = judge(s, question, true).decision();
        }
        int score = decision.get("score") instanceof Number n && n.intValue() >= 0 ? n.intValue() : -1;
        if (score < 0) { // 模型没给分/解析失败 → 用整条链补评（与 turn() 收尾路径同款兜底，不冤枉记 0 分）
            Map<String, Object> ev = evaluate(question, transcript(s.chain), s.topic);
            score = ev.get("score") instanceof Number n2 ? n2.intValue() : 0;
            decision.put("highlights", ev.getOrDefault("highlights", List.of()));
            decision.put("gaps", ev.getOrDefault("gaps", List.of()));
            decision.put("reference", ev.getOrDefault("reference", ""));
        }
        return finishQuestion(s, question, decision, score);
    }

    /** 本题收尾：亮点闸门、记入台账、留档已考内容并查重、推进下一题（或收卷）。 */
    private Map<String, Object> finishQuestion(InterviewSession s, String question,
                                               Map<String, Object> decision, int score) {
        int fm = fullMarks(score, decision); // 无欠缺即满分：9 分 + 空欠缺 → 10
        if (fm != score) {
            log.info("[面试留痕] session={} 题{} 无欠缺提满：{} → 10", s.sessionId, s.current + 1, score);
            score = fm;
        }
        if (score <= 1) { // 硬闸：无效作答不允许带亮点
            decision.put("highlights", List.of());
        }
        // 硬闸 2：亮点只能逐字来自考生原话（防把参考资料内容当亮点），对照考生各轮作答过滤
        decision.put("highlights", honestHighlights(decision.get("highlights"), candidateTexts(s.chain)));
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

        // 逐题留档（复盘报告的输入）：题干/得分/亮点/欠缺/追问过的问题/本题涉及的笔记
        List<String> asked = new ArrayList<>();
        for (int i = 1; i < s.chain.size(); i += 2) {
            asked.add(s.chain.get(i));
        }
        Map<String, Object> qlog = new HashMap<>();
        qlog.put("question", question);
        qlog.put("score", score);
        qlog.put("highlights", decision.getOrDefault("highlights", List.of()));
        qlog.put("gaps", decision.getOrDefault("gaps", List.of()));
        qlog.put("followUps", asked);
        qlog.put("rounds", rounds);
        qlog.put("files", filesFor(question, s.topic));
        s.questionLog.add(qlog);

        if (!done) { // 查重闸：下一题若与前题考点重复/衍生（脱裤放屁），换一个独立考点
            s.questions.set(next, dedupGuard(s, next, s.questions.get(next)));
        }

        s.current = next;
        s.followUps = 0;
        s.chain.clear();
        s.checkpointDecision = null;
        s.checkpointChainSize = -1;
        s.checkpointGaps.clear();
        if (done) {
            submitReport(s); // 收卷即后台生成复盘报告（异步，不阻塞本次响应）
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

    /** 追问是否已经问过：与原题或此前任何一条追问重复即真（提示词禁不住小模型原样重问，服务端判重硬闸） */
    private static boolean isAskedBefore(InterviewSession s, String fu) {
        if (isSameQuestion(fu, s.questions.get(s.current))) {
            return true;
        }
        for (int i = 1; i < s.chain.size(); i += 2) {
            if (isSameQuestion(fu, s.chain.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** 两问是否同题：归一化后完全相同 / 互为包含（短边≥8字）/ 字符二元组 Jaccard ≥ 0.75（换措辞重问） */
    private static boolean isSameQuestion(String a, String b) {
        String na = a.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        String nb = b.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "");
        if (na.isEmpty() || nb.isEmpty() || na.equals(nb)) {
            return !na.isEmpty() && na.equals(nb);
        }
        String sh = na.length() <= nb.length() ? na : nb, ln = na.length() <= nb.length() ? nb : na;
        if (sh.length() >= 8 && ln.contains(sh)) {
            return true;
        }
        java.util.Set<String> bigA = bigrams(na), bigB = bigrams(nb);
        java.util.Set<String> inter = new java.util.HashSet<>(bigA);
        inter.retainAll(bigB);
        java.util.Set<String> union = new java.util.HashSet<>(bigA);
        union.addAll(bigB);
        return !union.isEmpty() && (double) inter.size() / union.size() >= 0.75;
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    /** 对话链里的候选人各轮作答（偶数下标） */
    private static List<String> candidateTexts(List<String> chain) {
        List<String> said = new ArrayList<>();
        for (int i = 0; i < chain.size(); i += 2) {
            said.add(chain.get(i));
        }
        return said;
    }

    /** 无欠缺即满分：模型给 9 分且找不出任何欠缺时，压分的唯一理由只剩吝啬——直接给 10。
     *  仅 9 分档触发（模型对浅答犯懒返回空欠缺也不会被抬分）。 */
    private static int fullMarks(int score, Map<String, Object> decision) {
        return score == 9 && decision.get("gaps") instanceof List<?> gl && gl.isEmpty() ? 10 : score;
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
        if (!s.checkpointGaps.isEmpty()) {
            sb.append("\n中途评分指出的欠缺（你的追问应优先针对这些欠缺一层一层递进验证，候选人已补齐的不要再问）：");
            for (int i = 0; i < s.checkpointGaps.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(s.checkpointGaps.get(i));
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
                   未能深入的追问在 gaps 中体现；吝啬给分：答对核心概念 5-6 分，补齐关键细节与深度才继续往上加；
                   若候选人已完整、准确覆盖考点（机制、流程、原因都讲清且无错误），gaps 允许为空数组并给 9-10 分——
                   满分应当罕见但可达，不许为了凑欠缺而编造欠缺""");
        if (mustScore) {
            sb.append("\n\n注意：候选人已放弃本题，action 必须为 \"score\"。"
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

    // ---------- 复盘报告（收卷后异步生成，前端铃铛轮询取） ----------

    /** 收卷即把输入快照放入报告注册表并提交后台生成；LLM 分析 + 服务端白名单闸 + 确定性渲染。 */
    private void submitReport(InterviewSession s) {
        List<String> files = s.questionLog.stream()
                .flatMap(q -> ((List<?>) q.getOrDefault("files", List.of())).stream().map(String::valueOf))
                .distinct().toList();
        ReportEntry entry = new ReportEntry(s.sessionId, s.topic,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                List.copyOf(s.scores), List.copyOf(s.questionLog), files);
        reports.put(s.sessionId, entry);
        reportExecutor.submit(() -> generateReport(entry));
        log.info("[面试复盘] session={} 收卷，复盘报告后台生成中（{} 题，涉及笔记 {} 篇）",
                s.sessionId, entry.questionLog.size(), entry.files.size());
    }

    /** 报告生成的主体：逐题记录 + 笔记白名单 → LLM 严格 JSON 分析 → 服务端渲染 Markdown。 */
    private void generateReport(ReportEntry e) {
        long t0 = System.currentTimeMillis();
        try {
            StringBuilder qs = new StringBuilder();
            for (int i = 0; i < e.questionLog.size(); i++) {
                Map<String, Object> q = e.questionLog.get(i);
                qs.append("\n第").append(i + 1).append("题「").append(q.get("question")).append("」 得分 ")
                  .append(q.get("score")).append("/10");
                if (q.get("highlights") instanceof List<?> hl && !hl.isEmpty()) {
                    qs.append("\n  亮点：").append(String.join("；", hl.stream().map(String::valueOf).toList()));
                }
                if (q.get("gaps") instanceof List<?> gl && !gl.isEmpty()) {
                    qs.append("\n  欠缺：").append(String.join("；", gl.stream().map(String::valueOf).toList()));
                }
                if (q.get("followUps") instanceof List<?> fl && !fl.isEmpty()) {
                    qs.append("\n  追问过：").append(String.join("；", fl.stream().map(String::valueOf).toList()));
                }
            }
            String prompt = """
                    你是资深 Java 面试官，刚结束一场《%s》主题的模拟面试，请基于逐题记录做复盘分析。

                    日期：%s
                    逐题记录：
                    %s

                    本场实际涉及的知识库笔记文件（「建议复习」只能从这份清单里挑，文件名一个字不能改）：
                    %s

                    只输出严格 JSON（不要 markdown 代码块、不要多余文字），结构：
                    {"overall": "总体评价150字内：整体表现、得分结构、最突出的强项与短板",
                     "weakAreas": [{"area": "薄弱领域", "detail": "具体欠缺什么", "advice": "怎么补，30字内"}],
                     "reviewFiles": ["清单里最该复习的2-4个文件，按优先级"],
                     "next": "下一步行动，60字内"}

                    要求：weakAreas 按知识领域归并同类欠缺（不按题号罗列），2-5 组；
                    欠缺为空的题说明掌握扎实，不要硬找毛病；整体低分时如实指出基础薄弱。"""
                    .formatted(e.topic, e.date, qs, e.files.isEmpty() ? "（本场未命中笔记）" : String.join("\n", e.files));

            Map<String, Object> d = parseObject(evalChatModel.chat(prompt));
            List<String> review = filterReviewFiles(d.get("reviewFiles"), e.files);
            if (review.isEmpty() && !e.files.isEmpty()) {
                review = e.files.stream().limit(4).toList(); // 模型清单没过白名单 → 回退为本场实际涉及笔记（仍是真实文件）
                log.info("[面试复盘] session={} 模型复习清单未过白名单，回退为实际涉及笔记 {}", e.sessionId, review);
            }
            e.markdown = renderReport(e, d, review);
            e.status = "ready";
            log.info("[面试复盘] session={} 报告生成完成（{} ms，复习清单 {} 篇）",
                    e.sessionId, System.currentTimeMillis() - t0, review.size());
        } catch (Exception ex) {
            e.status = "failed";
            e.error = "复盘报告生成失败：" + ex.getMessage();
            log.warn("[面试复盘] session={} 生成失败: {}", e.sessionId, ex.getMessage());
        }
    }

    /** 白名单闸：LLM 挑的「建议复习」文件必须对应本场真实检索命中的笔记（防编造文件名），映射回真实文件名返回。 */
    private static List<String> filterReviewFiles(Object picked, List<String> realFiles) {
        List<String> out = new ArrayList<>();
        if (picked instanceof List<?> list) {
            for (Object x : list) {
                String fn = String.valueOf(x).replace(".md", "").trim();
                if (fn.isEmpty()) {
                    continue;
                }
                for (String r : realFiles) {
                    String rn = r.replace(".md", "").trim();
                    if (rn.equalsIgnoreCase(fn) || (fn.length() >= 4 && (rn.contains(fn) || fn.contains(rn)))) {
                        if (!out.contains(r)) {
                            out.add(r); // 展示真实文件名，LLM 写什么变体都归一到检索事实
                        }
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** 报告 Markdown 由服务端确定性渲染（LLM 只出结构化 JSON，版式/清单不交給模型自由发挥）。 */
    private static String renderReport(ReportEntry e, Map<String, Object> d, List<String> reviewFiles) {
        double avg = e.scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        StringBuilder md = new StringBuilder();
        md.append("# 面试复盘 · 《").append(e.topic).append("》\n\n");
        md.append("> ").append(e.date).append(" · 共 ").append(e.scores.size()).append(" 题 · 平均 ")
          .append(String.format("%.1f", avg)).append(" / 10\n\n");
        md.append("## 逐题得分\n\n| # | 题目 | 得分 |\n|---|---|---|\n");
        for (int i = 0; i < e.questionLog.size(); i++) {
            Map<String, Object> q = e.questionLog.get(i);
            md.append("| ").append(i + 1).append(" | ")
              .append(String.valueOf(q.get("question")).replace("|", "／")).append(" | ")
              .append(q.get("score")).append("/10 |\n");
        }
        md.append("\n## 总体评价\n\n").append(str(d.get("overall"))).append("\n");
        md.append("\n## 薄弱项归类\n\n");
        if (d.get("weakAreas") instanceof List<?> was && !was.isEmpty()) {
            for (Object w0 : was) {
                if (w0 instanceof Map<?, ?> w) {
                    md.append("- **").append(str(w.get("area"))).append("** — ")
                      .append(str(w.get("detail"))).append("\n  - 补法：").append(str(w.get("advice"))).append("\n");
                }
            }
        } else {
            md.append("（本场未发现明显薄弱项，保持手感）\n");
        }
        md.append("\n## 建议复习笔记\n\n");
        if (reviewFiles.isEmpty()) {
            md.append("（本场未检索到相关笔记）\n");
        } else {
            for (String f : reviewFiles) {
                md.append("- `").append(f).append("`\n");
            }
        }
        md.append("\n## 下一步\n\n").append(str(d.get("next"))).append("\n");
        return md.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 本题涉及的知识库笔记：与评分同款的主题限定检索，零 token、结果即白名单事实。 */
    private List<String> filesFor(String question, String topic) {
        List<KnowledgeService.KnowledgeHit> hits;
        if (topic != null && !topic.isBlank()) {
            hits = knowledgeService.searchInFiles(question, topic.trim() + "-", 4);
        } else {
            hits = List.of();
        }
        if (hits.isEmpty()) {
            hits = knowledgeService.search(question, 4);
        }
        List<String> files = new ArrayList<>();
        for (KnowledgeService.KnowledgeHit h : hits) {
            if (!files.contains(h.file())) {
                files.add(h.file());
            }
        }
        return files;
    }

    public ReportEntry report(String sessionId) {
        return sessionId == null ? null : reports.get(sessionId.trim());
    }

    /** 失败重试 / 重新生成：输入快照还在注册表里，直接再跑一次。 */
    public ReportEntry regenerateReport(String sessionId) {
        ReportEntry e = report(sessionId);
        if (e == null || "generating".equals(e.status)) {
            return e;
        }
        e.status = "generating";
        e.error = "";
        reportExecutor.submit(() -> generateReport(e));
        log.info("[面试复盘] session={} 重新生成", e.sessionId);
        return e;
    }

    /** 报告对外视图（GET/POST /api/interview/report 的响应体）。 */
    public Map<String, Object> reportView(ReportEntry e) {
        Map<String, Object> out = new HashMap<>();
        out.put("sessionId", e.sessionId);
        out.put("status", e.status);
        out.put("topic", e.topic);
        out.put("date", e.date);
        out.put("scores", e.scores);
        out.put("avg", e.scores.isEmpty() ? 0
                : Math.round(e.scores.stream().mapToInt(Integer::intValue).average().orElse(0) * 10) / 10.0);
        out.put("files", e.files);
        out.put("markdown", e.markdown);
        out.put("error", e.error);
        return out;
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
