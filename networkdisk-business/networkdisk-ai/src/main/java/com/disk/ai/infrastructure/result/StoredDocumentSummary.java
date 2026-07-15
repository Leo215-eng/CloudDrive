package com.disk.ai.infrastructure.result;

import lombok.Data;

@Data
// 从 ai_document_result 表读出来的已保存摘要。
// 它是内部缓存对象，最后会转成 AiSummaryData 返回给前端。
public class StoredDocumentSummary {

    private String filename;

    // 摘要正文。
    private String summary;

    // 生成摘要时使用的模型名。
    private String model;

    // 是否来自 MockAiProviderClient。
    private Boolean mocked;
}
