package com.disk.file.fastdfs;

import com.disk.base.constant.BaseConstant;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.FileUtil;
import com.disk.file.config.FastDFSStorageEngineConfig;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.context.ReadFileContext;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.context.StoreFileContext;
import com.disk.file.core.AbstractStorageEngine;
import com.github.tobato.fastdfs.domain.fdfs.StorePath;
import com.github.tobato.fastdfs.domain.proto.storage.DownloadByteArray;
import com.github.tobato.fastdfs.service.FastFileStorageClient;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * FastDFS 分布式文件存储引擎实现类
 * 继承抽象存储父类 AbstractStorageEngine，统一实现 StorageEngine 顶层存储接口
 * 功能：对接FastDFS分布式文件系统，提供文件上传、删除、读取能力
 * 限制：FastDFS原生不支持自定义分片断点续传，因此分片上传、合并文件方法直接抛异常拦截
 */
@Component
public class FastDFSStorageEngine extends AbstractStorageEngine {
    // FastDFS官方Spring客户端，封装上传/下载/删除文件所有底层网络请求
    @Autowired
    private FastFileStorageClient fastFileStorageClient;
    // FastDFS自定义配置：存储组group名称等业务配置
    @Autowired
    private FastDFSStorageEngineConfig config;

    /**
     * 完整文件上传实现（单文件直传）
     * @param context 文件上传上下文：输入流、文件大小、文件名等信息
     */
    @Override
    protected void doStore(StoreFileContext context) throws IOException {
        // 调用FastDFS客户端上传文件至指定存储分组
        // 参数说明：分组名、文件输入流、文件字节大小、文件后缀
        StorePath storePath = fastFileStorageClient.uploadFile(config.getGroup(), context.getInputStream(), context.getTotalSize(), FileUtil.getFileExtName(context.getFilename()));
        // 将FastDFS返回的完整文件路径存入上下文，后续删/读文件依靠该路径定位资源
        context.setRealPath(storePath.getFullPath());    }
    /**
     * 删除文件实现，支持批量删除多个文件
     * @param context 删除上下文：待删除文件完整路径集合
     */
    @Override
    protected void doDelete(DeleteFileContext context) throws IOException {
        List<String> realFilePathList = context.getRealFilePathList();
        // 非空判断，避免空集合循环调用客户端
        if (CollectionUtils.isNotEmpty(realFilePathList)) {
            // 遍历所有文件路径，逐个调用FastDFS删除接口
            realFilePathList.forEach(fastFileStorageClient::deleteFile);
        }
    }
    /**
     * 分片上传接口实现
     * FastDFS不兼容项目自定义分片上传逻辑，直接抛出业务异常禁止调用
     */
    @Override
    protected void doStoreChunk(StoreFileChunkContext context) throws IOException {
        throw new SystemException("FastDFS不支持分片上传");
    }
    /**
     * 分片合并接口实现
     * 同上，项目分片合并逻辑不适配FastDFS，拦截报错
     */
    @Override
    protected void doMergeFile(MergeFileContext context) throws IOException {
        throw new SystemException("FastDFS不支持分片上传");
    }
    /**
     * 文件读取下载实现
     * 根据FastDFS存储路径，读取文件二进制写入输出流（前端下载/预览）
     * @param context 读取上下文：文件真实路径、输出流
     */
    @Override
    protected void doReadFile(ReadFileContext context) throws IOException {
        String realPath = context.getRealPath();
        // FastDFS完整路径格式：group1/M00/00/01/xxx.pdf
        // 截取分组名称：斜杠前面的group部分
        String group = realPath.substring(BaseConstant.ZERO_INT, realPath.indexOf(BaseConstant.SLASH_STR));
        // 截取除去分组后的剩余文件路径
        String path = realPath.substring(realPath.indexOf(BaseConstant.SLASH_STR) + BaseConstant.ONE_INT);
        // try-with-resources 自动关闭输出流，无需手动close，防止流泄漏
        try (OutputStream outputStream = context.getOutputStream()) {
            // 下载回调：将文件完整转为byte数组加载到内存
            DownloadByteArray downloadByteArray = new DownloadByteArray();
            // 从FastDFS服务拉取文件全部二进制字节
            byte[] bytes = fastFileStorageClient.downloadFile(group, path, downloadByteArray);
            // 把二进制写入输出流，返回给前端/调用方
            outputStream.write(bytes);
            outputStream.flush(); // 强制刷新缓冲区，确保数据全部写出
        }

    }
}
