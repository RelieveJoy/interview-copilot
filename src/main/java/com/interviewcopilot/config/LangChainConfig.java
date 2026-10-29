package com.interviewcopilot.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大模型接入配置。
 *
 * DeepSeek 官方 API 兼容 OpenAI 协议（LangChain4j 无 deepseek 专用模块，
 * 官方推荐做法即用 openai 客户端指向 DeepSeek 端点）。
 * baseUrl / modelName 全部走配置文件，故障时改两行即可切换其他
 * OpenAI 兼容服务（如智谱 glm-4-flash）。
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
