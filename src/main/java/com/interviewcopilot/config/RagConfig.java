package com.interviewcopilot.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索链路配置 —— 混合架构的"本地"那一半。
 *
 * 向量化用进程内 ONNX 模型 BAAI/bge-small-zh-v1.5（512 维），
 * 不需要任何 API key、不依赖外部向量库服务；向量存内存
 * （语料规模为百级文档段，内存库完全够用且零部署）。
 */
@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel,
                                             @Value("${rag.max-results:4}") int maxResults,
                                             @Value("${rag.min-score:0.7}") double minScore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
