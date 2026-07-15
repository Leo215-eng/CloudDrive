package com.disk.file.local;

import com.disk.base.utils.FileUtil;
import com.disk.file.config.LocalStorageEngineConfig;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.context.ReadFileContext;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.context.StoreFileContext;
import com.disk.file.core.AbstractStorageEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Primary
@Component
public class LocalStorageEngine extends AbstractStorageEngine {


    @Autowired
    private LocalStorageEngineConfig config;

    /**
     * 执行保存物理文件的动作
     * 下沉到具体的子类去实现
     *
     * @param context
     */
    @Override
    protected void doStore(StoreFileContext context) throws IOException {
        String basePath = config.getRootFilePath();
        String realFilePath = FileUtil.generateStoreFileRealPath(basePath, context.getFilename());
        FileUtil.writeStreamToFile(context.getInputStream(), new File(realFilePath), context.getTotalSize());
        context.setRealPath(realFilePath);
    }

    /**
     * 执行删除物理文件的动作
     * 下沉到子类去实现
     *
     * @param context
     * @throws IOException
     */
    @Override
    protected void doDelete(DeleteFileContext context) throws IOException {
        FileUtil.deleteFiles(context.getRealFilePathList());
    }

    /**
     * 执行保存文件分片
     * 下沉到底层去实现
     *
     * @param context
     * @throws IOException
     */
    /**
     * 本地存储引擎：真正把分片文件写入服务器硬盘
     * 这是父类抽象方法 doStoreChunk 的具体实现（模板方法模式）
     * @param context 存储分片的上下文，包含分片输入流、MD5标识、分片号、总大小等信息
     */
    @Override
    protected void doStoreChunk(StoreFileChunkContext context) throws IOException {
        // 1. 从配置里读取：分片文件的根目录（所有分片都存在这个总文件夹下面）
        String basePath = config.getRootFileChunkPath();

        // 2. 生成当前分片的完整存储路径
        // 规则：根目录 / 文件MD5值 / 分片编号
        // 好处：同一个文件的所有分片都放在同一个MD5文件夹里，后续合并文件时好找
        String realFilePath = FileUtil.generateStoreFileChunkRealPath(
                basePath,
                context.getIdentifier(),  // 文件MD5唯一标识
                context.getChunkNumber()  // 当前是第几号分片
        );

        // 3. 核心动作：把前端传过来的分片输入流（二进制数据），写入到硬盘的目标文件里
        FileUtil.writeStreamToFile(
                context.getInputStream(),  // 分片的二进制输入流（从前端请求里拿到的）
                new File(realFilePath),    // 要写入的目标文件（硬盘上的位置）
                context.getTotalSize()     // 分片总大小（工具内部用来控制读写缓冲区）
        );

        // 4. 把生成好的真实存储路径放回上下文，后面存数据库分片记录要用
        context.setRealPath(realFilePath);
    }


    /**
     * 底层磁盘IO：按顺序拼接所有分片，生成完整文件
     * @param context 合并上下文，有序分片磁盘路径列表
     * @throws IOException 文件读写、文件不存在、磁盘权限不足等IO异常
     */
    @Override
    protected void doMergeFile(MergeFileContext context) throws IOException {
        // 1. 读取配置：文件存储根目录（服务器统一文件存放根路径）
        String basePath = config.getRootFilePath();
        // 2. 根据根目录+原始文件名，生成合并后完整文件的最终磁盘绝对路径
        String realFilePath = FileUtil.generateStoreFileRealPath(basePath, context.getFilename());
        // 3. 在磁盘创建空白完整文件，准备后续追加写入分片内容
        FileUtil.createFile(new File(realFilePath));

        // 4. 获取排好序的所有分片磁盘路径（已按chunkNumber从小到大排序，顺序不能乱）
        List<String> chunkPaths = context.getRealPathList();
        // 5. 循环每一块分片，依次把分片二进制追加写入完整文件末尾
        for (String chunkPath : chunkPaths) {
            // 把当前分片文件内容，追加到目标完整文件尾部
            FileUtil.appendWrite(Paths.get(realFilePath), new File(chunkPath).toPath());
        }

        // 6. 全部分片拼接完成，批量删除磁盘上所有临时分片文件，释放磁盘空间
        FileUtil.deleteFiles(chunkPaths);
        // 7. 将合并后完整文件的磁盘路径回填上下文，供上层入库FileDO表使用
        context.setRealPath(realFilePath);
    }


    /**
     * 读取文件内容并写入到输出流中
     * 下沉到子类去实现
     *
     * @param context
     */
    @Override
    protected void doReadFile(ReadFileContext context) throws IOException {
        // 根据真实路径创建 File 对象。
        // File 只是代表一个路径，不等于文件内容已经读进来了。
        File file = new File(context.getRealPath());

        // FileInputStream：文件输入流。
        // InputStream 是“从某个地方读数据”。
        // 这里的来源是磁盘文件。
        // 把 inputStream 里的文件字节，写到 context.getOutputStream()。
        // 当前 AI 场景里，这个 outputStream 就是 ByteArrayOutputStream。
        FileUtil.writeFileToOutputStream(new FileInputStream(file), context.getOutputStream(), file.length());
    }
}
