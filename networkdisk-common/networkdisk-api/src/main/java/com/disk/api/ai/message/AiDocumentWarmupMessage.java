package com.disk.api.ai.message;

import lombok.Data;

@Data
// 文件服务发给 AI 服务的“文档预热”消息。
// AI 服务消费后会自动建索引、生成摘要和标签。
public class AiDocumentWarmupMessage {

    private Long userId;

    private Long userFileId;

    private String fileId;

    private String filename;

    private Integer topK = 5;
}
