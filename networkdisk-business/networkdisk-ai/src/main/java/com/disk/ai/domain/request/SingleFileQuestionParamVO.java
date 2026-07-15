package com.disk.ai.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
// 前端请求“单文件问答”时传给 Controller 的参数。
public class SingleFileQuestionParamVO {

    @NotBlank(message = "fileId can not be blank")
    private String fileId;

    private String filename;

    // 用户的问题。
    @NotBlank(message = "question can not be blank")
    private String question;

    // 是否返回命中的引用片段；false 时后端只返回答案。
    private Boolean includeReferences = Boolean.TRUE;
}
