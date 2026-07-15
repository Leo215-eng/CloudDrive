package com.disk.api.ai.request;

import lombok.Data;

@Data
// AI 服务内部/远程调用使用的标签生成请求。
public class AiDocumentTagRequest {

    private Long userId;

    private String fileId;

    private Long userFileId;

    private String filename;

    // 需要的标签数量。
    private Integer topK = 5;
}
