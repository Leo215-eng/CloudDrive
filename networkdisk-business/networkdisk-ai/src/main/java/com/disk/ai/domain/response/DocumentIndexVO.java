package com.disk.ai.domain.response;

import lombok.Data;

@Data
// 返回给前端的文档索引结果。
// 只展示索引规模和状态，不返回具体向量。
public class DocumentIndexVO {

    private String fileId;

    private String filename;

    private String mediaType;

    private String parser;

    private Integer blockCount;

    private Integer chunkCount;

    private Integer vectorDimension;

    private Long contentLength;

    // 是否已建索引。
    private Boolean indexed;

    // 本次是否属于重新建索引。
    private Boolean reindexed;

    // 当前向量库是否启用。
    private Boolean vectorStoreEnabled;
}
