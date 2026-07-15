package com.disk.ai.domain.response;

import lombok.Data;

@Data
// 返回给前端的文档摘要结果。
public class DocumentSummaryVO {

    private String fileId;

    private String filename;

    // 摘要正文。
    private String summary;

    // 生成摘要使用的模型名。
    private String model;

    // 是否为 mock 模拟数据。
    private Boolean mocked;
}
