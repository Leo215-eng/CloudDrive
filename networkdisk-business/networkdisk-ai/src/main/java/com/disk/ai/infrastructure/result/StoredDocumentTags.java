package com.disk.ai.infrastructure.result;

import lombok.Data;

import java.util.List;

@Data
// 从 ai_document_result 表读出来的已保存标签。
// 数据库里是 jsonb，读出后转成 List<String>。
public class StoredDocumentTags {

    private String filename;

    // 标签列表，例如 ["合同", "风险", "付款"]。
    private List<String> tags;

    // 生成标签时使用的模型名。
    private String model;

    // 是否来自 MockAiProviderClient。
    private Boolean mocked;
}
