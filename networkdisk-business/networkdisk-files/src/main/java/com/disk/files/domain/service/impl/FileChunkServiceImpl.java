package com.disk.files.domain.service.impl;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.IdUtil;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.core.StorageEngine;
import com.disk.files.domain.context.FileChunkSaveContext;
import com.disk.files.domain.entity.FileChunkDO;
import com.disk.files.domain.entity.convertor.FileConvertor;
import com.disk.files.domain.service.FileChunkService;
import com.disk.files.infrastructure.config.DiskConfig;
import com.disk.files.infrastructure.enums.MergeFlagEnum;
import com.disk.files.infrastructure.mapper.FileChunkMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * 文件分片服务实现
 *
 * @author weikunkun
 */
@Service
public class FileChunkServiceImpl extends ServiceImpl<FileChunkMapper, FileChunkDO> implements FileChunkService {

    @Autowired
    private FileConvertor fileConvertor;

    @Autowired
    private StorageEngine storageEngine;

    @Autowired
    private DiskConfig config;

    /**
     * 分片保存。
     * 先清理唯一键下无效记录，再判断是否已经上传，最后再执行保存。
     *
     * @param context 分片上下文
     */
    /**
     * 保存单个文件分片的主入口方法
     * 前端每上传一个分片，就调用一次这个方法
     * @param context 分片上传上下文对象——打包了本次所有参数（文件MD5、第几块、总分片数、用户ID、文件流等）
     */
    @Override
    public void saveChunkFile(FileChunkSaveContext context) {
        // 第一步：清理当前文件对应的无效/过期分片记录（比如传了一半放弃、超时过期的分片）
        clearInvalidChunkRecord(context);

        // 第二步：检查当前这个分片，是不是已经上传过了（断点续传：避免重复上传同一个分片）
        if (checkChunkUploaded(context)) {
            // 分片已经存在 → 不用重复存文件，直接判断是否所有分片都传完了
            doJudgeMergeFile(context);
            return; // 直接结束，不执行后面的存文件逻辑
        }

        // 第三步：分片没传过 → 真正保存这个分片
        // 包含两步：1. 把分片的二进制文件存到磁盘/云存储；2. 往数据库插一条分片记录
        doSaveChunkFile(context);

        // 第四步：保存完当前分片后，再次判断：所有分片是不是都传齐了？要不要合并？
        doJudgeMergeFile(context);
    }

    /**
     * 批量物理删除file_chunk分片临时表数据
     * @param chunkRecordIds 分片记录主键ID集合
     */
    @Override
    public void removeChunkRecordsPhysically(List<Long> chunkRecordIds) {
        // 空集合直接返回，不执行删除SQL，避免无效数据库操作
        if (CollectionUtils.isEmpty(chunkRecordIds)) {
            return;
        }
        // 自定义批量物理删除SQL，直接DELETE，不是逻辑软删除
        baseMapper.deleteByIdsPhysical(chunkRecordIds);
    }


    /**
     * 判断：当前文件的所有分片是不是都传完了？要不要触发合并？
     * 逻辑：数一下有效分片总数 = 前端传的总分片数吗？相等就说明传齐了
     */
    private void doJudgeMergeFile(FileChunkSaveContext context) {
        QueryWrapper<FileChunkDO> queryWrapper = Wrappers.query();
        // 同一个文件（同一个MD5标识）
        queryWrapper.eq("identifier", context.getIdentifier());
        // 同一个用户
        queryWrapper.eq("create_user", context.getUserId());
        // 只统计没过期的有效分片
        queryWrapper.gt("expiration_time", new Date());

        // 查询当前文件一共已经传了多少个有效分片
        long count = count(queryWrapper);

        // 如果已传分片数 = 文件总分片数 → 说明全传完了，标记为「可以合并」
        // 前端收到这个标记，就会调用合并接口
        if (count == context.getTotalChunks().longValue()) {
            context.setMergeFlagEnum(MergeFlagEnum.READY);
        }
    }


    /**
     * 保存分片的总调度：先存文件到磁盘，再存记录到数据库
     */
    private void doSaveChunkFile(FileChunkSaveContext context) {
        // 1. 把分片的二进制文件，真正存到磁盘/云存储里
        doStoreFileChunk(context);
        // 2. 往数据库的分片记录表，插一条记录，标记「这个分片存好了、存在哪」
        doSaveRecord(context);
    }


    /**
     * 检查【当前这个分片】是否已经上传过了
     * 核心作用：实现断点续传——同一个分片不用重复传，节省时间
     * @return true=已经传过了；false=还没传
     */
    private boolean checkChunkUploaded(FileChunkSaveContext context) {
        // MyBatis-Plus 条件构造器：拼接查询SQL的条件
        QueryWrapper<FileChunkDO> queryWrapper = Wrappers.query();
        // 条件1：同一个文件（MD5标识相同，就是同一个文件的分片）
        queryWrapper.eq("identifier", context.getIdentifier());
        // 条件2：同一个分片号（比如第3块）
        queryWrapper.eq("chunk_number", context.getChunkNumber());
        // 条件3：同一个用户上传的
        queryWrapper.eq("create_user", context.getUserId());
        // 条件4：分片记录还没过期（过期的分片是无效的，不算）
        queryWrapper.gt("expiration_time", new Date());

        // 统计符合条件的记录数，大于0就说明这个分片已经传过了
        return count(queryWrapper) > 0L;
    }

    /**
     * 清理同一分片唯一键下的失效记录（逻辑删除或已过期）。
     *
     * @param context 分片上下文
     */
    private void clearInvalidChunkRecord(FileChunkSaveContext context) {
        baseMapper.deleteDeletedOrExpiredByUniqueKey(
                context.getIdentifier(),
                context.getChunkNumber(),
                context.getUserId(),
                new Date()
        );
    }

    /**
     * 保存分片记录。
     *
     * @param context 分片上下文
     */
    private void doSaveRecord(FileChunkSaveContext context) {
        FileChunkDO record = new FileChunkDO();
        record.setId(IdUtil.get());
        record.setIdentifier(context.getIdentifier());
        record.setRealPath(context.getRealPath());
        record.setChunkNumber(context.getChunkNumber());
        record.setExpirationTime(DateUtil.offsetDay(new Date(), config.getChunkFileExpirationDays()));
        record.setCreateUser(context.getUserId());
        record.setUpdateUser(context.getUserId());
        if (!save(record)) {
            throw new SystemException("文件分片上传失败");
        }
    }

     /*** 把分片文件，真正写入磁盘/云存储
     * 调用存储引擎来写文件，上层不用关心存在本地还是云端
     */
    private void doStoreFileChunk(FileChunkSaveContext context) {
        try {
            // 类型转换：把分片上传上下文，转成存储引擎需要的上下文对象
            StoreFileChunkContext storeFileChunkContext = fileConvertor.fileChunkSaveContext2StoreFileChunkContext(context);
            // 把分片的文件输入流（前端传过来的二进制数据）放进上下文
//            .getInputStream()MultipartFile 提供的方法，生成文件输入流 InputStream。
//            作用：以流式读取分片的二进制数据，不会一次性把整个分片加载进内存，适合大分片，避免 OOM 内存溢出。
//            storeFileChunkContext.setInputStream(输入流)
//            把上面拿到的分片二进制输入流，赋值给存储上下文对象的流字段。
//            后面底层保存分片的方法会读取这个 inputStream，循环读字节写入服务器本地临时文件。
            storeFileChunkContext.setInputStream(context.getFile().getInputStream());

            // 调用存储引擎，把分片写入磁盘/云存储
            // 和之前下载的 storageEngine 是同一个，支持本地、OSS等多种存储方式
            storageEngine.storeChunk(storeFileChunkContext);

            // 存储完成后，把分片的真实存储路径，放回上下文，后面存数据库要用
            context.setRealPath(storeFileChunkContext.getRealPath());

        } catch (IOException e) {
            // 文件读写异常，打印日志并抛出业务异常
            e.printStackTrace();
            throw new SystemException("文件分片上传失败");
        }
    }

}
