package com.disk.file.core;

import cn.hutool.core.lang.Assert;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.EmptyUtil;
import com.disk.cache.constant.CacheConstant;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.context.ReadFileContext;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.context.StoreFileContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.io.IOException;
import java.util.Objects;

/**
 * 存储引擎抽象父类
 * 实现 StorageEngine 顶层存储接口，抽取所有存储实现共用的缓存逻辑
 * 子类：本地文件存储、对象存储MinIO、阿里云OSS等，全部继承此类复用缓存能力
 */
public abstract class AbstractStorageEngine implements StorageEngine {

    // Spring自动注入缓存管理器（RedisCacheManager/CaffeineCacheManager等缓存底层实现）
    @Autowired
    private CacheManager cacheManager;
//    getCache() 提供统一入口操作 Redis / 本地内存缓存，核心目的
//    就是缓存文件元数据、文档摘要等信息，避免重复查库、读盘、调 AI，实现读取加速。
    protected Cache getCache() {
        // 判断缓存管理器是否为空，代表项目缺少缓存实现依赖/配置
        if (EmptyUtil.isEmpty(cacheManager)) {
            throw new SystemException("具体的缓存实现需要引用到项目中");
        }
        // 根据固定缓存分组名称，取出预定义的公共缓存容器
        return cacheManager.getCache(CacheConstant.CACHE_COMMON_NAME);
    }

    /**
     * 存储物理文件
     * <p>
     * 1、参数校验
     * 2、执行动作
     *
     * @param context
     * @throws IOException
     */
    @Override
    public void store(StoreFileContext context) throws IOException {
        checkStoreFileContext(context);
        doStore(context);
    }

    /**
     * 执行保存物理文件的动作
     * 下沉到具体的子类去实现
     *
     * @param context
     */
    protected abstract void doStore(StoreFileContext context) throws IOException;

    /**
     * 校验上传物理文件的上下文信息
     *
     * @param context
     */
    private void checkStoreFileContext(StoreFileContext context) {
        Assert.notBlank(context.getFilename(), "文件名称不能为空");
        Assert.notNull(context.getTotalSize(), "文件的总大小不能为空");
        Assert.notNull(context.getInputStream(), "文件不能为空");
    }

    /**
     * 删除物理文件
     * <p>
     * 1、参数校验
     * 2、执行动作
     *
     * @param context
     * @throws IOException
     */
    @Override
    public void delete(DeleteFileContext context) throws IOException {
        checkDeleteFileContext(context);
        doDelete(context);
    }

    /**
     * 执行删除物理文件的动作
     * 下沉到子类去实现
     *
     * @param context
     * @throws IOException
     */
    protected abstract void doDelete(DeleteFileContext context) throws IOException;

    /**
     * 校验删除物理文件的上下文信息
     *
     * @param context
     */
    private void checkDeleteFileContext(DeleteFileContext context) {
        Assert.notEmpty(context.getRealFilePathList(), "要删除的文件路径列表不能为空");
    }

    /**
     * 存储引擎层：保存分片的入口
     * 模板方法模式：先校验参数，再由具体子类实现真正的存储逻辑
     */
    @Override
    public void storeChunk(StoreFileChunkContext context) throws IOException {
        // 先校验参数：路径、输入流不能为空
        checkStoreFileChunkContext(context);
        // 真正写文件：由子类实现（本地存储/阿里云OSS/MinIO等不同存储方式）
        doStoreChunk(context);
    }


    /**
     * 执行保存文件分片
     * 下沉到底层去实现
     *
     * @param context
     * @throws IOException
     */
//`doStoreChunk` 具体会执行哪个实现，取决于 `storageEngine` 在运行时实际注入的是哪个 `StorageEngine` Bean。
// 当前项目中，`LocalStorageEngine` 类上同时标注了 `@Component` 和 `@Primary`，
// 因此当 Spring 容器中存在多个 `StorageEngine` 实现类时，会优先选择 `LocalStorageEngine` 注入到 `storageEngine` 字段中。
// 所以在默认情况下，调用 `storageEngine.storeChunk(...)` 最终会走到 `LocalStorageEngine#doStoreChunk(...)`。
    protected abstract void doStoreChunk(StoreFileChunkContext context) throws IOException;

    /**
     * 校验保存文件分片的参数
     *
     * @param context
     */
    private void checkStoreFileChunkContext(StoreFileChunkContext context) {
        Assert.notBlank(context.getFilename(), "文件名称不能为空");
        Assert.notBlank(context.getIdentifier(), "文件唯一标识不能为空");
        Assert.notNull(context.getTotalSize(), "文件大小不能为空");
        Assert.notNull(context.getInputStream(), "文件分片不能为空");
        Assert.notNull(context.getTotalChunks(), "文件分片总数不能为空");
        Assert.notNull(context.getChunkNumber(), "文件分片下标不能为空");
        Assert.notNull(context.getCurrentChunkSize(), "文件分片的大小不能为空");
        Assert.notNull(context.getUserId(), "当前登录用户的ID不能为空");
    }

    /**
     * 存储引擎对外提供的分片合并统一入口
     * @param context 合并分片上下文，包含有序分片路径、MD5、用户、文件名
     * @throws IOException 磁盘读写、文件不存在、权限不足等IO异常
     */
    @Override
    public void mergeFile(MergeFileContext context) throws IOException {
        // 校验合并入参合法性（校验MD5、分片路径列表不能为空、用户ID等必填项）
        checkMergeFileContext(context);
        // 真正执行磁盘层面分片拼接逻辑
        doMergeFile(context);
    }


    /**
     * 执行文件分片的动作
     * 下沉到子类实现
     *
     * @param context
     */
    protected abstract void doMergeFile(MergeFileContext context) throws IOException;

    /**
     * 检查文件分片合并的上线文实体信息
     *
     * @param context
     */
    private void checkMergeFileContext(MergeFileContext context) {
        Assert.notBlank(context.getFilename(), "文件名称不能为空");
        Assert.notBlank(context.getIdentifier(), "文件唯一标识不能为空");
        Assert.notNull(context.getUserId(), "当前登录用户的ID不能为空");
        Assert.notEmpty(context.getRealPathList(), "文件分片列表不能为空");
    }

    /**
     * 读取文件内容写入到输出流中
     * <p>
     * 1、参数校验
     * 2、执行动作
     *
     * @param context
     * @throws IOException
     */
    @Override
    public void realFile(ReadFileContext context) throws IOException {
        checkReadFileContext(context);

        // 真正读取文件。
        // 具体怎么读，交给子类：
        // LocalStorageEngine / OssStorageEngine / FastDFSStorageEngine
        doReadFile(context);
    }

    /**
     * 读取文件内容并写入到输出流中
     * 下沉到子类去实现
     *
     * @param context
     */
    protected abstract void doReadFile(ReadFileContext context) throws IOException;

    /**
     * 文件读取参数校验
     *
     * @param context
     */
    private void checkReadFileContext(ReadFileContext context) {
        Assert.notBlank(context.getRealPath(), "文件真实存储路径不能为空");
        Assert.notNull(context.getOutputStream(), "文件的输出流不能为空");
    }

}
