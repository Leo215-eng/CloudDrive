package com.disk.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
// 前端请求“生成文档摘要”时传给 Controller 的参数。
public class DocumentSummaryParamVO {

    // 加密文件 ID。
    @NotBlank(message = "fileId can not be blank")
    private String fileId;

    private String filename;

    // 用户自定义摘要要求；为空时走默认摘要，并允许复用已保存结果。
    private String prompt;
}
