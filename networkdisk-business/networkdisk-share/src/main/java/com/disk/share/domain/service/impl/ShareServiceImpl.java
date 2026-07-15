package com.disk.share.domain.service.impl;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.disk.base.enums.DeleteEnum;
import com.disk.base.utils.EmptyUtil;
import com.disk.base.utils.HttpUtil;
import com.disk.base.utils.IdUtil;
import com.disk.base.utils.UserIdUtil;
import com.disk.delayqueue.DelayQueueHolder;
import com.disk.share.domain.context.CreateShareContext;
import com.disk.share.domain.context.DeleteShareContext;
import com.disk.share.domain.entity.FileDO;
import com.disk.share.domain.entity.ShareDO;
import com.disk.share.domain.entity.ShareFileDO;
import com.disk.share.domain.entity.UserFileDO;
import com.disk.share.job.delay.DeleteShareInfoMessage;
import com.disk.share.domain.response.ShareFileInfoVO;
import com.disk.share.domain.service.ShareFileService;
import com.disk.share.domain.service.ShareService;
import com.disk.share.exception.ShareErrorCode;
import com.disk.share.exception.ShareException;
import com.disk.share.infrastructure.constant.ShareConstant;
import com.disk.share.infrastructure.enums.ShareDayTypeEnum;
import com.disk.share.infrastructure.enums.ShareStatusEnum;
import com.disk.share.infrastructure.mapper.FileMapper;
import com.disk.share.infrastructure.mapper.ShareMapper;
import com.disk.share.infrastructure.mapper.UserFileMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Share domain service implementation.
 */
@Slf4j
@Service
//这个 Service 操作的是 ShareDO 这个实体，使用 ShareMapper 这个 Mapper
public class ShareServiceImpl extends ServiceImpl<ShareMapper, ShareDO> implements ShareService {

    private static final Integer PRIVATE_SHARE_TYPE = 1;
    private static final Integer FOLDER_FLAG = 1;

    @Autowired
    private ShareFileService shareFileService;

    @Autowired
    private UserFileMapper userFileMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private DelayQueueHolder delayQueueHolder;

    @Value("${com.disk.share.access-url-prefix:http://localhost:5173/share}")
    private String shareAccessUrlPrefix;
    /**
     * 根据当前登录用户ID，查询该用户所有未删除的分享记录
     * @param createUser 创建分享的用户ID（当前登录人）
     * @return 当前用户全部有效分享集合 ShareDO列表
     */
    @Override
    public List<ShareDO> listUserShareInfos(Long createUser) {
        // MyBatis-Plus 条件构造器，用于拼接SQL查询条件
        QueryWrapper<ShareDO> queryWrapper = Wrappers.query();

        // 条件1：create_user = 传入的用户ID，只查自己创建的分享，看不到别人的
        queryWrapper.eq("create_user", createUser);
        // 条件2：deleted = 0（未逻辑删除），过滤已经取消、过期删除的分享
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());

        // this.list(wrapper)：MyBatis-Plus IService自带方法，自动执行SELECT查询
        // 根据上面两个条件，查询share表所有符合条件的数据，封装成ShareDO集合返回
        return this.list(queryWrapper);
    }


    @Override
    public ShareDO getByShareId(Long shareId) {
        if (EmptyUtil.isEmpty(shareId)) {
            throw new ShareException(ShareErrorCode.INVALID_ARGS);
        }
        QueryWrapper<ShareDO> queryWrapper = Wrappers.query();
        queryWrapper.eq("id", shareId);
        queryWrapper.eq("deleted", DeleteEnum.NO.getCode());
        return this.getOne(queryWrapper);
    }

    /**
     * 新增分享主记录、批量绑定分享关联文件、创建过期定时任务
     * @Transactional 事务注解：任意步骤抛异常全部回滚，保证数据一致性
     * rollbackFor = Exception.class 所有异常均触发事务回滚
     * @param context 分享业务上下文
     * @return 数据库分享记录主键ID
     */
    @Override
//    @Transactional作用：如果保存主记录成功、批量存中间表失败，会整体回滚，不会出现只有分享无关联文件的脏数据；
//    rollbackFor	指定哪些异常触发事务回滚。  Exception.class	回滚范围：所有 Exception 及其子类
//    强制 Spring 在任何异常（包括受检异常）发生时回滚事务
    @Transactional(rollbackFor = Exception.class)
    public Long addUserShareInfo(CreateShareContext context) {
        // 获取本次分享选中的多个文件id集合
        List<Long> shareFileIds = context.getShareFileIds();
        log.info("fileIdList: [{}]", shareFileIds);

        // 组装分享主表ShareDO实体：分享类型、提取码、过期时间、创建用户等基础信息
        ShareDO shareDO = assembleShareInfo(context);

        // 流式遍历所有选中文件id，批量组装分享-文件关联中间表实体
//  map 是一对一映射转换方法，接收集合里每一个原始元素，执行自定义转换逻辑生成全新对象，返回装有新对象的流，最后 collect 转成集合；
        List<ShareFileDO> shareFileDOList = shareFileIds.stream().map(fileId -> {
            ShareFileDO shareFileDO = new ShareFileDO();
            shareFileDO.setFileId(fileId);        // 用户文件ID，对应 user_file.id
            shareFileDO.setShareId(shareDO.getId()); // 待保存的分享主记录id
            shareFileDO.setCreateUser(shareDO.getCreateUser()); // 创建分享的用户
            shareFileDO.setId(IdUtil.get());     // 中间表唯一雪花ID
            return shareFileDO;
        }).collect(Collectors.toList());

//        而是调用 MyBatis-Plus 封装好的插入方法
        // 第一步：插入分享主表一条记录，生成shareDO主键id
        save(shareDO);
        // 第二步：批量插入分享和文件关联中间表多条记录
        shareFileService.saveBatch(shareFileDOList);
        // 第三步：根据分享过期时间，创建定时任务，到期自动清理失效分享数据
        scheduleShareExpireDeleteJob(shareDO);

        // 返回分享主键给控制器，加密后返回前端
        return shareDO.getId();
    }


    @Override
    @Transactional(rollbackFor = Exception.class) // 事务：任意异常两张表同时回滚
    public void deleteUserShare(DeleteShareContext context) {
        // 1、获取分享主键并做非空校验
        Long shareId = context.getShareId();
        if (EmptyUtil.isEmpty(shareId)) {
            throw new ShareException(ShareErrorCode.INVALID_ARGS);
        }

        // 2、逻辑删除 share 分享主表记录（更新deleted=1）
        UpdateWrapper<ShareDO> updateShareWrapper = Wrappers.update();
        updateShareWrapper.eq("id", shareId);
        updateShareWrapper.set("deleted", DeleteEnum.YES.getCode());
        update(updateShareWrapper);

        // 3、逻辑删除 share_file 关联中间表，解绑分享绑定的所有文件
        UpdateWrapper<ShareFileDO> updateShareFileWrapper = Wrappers.update();
        updateShareFileWrapper.eq("share_id", shareId);
        updateShareFileWrapper.set("deleted", DeleteEnum.YES.getCode());
        shareFileService.update(updateShareFileWrapper);

        // 4、移除该分享对应的到期自动删除延时任务
        removeShareExpireDeleteJob(shareId);
    }


    /**
     * 验证提取码（对外服务接口实现）
     * @param shareId 分享ID
     * @param shareCode 用户输入的提取码
     * @return 校验成功返回true，失败直接抛异常
     */
    @Override
    public boolean checkShareCode(Long shareId, String shareCode) {
        // 第一步：先校验分享本身是否可用（存在、未过期）
        ShareDO shareDO = assertShareAvailable(shareId);
        // 第二步：再校验提取码是否正确
        validateShareCodeIfNeeded(shareDO, shareCode);
        // 两步都通过，返回验证成功
        return true;
    }


    @Override
    public List<ShareFileInfoVO> listShareFiles(Long shareId, String shareCode) {
        // 1. 前置统一校验：先确认分享存在、未过期；再按需校验提取码
        // 复用通用校验方法，和提取码验证、文件下载等接口保持完全一致的校验规则
        ShareDO shareDO = assertShareAvailable(shareId);
        validateShareCodeIfNeeded(shareDO, shareCode);

        // 2. 查询「分享-文件关联中间表」，获取该分享绑定的所有文件ID
//        查 share_file：这个文件是否属于这条分享
//        这一关查的是 share_file 表。它的作用非常重要：防止别人拿着一个合法分享链接，乱传别的 fileId 下载。
//        比如这条分享只分享了：A.pdf B.docx 但是用户自己改请求参数，传了另一个 fileId = C.zip。
//        后端会去查：share_file 里是否存在 share_id = 当前分享ID 且 file_id = C.zip 的记录
//        没有就拒绝。这一关是在问：你要下载的这个文件，真的在这条分享里面吗？
        QueryWrapper<ShareFileDO> mappingQuery = Wrappers.query();
        mappingQuery.eq("share_id", shareId);          // 匹配当前分享
        mappingQuery.eq("deleted", DeleteEnum.NO.getCode()); // 只查未逻辑删除的关联
        mappingQuery.orderByDesc("gmt_create");        // 按绑定时间倒序排列
        List<ShareFileDO> mappings = shareFileService.list(mappingQuery);

        // 关联表为空，直接返回空列表
        if (EmptyUtil.isEmpty(mappings)) {
            return List.of();
        }

        // 3. 从关联记录中提取所有文件ID并去重，准备批量查询文件详情
        List<Long> fileIds = mappings.stream()
                .map(ShareFileDO::getFileId)
                .distinct() // 防止关联表出现重复数据
                .toList();

        // 4. 批量查询用户文件主表，获取文件名、大小、更新时间等真实文件信息
        List<UserFileDO> fileRecords = userFileMapper.selectBatchIds(fileIds);
        if (EmptyUtil.isEmpty(fileRecords)) {
            return List.of();
        }

        // 5. 将文件记录转为「文件ID → 文件实体」的Map，方便后续快速匹配
        // 同时过滤掉已被用户逻辑删除的源文件，避免前端展示无效文件
        Map<Long, UserFileDO> fileRecordMap = fileRecords.stream()
                .filter(record -> Objects.equals(record.getDeleted(), DeleteEnum.NO.getCode()))
                .collect(Collectors.toMap(
                        UserFileDO::getId,
                        record -> record,
                        (left, right) -> left // 出现重复ID时保留前者，兜底防异常
                ));

        // 6. 按关联表顺序组装最终返回列表
        // 遍历关联记录，从Map中取出对应文件实体，过滤掉已删除的空数据，最后转为VO
        return mappings.stream()
                .map(mapping -> fileRecordMap.get(mapping.getFileId()))
                .filter(Objects::nonNull) // 源文件已删除则跳过
                .map(this::toShareFileInfoVO) // 实体转对外展示VO
                .toList();
    }

    @Override
    public void downloadShareFile(Long shareId, Long fileId, String shareCode, HttpServletResponse response) {
        // 1. 前置统一校验：复用通用校验逻辑，校验分享是否存在、未过期，私有分享则校验提取码
        ShareDO shareDO = assertShareAvailable(shareId);
        validateShareCodeIfNeeded(shareDO, shareCode);

        // 2. 校验「分享-文件」关联关系：确认该文件确实属于当前分享，防止用户篡改fileId越权下载其他分享的文件
        QueryWrapper<ShareFileDO> mappingQuery = Wrappers.query();
        mappingQuery.eq("share_id", shareId);
        mappingQuery.eq("file_id", fileId);
        mappingQuery.eq("deleted", DeleteEnum.NO.getCode());
        ShareFileDO mapping = shareFileService.getOne(mappingQuery);

        // 关联关系不存在/已删除，抛出「分享内文件不存在」异常
        if (mapping == null) {
            throw new ShareException(ShareErrorCode.SHARE_FILE_NOT_FOUND);
        }

        // 3. 校验用户逻辑文件状态
        UserFileDO userFileDO = userFileMapper.selectById(fileId);
        // 文件记录不存在、或已被用户逻辑删除，直接报错
        if (userFileDO == null || Objects.equals(userFileDO.getDeleted(), DeleteEnum.YES.getCode())) {
            throw new ShareException(ShareErrorCode.SHARE_FILE_NOT_FOUND);
        }
        // 文件夹不支持直接下载，与前端按钮禁用逻辑前后对应
        if (Objects.equals(userFileDO.getFolderFlag(), FOLDER_FLAG)) {
            throw new ShareException(ShareErrorCode.INVALID_ARGS);
        }

        // 4. 校验全局物理文件记录（网盘文件去重层：相同内容的文件全局只存一份物理实体）
        FileDO fileDO = fileMapper.selectById(userFileDO.getRealFileId());
        // 物理文件记录不存在、或已被删除，报错
        if (fileDO == null || Objects.equals(fileDO.getDeleted(), DeleteEnum.YES.getCode())) {
            throw new ShareException(ShareErrorCode.SHARE_FILE_NOT_FOUND);
        }

        // 5. 最终兜底校验：确认磁盘上的物理文件真实存在，避免数据库有记录但磁盘文件丢失的异常
        Path realFilePath = Paths.get(fileDO.getRealPath());
        if (!Files.exists(realFilePath)) {
            throw new ShareException(ShareErrorCode.SHARE_FILE_NOT_FOUND);
        }

        // 6. 预处理下载响应头：设置文件名、文件大小、内容类型，告诉浏览器这是下载文件
        prepareDownloadResponse(response, userFileDO.getFilename(), fileDO.getFileSize());

        // 7. 流式输出文件：读取磁盘文件流，直接写入HTTP响应输出流
        // try-with-resources 语法自动关闭输入输出流，无需手动释放资源
        try (InputStream inputStream = Files.newInputStream(realFilePath);
             OutputStream outputStream = response.getOutputStream()) {
            // 高效流拷贝：底层做了传输优化，无需手动读写缓冲区循环
            inputStream.transferTo(outputStream);
            outputStream.flush();
        } catch (IOException e) {
            // 流读写异常，包装为业务异常抛出
            throw new ShareException("Share file download failed", e, ShareErrorCode.SHARE_FILE_NOT_FOUND);
        }
    }


    /**
     * 组装分享主表ShareDO实体，填充创建分享所需全部字段
     * @param context 创建分享上下文（前端解密后的完整分享参数）
     * @return 填充完毕的分享主表实体
     */
    private ShareDO assembleShareInfo(CreateShareContext context) {
        // 雪花算法生成分享记录唯一主键ID
        Long shareId = IdUtil.get();
        // 根据前端传入的过期类型（永久/1天/7天/30天），解析出有效天数数字
        Integer shareDay = ShareDayTypeEnum.getDayByType(context.getShareDayType());
        // 初始化分享主表实体
        ShareDO shareDO = new ShareDO();
        // 设置分享主键
        shareDO.setId(shareId);
        // 有效分享天数
        shareDO.setShareDay(shareDay);
        // 获取当前登录用户ID，作为分享创建人
        shareDO.setCreateUser(UserIdUtil.get());
        // 分享类型：0公开分享 / 1带提取码分享（前面三元表达式转换而来）
        shareDO.setShareType(context.getShareType());
        // 过期类型标识（永久/1天/7天等枚举编码）
        shareDO.setShareDayType(context.getShareDayType());
        // 用户自定义分享名称
        shareDO.setShareName(context.getShareName());
        // 计算分享过期时间：shareDay < 0代表永久有效，过期时间设为null；否则当前日期往后顺延对应天数
        shareDO.setShareEndTime(shareDay < 0 ? null : DateUtil.offsetDay(new Date(), shareDay));
        // 分享状态：正常可用
        shareDO.setShareStatus(ShareStatusEnum.NORMAL.getCode());
        // 随机生成4/6位提取码（仅shareType=1时生效）
        shareDO.setShareCode(createShareCode());
        // 拼接完整分享访问链接，传入未加密的shareId，后续接口返回加密后的ID给前端
        shareDO.setShareUrl(buildShareUrl(shareId));
        return shareDO;
    }


    /**
     * 断言分享可用：校验分享是否存在、是否已过期
     * 校验不通过直接抛出业务异常，校验通过返回分享实体
     * @param shareId 分享真实主键ID
     * @return 分享实体对象
     */
    private ShareDO assertShareAvailable(Long shareId) {
        // 1. 根据ID查询分享主表记录
        ShareDO shareDO = getByShareId(shareId);
        // 2. 分享记录不存在，抛出「分享不存在」异常
        if (shareDO == null) {
            throw new ShareException(ShareErrorCode.SHARE_NOT_FOUND);
        }

        // 3. 非永久分享，且过期时间不为空，且过期时间早于当前时间 → 分享已过期
        if (!isPermanentShare(shareDO)
                && shareDO.getShareEndTime() != null
                && shareDO.getShareEndTime().before(new Date())) {
            throw new ShareException(ShareErrorCode.SHARE_EXPIRED);
        }
        // 4. 全部校验通过，返回分享实体
        return shareDO;
    }


    private boolean isPermanentShare(ShareDO shareDO) {
        if (Objects.equals(shareDO.getShareDay(), -1)) {
            return true;
        }
        String dayType = StringUtils.trimToEmpty(shareDO.getShareDayType()).toLowerCase();
        return "-1".equals(dayType) || dayType.contains("permanent") || dayType.contains("永久");
    }

    /**
     * 按需校验提取码：仅私有分享需要验证提取码，公开分享直接放行
     * @param shareDO 分享实体
     * @param shareCode 用户输入的提取码
     */
    private void validateShareCodeIfNeeded(ShareDO shareDO, String shareCode) {
        // 1. 不是私有分享（公开分享），直接返回，无需校验
        if (!Objects.equals(shareDO.getShareType(), PRIVATE_SHARE_TYPE)) {
            return;
        }
        // 2. 对用户输入的提取码做去空格归一化处理
        String normalizedCode = StringUtils.trimToEmpty(shareCode);
        // 3. 提取码为空，或与数据库真实提取码不匹配（不区分大小写），抛出「提取码错误」异常
        if (StringUtils.isBlank(normalizedCode)
                || !StringUtils.equalsIgnoreCase(shareDO.getShareCode(), normalizedCode)) {
            throw new ShareException(ShareErrorCode.SHARE_CODE_ERROR);
        }
    }


    private ShareFileInfoVO toShareFileInfoVO(UserFileDO record) {
        ShareFileInfoVO vo = new ShareFileInfoVO();
        vo.setFileId(record.getId());
        vo.setFilename(record.getFilename());
        vo.setFolderFlag(record.getFolderFlag());
        vo.setFileType(record.getFileType());
        vo.setFileSizeDesc(record.getFileSizeDesc());
        vo.setUpdateTime(record.getGmtModified());
        return vo;
    }

    /**
     * 准备文件下载的 HTTP 响应头
     * @param response HTTP响应对象
     * @param filename 下载时显示的文件名
     * @param fileSize 文件大小（字符串形式，单位字节）
     */
    private void prepareDownloadResponse(HttpServletResponse response, String filename, String fileSize) {
        // 1. 清空响应对象里之前设置的所有头信息和数据
        // 避免之前的过滤器、拦截器加了多余的内容，影响下载
        response.reset();

        // 2. 添加跨域响应头，允许前端跨域调用下载接口
        // 比如前端域名和后端接口域名不一样，不加这个浏览器会拦截下载响应
        HttpUtil.addCorsResponseHeaders(response);

        // 3. 设置响应内容类型：二进制流（通用文件类型）
        // APPLICATION_OCTET_STREAM = application/octet-stream
        // 告诉浏览器：这是二进制文件，不要解析成网页/图片，直接按下载处理
        response.addHeader("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        // 4. 文件名编码：解决中文文件名乱码问题
        // 先用 UTF-8 对文件名做 URL 编码，再把 "+" 替换成 "%20"
        // 原因：URLEncoder 会把空格转成 "+"，但浏览器下载时 "+" 会显示成加号，不是空格
        // 替换成 %20 才是标准的空格编码，所有浏览器都能正确识别
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");

        // 5. 设置下载文件名：兼容所有浏览器的双写法
        // Content-Disposition: attachment 代表「附件形式下载」，不是直接展示
        // filename="" 兼容旧版 IE、Edge
        // filename*=UTF-8''  是 RFC 标准写法，兼容 Chrome、Firefox、Safari 等现代浏览器
        response.setHeader(
                "Content-Disposition",
                "attachment;filename=\"" + encodedFilename + "\";filename*=UTF-8''" + encodedFilename
        );

        // 6. 设置文件总大小（可选，传了合法数字才设置）
        // 浏览器拿到 Content-Length 后，才能显示下载进度条、剩余时间
        if (StringUtils.isNumeric(fileSize)) {
            response.setContentLengthLong(Long.parseLong(fileSize));
        }
    }


    private String buildShareUrl(Long shareId) {
        String encryptedShareId = URLEncoder.encode(IdUtil.encrypt(shareId), StandardCharsets.UTF_8);
        if (shareAccessUrlPrefix.contains("{shareId}")) {
            return shareAccessUrlPrefix.replace("{shareId}", encryptedShareId);
        }
        if (shareAccessUrlPrefix.contains("?")) {
            String separator = shareAccessUrlPrefix.endsWith("?") || shareAccessUrlPrefix.endsWith("&") ? "" : "&";
            return shareAccessUrlPrefix + separator + "shareId=" + encryptedShareId;
        }
        return shareAccessUrlPrefix + "?shareId=" + encryptedShareId;
    }

    private String createShareCode() {
        return RandomStringUtils.randomAlphabetic(4).toLowerCase();
    }

    /**
     * 创建分享到期自动删除的延时任务
     * 核心作用：给有有效期的分享定一个“闹钟”，到点自动清理分享记录和关联数据
     * @param shareDO 分享主记录实体
     */
    private void scheduleShareExpireDeleteJob(ShareDO shareDO) {
        // ========== 前置判断：不需要定时删除的情况，直接退出 ==========
        // 三种情况不用删：分享记录为空 / 是永久分享 / 没有设置过期时间
        if (shareDO == null || isPermanentShare(shareDO) || shareDO.getShareEndTime() == null) {
            return;
        }

        // ========== 计算延时时间：还有多少毫秒到期 ==========
        // 过期时间戳 - 当前时间戳 = 距离到期还有多少毫秒
        // Math.max 保证最少延时1秒，避免过期时间已过、出现负数导致异常
        final long delayInMillis = Math.max(1000L, shareDO.getShareEndTime().getTime() - System.currentTimeMillis());

        // ========== 封装删除任务的消息体 ==========
        // 把要删除的分享ID打包成消息对象，延时队列到点后，拿着这个ID去删数据
        DeleteShareInfoMessage message = new DeleteShareInfoMessage();
        message.setShareId(shareDO.getId());

        // ========== 定义入队操作：把任务放进延时队列 ==========
        // Runnable：你可以理解成「一段打包好的代码」，现在不执行，等合适的时机再调用 run() 执行。这里面包的逻辑就是「把删除任务放进延时队列」。
//        delayQueueHolder.addJob(四个参数)：把任务放进延时队列的核心方法，四个参数分别是：
//            message：要执行的任务内容（删哪个分享）
//            delayInMillis：延时多久执行
//            TimeUnit.MILLISECONDS：时间单位是毫秒
//            队列名称：指定放到哪个延时队列里（项目里可能有多个不同业务的延时队列，用名字区分）
        Runnable enqueue = () -> delayQueueHolder.addJob(
                message,                  // 任务消息体
                delayInMillis,            // 延时多久执行
                TimeUnit.MILLISECONDS,    // 时间单位：毫秒
                ShareConstant.DELETE_SHARE_INFO_DELAY_QUEUE_NAME // 延时队列的名称
        );

        // ========== 核心设计：事务提交成功后，再把任务放进队列 ==========
        // 判断当前是不是正处在事务中。创建分享的方法有事务，所以这里会返回 true。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 有事务：注册一个事务同步器，等事务提交成功之后，再执行入队操作
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 事务提交成功 → 分享记录已经真实存在数据库了 → 再加延时任务
                    enqueue.run();
                }
            });
            return;
        }

        // 没有事务：直接把任务放进延时队列
        enqueue.run();
    }


    /**
     * 移除分享到期自动删除延时任务
     * 手动取消分享时，清理对应的延时队列任务，避免到期重复删除
     * @param shareId 分享主键
     */
    private void removeShareExpireDeleteJob(Long shareId) {
        // 分享ID为空直接退出，不执行操作
        if (shareId == null) {
            return;
        }
        // 封装延时消息载体，携带要删除的分享ID
        DeleteShareInfoMessage message = new DeleteShareInfoMessage();
        message.setShareId(shareId);
        try {
            // 根据消息、队列名称，从延时队列中移除这条定时删除任务
            delayQueueHolder.removeJob(message, ShareConstant.DELETE_SHARE_INFO_DELAY_QUEUE_NAME);
        } catch (Exception e) {
            // 移除失败仅打印警告日志，不抛出异常打断取消分享主流程
            log.warn("remove delayed share delete message failed, shareId={}", shareId, e);
        }
    }

}
