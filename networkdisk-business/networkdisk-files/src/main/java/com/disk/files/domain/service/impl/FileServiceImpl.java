package com.disk.files.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.base.exception.BizException;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.IdUtil;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.core.StorageEngine;
import com.disk.files.domain.context.FileChunkMergeAndSaveContext;
import com.disk.files.domain.context.ListFileContext;
import com.disk.files.domain.context.SaveFileContext;
import com.disk.files.domain.context.StoreFileContext;
import com.disk.files.domain.entity.FileChunkDO;
import com.disk.files.domain.entity.FileDO;
import com.disk.files.domain.service.FileChunkService;
import com.disk.files.domain.service.FileService;
import com.disk.files.infrastructure.mapper.FileMapper;
import com.disk.base.utils.FileUtil;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, FileDO> implements FileService {


    @Autowired
    private StorageEngine storageEngine;


    @Autowired
    private FileChunkService fileChunkService;

    /**
     * 根据条件查询底层物理文件记录 FileDO
     * 主要两个使用场景：1. 前端秒传secUpload查询MD5文件  2. 分片预校验查询已上传分片
     * @Override 实现上层接口定义的查询文件列表方法
     * @param context 查询上下文，封装查询条件：userId、identifier(文件MD5)
     * @return 匹配条件的物理文件集合，一条FileDO代表服务器一份真实二进制文件
     */
    @Override
    public List<FileDO> getFileList(ListFileContext context) {
        // 1. 从上下文取出登录用户ID
        Long userId = context.getUserId();
        // 2. 取出文件MD5唯一标识
        String identifier = context.getIdentifier();

        // Mybatis-Plus 条件构造器，用于拼接SQL查询条件
        LambdaQueryWrapper<FileDO> queryWrapper = new LambdaQueryWrapper<>();

//        MyBatis-Plus 连续调用多个 eq，多个条件之间默认用 AND 连接，也就是同时满足两个条件的数据才会被查出来。
//          查询该用户上传过、且 MD5 完全一致的物理文件（秒传核心查询）
        // 条件1：userId不为空时，拼接SQL：create_user = #{userId}
        queryWrapper.eq(Objects.nonNull(userId), FileDO::getCreateUser, userId);
        // Objects.nonNull(userId) 为前置判断：userId为空则不加该查询条件
        // 条件2：identifier不为空/不为空白，拼接SQL：identifier = #{md5}
        queryWrapper.eq(StringUtils.isNotBlank(identifier), FileDO::getIdentifier, identifier);

        // 执行查询，根据上面拼接的条件从file物理文件表查出数据并返回
        return list(queryWrapper);
    }


    @Override
    public void saveFile(SaveFileContext context) {
        storeMultipartFile(context);
        FileDO record = doSaveFile(context.getFilename(),
                context.getRealPath(),
                context.getTotalSize(),
                context.getIdentifier(),
                context.getUserId());
        context.setFileRecord(record);
    }

    /**
     * 合并物理文件并保存物理文件记录
     * <p>
     * 1、委托文件存储引擎合并文件分片
     * 2、保存物理文件记录
     *
     * @param context
     */
    @Override
    public void mergeFileChunkAndSaveFile(FileChunkMergeAndSaveContext context) {
        // 第一步：根据MD5+用户ID查询所有有效分片，合并磁盘文件
        // Keep source chunks until the completed-file metadata is persisted.
        // A database failure can then be retried without uploading chunks again.
        doMergeFileChunk(context);
        // 第二步：将合并后的完整文件信息存入物理文件表file，返回FileDO记录
        FileDO record = doSaveFile(context.getFilename(), context.getRealPath(), context.getTotalSize(), context.getIdentifier(), context.getUserId());
        // 把新建的物理文件记录塞回上下文，上层业务拿record.id做用户目录映射
        context.setRecord(record);
    }

    /**
     * 1. 查询当前用户该文件所有有效分片
     * 2. 按分片序号排序，拿到所有分片磁盘路径
     * 3. 调用存储引擎按顺序拼接成分整文件
     * 4. 拼接成功后物理删除所有临时分片文件 + 删除数据库分片记录
     * @param context 分片合并上下文
     */
    private List<FileChunkDO> doMergeFileChunk(FileChunkMergeAndSaveContext context) {
        // 构建分片查询条件
        QueryWrapper<FileChunkDO> queryWrapper = Wrappers.query();
        // 匹配文件唯一MD5标识
        queryWrapper.eq("identifier", context.getIdentifier());
        // 匹配当前登录用户，隔离他人分片
        queryWrapper.eq("create_user", context.getUserId());
        // 只查未过期分片，过期分片失效不参与合并
        queryWrapper.ge("expiration_time", new Date());
        // 查询该文件全部分片数据库记录
        List<FileChunkDO> chunkRecoredList = fileChunkService.list(queryWrapper);

        // 没有分片直接抛异常，无法合并
        if (CollectionUtils.isEmpty(chunkRecoredList)) {
            throw new SystemException("该文件未找到分片记录");
        }

        // 流式处理：按分片编号升序排序 → 提取每个分片的磁盘真实路径
        List<String> realPathList = chunkRecoredList.stream()
                .sorted(Comparator.comparing(FileChunkDO::getChunkNumber)) // 分片从小到大排序，顺序不能乱
                .map(FileChunkDO::getRealPath) // 取出每个分片存在服务器的文件路径
                .collect(Collectors.toList());

        try {
            // 组装存储引擎合并文件入参
            MergeFileContext mergeFileContext = new MergeFileContext();
            mergeFileContext.setFilename(context.getFilename());
            mergeFileContext.setIdentifier(context.getIdentifier());
            mergeFileContext.setUserId(context.getUserId());
            mergeFileContext.setRealPathList(realPathList); // 有序分片路径集合

            // 调用底层存储引擎，按顺序读取所有分片，拼接生成完整大文件
            storageEngine.mergeFile(mergeFileContext);
            // 将合并完成后的完整文件磁盘路径回填上下文，后续入库使用
            context.setRealPath(mergeFileContext.getRealPath());
        } catch (IOException e) {
            e.printStackTrace();
            // IO异常：磁盘读写失败、分片缺失、权限不足等，抛出业务异常给前端
            throw new SystemException("文件分片合并失败");
        }

        // 收集所有分片数据库主键ID
        List<Long> fileChunkRecordIdList = chunkRecoredList.stream()
                .map(FileChunkDO::getId)
                .collect(Collectors.toList());
        // 物理删除临时分片文件 + 删除file_chunk表分片记录，释放磁盘与数据库空间
        return chunkRecoredList;
    }

    /**
     * 将合并完成的完整文件信息插入FileDO物理文件表
     * 秒传功能依赖本表，相同MD5直接复用记录，无需重复上传
     * @param filename 原始文件名
     * @param realPath 合并后完整文件磁盘路径
     * @param totalSize 文件总字节大小
     * @param identifier 文件全局MD5标识
     * @param userId 上传用户ID
     * @return 新增的物理文件数据库记录
     */
    private void cleanupMergedChunks(List<FileChunkDO> chunkRecordList) {
        List<String> chunkPaths = chunkRecordList.stream()
                .map(FileChunkDO::getRealPath)
                .collect(Collectors.toList());
        List<Long> chunkRecordIds = chunkRecordList.stream()
                .map(FileChunkDO::getId)
                .collect(Collectors.toList());
        try {
            storageEngine.cleanupTemporaryChunks(chunkPaths);
        } catch (IOException exception) {
            // The completed file is already durable. Leave records for expiry
            // cleanup rather than turning a cleanup failure into user failure.
            return;
        }
        fileChunkService.removeChunkRecordsPhysically(chunkRecordIds);
    }

    @Override
    public void cleanupMergedChunks(String identifier, Long userId) {
        QueryWrapper<FileChunkDO> queryWrapper = Wrappers.query();
        queryWrapper.eq("identifier", identifier);
        queryWrapper.eq("create_user", userId);
        queryWrapper.ge("expiration_time", new Date());
        cleanupMergedChunks(fileChunkService.list(queryWrapper));
    }

    @Override
    public void compensateMergedFile(String realPath) {
        try {
            DeleteFileContext deleteFileContext = new DeleteFileContext();
            deleteFileContext.setRealFilePathList(Lists.newArrayList(realPath));
            storageEngine.delete(deleteFileContext);
        } catch (IOException exception) {
            // The caller rethrows its original database exception. A scheduled
            // orphan scanner is still required for a failed remote deletion.
        }
    }

    private FileDO doSaveFile(String filename, String realPath, Long totalSize, String identifier, Long userId) {
        // 封装FileDO实体，填充文件路径、大小、MD5、上传人等信息
        FileDO record = assembleFileDO(filename, realPath, totalSize, identifier, userId);
        // 插入物理文件表
        if (save(record)) {
            return record;
        }
        {
            // 入库失败容错分支：磁盘文件已生成但数据库插入失败，需要删除刚合并的完整文件，避免垃圾文件残留
            try {
                DeleteFileContext deleteFileContext = new DeleteFileContext();
                deleteFileContext.setRealFilePathList(Lists.newArrayList(realPath));
                storageEngine.delete(deleteFileContext);
                // TODO 调用存储引擎删除磁盘上合并好的完整文件
            } catch (Exception e) {
                e.printStackTrace();
                // TODO 异常消息投递/日志告警，记录垃圾文件便于清理
            }
        }
        // 返回物理文件记录，外层拿record.id创建user_file用户目录映射
        throw new SystemException("completed file metadata persistence failed");
    }


    /**
     * 拼装文件实体对象
     *
     * @param filename
     * @param realPath
     * @param totalSize
     * @param identifier
     * @param userId
     * @return
     */
    private FileDO assembleFileDO(String filename, String realPath, Long totalSize, String identifier, Long userId) {
        FileDO record = new FileDO();
        record.setId(IdUtil.get());
        record.setFilename(filename);
        record.setRealPath(realPath);
        record.setFileSize(String.valueOf(totalSize));
        record.setFileSizeDesc(FileUtil.byteCountToDisplaySize(totalSize));
        record.setFileSuffix(FileUtil.getFileSuffix(filename));
        record.setFilePreviewContentType(FileUtil.getContentType(realPath));
        record.setIdentifier(identifier);
        record.setCreateUser(userId);
        return record;
    }

    /**
     * 上传单文件
     * 该方法委托文件存储引擎实现
     *
     * @param context
     */
    private void storeMultipartFile(SaveFileContext context) {
        try {
            StoreFileContext storeFileContext = new StoreFileContext();
            storeFileContext.setInputStream(context.getFile().getInputStream());
            storeFileContext.setFilename(context.getFilename());
            storeFileContext.setTotalSize(context.getTotalSize());
            // TODO 通过文件引擎存储文件信息
            context.setRealPath(storeFileContext.getRealPath());
        } catch (IOException e) {
            e.printStackTrace();
            throw new BizException("文件上传失败");
        }
    }
}
