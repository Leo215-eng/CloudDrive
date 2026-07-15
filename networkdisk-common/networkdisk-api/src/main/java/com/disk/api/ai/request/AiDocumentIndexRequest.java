package com.disk.api.ai.request;

import lombok.Data;

@Data
// AI 服务内部/远程调用使用的建索引请求。
// Controller 会把前端 VO 转成这个对象，再交给 AiApplicationService。
public class AiDocumentIndexRequest {

    private Long userId;

    private String fileId;

    private Long userFileId;

    private String filename;

    private Boolean forceReindex = Boolean.FALSE;
}
