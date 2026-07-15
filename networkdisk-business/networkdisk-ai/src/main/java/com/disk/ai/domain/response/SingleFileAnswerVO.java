package com.disk.ai.domain.response;

import lombok.Data;

import java.util.List;

@Data
// 返回给前端的单文件问答结果。
public class SingleFileAnswerVO {

    private String fileId;

    private String filename;

    // 用户提出的问题。
    private String question;

    // 模型回答。
    private String answer;

    // 命中的引用片段说明。
    private List<String> references;

    // 生成回答使用的模型名。
    private String model;

    // 是否为 mock 模拟数据。
    private Boolean mocked;
}
