package com.disk.files.controller;

import com.disk.base.constant.BaseConstant;
import com.disk.base.utils.IdUtil;
import com.disk.base.utils.UserIdUtil;
import com.disk.files.domain.context.CopyFileContext;
import com.disk.files.domain.context.CreateFolderContext;
import com.disk.files.domain.context.DeleteUserFileContext;
import com.disk.files.domain.context.FileChunkMergeContext;
import com.disk.files.domain.context.FileChunkUploadContext;
import com.disk.files.domain.context.FileDownloadContext;
import com.disk.files.domain.context.FilePreviewContext;
import com.disk.files.domain.context.FileSearchContext;
import com.disk.files.domain.context.QueryBreadcrumbsContext;
import com.disk.files.domain.context.QueryFolderTreeContext;
import com.disk.files.domain.context.QueryUploadedChunksContext;
import com.disk.files.domain.context.TransferFileContext;
import com.disk.files.domain.request.CopyFileParamVO;
import com.disk.files.domain.request.FileSearchParamVO;
import com.disk.files.domain.request.TransferFileParamVO;
import com.disk.files.domain.context.UploadFileContext;
import com.disk.files.domain.context.QueryFileContext;
import com.disk.files.domain.context.SecUploadFileContext;
import com.disk.files.domain.context.UpdateFilenameContext;
import com.disk.files.domain.entity.UserFileDO;
import com.disk.files.domain.entity.convertor.FileConvertor;
import com.disk.files.domain.request.CreateFolderParamVO;
import com.disk.files.domain.request.DeleteFileParamVO;
import com.disk.files.domain.request.FileChunkMergeParamVO;
import com.disk.files.domain.request.FileChunkUploadParamVO;
import com.disk.files.domain.request.FileUploadParamVO;
import com.disk.files.domain.request.QueryUploadedChunkListParamVO;
import com.disk.files.domain.request.SecUploadFileParamVO;
import com.disk.files.domain.request.UpdateFilenameParamVO;
import com.disk.files.domain.response.BreadcrumbVO;
import com.disk.files.domain.response.FileChunkUploadVO;
import com.disk.files.domain.response.FileSearchVO;
import com.disk.files.domain.response.FolderTreeNodeVO;
import com.disk.files.domain.response.HomeOverviewVO;
import com.disk.files.domain.response.UploadedChunkListVO;
import com.disk.files.domain.response.UserFileVO;
import com.disk.files.domain.service.UserFileService;
import com.disk.files.infrastructure.constant.FileConstant;
import com.disk.base.enums.DeleteEnum;
import com.disk.web.vo.Result;
import com.google.common.base.Splitter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.disk.files.exception.FilesErrorCode.FILE_NOT_EXIT;

/**
 * 类描述: 文件相关Controller
 *
 * @author weikunkun
 */
@Validated
@RestController
@RequestMapping("/api/v1/files")
public class UserFileController {

    @Autowired
    private UserFileService userFileService;
    
    @Autowired
    private FileConvertor fileConvertor;

    @GetMapping("/home/overview")
    public Result<HomeOverviewVO> homeOverview() {
        return Result.success(userFileService.getHomeOverview(UserIdUtil.get()));
    }

    /**
     * 查询文件列表
     */
    /**
     * 查询指定目录下的文件/文件夹列表接口
     * @param parentId 父文件夹加密ID，非必传，不传代表根目录
     * @param fileTypes 文件类型筛选，默认查询全部类型文件
     * @return 当前目录下文件列表VO
     */
    @GetMapping("/folders-files")
    public Result<List<UserFileVO>> list(
            // 父文件夹加密ID，非必填
            @RequestParam(value = "parentId", required = false) String parentId,
            // 文件类型筛选字符串，非必填，默认常量代表全部文件类型
            @RequestParam(value = "fileTypes", required = false, defaultValue = FileConstant.ALL_FILE_TYPE) String fileTypes
    ) {
        // 初始化真实父目录ID为-1（代表根目录）
        Long realParentId = -1L;
        // 判断是否指定了文件类型筛选（不是"全部文件"的情况）
        if (!Objects.equals(FileConstant.ALL_FILE_TYPE, parentId)) {
            // 将前端加密的文件夹ID解密为数据库真实主键Long
            realParentId = IdUtil.decrypt(parentId);
        }

        // 存放拆分后的文件类型数字集合
        List<Integer> fileTypeArray = null;
        // 判断是否指定了文件类型筛选（非全部文件）
        if (!Objects.equals(FileConstant.ALL_FILE_TYPE, fileTypes)) {
            // 根据逗号分割字符串，转为Integer类型集合
            // 把 "1,2,3" 这样的字符串 → 拆成 [1, 2, 3] 这样的数字列表
            fileTypeArray = Arrays.stream(fileTypes.split(BaseConstant.COMMA)) // ① 拆成流
                    .map(Integer::valueOf)// ② 转数字
                    .collect(Collectors.toList()); // ③ 收集成列表
        }

        // 封装文件查询上下文对象，统一承载查询条件
        QueryFileContext request = new QueryFileContext();
        request.setParentId(realParentId);          // 设置解密后的父目录ID
        request.setFileTypeArray(fileTypeArray);    // 设置文件类型筛选数组
        request.setUserId(UserIdUtil.get());        // 从线程上下文获取当前登录用户ID
        request.setDeleted(DeleteEnum.NO.getCode());// 只查询未删除的正常文件

        // 调用业务层查询当前用户对应目录下的文件数据库实体
        List<UserFileDO> result = userFileService.getUserFileList(request);
        // DO数据库实体转换为前端展示VO，过滤敏感字段
        List<UserFileVO> userFileVOList = fileConvertor.mapToVo(result);
        // 封装统一成功响应返回前端
        return Result.success(userFileVOList);
    }

    /**
     * 文件夹创建
     */
    @PostMapping("/folder")
    // @Validated    开启 VO 参数校验。
    //在 CreateFolderParamVO 实体类上的注解（@NotBlank、长度校验、非法字符校验）会生效，
    // 如果校验不通过直接抛出异常，不会往下执行业务代码，返回错误信息给前端。
    public Result<String> createFolder(@Validated @RequestBody CreateFolderParamVO createFolder) {
    //     CreateFolderParamVO createFolder  VO（视图层对象），专门用来接收前端的 JSON 参数，内部属性：
    //    parentId：父文件夹 id
    //    folderName：文件夹名字
        CreateFolderContext context = fileConvertor.createFolderParamToCreateFolderContext(createFolder);
        Long fileId = userFileService.createFolder(context);
    //    IdUtil.encrypt(fileId)：对数据库原生 Long 型 id 做加密。
    //    数据库存明文数字 id；
    //    前后端交互一律使用加密后的字符串 ID，避免 id 泄露被爬虫遍历、越权访问。
    //    包装成统一返回体 Result.success，返回给前端。
        return Result.success(IdUtil.encrypt(fileId));
    }

    /**
     * 文件重命名接口
     * @param updateFilenameParam 前端传参VO：加密fileId、newFilename
     * @return 统一成功返回体，无返回数据
     */
    @PutMapping("/file")
    public Result updateFilename(@Validated @RequestBody UpdateFilenameParamVO updateFilenameParam) {
        // 转换器：前端VO对象 转为 Service层使用的上下文对象，补充当前登录用户ID等后端信息
        UpdateFilenameContext context = fileConvertor.updateFilenameParamToUpdateFilenameContext(updateFilenameParam);
        // 调用业务层执行重命名逻辑
        userFileService.updateFilename(context);
        // 无返回数据，直接返回成功标识
        return Result.success();
    }



    /**
     * 批量删除文件
     */
    /**
     * 批量删除文件/文件夹接口
     * 请求方式：DELETE 符合REST规范，代表删除资源
     * @param deleteFileParam 前端传入的批量删除参数VO，内部包含 fileIds 加密ID字符串数组
     * @return 统一成功返回体，无业务数据
     */
    @DeleteMapping("/file")
    public Result deleteFile(@Validated @RequestBody DeleteFileParamVO deleteFileParam) {
        // 1. 对象转换器：前端VO → 业务上下文DeleteUserFileContext，基础封装参数、注入登录用户等信息
        DeleteUserFileContext context = fileConvertor.deleteFileParamToDeleteFileContext(deleteFileParam);

        // 2. Stream流式处理：对前端传来的加密ID数组做解密、去重
        List<Long> fileIdList = deleteFileParam.getFileIds()
                // 遍历每一个加密ID字符串
                .stream()
                // 调用工具解密：加密字符串 → 数据库明文Long主键
                .map(IdUtil::decrypt)
                // 去重：防止前端重复传同一个ID，避免重复删除
                .distinct()
                // 收集处理后的明文ID，转为Long集合
                .collect(Collectors.toList());

        // 3. 将解密、去重后的明文ID列表存入上下文，供Service层使用
        context.setFileIdList(fileIdList);

        // 4. 调用业务层执行批量删除核心逻辑
        userFileService.deleteFile(context);

        // 5. 全部逻辑执行完成，返回统一成功响应
        return Result.success();
    }

    /**
     * 文件秒传
     */
    @PostMapping("/file/sec-upload")
    public Result secUpload(@Validated @RequestBody SecUploadFileParamVO secUploadFileParam) {
        // 前端参数转换成业务上下文，同时会解密 parentId、补充当前 userId
        SecUploadFileContext context = fileConvertor.secUploadFileParamToSecUploadFileContext(secUploadFileParam);

        // 调用业务层判断是否能秒传
        boolean result = userFileService.secUpload(context);

        if (!result) {
            // 秒传未命中，返回 FILE_NOT_EXIT，前端把它当成正常分支继续上传
            return Result.error(FILE_NOT_EXIT.getCode(), FILE_NOT_EXIT.getMessage());
        }

        // 秒传命中
        return Result.success("");
    }
//
    /**
     * 单文件上传
     */
    @PostMapping("/file/upload")
    public Result upload(@Validated FileUploadParamVO fileUploadParam) {
        UploadFileContext context = fileConvertor.fileUploadParamToFileUploadContext(fileUploadParam);
        userFileService.upload(context);
        return Result.success("");
    }


    /**
     * 分片上传提交接口 POST
     * 接收前端每一块分片二进制数据，保存单块分片到临时存储
     * @param fileChunkUploadParam 前端提交的分片参数（分片序号、文件MD5、二进制文件、文件名等）
     * @return 封装返回VO，携带mergeFlag标记：是否所有分片上传完成需要合并
     */
    @PostMapping("/file/chunk-upload")
    public Result<FileChunkUploadVO> chunkUpload(@Validated FileChunkUploadParamVO fileChunkUploadParam) {
        // 1. 参数转换器：前端VO转后端业务上下文对象，统一内部入参格式
        FileChunkUploadContext context = fileConvertor.fileChunkUploadParamToFileChunkUploadContext(fileChunkUploadParam);
        // 2. 调用业务层，保存当前这块分片，返回状态码
        Integer code = userFileService.chunkUpload(context);
        // 3. 组装返回实体，把是否需要合并的标识丢给前端
        FileChunkUploadVO vo = new FileChunkUploadVO();
        vo.setMergeFlag(code);
        // 统一返回标准成功响应体
        return Result.success(vo);
    }

    /**
     * 查询已上传分片接口 GET
     * 断点续传核心接口：上传分片前，先调用此接口，查询该MD5文件哪些分片已经上传成功
     * @param queryUploadedChunkListParam 前端传入文件MD5标识
     * @return 返回已上传完成的分片编号数组 uploadedChunks
     */
    @GetMapping("/file/chunk-upload")
    public Result<UploadedChunkListVO> getUploadedChunks(@Validated QueryUploadedChunkListParamVO queryUploadedChunkListParam) {
        // 参数转换，前端查询参数转业务上下文
        QueryUploadedChunksContext context = fileConvertor.queryUploadedChunksParam2QueryUploadedChunksContext(queryUploadedChunkListParam);
        // 业务层查询数据库，拿到已上传分片编号集合
        List<Integer> uploadedChunkList = userFileService.getUploadedChunkList(context);
        // 封装返回给前端
        UploadedChunkListVO uploadedChunkListVO = new UploadedChunkListVO();
        uploadedChunkListVO.setUploadedChunks(uploadedChunkList);
        return Result.success(uploadedChunkListVO);
    }


    /**
     * 文件分片合并
     */
    @PostMapping("/file/merge")
    public Result mergeFile(@Validated @RequestBody FileChunkMergeParamVO fileChunkMergeParam) {
        FileChunkMergeContext context = fileConvertor.fileChunkMergeParamVOToFileChunkMergeContext(fileChunkMergeParam);
        userFileService.mergeFile(context);
        return Result.success("");
    }

    /**
     * 文件下载
     */
    /**
     * 文件下载接口
     * 请求方式GET，接收前端加密文件ID，流式输出文件二进制流触发浏览器下载
     * @param fileId 前端URL传递的加密文件唯一ID字符串
     * @param response Spring自动注入的原生HTTP响应对象，用于设置下载响应头、输出文件字节流
     */
    @GetMapping("/file/download")
    public void download(String fileId, HttpServletResponse response) {
        // 实例化下载业务上下文，承载本次下载全部业务参数，传递给Service层
        FileDownloadContext context = new FileDownloadContext();
        // 解密前端加密ID，转换为数据库存储的明文Long主键
        context.setFileId(IdUtil.decrypt(fileId));
        // 将响应对象存入上下文，Service层操作输出流、设置下载头部
        context.setResponse(response);
        // 工具类从登录线程上下文获取当前登录用户ID，用于文件归属权限校验
        context.setUserId(UserIdUtil.get());

        // 调用业务层执行下载核心逻辑：权限校验、读取文件、流式输出二进制
        userFileService.download(context);
    }


    /**
     * 文件预览
     */
    /**
     * 文件预览接口
     * GET /file/preview
     * 直接向响应输出文件二进制流，不返回JSON
     * @param fileId 前端传递加密后的文件ID字符串
     * @param response Http响应对象，用来输出文件二进制流、设置响应头
     */
    @GetMapping("/file/preview")
    public void preview(
            // 参数校验：fileId不能为空，为空直接抛出参数异常
            @NotBlank(message = "文件ID不能为空")
            @RequestParam(value = "fileId", required = false)
            String fileId,
            // 注入原生HttpServletResponse，用于输出文件字节流到前端
            HttpServletResponse response
    ) {
        // 构建预览业务上下文载体
        FilePreviewContext context = new FilePreviewContext();
        // 1. 解密加密文件ID，转为数据库明文Long主键
        context.setFileId(IdUtil.decrypt(fileId));
        // 2. 把响应对象存入上下文，service层用来写二进制流、设置header
        // 图片 image/png、视频 video/mp4、文本 text/plain
        context.setResponse(response);
        // 3. 工具类从登录上下文获取当前操作用户ID，用于权限校验
        context.setUserId(UserIdUtil.get());
        // 调用业务层执行预览逻辑：权限校验、读取文件二进制、输出到response
        userFileService.preview(context);
    }

    /**
     * 查询文件夹树
     */
    @GetMapping("/file/folder/tree")
    public Result<List<FolderTreeNodeVO>> getFolderTree() {
        QueryFolderTreeContext context = new QueryFolderTreeContext();
        context.setUserId(UserIdUtil.get());
        List<FolderTreeNodeVO> result = userFileService.getFolderTree(context);
        return Result.success(result);
    }

    /**
     * 文件转移
     */
    /**
     * 文件转移接口：把选中的多个文件/文件夹移动到目标文件夹
     */
    @PostMapping("/file/transfer")
    public Result transfer(@Validated @RequestBody TransferFileParamVO transferFileParam) {
        // 1. 前端传加密后的文件ID数组，循环解密转为真实Long主键
//        IdUtil.decrypt 前后端分离安全设计，前端不直接传数据库真实 ID，全部加密传输，后端解密拿到真实主键；
        List<Long> fileIdList = transferFileParam.getFileIds()
                .stream()
                .map(IdUtil::decrypt)
                .collect(Collectors.toList());

        // 2. 获取目标文件夹加密ID
        String targetParentId = transferFileParam.getTargetParentId();

        // 3. 封装转移上下文载体，统一存放本次操作全部参数
        TransferFileContext context = new TransferFileContext();
        context.setFileIdList(fileIdList);
        // 解密目标文件夹ID
        context.setTargetParentId(IdUtil.decrypt(targetParentId));
        // 获取当前登录用户ID
        context.setUserId(UserIdUtil.get());

        // 4. 调用业务层执行文件移动逻辑
        userFileService.transfer(context);
        // 5. 无返回数据，返回成功提示
        return Result.success("");
    }


    /**
     * 文件复制
     */
    /**
     * 文件复制接口：批量复制文件/文件夹到目标目录
     */
    @PostMapping("/file/copy")
    public Result copy(@Validated @RequestBody CopyFileParamVO copyFilePO) {
        // 前端传逗号分隔的加密ID字符串，不是数组
        String fileIds = copyFilePO.getFileIds();
        // 目标文件夹加密ID
        String targetParentId = copyFilePO.getTargetParentId();

        // 1. 按分隔符切割字符串 → 字符串列表 → 循环解密为真实Long主键
        List<Long> fileIdList = Splitter.on(BaseConstant.COMMON_SEPARATOR)
                .splitToList(fileIds)
                .stream()
                .map(IdUtil::decrypt)
                .collect(Collectors.toList());

        // 2. 封装复制操作上下文，统一承载所有参数
        CopyFileContext context = new CopyFileContext();
        context.setFileIdList(fileIdList);
        context.setTargetParentId(IdUtil.decrypt(targetParentId));
        context.setUserId(UserIdUtil.get()); // 当前登录用户

        // 3. 调用业务层执行复制逻辑
        userFileService.copy(context);
        return Result.success("");
    }


    /**
     * 文件搜索 ES
     */
    /**
     * 文件搜索接口（基于ES实现）
     * 请求方式：POST
     * 请求路径：/file/search
     * @param fileSearchParam 前端传的搜索参数（关键词、文件类型过滤）
     * @return 搜索结果列表
     */
    @PostMapping("/file/search")
    public Result<List<FileSearchVO>> search(@Validated @RequestBody FileSearchParamVO fileSearchParam) {
        // 1. 创建业务上下文对象：把前端参数转成后端业务层用的上下文，统一传递参数
        FileSearchContext context = new FileSearchContext();
        // 搜索关键词
        context.setKeyword(fileSearchParam.getKeyword());
        // 当前登录用户ID：从登录上下文取，保证只能搜自己的文件（用户数据隔离）
        context.setUserId(UserIdUtil.get());

        // 2. 处理文件类型过滤参数
        String fileTypes = fileSearchParam.getFileTypes();
        // 判断：文件类型参数不为空，且不是“全部文件”的标识
        if (StringUtils.isNotBlank(fileTypes) && !Objects.equals(FileConstant.ALL_FILE_TYPE, fileTypes)) {
            // 前端传的是逗号分隔的字符串（比如 "1,2,3"），先按逗号拆分，再转成Integer类型的列表
            // Splitter 是 Guava 工具类，专门用来拆分字符串
            List<Integer> fileTypeArray = Splitter.on(BaseConstant.COMMA)
                    .splitToList(fileTypes)
                    .stream()
                    .map(Integer::valueOf)
                    .collect(Collectors.toList());
            // 把文件类型列表放进上下文
            context.setFileTypeArray(fileTypeArray);
        }

        // 3. 调用业务层执行搜索
        List<FileSearchVO> result = userFileService.search(context);

        // 4. 封装统一返回结果给前端
        return Result.success(result);
    }


    /**
     * 查询面包屑列表
     *  用于导航
     */
    @GetMapping("/file/breadcrumbs")
//    @RequestParam：Spring MVC 注解，作用是把 URL 里的请求参数 fileId 绑定到方法入参 fileId 上
//    value = "fileId"：指定前端传的参数名，和入参变量名一致。
    public Result<List<BreadcrumbVO>> getBreadcrumbs(@NotBlank(message = "文件ID不能为空") @RequestParam(value = "fileId", required = false) String fileId) {
//      QueryBreadcrumbsContext 是自定义的查询上下文类，用来打包本次查询需要的所有条件（文件 ID、用户 ID 等）。
//      设计目的：避免方法入参写一长串零散参数，后续新增查询条件时，只需要给这个类加字段，不用修改方法签名，扩展性和可读性更好。
        QueryBreadcrumbsContext context = new QueryBreadcrumbsContext();
//      IdUtil.decrypt(fileId)：调用 ID 工具类，对前端传过来的 fileId 进行解密。
        context.setFileId(IdUtil.decrypt(fileId));
        context.setUserId(UserIdUtil.get());
        List<BreadcrumbVO> result = userFileService.getBreadcrumbs(context);
        return Result.success(result);
    }

}
