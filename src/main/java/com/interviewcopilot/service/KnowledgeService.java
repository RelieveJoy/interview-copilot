package com.interviewcopilot.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库服务：Markdown 笔记的 加载 → 切分 → 向量化 → 入库 → 检索。
 *
 * 语料为自写的八股笔记（## 分节 + 表格），400 字/段 + 50 字重叠，
 * 每段拼上文档标题前缀提升主题词权重。导入按文件名去重，重复自动跳过。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentSplitter splitter;
    private final int maxResults;
    private final double minScore;
    private final String autoImportDir;

    /** 已导入文件名（去重用） */
    private final Set<String> importedFiles = ConcurrentHashMap.newKeySet();

    public KnowledgeService(EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            @Value("${rag.segment-size:400}") int segmentSize,
                            @Value("${rag.segment-overlap:50}") int segmentOverlap,
                            @Value("${rag.max-results:4}") int maxResults,
                            @Value("${rag.min-score:0.7}") double minScore,
                            @Value("${rag.auto-import-dir:}") String autoImportDir) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.splitter = DocumentSplitters.recursive(segmentSize, segmentOverlap);
        this.maxResults = maxResults;
        this.minScore = minScore;
        this.autoImportDir = autoImportDir;
    }

    /** 待入库文档：文件名 + 全文 */
    private record DocPair(String name, String text) {
    }

    /** 导入结果：新导入文件数 / 跳过数 / 生成向量段数 */
    public record ImportResult(int importedFiles, int skippedFiles, long segments, List<String> fileNames) {
    }

    /** 检索命中：来源文件 + 相似度得分 + 原文 */
    public record KnowledgeHit(String file, double score, String text) {
    }

    /** 启动时若配置了 rag.auto-import-dir 则自动导入语料（clone 后零接口操作即可用）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void autoImportOnStartup() {
        if (autoImportDir == null || autoImportDir.isBlank()) {
            return;
        }
        Path path = Path.of(autoImportDir.trim());
        if (!Files.exists(path)) {
            log.warn("rag.auto-import-dir 路径不存在, 跳过自动导入: {}", path.toAbsolutePath());
            return;
        }
        try {
            ImportResult result = importFrom(path);
            log.info("启动自动导入完成: {} 个文件, {} 段向量", result.importedFiles(), result.segments());
        } catch (Exception e) {
            log.warn("启动自动导入知识库失败: {}", e.getMessage());
        }
    }

    /** 导入一个目录下全部 .md 文件（不递归），或单个文件。 */
    public ImportResult importFrom(Path path) throws IOException {
        List<Path> files;
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                files = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md")).sorted().toList();
            }
        } else {
            files = List.of(path);
        }
        List<DocPair> pairs = new ArrayList<>();
        for (Path p : files) {
            pairs.add(new DocPair(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8)));
        }
        return ingest(pairs);
    }

    /** 导入前端上传的单个文档内容。 */
    public ImportResult importText(String fileName, String text) {
        return ingest(List.of(new DocPair(fileName, text)));
    }

    /** 去重 → 切分（带标题前缀）→ 批量向量化 → 批量入库（本地 ONNX，无网络开销）。 */
    private ImportResult ingest(List<DocPair> pairs) {
        List<DocPair> fresh = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int skipped = 0;
        for (DocPair pair : pairs) {
            if (importedFiles.contains(pair.name())) {
                skipped++;
                continue;
            }
            fresh.add(pair);
            names.add(pair.name());
        }
        if (fresh.isEmpty()) {
            return new ImportResult(0, skipped, 0, names);
        }

        List<TextSegment> segments = new ArrayList<>();
        for (DocPair pair : fresh) {
            Document doc = Document.from(pair.text(),
                    new dev.langchain4j.data.document.Metadata().put("source", pair.name()));
            // 不给段落拼标题前缀：实测会过度放大标题词权重，
            // 把"跨主题问题"（如 HashMap+线程安全）的目标文档挤出 top-N
            segments.addAll(splitter.split(doc));
        }
        Response<List<Embedding>> embeddings = embeddingModel.embedAll(segments);
        embeddingStore.addAll(embeddings.content(), segments);
        importedFiles.addAll(names);

        log.info("知识库导入: {} 个文件, 跳过 {} 个, 共 {} 段向量", names.size(), skipped, segments.size());
        return new ImportResult(names.size(), skipped, segments.size(), names);
    }

    /** 向量检索：问题 → 相似度最高的若干知识段。 */
    public List<KnowledgeHit> search(String question) {
        Embedding queryVector = embeddingModel.embed(question).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryVector)
                .maxResults(maxResults)
                .minScore(minScore)
                .build());
        List<KnowledgeHit> hits = result.matches().stream()
                .map((EmbeddingMatch<TextSegment> m) -> new KnowledgeHit(
                        m.embedded().metadata().getString("source"),
                        m.score() == null ? 0.0 : m.score(),
                        m.embedded().text()))
                .toList();
        log.debug("检索 '{}' 命中 {} 段", question, hits.size());
        return hits;
    }

    public int importedFileCount() {
        return importedFiles.size();
    }

    public List<String> importedFileNames() {
        return importedFiles.stream().sorted().toList();
    }
}
