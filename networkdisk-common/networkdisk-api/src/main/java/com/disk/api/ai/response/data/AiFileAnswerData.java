package com.disk.api.ai.response.data;

import lombok.Data;

import java.util.List;

@Data
// AI 服务层返回的单文件问答结果数据。
public class AiFileAnswerData {

    private Long userId;

    private String fileId;

    private String filename;

    private String question;

    // 模型回答。
    private String answer;

    // 检索命中的引用片段说明。
    private List<String> references;

    private String model;

    private Boolean mocked;
}
