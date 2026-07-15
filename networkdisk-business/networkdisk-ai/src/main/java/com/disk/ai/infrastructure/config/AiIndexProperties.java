package com.disk.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "com.disk.ai.index")
// 文档索引配置类。
// Spring Boot 会把 application.yml 中 com.disk.ai.index 下的配置绑定到这里。
public class AiIndexProperties {

    // 每个文本块的目标字符数。
    private Integer chunkSize = 1200;

    // 相邻文本块重叠字符数，避免语义刚好被切断。
    private Integer chunkOverlap = 200;

    // 问答时从向量库取最相关的前 K 个文本块。
    private Integer retrievalTopK = 6;

    // Tika 解析后最多保留多少字符进入 AI 索引流程。
    private Integer maxTextChars = 200000;

    // 支持进入 AI 解析/索引流程的文件后缀。
    private List<String> supportedFileSuffixes = List.of(
            ".pdf",
            ".doc",
            ".docx",
            ".txt",
            ".md",
            ".markdown",
            ".csv",
            ".xls",
            ".xlsx",
            ".ppt",
            ".pptx",
            ".html",
            ".htm",
            ".xml",
            ".json",
            ".sql",
            ".java",
            ".js",
            ".css"
    );
}
