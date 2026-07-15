package com.disk.ai.infrastructure.parser;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
// Tika 解析后的文档对象。
// 后续摘要、标签、切块、建索引都基于这里的 plainText 和 blocks。
public class ParsedDocument {

    // 文件媒体类型，例如 application/pdf。
    private String mediaType;

    // 实际使用的解析器名称，方便排查 Tika 解析问题。
    private String parser;

    // 从文件中提取出的纯文本。
    private String plainText;

    // 按段落拆出来的文本块。
    private List<DocumentBlock> blocks;

    // Tika 读取到的元数据，例如 content-type、title 等。
    private Map<String, String> metadata;
}
