package com.disk.files.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Getter
@Setter
public class FileSearchParamVO {

    /**
     * 搜索的关键字
     */
    @NotBlank(message = "搜索关键字不能为空")
    private String keyword;

    /**
     * 文件类型，多个文件类型使用公用分隔符拼接
     */
    private String fileTypes;

    /**
     * 页码和每页数量都有上限，避免搜索接口被当成批量导出接口。
     */
    @Min(value = 1, message = "页码不能小于1")
    @Max(value = 100, message = "页码不能大于100")
    @NotNull(message = "页码不能为空")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 100, message = "每页数量不能大于100")
    @NotNull(message = "每页数量不能为空")
    private Integer pageSize = 20;
}
