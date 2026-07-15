package com.disk.api.ai.request;

import lombok.Data;

@Data
// AI 服务内部/远程调用使用的摘要请求。
public class AiDocumentSummaryRequest {

    private Long userId;

    private String fileId;

    private Long userFileId;

    private String filename;

    // 自定义摘要提示词；为空表示默认摘要。
    private String prompt;
}
