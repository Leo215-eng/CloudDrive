package com.disk.ai.infrastructure.vector;

import lombok.Data;

@Data
// pgvector 相似度检索的返回对象。
// 用户问题会转成向量，这个对象表示“最像这个问题的某个文档片段”。
public class PgVectorSearchResult {

    // 命中的 chunk 序号。
    private Integer chunkIndex;

    // 命中的段落块序号。
    private Integer blockIndex;

    // 命中的文本片段。
    private String chunkText;

    // 相似度分数，越高表示越相关。
    private Double similarity;
}
