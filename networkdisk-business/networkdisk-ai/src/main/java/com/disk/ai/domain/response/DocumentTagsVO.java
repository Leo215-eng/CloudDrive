package com.disk.ai.domain.response;

import lombok.Data;

import java.util.List;

@Data
// 返回给前端的智能标签结果。
public class DocumentTagsVO {

    private String fileId;

    private String filename;

    // 标签数组。
    private List<String> tags;

    // 生成标签使用的模型名。
    private String model;

    // 是否为 mock 模拟数据。
    private Boolean mocked;
}
