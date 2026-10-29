package com.interviewcopilot.config;

import com.interviewcopilot.service.KnowledgeAssistant;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 组装声明式助手：多轮记忆（按会话隔离） + 检索增强 + 在线 LLM。
 */
@Configuration
public class AssistantConfig {

    @Bean
    public KnowledgeAssistant knowledgeAssistant(ChatModel chatModel,
                                                 StreamingChatModel streamingChatModel,
                                                 ContentRetriever contentRetriever) {
        // 检索增强器：检索 → 中文化注入模板（把来源文件名一并注入，供引用溯源）
        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(DefaultContentInjector.builder()
                        .promptTemplate(PromptTemplate.from("""
                                {{userMessage}}

                                请结合以下<参考资料>回答（引用时注明来源文件名）：
                                {{contents}}"""))
                        .metadataKeysToInclude(List.of("source"))
                        .build())
                .build();

        return AiServices.builder(KnowledgeAssistant.class)
                .chatModel(chatModel)
                // TokenStream 返回类型的方法走流式模型
                .streamingChatModel(streamingChatModel)
                .retrievalAugmentor(augmentor)
                // 每个 sessionId 独立记忆窗口，支撑多轮追问
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(12)
                        .build())
                .build();
    }
}
