package com.disk.ai.infrastructure.file;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.api.files.request.AiFileReadRequest;
import com.disk.api.files.response.FileQueryResponse;
import com.disk.api.files.response.data.AiFileReadData;
import com.disk.api.files.service.FileFacadeService;
import com.disk.base.utils.IdUtil;
import com.disk.file.context.ReadFileContext;
import com.disk.file.core.StorageEngine;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
// AI 原始文件加载器。
// 作用：根据用户文件 ID 找到真实文件路径，再把文件字节读出来，交给 Tika 解析。
public class AiSourceFileLoader {

    // 文件存储引擎：真正从磁盘/对象存储读取文件内容。
    private final StorageEngine storageEngine;

    // Dubbo 远程服务：向 files 服务查询文件读取信息，例如 realPath、文件名、后缀、真实文件 ID。
    @DubboReference(version = "1.0.0")
    private FileFacadeService fileFacadeService;

    public AiSourceFileLoader(StorageEngine storageEngine) {
        this.storageEngine = storageEngine;
    }

    public AiSourceFile load(Long userId, String fileId, Long userFileId) {
        // Controller 通常已经解密出 userFileId；如果没传，这里再用 fileId 解密兜底。
        Long resolvedUserFileId = userFileId == null ? IdUtil.decrypt(fileId) : userFileId;

        // 向文件服务请求“这个用户文件该怎么读”。
        AiFileReadRequest request = new AiFileReadRequest();
        request.setUserId(userId);
        request.setUserFileId(resolvedUserFileId);

        FileQueryResponse<AiFileReadData> response = fileFacadeService.getFileReadInfo(request);
        if (response == null || !Boolean.TRUE.equals(response.getSuccess()) || response.getData() == null) {
            throw new AiException(AiErrorCode.FILE_READ_FAILED.getMessage(), AiErrorCode.FILE_READ_FAILED);
        }

        // 文件服务返回元信息，storageEngine 根据 realPath 读取真实字节。
        // response.getData() 才是真正的文件读取信息
//        从响应对象里取出 data 字段，这个字段是一个 AiFileReadData 对象
        AiFileReadData data = response.getData();
        byte[] bytes = readBytes(data.getRealPath());

        // 封装成 AI 模块内部统一使用的文件对象。
        AiSourceFile sourceFile = new AiSourceFile();
        sourceFile.setUserId(userId);
        sourceFile.setFileId(fileId);
        sourceFile.setUserFileId(resolvedUserFileId);
        sourceFile.setRealFileId(data.getRealFileId());
        sourceFile.setFilename(data.getFilename());
        sourceFile.setFileSuffix(data.getFileSuffix());
        sourceFile.setContentType(data.getFilePreviewContentType());
        sourceFile.setIdentifier(data.getIdentifier());
        sourceFile.setFileSize(data.getFileSize());
        sourceFile.setFileType(data.getFileType());
        sourceFile.setBytes(bytes);
        return sourceFile;
    }

    private byte[] readBytes(String realPath) {
        // ByteArrayOutputStream：内存输出流。
        // 普通 OutputStream 是“往某个地方写数据”，这里的“某个地方”就是内存。
        // storageEngine 会把文件内容写进 outputStream。
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // ReadFileContext 是读取文件时的参数包。
            // 里面放两个东西：
            // 1. realPath：读哪个文件
            // 2. outputStream：读出来以后写到哪里
            ReadFileContext context = new ReadFileContext();
            context.setRealPath(realPath);
            context.setOutputStream(outputStream);
            // 让具体存储引擎去读文件。
            // 如果是本地存储，就从磁盘读。
            // 如果是 OSS，就从对象存储读。
            // 如果是 FastDFS，就从 FastDFS 读。
            // 但不管从哪里读，最后都写进 outputStream。
            storageEngine.realFile(context);
            // ByteArrayOutputStream 里面已经有完整文件内容了。
            // toByteArray() 把内存里的内容转成 byte[]。
            // 后续 Tika 可以用这些字节解析 PDF、Word、TXT 等文件。
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new AiException("Failed to read source file bytes", e, AiErrorCode.FILE_READ_FAILED);
        }
    }
}
