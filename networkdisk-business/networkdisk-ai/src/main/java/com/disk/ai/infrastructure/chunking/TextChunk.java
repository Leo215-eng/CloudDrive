package com.disk.ai.infrastructure.chunking;

import lombok.Data;

@Data
// 最终用于 embedding 和向量检索的文本片段。
// 一个 DocumentBlock 可能会切成多个 TextChunk。
public class TextChunk {

    // 全文范围内的 chunk 序号。
    private Integer chunkIndex;

    // 来源段落块序号。
    private Integer blockIndex;

    // 实际送去生成 embedding 的文本。
    private String text;

    // 当前 chunk 在全文中的开始位置。
    private Integer startOffset;

    // 当前 chunk 在全文中的结束位置。
    private Integer endOffset;

    // 粗略 token 估算，用于观察上下文大小。
    private Integer tokenEstimate;
}
