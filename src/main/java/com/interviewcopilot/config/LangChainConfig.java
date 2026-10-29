package com.interviewcopilot.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
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
}
