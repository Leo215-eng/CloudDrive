package com.disk.files.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.base.constant.BaseConstant;
import com.disk.base.enums.DeleteEnum;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.EmptyUtil;
import com.disk.base.utils.HttpUtil;
import com.disk.base.utils.IdUtil;
import com.disk.file.context.ReadFileContext;
import com.disk.file.core.StorageEngine;
import com.disk.files.domain.context.CopyFileContext;
import com.disk.files.domain.context.CreateFolderContext;
import com.disk.files.domain.context.DeleteUserFileContext;
import com.disk.files.domain.context.FileChunkMergeAndSaveContext;
import com.disk.files.domain.context.FileChunkMergeContext;
import com.disk.files.domain.context.FileChunkSaveContext;
import com.disk.files.domain.context.FileChunkUploadContext;
import com.disk.files.domain.context.FileDownloadContext;
import com.disk.files.domain.context.FilePreviewContext;
import com.disk.files.domain.context.FileSearchContext;
import com.disk.files.domain.context.ListFileContext;
import com.disk.files.domain.context.QueryBreadcrumbsContext;
import com.disk.files.domain.context.QueryFolderTreeContext;
import com.disk.files.domain.context.QueryUploadedChunksContext;
import com.disk.files.domain.context.SaveFileContext;
import com.disk.files.domain.context.TransferFileContext;
import com.disk.files.domain.context.UploadFileContext;
import com.disk.files.domain.context.QueryFileContext;
import com.disk.files.domain.context.SecUploadFileContext;
import com.disk.files.domain.context.UpdateFilenameContext;
import com.disk.files.domain.entity.FileDO;
import com.disk.files.domain.entity.UserFileDO;
import com.disk.files.domain.entity.convertor.FileConvertor;
import com.disk.files.infrastructure.es.entity.UserFileESEntity;
import com.disk.files.domain.response.BreadcrumbVO;
import com.disk.files.domain.response.FileSearchVO;
import com.disk.files.domain.response.FolderTreeNodeVO;
import com.disk.files.domain.response.HomeOverviewVO;
import com.disk.files.domain.response.UserFileVO;
import com.disk.files.domain.service.FileChunkService;
import com.disk.files.domain.service.FileService;
import com.disk.files.domain.service.UserFileService;
import com.disk.files.exception.FileException;
import com.disk.files.infrastructure.ai.DocumentAiInitializer;
import com.disk.files.infrastructure.constant.FileConstant;
import com.disk.files.infrastructure.enums.FileTypeEnum;
import com.disk.files.infrastructure.enums.FolderFlagEnum;
import com.disk.files.infrastructure.es.mapper.UserFileESMapper;
import com.disk.files.infrastructure.mapper.UserFileMapper;
import com.disk.base.utils.FileUtil;
import com.disk.lock.DistributeLock;
import com.google.common.collect.Lists;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.disk.files.exception.FilesErrorCode.FILE_DELETE_ERROR;
import static com.disk.files.exception.FilesErrorCode.FILE_NEW_NAME_EQUALS;
import static com.disk.files.exception.FilesErrorCode.FILE_NEW_NAME_EXIST;
import static com.disk.files.exception.FilesErrorCode.FILE_NOT_CUR_USER;
import static com.disk.files.exception.FilesErrorCode.FILE_NOT_EXIT;
import static com.disk.files.exception.FilesErrorCode.FILE_NO_AUTH;
import static com.disk.files.exception.FilesErrorCode.FILE_RENAME_ERROR;
import static com.disk.files.exception.FilesErrorCode.FOLDER_INVALID;
import static com.disk.files.exception.FilesErrorCode.FOLDER_NOT_DOWNLOAD;
import static com.disk.files.exception.FilesErrorCode.TARGET_FOLDER_TYPE_ERROR;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Service
public class UserFileServiceImpl extends ServiceImpl<UserFileMapper, UserFileDO> implements UserFileService {

    private static final int HOME_RECENT_FILE_LIMIT = 5;

    private static final List<Integer> HOME_DOCUMENT_FILE_TYPES = List.of(
            FileTypeEnum.EXCEL_FILE.getCode(),
            FileTypeEnum.WORD_FILE.getCode(),
            FileTypeEnum.PDF_FILE.getCode(),
            FileTypeEnum.TXT_FILE.getCode(),
            FileTypeEnum.POWER_POINT_FILE.getCode(),
            FileTypeEnum.SOURCE_CODE_FILE.getCode(),
            FileTypeEnum.CSV_FILE.getCode()
    );

    @Autowired
    private FileConvertor fileConvertor;

    @Autowired
    private FileService fileService;

    @Autowired
    private FileChunkService fileChunkService;

    @Autowired
    private StorageEngine storageEngine;
    @Autowired
    private UserFileMapper userFileMapper;

    @Autowired
    private UserFileESMapper fileEsMapper;

    @Autowired
    private DocumentAiInitializer documentAiInitializer;


    @Override
    public List<UserFileDO> getUserFileList(QueryFileContext context) {
        return baseMapper.listUserFiles(context);
    }

    @Override
    public Long createFolder(CreateFolderContext context) {
        // 1. 上下文参数 → 组装数据库实体 UserFileDO
        UserFileDO entity = assembleUserFolder(context);
        // 2. MyBatis-Plus 自带 save() 方法，执行insert插入数据库
        if (!save((entity))) {
            // 插入失败（数据库报错/影响行数0），抛出系统自定义异常
            throw new SystemException("保存文件信息失败");
        }
        // 插入成功，返回自生成的文件夹唯一ID，给上层Facade封装进响应data
//        插入数据库后 MyBatis‑Plus 会把主键回填到 entity 的 id 属性，controller 拿到 id 后再进行 ID 加密，返回前端。
//        两个类都加了 Lombok 的注解：@Getter、@Setter，Lombok 在编译阶段自动生成 get/set 方法：
        return entity.getId();
    }


    /**
     * 封装组装文件夹数据库实体
     * 把CreateFolderContext上下文的数据映射为UserFileDO，补齐表中必填默认字段
     * @param context 业务上下文
     * @return 拼装完成的数据库实体
     */
    private UserFileDO assembleUserFolder(CreateFolderContext context) {
        // 新建数据库实体对象，对应user_file数据表的一条记录
        UserFileDO entity = new UserFileDO();

        entity.setId(IdUtil.get()); // 生成雪花ID/自定义ID，作为这条文件夹记录的主键
        entity.setUserId(context.getUserId()); // 当前操作人用户ID，归属人ID
        entity.setParentId(context.getParentId()); // 父文件夹ID，标记该文件夹挂载在哪个目录下
        entity.setRealFileId(null); // realFileId为实际文件存储记录ID；文件夹不存在实际文件，赋值null
        entity.setFilename(context.getFolderName()); // 文件夹名称，对应前端传入的folderName
        entity.setFolderFlag(FolderFlagEnum.YES.getCode()); // 标记该条记录是文件夹（区分普通文件）
        entity.setFileSizeDesc(null); // 文件夹无文件大小，置空，只有文件才会记录大小
        entity.setFileType(null); // 文件类型只针对文档、图片等文件，文件夹不需要文件类型
        entity.setDeleted(DeleteEnum.NO.getCode()); // 逻辑删除标识：0‑未删除、1‑已删除，默认不删除
        entity.setCreateUser(context.getUserId()); // 创建人id
        entity.setUpdateUser(context.getUserId()); // 修改人id，新建时创建人和修改人为同一人

        // 核心方法：处理同级目录下文件夹重名问题（自动重命名或者直接抛异常）
        handleDuplicateFilename(entity);
        return entity;
    }

    /**
     * 执行文件/文件夹重命名核心逻辑
     * @param context 重命名业务上下文（文件ID、新名称、登录用户ID等）
     */
    @Override
    public void updateFilename(UpdateFilenameContext context) {
        // 第一步：前置全部合法性校验，不满足直接抛异常中断流程
        checkUpdateFilenameCondition(context);

        // 从上下文取出校验完成后的原始数据库DO实体
        UserFileDO entity = context.getEntity();
        // 替换文件名为用户输入的新名称
        entity.setFilename(context.getNewFilename());

        // MyBatis-Plus 根据主键ID更新单条记录
        if (!updateById(entity)) {
            // 更新影响行数为0，抛出重命名失败异常
            throw new FileException(FILE_RENAME_ERROR);
        }
    }


    /**
     * 批量删除文件核心业务实现
     * @param context 删除操作上下文：包含登录用户ID、解密后的明文文件ID集合
     */
    @Override
    public void deleteFile(DeleteUserFileContext context) {
        // 从上下文取出待删除的明文文件ID列表（已解密、去重）
        List<Long> fileIdList = context.getFileIdList();

        // MyBatis-Plus 批量根据主键ID查询多条记录
        // 查询出所有待删除文件完整数据库实体 UserFileDO
        List<UserFileDO> userFiles = listByIds(fileIdList);

        // 流式处理：提取所有文件的归属用户ID，放入Set自动去重
        Set<Long> userIdSet = userFiles.stream()
                .map(UserFileDO::getUserId) // 遍历每条文件，取出归属人userId
                .collect(Collectors.toSet()); // Set特性：自动剔除重复userId

        // 校验1：选中的所有文件必须全部属于同一个用户
        // 如果Set长度不等于1，说明勾选的文件分属多个不同用户，禁止删除
        if (userIdSet.size() != 1) {
            throw new FileException(FILE_DELETE_ERROR);
        }

        // 获取唯一的文件归属用户ID（Set只有一个元素）
        Long creatUser = userIdSet.stream().findFirst().get();

        // 校验2：校验当前登录人 == 文件归属人，防止越权删除别人文件
        if (!Objects.equals(creatUser, context.getUserId())) {
            throw new FileException(FILE_DELETE_ERROR);
        }

        // 校验全部通过，执行真正的批量删除逻辑
        doDeleteFile(context);

        // TODO 后续可扩展：发布文件删除事件，用于日志、消息通知、对象存储清理等
    }


    /**
     * 秒传核心业务方法
     * @Override 重写上层接口定义的secUpload方法
     * @param context 秒传请求上下文对象，封装前端传参：文件名、md5(identifier)、目标文件夹parentId、当前登录userId
     * @return boolean true=秒传成功，false=无匹配文件，需要前端走分片上传
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean secUpload(SecUploadFileContext context) {
        // 1. 校验登录用户ID不能为空，无用户直接抛业务异常
        if (Objects.isNull(context.getUserId())) {
            throw new SystemException("秒传失败：用户ID不能为空");
        }

        // 2. 根据 用户ID + 文件MD5标识 查询物理文件表FileDO
        // 查询：当前系统内有没有已经上传过的一模一样的文件
        List<FileDO> fileList = getFilesByUserIdAndIdentifier(context.getUserId(), context.getIdentifier());

        // 3. 查询结果为空 → 不存在相同文件，返回false，前端需要正常分片上传
        if (EmptyUtil.isEmpty(fileList)) {
            return false;
        }

        // 4. 存在相同MD5文件，取第一条物理文件记录
//        正常设计里同一个 MD5 只会对应一条物理文件记录，
//        即便因为历史原因出现多条，它们指向的文件二进制也完全相同，不影响秒传结果，因此直接取下标 0 的第一条即可，不需要额外排序筛选。
        FileDO record = fileList.get(BaseConstant.ZERO_INT);

        // 5. 新增用户网盘目录记录 user_file（核心秒传逻辑，不用传文件）
        // 不用接收二进制分片，直接复制已有物理文件记录到目标文件夹
//        不是创建数据表，是往已经存在的 user_file（用户文件目录表）里插入一行新数据。
//        它的作用是：给当前登录用户的目标文件夹，挂上一份已经存在的物理文件引用 ——
//        用户在网盘里能看到这个文件，但服务器不需要重复存储文件内容，这就是 “秒传” 的核心：只写数据库，不传文件二进制。
        saveUserFile(
                context.getParentId(),                          // 文件上传到哪个文件夹ID
                context.getFilename(),                          // 前端上传的文件原名
                FolderFlagEnum.NO,                               // 是否文件夹：NO=普通文件
                FileTypeEnum.getFileTypeCode(FileUtil.getFileSuffix(context.getFilename())), // 根据后缀获取文件类型编码
                record.getId(),                                  // 已有物理文件主键ID（关联底层文件）
                context.getUserId(),                             // 当前登录用户ID
                record.getFileSizeDesc()                         // 文件大小展示文本
        );

        // 6. 秒传完成，返回true，前端收到后直接取消上传、刷新文件列表
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void upload(UploadFileContext context) {
        saveFile(context);
        saveUserFile(context.getParentId(),
                context.getFilename(),
                FolderFlagEnum.NO,
                FileTypeEnum.getFileTypeCode(FileUtil.getFileSuffix(context.getFilename())),
                context.getFileRecord().getId(),
                context.getUserId(),
                context.getFileRecord().getFileSizeDesc());
    }


    /**
     * 接收前端单块分片上传，保存临时分片记录
     * @DistributeLock 分布式锁注解，防止并发重复上传同一块分片
     * @param context 分片上传上下文：用户ID、文件MD5、分片序号、分片二进制等
     * @return 数字标记mergeFlag，告诉前端是否所有分片已上传完成
     */
    // 分布式锁：场景标识FILE_CHUNK_UPLOAD
    // 锁key = 当前用户ID + "-" + 文件MD5，同一个用户同一个文件互斥
    @DistributeLock(scene = "FILE_CHUNK_UPLOAD", keyExpression = "#context.userId + '-' + #context.identifier")
    @Override
    public Integer chunkUpload(FileChunkUploadContext context) {
        // 转换器：把接口入参上下文转为分片存储专用上下文
        FileChunkSaveContext fileChunkSaveContext = fileConvertor.fileChunkUploadContextToFileChunkSaveContext(context);
        // 业务层：保存当前这块分片（存文件二进制 + 插入file_chunk分片表记录）
        fileChunkService.saveChunkFile(fileChunkSaveContext);
        // 返回是否需要合并分片的标识给前端
        return fileChunkSaveContext.getMergeFlagEnum().getCode();
    }


    /**
     * 查询用户已上传的分片列表
     * <p>
     * 1、查询已上传的分片列表
     * 2、封装返回实体
     *
     * @param context
     * @return
     */
    /**
     * 查询当前文件已上传完成的分片编号列表（断点续传核心方法）
     * @Override 实现上层业务接口
     * @param context 查询上下文，携带文件MD5标识identifier、当前登录用户userId
     * @return 已上传成功的分片编号集合，前端上传库用来跳过已传分片
     */
    @Override
    public List<Integer> getUploadedChunkList(QueryUploadedChunksContext context) {
        // 1. 创建通用查询条件构造器
        QueryWrapper queryWrapper = Wrappers.query();
        // 只查询数据库chunk_number分片编号字段，减少数据传输
        queryWrapper.select("chunk_number");
        // 条件1：匹配文件MD5唯一标识，区分不同文件分片
        queryWrapper.eq("identifier", context.getIdentifier());
        // 条件2：匹配当前登录用户，只查自己上传的分片，隔离多用户数据
        queryWrapper.eq("create_user", context.getUserId());
        // 条件3：分片有效期大于当前时间，过滤已过期清理的临时分片
        queryWrapper.gt("expiration_time", new Date());

        // 执行查询，只取出chunk_number字段并强转为Integer分片编号
        List<Integer> uploadedChunks = fileChunkService.listObjs(queryWrapper, value -> (Integer) value);
        return uploadedChunks;
    }


    @Transactional(rollbackFor = Exception.class)
    @DistributeLock(scene = "FILE_CHUNK_MERGE", keyExpression = "#context.userId + '-' + #context.identifier", expireTime = 60000)
    @Override
    public void mergeFile(FileChunkMergeContext context) {
        mergeFileChunkAndSaveFile(context);
        try {
            saveUserFile(context.getParentId(),
                    context.getFilename(),
                    FolderFlagEnum.NO,
                    FileTypeEnum.getFileTypeCode(FileUtil.getFileSuffix(context.getFilename())),
                    context.getRecord().getId(),
                    context.getUserId(),
                    context.getRecord().getFileSizeDesc());
            fileService.cleanupMergedChunks(context.getIdentifier(), context.getUserId());
        } catch (RuntimeException exception) {
            if (context.getRecord() != null) {
                fileService.compensateMergedFile(context.getRecord().getRealPath());
            }
            throw exception;
        }
    }

    /**
     * 文件下载
     * <p>
     * 1、参数校验：校验文件是否存在，文件是否属于该用户
     * 2、校验该文件是不是一个文件夹
     * 3、执行下载的动作
     *
     * @param context
     */
    @Override
    public void download(FileDownloadContext context) {
        // 1. 根据解密后的文件主键ID，查询数据库该文件完整记录
        UserFileDO record = getById(context.getFileId());
        // 2. 权限校验：校验当前登录用户是否有权限操作该文件（文件是否存在、是否属于本人、是否已删除）
        checkOperatePermission(record, context.getUserId());
        // 3. 判断当前记录是否为文件夹，文件夹不支持单独下载，直接抛出异常
        if (isFolder(record)) {
            throw new FileException(FOLDER_NOT_DOWNLOAD);
        }
        // 4. 执行真实下载逻辑：读取OSS文件流、设置下载响应头、流式输出二进制到前端浏览器
        doDownload(record, context.getResponse());
    }

    /**
     * 文件预览
     * 1、参数校验：校验文件是否存在，文件是否属于该用户
     * 2、校验该文件是不是一个文件夹
     * 3、执行预览的动作
     *
     * @param context
     */
    @Override
    public void preview(FilePreviewContext context) {
//        getById()：MyBatis-Plus 提供的内置方法，根据主键 ID（文件 ID）直接从 user_file 表查询单条完整记录，返回数据库实体 UserFileDO
        UserFileDO record = getById(context.getFileId());
//        作用是校验当前登录用户是否有权限操作这个文件。
        checkOperatePermission(record, context.getUserId());
        if (isFolder(record)) {
            throw new SystemException("文件夹暂不支持下载");
        }
        doPreview(record, context.getResponse());
    }

    /**
     * 查询用户的文件夹树
     * <p>
     * 1、查询出该用户的所有文件夹列表
     * 2、在内存中拼装文件夹树
     *
     * @param context
     * @return
     */
    @Override
    public List<FolderTreeNodeVO> getFolderTree(QueryFolderTreeContext context) {
        List<UserFileDO> folderRecords = queryFolderRecords(context.getUserId());
        return assembleFolderTreeNodeVOList(folderRecords);
    }

    /**
     * 文件转移
     * <p>
     * 1、权限校验
     * 2、执行工作
     *
     * @param context
     */
    @Override
    public void transfer(TransferFileContext context) {
        // 第一步：前置校验（权限校验、不能移动到自身子目录、目标文件夹是否存在等）
        checkTransferCondition(context);
        // 根据文件ID批量查询本次要移动的所有文件/文件夹记录（上下文内部封装查询逻辑）
        List<UserFileDO> prepareRecords = context.getPrepareRecords();

        // 遍历每一条待移动文件记录，修改归属信息
        prepareRecords.forEach(record -> {
            // 修改父文件夹ID：核心逻辑，移动就是改parentId
            record.setParentId(context.getTargetParentId());
            // 归属用户ID（网盘多用户隔离）
            record.setUserId(context.getUserId());
            // 创建人、更新人改为当前操作者
            record.setCreateUser(context.getUserId());
            record.setUpdateUser(context.getUserId());
            // 处理重名：目标文件夹存在同名文件时，自动重命名（xxx(1)、xxx(2)）
            handleDuplicateFilename(record);
        });

        // 批量更新数据库所有修改后的记录
        if (!updateBatchById(prepareRecords)) {
            // 数据库更新行数为0，抛出异常提示转移失败
            throw new SystemException("文件转移失败");
        }
    }


    /**
     * 文件复制
     * <p>
     * 1、条件校验
     * 2、执行动作
     *
     * @param context
     */
    @Override
    public void copy(CopyFileContext context) {
        // 前置校验：目标是否文件夹、不能复制到子目录、权限校验等
        checkCopyCondition(context);
        // 上下文中取出待复制的原文件/文件夹数据库记录（校验阶段已批量查询存入）
        List<UserFileDO> prepareRecords = context.getPrepareRecords();

        // 没有选中文件直接结束方法
        if (EmptyUtil.isEmpty(prepareRecords)) {
            return;
        }

        // 存放所有要新增的复制副本记录（包括文件夹下所有子文件、子文件夹）
        List<UserFileDO> allRecords = Lists.newArrayList();

        // 遍历每一个选中的源文件/文件夹，递归生成副本记录存入allRecords
        prepareRecords.forEach(record ->
                assembleCopyChildRecord(allRecords, record, context.getTargetParentId(), context.getUserId())
        );

        // 批量插入所有副本到数据库
        if (!saveBatch(allRecords)) {
            throw new SystemException("文件复制失败");
        }
    }


    /**
     * 核心搜索业务方法
     * 策略：优先走ES全文检索，ES不可用/查不到结果时降级走MySQL
     * @param context 搜索上下文（关键词、用户ID、文件类型过滤）
     * @return 格式化后的搜索结果列表
     */
    @Override
    public List<FileSearchVO> search(FileSearchContext context) {
        // 1. 根据用户输入的关键词构造多个搜索词
        // 例如输入“代码随想录”，可能生成“代码随想录”“代码”“随想”“想录”等
        List<String> searchTerms = buildSearchTerms(context.getKeyword());

        List<FileSearchVO> result;

        // 2. 优先走 ES 搜索
        // fileEsMapper 是操作 ES 索引 user_file_index 的 Mapper
        // 可以类比 MyBatis-Plus 的 Mapper，只不过它查的是 ES，不是 MySQL
        // fileEsMapper != null 代表 ES 初始化成功，服务可用
        if (fileEsMapper != null) {
            // 创建 ES 查询条件构造器，类比 MP 的 LambdaQueryWrapper
            // UserFileESEntity 是 ES 索引对应的实体类，对应 ES 里的 user_file 索引
            LambdaEsQueryWrapper<UserFileESEntity> wrapper = new LambdaEsQueryWrapper<>();

            // ========== 步骤3：拼接文件名的模糊匹配条件 ==========
            // 拆分后的搜索词不为空，就循环拼接 OR 条件：命中任意一个词就算匹配
            if (CollectionUtils.isNotEmpty(searchTerms)) {

                // wrapper.and()：把下面所有条件用一个括号包起来，作为一组整体条件
                // 括号里 w -> { ... } 是 Lambda 表达式，w 就是内部的条件构造器
//                这里的 w 也是一个 Wrapper 条件构造器对象，只是它是 wrapper.and(...) 里面临时传进来的“内部条件构造器”。
//                给外层 wrapper 添加一组 AND 条件。这组条件内部怎么写，由 w 来继续拼。当前这组 AND 括号里面的条件构造器
                wrapper.and(w -> {
                    // first 标记位：第一个条件前面不加 OR，后面每个条件前面加 OR
                    boolean first = true;
                    // 循环每一个拆分好的搜索词
                    for (String term : searchTerms) {
                        if (StringUtils.isBlank(term)) {
                            continue;
                        }
                        if (first) {
                            // 第一个词：直接加 like 模糊匹配（文件名包含这个词）
                            // UserFileESEntity::getFilename 是方法引用，指定匹配哪个字段
                            w.like(UserFileESEntity::getFilename, term);
                            first = false;
                        } else {
                            // 后面的词：先加 OR，再加 like
                            // 效果：文件名包含词1 OR 包含词2 OR 包含词3...
                            w.or().like(UserFileESEntity::getFilename, term);
                        }
                    }
                    // 兜底：如果循环完所有词都是空的（极端情况），就用原始关键词整体匹配
                    if (first) {
                        w.like(UserFileESEntity::getFilename, context.getKeyword());
                    }
                });
            } else {
                // 拆分后没有搜索词（比如用户输入全是空格），直接用原始关键词模糊匹配
                wrapper.like(UserFileESEntity::getFilename, context.getKeyword());
            }

            // ========== 步骤4：拼接基础过滤条件（权限+数据有效性） ==========
            // 条件1：用户ID相等 → 数据隔离，只能搜自己的文件，不能搜别人的
            wrapper.eq(UserFileESEntity::getUserId, context.getUserId());

            // 条件2：只查未删除的文件 → 逻辑删除过滤，删掉的文件不展示
            // DeleteEnum.NO.getCode() 就是 0，代表未删除
            wrapper.eq(UserFileESEntity::getDeleted, DeleteEnum.NO.getCode());

            // ========== 步骤5：拼接文件类型过滤 ==========
            // 如果前端传了文件类型（比如只搜图片、只搜文档），就用 in 条件过滤
            if (!EmptyUtil.isEmpty(context.getFileTypeArray())) {
                wrapper.in(UserFileESEntity::getFileType, context.getFileTypeArray());
            }

            // ========== 步骤6：排序规则 ==========
            // 按文件更新时间倒序：最近修改过的文件排在最前
            wrapper.orderByDesc(UserFileESEntity::getGmtModified);

            // ========== 步骤7：执行 ES 查询，拿到结果 ==========
            // selectList：查询多条文档，类比 MP 的 selectList
            // docs 就是 ES 返回的文档列表，每一条对应一个文件
            List<UserFileESEntity> docs = fileEsMapper.selectList(wrapper);

            // ========== 步骤8：ES 结果转成前端需要的 VO 对象 ==========
            // stream().map()：Java 流式编程，把 ES 实体类 转换成 前端视图对象 VO
            // 为什么要转？ES 实体是给后端用的，字段很多；VO 是给前端用的，只保留页面需要的字段
            result = docs.stream().map(doc -> {
                FileSearchVO vo = new FileSearchVO();
                vo.setFileId(doc.getId());
                vo.setFilename(doc.getFilename());
                vo.setParentId(doc.getParentId());
                vo.setFolderFlag(doc.getFolderFlag());
                vo.setFileType(doc.getFileType());
                vo.setFileSizeDesc(doc.getFileSizeDesc());
                vo.setUpdateTime(doc.getGmtModified());
                return vo;
            }).toList();

            // ========== 步骤9：ES 没查到结果，降级去查 MySQL ==========
            // 场景：ES 数据还没同步、索引为空，导致 ES 搜不到，但 MySQL 里实际有数据
            // 去查 MySQL 保证用户能搜到结果，不会出现“明明有文件却搜不到”的情况
            if (CollectionUtils.isEmpty(result)) {
                result = doSearch(context, searchTerms);
            }

        } else {
            // ========== 步骤10：ES 完全不可用，直接查 MySQL ==========
            // 场景：ES 服务挂了、项目没配置 ES，mapper 是 null
            // 直接走 MySQL 模糊查询，功能不失效
            result = doSearch(context, searchTerms);
        }

        // ========== 步骤11：给结果补全父文件夹名称 ==========
        // 搜索结果里只有父文件夹ID，用户看不懂
//        fillParentFilename：给搜索结果补充“父文件夹名称”
        // 这个方法会根据 parentId 查出对应的文件夹名称，前端可以展示“在 XX 文件夹中”
        fillParentFilename(result);

        // ========== 步骤12：给文件名加关键词高亮 ==========
        // 把匹配到的关键词加上样式（比如标红、加粗），用户一眼就能看到为什么这条结果被命中
        applyHighlight(result, searchTerms);

        return result;
    }

    @Override
    public HomeOverviewVO getHomeOverview(Long userId) {
        HomeOverviewVO result = new HomeOverviewVO();
        result.setTotalFiles(countUserFilesByFileTypes(userId, null));
        result.setImages(countUserFilesByFileTypes(userId, List.of(FileTypeEnum.IMAGE_FILE.getCode())));
        result.setVideos(countUserFilesByFileTypes(userId, List.of(FileTypeEnum.VIDEO_FILE.getCode())));
        result.setDocuments(countUserFilesByFileTypes(userId, HOME_DOCUMENT_FILE_TYPES));
        result.setRecentFiles(listHomeRecentFiles(userId, HOME_RECENT_FILE_LIMIT));
        return result;
    }

    @Override
    public UserFileDO getUserRootInfo(Long userId, FolderFlagEnum folderFlag) {
        QueryFileContext context = new QueryFileContext();
        context.setUserId(userId);
        context.setFileFolderType(folderFlag.getCode());
        context.setParentId(FileConstant.TOP_PARENT_ID);
        UserFileDO userRootInfo = userFileMapper.getUserRootInfo(context);
        return userRootInfo;
    }

    @Override
    public List<BreadcrumbVO> getBreadcrumbs(QueryBreadcrumbsContext context) {
//        从数据库查出当前用户所有的、未删除的文件夹记录，为后续内存组装路径提供全量数据。
        List<UserFileDO> folderRecords = queryFolderRecords(context.getUserId());
        // 2. 转成 ID → 面包屑VO 的 Map 字典
    //map(BreadcrumbVO::transfer)：把数据库实体 UserFileDO 转换成给前端用的 BreadcrumbVO，只保留 ID、名称、父 ID 等前端需要的字段。
    //collect(Collectors.toMap(...))：把列表转换成「文件夹 ID → 文件夹 VO 对象」的 Map 字典。
    //作用：后续查找父文件夹时，直接用 ID 从 Map 里取，时间复杂度是 O(1)，比每次遍历列表找父级快得多。
        Map<Long, BreadcrumbVO> prepareBreadcrumbVOMap = folderRecords.stream().map(BreadcrumbVO::transfer).collect(Collectors.toMap(BreadcrumbVO::getId, a -> a));
        // 3. 初始化变量
        BreadcrumbVO currentNode; // 每次循环的当前节点
        Long fileId = context.getFileId();// 起点：前端传的目标文件夹ID
//        用 LinkedList 是因为后面要频繁往列表头部插入元素，链表的头部插入性能比 ArrayList 更好。
        List<BreadcrumbVO> result = Lists.newLinkedList();// 最终结果，用链表适合频繁头部插入
        // 4. 从当前文件夹向上回溯，组装路径
//        这是一个 do-while 循环，从当前文件夹开始，不断向上找父级，直到找不到父节点（到达根目录之上）就停止
        do {
            currentNode = prepareBreadcrumbVOMap.get(fileId);
            if (Objects.nonNull(currentNode)) {
                result.add(0, currentNode);// 插到列表最前面
                fileId = currentNode.getParentId();
            }
        } while (Objects.nonNull(currentNode));
//        最终返回的列表就是从根到当前的正序路径，前端拿到后可以直接渲染成 全部文件 > 工作资料 > 项目文档。
        return result;
    }


    /**
     * 文件保存
     * @param context
     */
    private void saveFile(UploadFileContext context) {
        SaveFileContext fileSaveContext = fileConvertor.fileUploadContextToFileSaveContext(context);
        fileService.saveFile(fileSaveContext);
        context.setFileRecord(fileSaveContext.getFileRecord());
    }

    /**
     * 查询文件列表
     * @param userId
     * @param identifier
     * @return
     */
    private List<FileDO> getFilesByUserIdAndIdentifier(Long userId, String identifier) {
        ListFileContext context = new ListFileContext();
        context.setUserId(userId);
        context.setIdentifier(identifier);
        return fileService.getFileList(context);
    }

    /**
     * 执行文件删除的操作
     *
     * @param context
     */
    private void doDeleteFile(DeleteUserFileContext context) {
        List<Long> fileIdList = context.getFileIdList();

        UpdateWrapper updateWrapper = new UpdateWrapper();
        updateWrapper.in("id", fileIdList);
        updateWrapper.set("deleted", DeleteEnum.YES.getCode());

        if (!update(updateWrapper)) {
            throw new FileException(FILE_DELETE_ERROR);
        }
    }

    /**
     * 更新文件名称的条件校验
     * <p>
     * 1、文件ID是有效的
     * 2、用户有权限更新该文件的文件名称
     * 3、新旧文件名称不能一样
     * 4、不能使用当前文件夹下面的子文件的名称
     *
     * @param context
     */
    /**
     * 重命名前置校验：文件是否存在、是否本人文件、新旧名称一致、同级目录重名校验
     * @param context 重命名上下文
     */
    private void checkUpdateFilenameCondition(UpdateFilenameContext context) {
    //entity：从数据库查出来的旧数据（文件原本的信息：原来的名字、归属人、父目录）
    //context：本次前端提交的新操作信息（解密后的文件 ID、当前登录用户、用户输入的新文件名）
//        context 里的 fileId：前端传加密 ID，后端解密得到，代表用户想要修改哪一个文件
//        拿着这个 ID 去数据库查询，得到 entity：这个文件在数据库里真实存在的一行数据
        // 获取待修改文件ID（解密后的明文主键id）
        Long fileId = context.getFileId();
        // 根据ID查询数据库文件记录
        UserFileDO entity = getById(fileId);

        // 校验1：文件不存在
        if (EmptyUtil.isEmpty(entity)) {
            throw new FileException(FILE_NOT_EXIT);
        }

        // 校验2：越权校验，只能修改自己上传/创建的文件
        if (!Objects.equals(entity.getUserId(), context.getUserId())) {
            throw new FileException("abc", FILE_NOT_CUR_USER);
        }

        // 校验3：新名称和原有名称完全一样，无需修改，直接报错
        if (Objects.equals(entity.getFilename(), context.getNewFilename())) {
            throw new FileException(FILE_NEW_NAME_EQUALS);
        }

        // 校验4：同级目录下不能存在同名文件/文件夹
//        查询逻辑：在同一个父目录下，有没有其他文件叫这个新名字
        QueryWrapper queryWrapper = new QueryWrapper<>();
        // 同一父目录 entity.getParentId ()：从数据库实体拿到当前文件所在的文件夹 ID（它现在在哪一级目录）
        queryWrapper.eq("parent_id", entity.getParentId());
        // 精准匹配新文件名
        queryWrapper.eq("filename", context.getNewFilename());
        // 统计满足条件的记录数量
        long count = count(queryWrapper);

        // 存在同名记录，抛出名称已存在异常
        if (count > 0) {
            throw new FileException(FILE_NEW_NAME_EXIST);
        }

        // 把查询出来的原始文件实体存入上下文，供上层更新使用
        context.setEntity(entity);
    }



    /**
     * 保存用户文件/文件夹目录记录到数据库
     * 自动处理同目录重名、新增成功后文件触发AI解析任务
     * @param parentId 父文件夹ID，根目录传0
     * @param filename 原始文件/文件夹名称
     * @param folderFlagEnum 文件夹标识枚举：YES文件夹 / NO普通文件
     * @param fileType 文件类型编码（文档/图片/视频等）
     * @param realFileId 底层真实存储文件id（关联物理文件）
     * @param userId 文件归属用户ID、操作人ID
     * @param fileSizeDesc 格式化文件大小展示文本 例:2.5MB
     * @return Long 新增文件记录主键ID
     * @throws SystemException 数据库插入失败抛出异常
     */
//    Spring 事务一般是通过代理拦截 public 方法 生效的。saveUserFile(...) 是私有方法：
//    private Long saveUserFile(...)
//    所以它有没有事务，要看调用它的外层方法有没有事务。


    private Long saveUserFile(Long parentId,
                              String filename,
                              FolderFlagEnum folderFlagEnum,
                              Integer fileType,
                              Long realFileId,
                              Long userId,
                              String fileSizeDesc) {
        // 1. 初始化数据库实体对象，对应user_file表一条记录
        UserFileDO entity = new UserFileDO();
        // 生成分布式全局唯一主键
        entity.setId(IdUtil.get());
        // 文件归属用户ID
        entity.setUserId(userId);
        // 父级文件夹ID，代表该文件存放在哪个目录下
        entity.setParentId(parentId);
        // 关联底层真实存储的文件id（真正存oss/磁盘的文件记录）
        entity.setRealFileId(realFileId);
        // 文件/文件夹展示名称
        entity.setFilename(filename);
        // 存入文件夹标识编码（0文件/1文件夹），从枚举获取code
        entity.setFolderFlag(folderFlagEnum.getCode());
        // 格式化后的文件大小字符串，用于前端展示
        entity.setFileSizeDesc(fileSizeDesc);
        // 文件类型编码，区分文档、图片、视频等
        entity.setFileType(fileType);
        // 逻辑删除标记：未删除
        entity.setDeleted(DeleteEnum.NO.getCode());
        // 创建人ID = 当前操作用户
        entity.setCreateUser(userId);
        // 更新人ID = 当前操作用户（新建记录创建更新人一致）
        entity.setUpdateUser(userId);

        // 2. 核心：处理同一父目录下重名文件
        // 逻辑：查询当前用户+当前parentId下是否存在同名文件，存在则自动重命名（xxx(1).txt）
        handleDuplicateFilename(entity);

        // 3. 执行数据库插入，save返回布尔值标识是否插入成功
        if (!save((entity))) {
            // 插入失败，抛全局系统异常，上层统一捕获返回前端错误提示
            throw new SystemException("保存文件信息失败");
        }
        if (FolderFlagEnum.NO.equals(folderFlagEnum)) {
            documentAiInitializer.scheduleInitialize(userId, entity.getId(), filename);
        }


        // 返回新增记录主键
        return entity.getId();
    }

    /**
     * 处理用户重复名称
     * 如果同一文件夹下面有文件名称重复
     * 按照系统级规则重命名文件
     *
     * @param entity
     */
    /**
     * 处理同级目录重名，自动拼接(1)(2)实现重命名
     * @param entity 待新增的文件夹/文件数据库DO实体
     */
    private void handleDuplicateFilename(UserFileDO entity) {
        // 取出原始名称
        String filename = entity.getFilename();
        // 定义变量：不带后缀的文件名、文件的后缀/扩展名
        String newFilenameWithoutSuffix, newFilenameSuffix;

        // 查找最后一个小数点 "." 的下标，用来分割文件名和后缀（例：test.txt，小数点用来拆分 test 和 .txt）
        int newFilenamePointPosition = filename.lastIndexOf(BaseConstant.POINT_STR);

        // -1：代表字符串里不存在小数点，说明是文件夹或者无后缀文件
        if (newFilenamePointPosition == BaseConstant.MINUS_ONE_INT) {
            newFilenameWithoutSuffix = filename; // 主名称 = 原名称
            newFilenameSuffix = StringUtils.EMPTY; // 后缀为空
        } else {
            // 存在小数点：截取小数点前面的文字作为主名称
            newFilenameWithoutSuffix = filename.substring(BaseConstant.ZERO_INT, newFilenamePointPosition);
            // 后缀就是小数点及后面的内容，如 .txt、.png
            newFilenameSuffix = filename.replace(newFilenameWithoutSuffix, StringUtils.EMPTY);
        }

        // 查询：同一父目录、同一用户下，所有以该主名称开头的已存在记录
        List<UserFileDO> existRecords = getDuplicateFilename(entity, newFilenameWithoutSuffix);

        // 该目录下没有重名文件/文件夹，直接结束方法，名称保持原样
        if (CollectionUtils.isEmpty(existRecords)) {
            return;
        }

        // 将数据库查出来的记录，只提取文件名称，转为字符串集合，方便后续比对
        List<String> existFilenames = existRecords.stream()
                .map(UserFileDO::getFilename)
                .collect(Collectors.toList());

        int count = 1;
        String newFilename;

        // do‑while循环：不断拼接新名称，只要名称已存在就继续自增count
        do {
            // 拼接成：主名称+(数字)+后缀，示例：文档(1)
            newFilename = assembleNewFilename(newFilenameWithoutSuffix, count, newFilenameSuffix);
            count++;
        } while (existFilenames.contains(newFilename));

        // 将最终不重复的新名称回写到DO实体，后续插入数据库用新名字
        entity.setFilename(newFilename);
    }


    /**
     * 查找通易付文件夹下面的同名文件数量
     *
     * @param entity
     * @param newFilenameWithoutSuffix
     * @return
     */
    /**
     * 查询同一父级目录中，同名前缀、同一用户下未删除的文件/文件夹
     * @param entity 待新增的文件夹DO实体
     * @param newFilenameWithoutSuffix 原始文件名称前缀（不带后缀）
     * @return 满足条件的文件集合
     */
    private List<UserFileDO> getDuplicateFilename(UserFileDO entity, String newFilenameWithoutSuffix) {
        // 构建MyBatis‑Plus条件构造器，拼接where条件
        QueryWrapper<UserFileDO> queryWrapper = new QueryWrapper<>();

        // 条件1：父文件夹id必须一致，只在当前目录校验重名，其他目录不限制
        queryWrapper.eq("parent_id", entity.getParentId());
        // 条件2：必须类型一致，只拿文件夹(folder_flag=1)，不会和普通文件做重名校验
        queryWrapper.eq("folder_flag", entity.getFolderFlag());
        // 条件3：仅限当前登录用户，A用户的文件夹不影响B用户
        queryWrapper.eq("user_id", entity.getUserId());
        // 条件4：只查询未逻辑删除的数据，回收站里的文件夹不参与重名校验
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        // 条件5：右模糊查询，匹配以该名称开头的记录
        // 例如前缀为“文档”，可以匹配：文档、文档(1)、文档(2)
        queryWrapper.likeRight("filename", newFilenameWithoutSuffix);

        // 执行查询，返回结果集合
        return list(queryWrapper);
    }



    /**
     * 拼接重命名后的新名称
     * @param newFilenameWithoutSuffix 原始文件名前缀（不带后缀）
     * @param count 自增序号
     * @param newFilenameSuffix 文件后缀，文件夹时为空字符串
     * @return 拼接完成的新文件名
     */
    private String assembleNewFilename(String newFilenameWithoutSuffix, int count, String newFilenameSuffix) {
        StringBuilder stringBuilder = new StringBuilder(newFilenameWithoutSuffix);
        // 拼接左括号 (
        stringBuilder.append(FileConstant.CN_LEFT_PARENTHESES_STR);
        // 拼接自增数字
        stringBuilder.append(count);
        // 拼接右括号 )
        stringBuilder.append(FileConstant.CN_RIGHT_PARENTHESES_STR);
        // 拼接文件后缀，文件夹场景该值为空，不会追加内容
        stringBuilder.append(newFilenameSuffix);

        // StringBuilder转为String返回
        String newFilename = stringBuilder.toString();
        return newFilename;
    }

    /**
     * 查询用户所有有效的文件夹信息
     *
     * @param userId
     * @return
     */
//    从数据库查出当前用户所有的、未删除的文件夹记录，为后续内存组装路径提供全量数据。
    private List<UserFileDO> queryFolderRecords(Long userId) {
//   这是 MyBatis-Plus 的条件构造器，用来动态拼接 SQL 查询条件，不用手写 XML 里的 SQL 片段。
        QueryWrapper queryWrapper = Wrappers.query();
//用户数据隔离：只查当前登录用户的文件记录，和之前 Controller、XML 里的逻辑完全一致，保证数据安全。
        queryWrapper.eq("user_id", userId);
//  只筛选「文件夹」类型的记录。面包屑是目录层级路径，只有文件夹才有上下级关系，普通文件不需要参与路径组装。这里用枚举类代替硬编码数字，是规范的工程写法。
        queryWrapper.eq("folder_flag", FolderFlagEnum.YES.getCode());
//        逻辑删除过滤：只查未被删除的文件夹，回收站里的文件夹不会出现在正常路径里。
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
//        调用 MyBatis-Plus 内置的 list 方法，执行查询并返回 UserFileDO 列表。DO = Data Object，和数据库表一一对应，是数据库层的实体对象。
        return list(queryWrapper);
    }

    /**
     * 拼装文件夹树列表
     *
     * @param folderRecords
     * @return
     */
    /**
     * 将扁平的文件夹数据库列表，组装成树形结构（递归父子层级），只返回顶层根节点集合
     * @param folderRecords 数据库查询出来的所有文件夹扁平列表（每条记录只存自己、父ID）
     * @return 顶层文件夹树形节点VO，每个节点内部封装子节点children
     */
    private List<FolderTreeNodeVO> assembleFolderTreeNodeVOList(List<UserFileDO> folderRecords) {
        // 1. 判空：无文件夹数据直接返回空集合
        if (CollectionUtils.isEmpty(folderRecords)) {
            return Lists.newArrayList();
        }

        // 2. DO转VO：把数据库实体UserFileDO 批量转换为前端树形节点VO
        //执行完这一步：我们得到装满树形对象的一维列表，每个对象的children都是空集合。
        List<FolderTreeNodeVO> mappedFolderTreeNodeVOList =
                // 把文件夹列表转为流，开启流式操作；
                folderRecords.stream()
                // 循环每一条UserFileDO，调用MapStruct转换方法
                .map(fileConvertor::userFile2FolderTreeNodeVO)
                // 把转换后的所有VO收集成一个List
                .toList();

        // 3. 分组：以父ID为key，把所有子文件夹归类，Map<父ID, 该父下所有子节点>
        Map<Long, List<FolderTreeNodeVO>> mappedFolderTreeNodeVOMap = mappedFolderTreeNodeVOList.stream()
            //groupingBy 核心作用（重点）
            //以每条 VO 的parentId作为 Map 的 key，把同一个父文件夹下的所有子文件夹分到一组。key=1  → [id=2, id=3]
                .collect(Collectors.groupingBy(FolderTreeNodeVO::getParentId));

        // 4. 遍历全部节点，把子节点挂载到父节点的children属性，生成嵌套树
//        mappedFolderTreeNodeVOList 是全局的原始 VO 集合，循环里只是原地修改每个对象内部的 children 字段；
//        所有父子嵌套关系全部绑定在原有对象上，不需要生成、返回新节点；
//        循环走完后，这个 list 里的所有 VO 已经自带完整树形嵌套关系，直接拿来过滤顶层节点即可。
        for (FolderTreeNodeVO node : mappedFolderTreeNodeVOList) {
            // node.getId() = 当前文件夹的ID，用这个ID去分组Map查：哪些文件夹的parentId等于我
            List<FolderTreeNodeVO> children = mappedFolderTreeNodeVOMap.get(node.getId());
            // 判断是否存在子文件夹
            if (CollectionUtils.isNotEmpty(children)) {
                // 把查到的子文件夹全部放进当前节点的children列表，形成嵌套
                node.getChildren().addAll(children);
            }
        }


        // 5. 过滤只返回【顶层根文件夹】：parentId等于顶层父常量ID的节点
        // 子节点已经挂载在父节点children里，不需要重复返回，前端递归渲染children即可
        return mappedFolderTreeNodeVOList.stream()
                .filter(node -> Objects.equals(node.getParentId(), FileConstant.TOP_PARENT_ID))
                .collect(Collectors.toList());
    }

    /**
     * 合并分片主入口方法
     * @param context 合并分片请求上下文，包含文件MD5、用户ID、文件名、总大小等
     */
    private void mergeFileChunkAndSaveFile(FileChunkMergeContext context) {
        // 转换器：对外合并参数实体 → 内部合并存储专用上下文
        FileChunkMergeAndSaveContext fileChunkMergeAndSaveContext = fileConvertor.fileChunkMergeContextToSaveContext(context);
        // 执行分片合并+物理文件入库完整逻辑
        fileService.mergeFileChunkAndSaveFile(fileChunkMergeAndSaveContext);
        // 将生成的物理文件记录回写给上层上下文，供外层创建user_file目录记录使用
        context.setRecord(fileChunkMergeAndSaveContext.getRecord());
    }


    /**
     * 校验用户的操作权限
     * <p>
     * 1、文件记录必须存在
     * 2、文件记录的创建者必须是该登录用户
     *
     * @param record
     * @param userId
     */
    private void checkOperatePermission(UserFileDO record, Long userId) {
        if (EmptyUtil.isEmpty(record)) {
            throw new FileException(FILE_NOT_EXIT);
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            throw new FileException(FILE_NO_AUTH);
        }
    }

    /**
     * 检查当前文件记录是不是一个文件夹
     *
     * @param record
     * @return
     */
    private boolean isFolder(UserFileDO record) {
        if (EmptyUtil.isEmpty(record)) {
            throw new FileException(FILE_NOT_EXIT);
        }
        return Objects.equals(FolderFlagEnum.YES.getCode(), record.getFolderFlag());
    }

    /**
     * 执行文件下载的动作
     * <p>
     * 1、查询文件的真实存储路径
     * 2、添加跨域的公共响应头
     * 3、拼装下载文件的名称、长度等等响应信息
     * 4、委托文件存储引擎去读取文件内容到响应的输出流中
     *
     * @param record
     * @param response
     */
    /**
     * 真正执行文件流式下载输出
     * @param record user_file表记录（用户文件记录，存储用户网盘目录结构）
     * @param response HTTP响应对象，用于输出二进制流、设置下载响应头
     */
    private void doDownload(UserFileDO record, HttpServletResponse response) {
        // 1. 根据用户文件记录里的realFileId，查询底层真实文件存储记录
        // realFileId 对应真实文件表，存文件物理路径、大小、存储桶等OSS/本地存储信息
        FileDO fileRecord = fileService.getById(record.getRealFileId());

        // 校验：底层真实文件存储记录不存在（源文件丢失、已清理）
        if (EmptyUtil.isEmpty(fileRecord)) {
            throw new FileException(FILE_NOT_EXIT);
        }

        // 2. 添加通用基础响应头
        // 默认二进制流类型，浏览器识别为可下载文件
//        通用公共响应头工具方法，内部一般设置：
//        跨域头 Access-Control
//        缓存策略
//        Content-Type = application/octet-stream 二进制流，通用下载类型
        addCommonResponseHeader(response, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        // 3. 添加下载专属头部：文件名、编码、文件长度等，触发浏览器下载弹窗
        addDownloadAttribute(response, record, fileRecord);

        // 4. 根据文件真实存储路径，读取文件并流式写入response输出流给到前端
//        后端只负责把文件数据发给前端，真正触发「保存到本地」的逻辑全在前端 saveBlobToLocal。
        realFile2OutputStream(fileRecord.getRealPath(), response);
    }


    /**
     * 添加公共的文件读取响应头
     *
     * @param response
     * @param contentTypeValue
     */
    /**
     * 统一添加文件流接口通用响应头
     * @param response HTTP响应对象
     * @param contentTypeValue 本次接口需要的MIME文件类型
     */
    private void addCommonResponseHeader(HttpServletResponse response, String contentTypeValue) {
        // 清空response原有内容、状态码、旧头部，避免前面逻辑残留数据干扰文件输出
        response.reset();
        // 添加跨域允许头部，解决前端浏览器跨域请求报错
        HttpUtil.addCorsResponseHeaders(response);
        // 手动追加Content-Type响应头
        response.addHeader(FileConstant.CONTENT_TYPE_STR, contentTypeValue);
        // 设置response主体内容类型，标准写法，和addHeader配套使用
        response.setContentType(contentTypeValue);
    }


    /**
     * 给下载接口添加专属响应头，实现浏览器弹出下载框、中文文件名不乱码、展示文件大小
     * @param response HTTP响应对象
     * @param record user_file 用户目录记录（存储用户自定义文件名）
     * @param realFileRecord 底层真实文件记录（存储文件字节大小）
     */
    private void addDownloadAttribute(HttpServletResponse response, UserFileDO record, FileDO realFileRecord) {
        // 1. 对中文文件名做URL编码，解决Chrome、Edge、Firefox多浏览器中文乱码问题
        String encodedFilename = URLEncoder.encode(record.getFilename(), StandardCharsets.UTF_8)
                // URLEncoder会把空格转为+，浏览器识别空格需要替换为标准URL空格编码%20
                .replace("+", "%20");


//        TODO 后端这堆响应头只是给浏览器下发规则提示，只起告知作用，本身不会触发下载动作，真正弹出保存窗口必须前端代码手动实现。

        // 2. 设置核心下载头 Content-Disposition
        // attachment：强制浏览器下载，不在页面内直接打开
        // filename：兼容旧浏览器，带编码文件名
        // filename*：新标准UTF-8文件名，兼容现代浏览器
        response.setHeader(
                FileConstant.CONTENT_DISPOSITION_STR,
                "attachment;filename=\"" + encodedFilename + "\";filename*=UTF-8''" + encodedFilename
        );

        // 3. 设置文件总字节长度，浏览器可显示下载进度条
        response.setContentLengthLong(Long.parseLong(realFileRecord.getFileSize()));
    }


    /**
     * 委托文件存储引擎去读取文件内容并写入到输出流中
     *
     * @param realPath
     * @param response
     */
    /**
     * 根据文件真实存储路径，读取文件并流式写入HTTP响应输出流，供前端下载/预览
     * @param realPath 文件在存储介质上的真实路径（OSS地址/服务器本地路径）
     * @param response HTTP响应对象，从中获取输出流
     */
    private void realFile2OutputStream(String realPath, HttpServletResponse response) {
        try {
            // 构建文件读取上下文载体
            ReadFileContext context = new ReadFileContext();
            // 设置文件真实存储路径，存储引擎通过该路径定位文件
            context.setRealPath(realPath);
            // 获取response底层输出流，存入上下文；存储引擎读取文件后直接写入该流
//            response.getOutputStream()
//            获取 HTTP 原生二进制输出流，所有文件字节会直接写入这个流，一路传递到前端浏览器。
//            全程流式读写：读取一块文件，立刻写出一块，不会把整个大文件加载到服务器内存，避免内存溢出 OOM。
            context.setOutputStream(response.getOutputStream());

            // 调用统一存储引擎，执行文件读取+流式输出逻辑
//            项目分层设计，统一封装文件读取逻辑，屏蔽底层存储差异：
//            如果是本地磁盘存储：引擎内部打开本地文件输入流，循环拷贝到 outputStream；
//            如果是对象存储 OSS（阿里云 / 腾讯云）：引擎调用 OSS SDK 流式拉取文件，边拉边输出；
            storageEngine.realFile(context);
        } catch (IOException e) {
            // 打印IO异常堆栈日志，方便排查文件读取、网络流错误
            e.printStackTrace();
            // 封装系统自定义异常，交给全局异常处理器返回前端提示
            throw new SystemException("文件下载失败");
        }
    }


    /**
     * 执行文件预览的动作
     * 1、查询文件的真实存储路径
     * 2、添加跨域的公共响应头
     * 3、委托文件存储引擎去读取文件内容到响应的输出流中
     *
     * @param record
     * @param response
     */
    /**
     * 执行文件在线预览底层逻辑
     * @param record user_file 用户网盘目录记录（文件名、realFileId、是否文件夹等）
     * @param response HTTP响应输出对象，用于返回文件二进制流
     */
    private void doPreview(UserFileDO record, HttpServletResponse response) {
        // 1. 根据用户文件记录中的realFileId，查询底层真实文件存储记录
        FileDO realFileRecord = fileService.getById(record.getRealFileId());

        // 校验：底层物理文件记录丢失，无法预览
        if (EmptyUtil.isEmpty(realFileRecord)) {
            throw new SystemException("当前的文件记录不存在");
        }

        // 2. 获取预览专用MIME类型，为空则兜底通用二进制流
        String previewContentType = StringUtils.defaultIfBlank(//第一个参数能用就用，不能用就用第二个凑数，保证永远不会返回 null 或空字符串。
                // 优先取数据库预存的预览MIME（image/png / application/pdf / text/plain等）
//文件上传时，系统就识别好文件的真实 MIME 类型（比如 image/png、application/pdf、video/mp4），提前存进数据库；
//预览时直接取出来用，是最准确的类型，浏览器拿到后就能直接渲染显示图片、播放视频、打开 PDF。
                realFileRecord.getFilePreviewContentType(),
                // 兜底：二进制通用类型
                MediaType.APPLICATION_OCTET_STREAM_VALUE
        );

        // 3. 设置通用响应头：跨域、重置响应、绑定文件MIME类型
        addCommonResponseHeader(response, previewContentType);

        // 4. 根据文件真实存储路径，流式读取文件并输出到前端浏览器
        realFile2OutputStream(realFileRecord.getRealPath(), response);
    }


    /**
     * 文件转移的条件校验
     * <p>
     * 1、目标文件必须是一个文件夹
     * 2、选中的要转移的文件列表中不能含有目标文件夹以及其子文件夹
     *
     * @param context
     */
    /**
     * 文件移动前置条件校验，不满足则直接抛出异常中断转移操作
     */
    /**
     * 校验文件转移（移动）的前置条件
     * 核心职责：1. 合法性校验，拦截非法移动操作；2. 预加载待移动数据，供后续逻辑复用，减少数据库查询
     * @param context 文件转移上下文对象，封装了目标文件夹ID、待移动文件ID列表、当前用户ID等全部参数
     */
    private void checkTransferCondition(TransferFileContext context) {
        // 从上下文中取出本次移动的目标位置ID（也就是要把文件移到哪个文件夹下面）
        Long targetParentId = context.getTargetParentId();

        // ========== 校验1：目标位置必须是文件夹，不能是普通文件 ==========
        // 先根据目标ID查询数据库记录，再判断这条记录是不是文件夹类型
        // 业务规则：文件/文件夹只能存放在文件夹（容器）里，不能移动到一个普通文件的“内部”
        if (!isFolder(getById(targetParentId))) {
            // 目标不是文件夹，直接抛出「目标文件夹类型错误」的业务异常，终止流程
            throw new FileException(TARGET_FOLDER_TYPE_ERROR);
        }

        // ========== 预加载：批量查出所有待移动的文件记录 ==========
        // 根据前端传的文件ID列表，一次性查出全部待移动的文件/文件夹实体
        // 这里提前查出来，后面的子目录校验、主方法里的属性修改都能直接用，不用重复查数据库
        List<UserFileDO> prepareRecords = listByIds(context.getFileIdList());
        // 把查到的记录存入上下文对象，实现「一次查询、多处复用」
        context.setPrepareRecords(prepareRecords);

        // ========== 校验2：禁止把文件夹移动到它自己的子目录里 ==========
        // 这是树形目录的核心边界校验：防止出现「A文件夹包含B文件夹，B文件夹又包含A文件夹」的循环嵌套
        // 内部逻辑：从目标文件夹开始，一层层向上找父级，检查目标文件夹是不是待移动文件夹的子孙级
        // 如果不拦截，后续查询面包屑、遍历文件夹时会陷入无限递归，直接导致系统卡死
        if (checkIsChildFolder(prepareRecords, targetParentId, context.getUserId())) {
            throw new FileException(FILE_NOT_EXIT);
        }
    }



    /**
     * 校验目标文件夹ID是都是要操作的文件记录的文件夹ID以及其子文件夹ID
     * <p>
     * 1、如果要操作的文件列表中没有文件夹，那就直接返回false
     * 2、拼装文件夹ID以及所有子文件夹ID，判断存在即可
     *
     * @param prepareRecords
     * @param targetParentId
     * @param userId
     * @return
     */
    private boolean checkIsChildFolder(List<UserFileDO> prepareRecords, Long targetParentId, Long userId) {
        prepareRecords = prepareRecords.stream().filter(record -> Objects.equals(record.getFolderFlag(), FolderFlagEnum.YES.getCode())).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(prepareRecords)) {
            return false;
        }
        List<UserFileDO> folderRecords = queryFolderRecords(userId);
        Map<Long, List<UserFileDO>> folderRecordMap = folderRecords.stream().collect(Collectors.groupingBy(UserFileDO::getParentId));
        List<UserFileDO> unavailableFolderRecords = Lists.newArrayList();
        unavailableFolderRecords.addAll(prepareRecords);
        prepareRecords.forEach(record -> findAllChildFolderRecords(unavailableFolderRecords, folderRecordMap, record));
        List<Long> unavailableFolderRecordIds = unavailableFolderRecords.stream().map(UserFileDO::getId).collect(Collectors.toList());
        return unavailableFolderRecordIds.contains(targetParentId);
    }

    /**
     * 查找文件夹的所有子文件夹记录
     *
     * @param unavailableFolderRecords
     * @param folderRecordMap
     * @param record
     */
    private void findAllChildFolderRecords(List<UserFileDO> unavailableFolderRecords, Map<Long, List<UserFileDO>> folderRecordMap, UserFileDO record) {
        if (EmptyUtil.isEmpty(record)) {
            return;
        }
        List<UserFileDO> childFolderRecords = folderRecordMap.get(record.getId());
        if (CollectionUtils.isEmpty(childFolderRecords)) {
            return;
        }
        unavailableFolderRecords.addAll(childFolderRecords);
        childFolderRecords.forEach(childRecord -> findAllChildFolderRecords(unavailableFolderRecords, folderRecordMap, childRecord));
    }

    /**
     * 文件转移的条件校验
     * <p>
     * 1、目标文件必须是一个文件夹
     * 2、选中的要转移的文件列表中不能含有目标文件夹以及其子文件夹
     *
     * @param context
     */
    private void checkCopyCondition(CopyFileContext context) {
        Long targetParentId = context.getTargetParentId();
        if (!isFolder(getById(targetParentId))) {
            throw new FileException(TARGET_FOLDER_TYPE_ERROR);
        }
        List<Long> fileIdList = context.getFileIdList();
        List<UserFileDO> prepareRecords = listByIds(fileIdList);
        context.setPrepareRecords(prepareRecords);
        if (checkIsChildFolder(prepareRecords, targetParentId, context.getUserId())) {
            throw new FileException(FOLDER_INVALID);
        }
    }

    /**
     * 拼装当前文件记录以及所有的子文件记录
     *
     * @param allRecords
     * @param record
     * @param targetParentId
     * @param userId
     */
    /**
     * 递归组装文件/文件夹的复制副本，所有新记录存入统一集合allRecords
     * @param allRecords 全局容器，存放所有待批量插入的复制后文件记录
     * @param record 当前要复制的原始文件/文件夹对象
     * @param targetParentId 当前副本要存放的父文件夹ID
     * @param userId 当前操作人ID
     */
    private void assembleCopyChildRecord(List<UserFileDO> allRecords, UserFileDO record, Long targetParentId, Long userId) {
        // 生成全新唯一主键，作为复制后文件的新id
        Long newFileId = IdUtil.get();
        // 保存原始文件id，后面查它的子文件夹要用
        Long oldFileId = record.getId();

        // 1. 修改当前这条副本的属性
        record.setParentId(targetParentId); // 副本放到指定父目录
        record.setId(newFileId);             // 覆盖id，变成全新文件记录
        record.setUserId(userId);            // 文件归属当前操作人
        record.setCreateUser(userId);
        record.setUpdateUser(userId);
        handleDuplicateFilename(record);     // 重名自动加后缀，避免同目录重名冲突

        // 把处理好的新文件记录加入待插入列表
        allRecords.add(record);

        // 2. 如果当前复制的是文件夹，需要递归复制它所有子文件、子文件夹
        if (isFolder(record)) {
            // 根据原始文件夹id，查询它所有直接子级
//            给一个文件夹 ID，去数据库查出它所有直接下级（文件、子文件夹），只拿没删除的数据。
//            只查一级子项，不会递归查孙子、曾孙。
            List<UserFileDO> childRecords = findChildRecords(oldFileId);
            // 没有子内容直接终止递归
            if (CollectionUtils.isEmpty(childRecords)) {
                return;
            }
            // 遍历每一个子项，递归复制
            // 注意第二个参数newFileId：子文件的父目录是刚复制出来的新文件夹id
//            查询原文件夹所有一级子内容，逐个递归复制，并且让复制后的子内容挂载到新复制出来的父文件夹下，完整复刻整套目录树。
            childRecords.forEach(childRecord -> assembleCopyChildRecord(allRecords, childRecord, newFileId, userId));
        }
    }


    /**
     * 查找下一级的文件记录
     *
     * @param parentId
     * @return
     */
    /**
     * 根据父文件夹ID，查询该目录下所有未删除的直接子文件/子文件夹
     * @param parentId 父文件夹ID
     * @return 当前目录下一级所有文件记录
     */
    private List<UserFileDO> findChildRecords(Long parentId) {
        // 构建数据库查询条件包装器
        QueryWrapper queryWrapper = Wrappers.query();
        // 条件1：parent_id = 传入的父文件夹ID，只查这个目录的子内容
        queryWrapper.eq("parent_id", parentId);
        // 条件2：逻辑未删除，只查正常文件，过滤回收站删除数据
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        // 执行查询，返回一级子文件列表
        return list(queryWrapper);
    }


    /**
     * 搜索文件列表
     *
     * @param context
     * @return
     */
    private List<FileSearchVO> doSearch(FileSearchContext context, List<String> searchTerms) {
        QueryWrapper<UserFileDO> queryWrapper = Wrappers.query();
        queryWrapper.select("id", "parent_id", "filename", "file_size_desc", "folder_flag", "file_type", "gmt_modified");
        queryWrapper.eq("user_id", context.getUserId());
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        if (CollectionUtils.isNotEmpty(context.getFileTypeArray())) {
            queryWrapper.in("file_type", context.getFileTypeArray());
        }
        // ========== 5. 文件名多关键词 OR 模糊匹配（加括号保证逻辑优先级） ==========
        // 和 ES 版的 wrapper.and(w -> {}) 作用完全相同：
        // 把所有关键词的 like 条件用括号包成一组，避免和外面的 AND 条件优先级混乱
        queryWrapper.and(wrapper -> {
            boolean first = true;
            for (String term : searchTerms) {
                if (StringUtils.isBlank(term)) {
                    continue;
                }
                if (first) {
                    wrapper.like("filename", term);
                    first = false;
                } else {
                    wrapper.or().like("filename", term);
                }
            }
            if (first) {
                wrapper.like("filename", context.getKeyword());
            }
        });
        queryWrapper.orderByDesc("gmt_modified");

        List<UserFileDO> records = list(queryWrapper);
        return records.stream().map(record -> {
            FileSearchVO vo = new FileSearchVO();
            vo.setFileId(record.getId());
            vo.setParentId(record.getParentId());
            vo.setFilename(record.getFilename());
            vo.setFolderFlag(record.getFolderFlag());
            vo.setFileType(record.getFileType());
            vo.setFileSizeDesc(record.getFileSizeDesc());
            vo.setUpdateTime(record.getGmtModified());
            return vo;
        }).toList();
    }


    /**
     * 给搜索结果补充父文件夹名称。
     *
     * 搜索结果 FileSearchVO 里只有 parentId，前端如果想显示“位于哪个文件夹”，
     * 还需要根据 parentId 再查一次 user_file 表，把父文件夹名称查出来。
     *
     * @param result 搜索结果列表
     */
    private void fillParentFilename(List<FileSearchVO> result) {
        // 如果搜索结果为空，没有任何文件需要补充父目录名，直接结束
        if (CollectionUtils.isEmpty(result)) {
            return;
        }

        // 从每条搜索结果中提取 parentId。
        // parentId 表示当前文件/文件夹所在的父目录 ID。
        List<Long> parentIdList = result.stream()
                .map(FileSearchVO::getParentId)
                .collect(Collectors.toList());

        // 根据父目录 ID 批量查询父文件夹记录。
        // listByIds 是 MyBatis-Plus 的方法，会根据主键 ID 集合查询 user_file 表。
        // 当前 Service 操作的是 UserFileDO，所以这里查的是 user_file 表。
        List<UserFileDO> parentRecords = listByIds(parentIdList);

        // 把父文件夹记录转成 Map：
        // key   = 父文件夹 ID
        // value = 父文件夹名称
        //
        // 这样后面根据 parentId 找名称时，就不用每次循环 parentRecords，
        // 直接 fileId2filenameMap.get(parentId) 即可。
        Map<Long, String> fileId2filenameMap = parentRecords.stream()
                .collect(Collectors.toMap(UserFileDO::getId, UserFileDO::getFilename));

        // 遍历每条搜索结果，根据它的 parentId 找到父文件夹名称，并设置回 VO。
        // 前端如果需要展示“所在文件夹”，就可以使用 parentFilename。
        result.forEach(vo -> vo.setParentFilename(fileId2filenameMap.get(vo.getParentId())));
    }

    private Long countUserFilesByFileTypes(Long userId, List<Integer> fileTypes) {
        QueryWrapper<UserFileDO> queryWrapper = Wrappers.query();
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        queryWrapper.eq("folder_flag", FolderFlagEnum.NO.getCode());
        if (CollectionUtils.isNotEmpty(fileTypes)) {
            queryWrapper.in("file_type", fileTypes);
        }
        return count(queryWrapper);
    }

    private List<UserFileVO> listHomeRecentFiles(Long userId, int limit) {
        QueryWrapper<UserFileDO> queryWrapper = Wrappers.query();
        queryWrapper.select("id", "parent_id", "filename", "file_size_desc", "folder_flag", "file_type", "gmt_modified");
        queryWrapper.eq("user_id", userId);
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        queryWrapper.eq("folder_flag", FolderFlagEnum.NO.getCode());
        queryWrapper.orderByDesc("gmt_modified");
        queryWrapper.last("limit " + limit);
        List<UserFileDO> records = list(queryWrapper);
        return records.stream().map(this::toUserFileVO).toList();
    }

    private UserFileVO toUserFileVO(UserFileDO record) {
        UserFileVO vo = new UserFileVO();
        vo.setId(record.getId());
        vo.setParentId(record.getParentId());
        vo.setFilename(record.getFilename());
        vo.setFileSizeDesc(record.getFileSizeDesc());
        vo.setFolderFlag(record.getFolderFlag());
        vo.setFileType(record.getFileType());
        vo.setUpdateTime(record.getGmtModified());
        return vo;
    }

    /**
     * 构建搜索词项列表
     * 处理逻辑：标准化 → 空格分词 → 中文n-gram切分 → 去重 → 按长度倒序
     * @param keyword 用户原始输入的搜索关键词
     * @return 处理后的多粒度搜索词项列表，长词在前、短词在后
     */
    private List<String> buildSearchTerms(String keyword) {
        // 第一步：关键词标准化 —— 去除前后空格，null自动转成空字符串
        String normalized = StringUtils.trimToEmpty(keyword);
        // 关键词为空，直接返回空列表，不做任何搜索
        if (StringUtils.isBlank(normalized)) {
            return List.of();
        }

        // 第二步：用 LinkedHashSet 存储词项
        // 特性1：Set 自动去重，避免重复词项浪费查询性能
        // 特性2：Linked 保证插入顺序，先加的长词排在前面
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        // 先把「完整的原始关键词」加进去 —— 最高优先级，精准匹配
        terms.add(normalized);

        // 按「任意空白字符（空格、多个空格、制表符等）」拆分关键词
        // 处理用户输入「2024 项目报告」这种空格分隔多关键词的场景
        for (String token : normalized.split("\\s+")) {
            // 跳过拆分后产生的空串
            if (StringUtils.isNotBlank(token)) {
                terms.add(token.trim());
            }
        }

        // 第三步：中文n-gram切分（二元、三元连续字组合）—— 提升中文模糊匹配的召回率
        // 先把关键词里的非中文字符全部去掉，只保留纯中文内容
        String chineseOnly = normalized.replaceAll("[^\\u4e00-\\u9fa5]", StringUtils.EMPTY);

        // 纯中文长度大于2，才做n-gram切分（太短切分没意义）
        if (chineseOnly.length() > 2) {
            // 二元切分（bi-gram）：每2个连续汉字组成一个词项
            // 比如「项目报告」→ 项目、目报、报告
            for (int i = 0; i <= chineseOnly.length() - 2; i++) {
                terms.add(chineseOnly.substring(i, i + 2));
            }
            // 三元切分（tri-gram）：每3个连续汉字组成一个词项
            // 比如「项目报告」→ 项目报、目报告
            for (int i = 0; i <= chineseOnly.length() - 3; i++) {
                terms.add(chineseOnly.substring(i, i + 3));
            }
        }

        // 第四步：最终整理输出
        return terms.stream()
                // 再过滤一遍空字符串，兜底防护
                .filter(StringUtils::isNotBlank)
                // 按「字符串长度倒序」排序：长词在前，短词在后
                // 原因：长词匹配更精准，高亮时优先匹配长词，避免短词提前占位
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }


    private void applyHighlight(List<FileSearchVO> result, List<String> searchTerms) {
        if (CollectionUtils.isEmpty(result) || CollectionUtils.isEmpty(searchTerms)) {
            return;
        }
        // 遍历每条搜索结果，生成带高亮样式的文件名，赋值给专门的高亮字段
        result.forEach(item -> item.setHighlightFilename(
                // 调用专门的高亮构建方法，传入原始文件名和所有搜索词
                buildHighlightFilename(item.getFilename(), searchTerms)));
    }

    /**
     * 根据搜索词给文件名生成高亮 HTML。
     *
     * 例如：
     * filename = "Java学习笔记.pdf"
     * searchTerms = ["Java", "笔记"]
     *
     * 返回：
     * <span class="search-highlight">Java</span>学习<span class="search-highlight">笔记</span>.pdf
     *
     * 前端通过 v-html 渲染 highlightFilename，再用 CSS 控制 .search-highlight 样式。
     *
     * @param filename 原始文件名
     * @param searchTerms 搜索词集合
     * @return 带高亮 span 的文件名 HTML
     */
    private String buildHighlightFilename(String filename, List<String> searchTerms) {
        // 文件名为空，或者搜索词为空，就没有高亮意义
        if (StringUtils.isBlank(filename) || CollectionUtils.isEmpty(searchTerms)) {
            return null;
        }

        // ranges 用来保存所有命中的区间。
        // 每个 int[] 表示一个区间：
        // int[0] = 命中开始位置
        // int[1] = 命中结束位置，左闭右开，不包含结束位置
        //
        // 例如 filename = "Java学习笔记"
        // 命中 "Java" 时，区间是 [0, 4)
        List<int[]> ranges = new ArrayList<>();

        // 遍历所有搜索词，查找每个搜索词在文件名中出现的位置
        for (String term : searchTerms) {
            if (StringUtils.isBlank(term)) {
                continue;
            }

            // from 表示从文件名的哪个位置开始继续查找
            int from = 0;

            // 一个关键词可能在文件名中出现多次，所以用 while 一直找
            while (from < filename.length()) {
                // 不区分大小写查找 term 在 filename 中的位置
                int index = StringUtils.indexOfIgnoreCase(filename, term, from);

                // index < 0 表示从 from 往后已经找不到这个词了
                if (index < 0) {
                    break;
                }

                // 找到了，就记录命中区间
                ranges.add(new int[]{index, index + term.length()});

                // 下一次从当前命中词的后面继续找，避免重复命中同一个位置
                from = index + term.length();
            }
        }

        // 没有任何关键词命中，返回 null，前端会展示普通 filename
        if (ranges.isEmpty()) {
            return null;
        }

        // 对所有命中区间排序。
        // 先按开始位置升序排；
        // 如果开始位置一样，就让结束位置更靠后的排前面，也就是更长的词优先。
        ranges.sort((left, right) -> {
            if (left[0] != right[0]) {
                return Integer.compare(left[0], right[0]);
            }
            return Integer.compare(right[1], left[1]);
        });

        // 合并重叠区间。
        //
        // 为什么要合并？
        // 比如文件名是 "代码随想录"，搜索词有 "代码随想录" 和 "代码"。
        // 它们命中的位置会重叠：
        // "代码随想录" -> [0, 5)
        // "代码"       -> [0, 2)
        //
        // 如果不合并，生成 HTML 时会乱套。
        List<int[]> merged = new ArrayList<>();

        for (int[] current : ranges) {
            // 第一个区间直接放进去
            if (merged.isEmpty()) {
                merged.add(new int[]{current[0], current[1]});
                continue;
            }

            // 取出已经合并好的最后一个区间
            int[] last = merged.get(merged.size() - 1);

            // 如果当前区间和最后一个区间有重叠或相邻，就合并
            if (current[0] <= last[1]) {
                last[1] = Math.max(last[1], current[1]);
            } else {
                // 如果没有重叠，就作为新的独立高亮区间
                merged.add(new int[]{current[0], current[1]});
            }
        }

        // 开始拼接最终 HTML
        StringBuilder builder = new StringBuilder();

        // cursor 表示当前已经处理到文件名的哪个位置
        int cursor = 0;

        for (int[] range : merged) {
            // 如果当前高亮区间开始位置 > cursor，
            // 说明中间有一段普通文本，需要先追加普通文本。
            if (range[0] > cursor) {
                builder.append(escapeHtml(filename.substring(cursor, range[0])));
            }

            // 把命中的部分包上 span，前端 CSS 会把这个 class 显示成高亮样式
            builder.append("<span class=\"search-highlight\">")
                    .append(escapeHtml(filename.substring(range[0], range[1])))
                    .append("</span>");

            // cursor 移动到当前高亮区间结束位置
            cursor = range[1];
        }

        // 如果最后一个高亮区间后面还有普通文本，也要追加上
        if (cursor < filename.length()) {
            builder.append(escapeHtml(filename.substring(cursor)));
        }

        // 返回完整的高亮 HTML 字符串
        return builder.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return StringUtils.EMPTY;
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
