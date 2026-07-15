package com.disk.ai.infrastructure.vector;

import lombok.Data;

@Data
// 准备写入 pgvector 表的文档块对象。
// 它包含文本块本身、文件元信息，以及该文本块的 embedding 向量。
public class PgVectorDocumentChunk {

    private Long userId;

    private Long userFileId;

    private Long realFileId;

    private String filename;

    private String fileSuffix;

    private String mediaType;

    private String parser;

    private Integer blockIndex;

    private Integer chunkIndex;

    private Integer startOffset;

    private Integer endOffset;

    private Integer tokenEstimate;

    private String chunkText;

    // 文本块的语义向量，最终写入 PostgreSQL 的 vector 字段。
    private float[] embedding;
}
