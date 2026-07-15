package com.disk.ai.infrastructure.parser;

import lombok.Data;

@Data
// 文档解析后的段落级文本块。
// 它比 TextChunk 更粗，TextChunk 是在它基础上继续按长度切出来的向量片段。
public class DocumentBlock {

    // 当前段落块序号。
    private Integer blockIndex;

    // 块类型，目前主要是 paragraph，兜底时可能是 document。
    private String blockType;

    // 当前段落文本。
    private String text;

    // 当前段落在全文中的开始位置。
    private Integer startOffset;

    // 当前段落在全文中的结束位置。
    private Integer endOffset;
}
