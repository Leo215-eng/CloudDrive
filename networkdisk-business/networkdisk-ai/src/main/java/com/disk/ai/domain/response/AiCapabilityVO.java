package com.disk.ai.domain.response;

import lombok.Data;

import java.util.List;

@Data
// 返回给前端的 AI 服务能力信息，用于抽屉顶部状态展示。
public class AiCapabilityVO {

    // 服务标识，例如 networkdisk-ai。
    private String service;

    // AI 服务商类型，例如 openai-compatible 或 mock。
    private String provider;

    // 当前对话模型名。
    private String chatModel;

    // 是否为 mock 模拟实现。
    private Boolean mockEnabled;

    // 是否启用向量检索。
    private Boolean vectorStoreEnabled;

    // embedding 向量维度。
    private Integer embeddingDimension;

    // 文档切块大小。
    private Integer chunkSize;

    // 文档切块重叠大小。
    private Integer chunkOverlap;

    // 支持的 AI 功能列表。
    private List<String> features;
}
