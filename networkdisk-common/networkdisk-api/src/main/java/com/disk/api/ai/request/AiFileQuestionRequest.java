package com.disk.api.ai.request;

import lombok.Data;

@Data
// AI 服务内部/远程调用使用的单文件问答请求。
public class AiFileQuestionRequest {

    private Long userId;

    private String fileId;

    private Long userFileId;

    private String filename;

    // 用户问题。
    private String question;

    // 是否返回向量检索命中的引用片段。
    private Boolean includeReferences = Boolean.TRUE;
}
