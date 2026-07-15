package com.disk.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
// 前端请求“重建/创建文档向量索引”时传给 Controller 的参数。
public class DocumentIndexParamVO {

    // 加密文件 ID，后端会解密成 userFileId。
    @NotBlank(message = "fileId can not be blank")
    private String fileId;

    private String filename;

    // true 表示忽略已有索引，强制重新解析和向量化。
    private Boolean forceReindex = Boolean.FALSE;
}
