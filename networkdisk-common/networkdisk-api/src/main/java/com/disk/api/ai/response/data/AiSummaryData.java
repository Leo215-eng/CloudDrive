package com.disk.api.ai.response.data;

import lombok.Data;

@Data
// AI 服务层返回的摘要结果数据。
public class AiSummaryData {

    private Long userId;

    private String fileId;

    private String filename;

    // 摘要正文。
    private String summary;

    // 使用的模型名。
    private String model;

    private Boolean mocked;
}
