package com.interviewcopilot.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大模型接入配置。
 *
 * 智谱 GLM 官方 API 兼容 OpenAI 协议，直接用 langchain4j-openai 客户端指向智谱端点。
 * baseUrl / modelName 全部走配置文件，改两行即可切换其他
 * OpenAI 兼容服务（如 DeepSeek、通义千问等）。
 */
@Configuration
public class LangChainConfig {

    @Bean
    public OpenAiChatModel chatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                // 未配置 key 时用占位符让应用仍能启动（/api/health 可用），
                // 真正调用前由 Controller 拦截并给出友好提示
                .apiKey(apiKey == null || apiKey.isBlank() ? "sk-not-configured" : apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(90))
                .build();
    }

    /** 流式版本（SSE 打字机效果），与同步版共用同一套配置 */
    @Bean
    public OpenAiStreamingChatModel streamingChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model-name}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? "sk-not-configured" : apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(90))
                .build();
    }

    /** 面试评分专用：temperature=0 保证同一作答多次评分结果一致（创意交给出题模型） */
    @Bean
    public OpenAiChatModel evalChatModel(
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model-name}") String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey == null || apiKey.isBlank() ? "sk-not-configured" : apiKey)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(90))
                .build();
    }
}
