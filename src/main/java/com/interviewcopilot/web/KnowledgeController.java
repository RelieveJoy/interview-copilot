package com.interviewcopilot.web;

import com.interviewcopilot.service.KnowledgeService;
import com.interviewcopilot.web.dto.ImportRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库导入接口。
 * POST /api/knowledge/import  { "path": "目录或.md文件" }
 * GET  /api/knowledge/status  当前导入统计
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importKnowledge(@RequestBody ImportRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "path 不能为空"));
        }
        Path path;
        try {
            path = Path.of(request.path().trim());
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "路径不合法：" + request.path()));
        }
        if (!Files.exists(path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "路径不存在：" + path.toAbsolutePath()));
        }
        try {
            KnowledgeService.ImportResult result = knowledgeService.importFrom(path);
            return ResponseEntity.ok(Map.of(
                    "importedFiles", result.importedFiles(),
                    "skippedFiles", result.skippedFiles(),
                    "segments", result.segments(),
                    "fileNames", result.fileNames()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /** 前端上传导入：multipart 文件（支持多选 .md），直接入库。 */
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未选择文件"));
        }
        int imported = 0;
        int skipped = 0;
        long segments = 0;
        List<String> names = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                KnowledgeService.ImportResult r = knowledgeService.importText(
                        name, new String(f.getBytes(), StandardCharsets.UTF_8));
                imported += r.importedFiles();
                skipped += r.skippedFiles();
                segments += r.segments();
                if (r.importedFiles() > 0) {
                    names.add(name);
                }
            } catch (IOException e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "读取文件失败：" + name + ", " + e.getMessage()));
            }
        }
        return ResponseEntity.ok(Map.of(
                "importedFiles", imported,
                "skippedFiles", skipped,
                "segments", segments,
                "fileNames", names));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("importedFiles", knowledgeService.importedFileCount());
    }

    /** 检索诊断：只走向量检索不调 LLM，用于验证知识库命中质量（零 token 消耗） */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "q 不能为空"));
        }
        List<Map<String, Object>> hits = knowledgeService.search(q).stream()
                .map(h -> Map.<String, Object>of(
                        "file", h.file(),
                        "score", h.score(),
                        "text", h.text()))
                .toList();
        return ResponseEntity.ok(Map.of("query", q, "hits", hits));
    }
}
