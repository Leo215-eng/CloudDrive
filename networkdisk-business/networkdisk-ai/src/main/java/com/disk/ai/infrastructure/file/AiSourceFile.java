package com.disk.ai.infrastructure.file;

import lombok.Data;

@Data
// AI 模块内部使用的原始文件对象。
// 它把文件服务返回的元信息和真实文件字节统一装起来，方便 Tika 解析。
public class AiSourceFile {

    // 当前用户 ID。
    private Long userId;

    // 前端传来的加密文件 ID。
    private String fileId;

    // 用户文件表 ID，用于定位“某个用户网盘里的某个文件”。
    private Long userFileId;

    // 真实文件 ID，同一个真实文件可能被多个用户文件记录引用。
    private Long realFileId;

    private String filename;

    private String fileSuffix;

    private String contentType;

    private String identifier;

    private String fileSize;

    private Integer fileType;

    // 原始文件二进制内容，Tika 会从这里读取并提取文本。
    private byte[] bytes;
}
