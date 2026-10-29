package com.interviewcopilot.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 声明式 RAG 助手（LangChain4j AiServices）。
 *
 * 调用 chat(sessionId, question) 时框架自动完成：
 * 按 sessionId 隔离的多轮记忆 → 检索增强（RetrievalAugmentor）→ 在线 LLM 生成。
 */
public interface KnowledgeAssistant {

    String SYSTEM_PROMPT = """
            你是「面试拷打助手」，帮用户备战 Java 后端面试。回答规则：
            1. 优先且仅依据用户消息中附带的<参考资料>回答，引用处注明来源文件名，如（来源：JVM-垃圾回收.md）
            2. 资料未覆盖的问题，先明确说明"知识库暂无此内容"，再给简短通用回答
            3. 面对追问要结合之前的对话上下文，不要重复已讲过的内容
            4. 中文作答，条理清晰，重点内容加粗；直接切入内容，不要以"根据参考资料"之类的元话语开场
            5. 用户消息是问候、寒暄、闲聊或与 Java 知识无关的请求时（如"你好呀"），只用一两句话简短友好地回应，
               忽略<参考资料>，不要罗列知识点、不要输出长篇结构""";

    @SystemMessage(SYSTEM_PROMPT)
    String chat(@MemoryId String sessionId, @UserMessage String question);

    /** 流式版本：TokenStream 支持逐 token 回调，onRetrieved 还能在生成前拿到检索命中 */
    @SystemMessage(SYSTEM_PROMPT)
    TokenStream chatStream(@MemoryId String sessionId, @UserMessage String question);
}
