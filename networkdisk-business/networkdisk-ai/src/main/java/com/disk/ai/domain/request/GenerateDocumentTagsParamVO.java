package com.disk.ai.domain.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
// 前端请求“生成智能标签”时传给 Controller 的参数。
public class GenerateDocumentTagsParamVO {

    @NotBlank(message = "fileId can not be blank")
    private String fileId;

    private String filename;

    // 希望返回的标签数量，限制在 1 到 20，避免一次请求过多标签。
    @Min(value = 1, message = "topK must be greater than 0")
    @Max(value = 20, message = "topK must be less than or equal to 20")
    private Integer topK = 5;
}
