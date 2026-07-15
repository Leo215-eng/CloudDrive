package com.disk.api.ai.response.data;

import lombok.Data;

import java.util.List;

@Data
// AI 服务层返回的标签结果数据。
public class AiTagData {

    private Long userId;

    private String fileId;

    private String filename;

    // 标签列表。
    private List<String> tags;

    // 使用的模型名。
    private String model;

    private Boolean mocked;
}
