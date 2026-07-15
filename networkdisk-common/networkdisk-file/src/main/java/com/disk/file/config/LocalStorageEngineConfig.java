package com.disk.file.config;

import com.disk.base.exception.SystemException;
import com.disk.base.utils.FileUtil;
import com.google.common.collect.Lists;
import lombok.Data;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Data
@Component
//ConfigurationProperties：Spring Boot 的注解，自动从配置文件里读参数。
// 比如你在 application.yml 里写 com.disk.file.storage.engine.local.rootFileChunkPath = /data/chunks，它就会用你配置的路径，不写就用默认值。
@ConfigurationProperties("com.disk.file.storage.engine.local")
public class LocalStorageEngineConfig {

    /**
     /**
     * 完整文件的存储根路径（合并后的完整文件存在这）
     * 不配置的话，用工具类生成的默认路径
     */
    private String rootFilePath = FileUtil.generateDefaultStoreFileRealPath();

    /**
     * 分片文件的存储根路径（所有分片都存在这个总目录下）
     * 不配置的话，默认存在 用户主目录/coder-pan/chunks
     */
    private String rootFileChunkPath = FileUtil.generateDefaultStoreFileChunkRealPath();

}
