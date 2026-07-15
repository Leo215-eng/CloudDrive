package com.disk.ai.infrastructure.vector;

import lombok.Data;

@Data
// 文件级索引摘要。
// 它不保存具体文本和向量，只记录这个文件是否建过索引以及索引规模。
public class DocumentIndexSummary {

    private String filename;

    private String mediaType;

    private String parser;

    private Integer blockCount;

    private Integer chunkCount;

    private Integer vectorDimension;

    private Long contentLength;
}
