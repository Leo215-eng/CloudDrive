package com.disk.share.controller;

import com.disk.base.utils.IdUtil;
import com.disk.base.utils.UserIdUtil;
import com.disk.share.domain.context.CreateShareContext;
import com.disk.share.domain.context.DeleteShareContext;
import com.disk.share.domain.entity.convertor.ShareConvertor;
import com.disk.share.domain.entity.ShareDO;
import com.disk.share.domain.request.CreateShareParamVO;
import com.disk.share.domain.request.DeleteShareParamVO;
import com.disk.share.domain.request.ShareCodeCheckParamVO;
import com.disk.share.domain.response.ShareFileInfoVO;
import com.disk.share.domain.response.UserShareInfoVO;
import com.disk.share.domain.service.ShareService;
import com.disk.share.exception.ShareErrorCode;
import com.disk.web.annotation.LoginIgnore;
import com.disk.web.vo.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/shares")
public class UserShareController {

    @Autowired
    private ShareService shareService;

    @Autowired
    private ShareConvertor shareConvertor;

    @GetMapping("/list")
    public Result<List<UserShareInfoVO>> listUserShares() {
        List<ShareDO> list = shareService.listUserShareInfos(UserIdUtil.get());
        List<UserShareInfoVO> shareInfoVOList = shareConvertor.mapToVoList(list);
        return Result.success(shareInfoVOList);
    }

    @GetMapping("/share")
    public Result<UserShareInfoVO> getUserShare(@RequestParam("shareId") String shareId) {
        Long id = IdUtil.decrypt(shareId);
        ShareDO shareDO = shareService.getByShareId(id);
        if (shareDO == null) {
            return Result.error(ShareErrorCode.SHARE_NOT_FOUND);
        }
        UserShareInfoVO infoVO = shareConvertor.mapToVo(shareDO);
        return Result.success(infoVO);
    }

    // @LoginIgnore：标记该接口免登录校验，跳过全局登录拦截器
    // 设计原因：分享链接对外公开，匿名用户无需登录即可访问基础信息
    @LoginIgnore
    @GetMapping("/share/simple")
    public Result<UserShareInfoVO> getSimpleShare(@RequestParam("shareId") String shareId) {
        // 1. 解密前端传入的加密shareId，还原数据库真实Long型主键
        Long id = IdUtil.decrypt(shareId);
        // 2. 根据主键查询share分享主表的完整记录
        ShareDO shareDO = shareService.getByShareId(id);
        // 3. 分享记录不存在（已删除/无效ID），返回「分享不存在」业务错误码
        if (shareDO == null) {
            return Result.error(ShareErrorCode.SHARE_NOT_FOUND);
        }
        // 4. 将数据库DO实体转换为前端展示用的VO对象
        UserShareInfoVO infoVO = shareConvertor.mapToVo(shareDO);
        // 5. 安全脱敏：手动清空提取码字段，绝不向匿名访客返回真实提取码
        infoVO.setShareCode(null);
        // 6. 返回脱敏后的分享基础信息
        return Result.success(infoVO);
    }


    @LoginIgnore
    @PostMapping("/share/code/check")
    public Result<Boolean> checkShareCode(@Validated @RequestBody ShareCodeCheckParamVO checkParam) {
        Long shareId = IdUtil.decrypt(checkParam.getShareId());
        return Result.success(shareService.checkShareCode(shareId, checkParam.getShareCode()));
    }

    @LoginIgnore
    @GetMapping("/share/files")
    public Result<List<ShareFileInfoVO>> listShareFiles(@RequestParam("shareId") String shareId,
                                                         @RequestParam(value = "shareCode", required = false) String shareCode) {
        Long id = IdUtil.decrypt(shareId);
        List<ShareFileInfoVO> files = shareService.listShareFiles(id, shareCode);
        return Result.success(files);
    }

    @LoginIgnore
    @GetMapping("/share/file/download")
    public void downloadShareFile(@RequestParam("shareId") String shareId,
                                  @RequestParam("fileId") String fileId,
                                  @RequestParam(value = "shareCode", required = false) String shareCode,
                                  HttpServletResponse response) {
        shareService.downloadShareFile(IdUtil.decrypt(shareId), IdUtil.decrypt(fileId), shareCode, response);
    }

    /**
     * 创建文件分享接口
     * @param createShareParam 前端提交分享参数（分享类型、提取码、过期时间、选中文件id数组等）
     * @return 加密后的分享ID，作为前端访问分享链接唯一标识
     */
    @PostMapping("/share")
    public Result<String> addUserShare(@Validated @RequestBody CreateShareParamVO createShareParam) {
        // 打印前端传来的完整分享参数日志，方便排查问题
        log.info("createShareParam: [{}]", createShareParam.toString());
        // 前端VO参数转换为业务层专用上下文实体，统一内部字段格式
        CreateShareContext context = shareConvertor.createShareParamToCreateShareContext(createShareParam);
        // 调用业务层新增分享主记录+关联文件
//        share_file是分享主表share和物理文件表file之间的多对多关联中间表，
//        业务逻辑是一条分享可以勾选多个文件，同一个文件也能被多次创建不同分享，无法只用单表外键存储这种多对多关系；
        Long shareId = shareService.addUserShareInfo(context);
        // 对分享主键ID加密后返回前端，避免暴露数据库自增ID，安全防护
        return Result.success(IdUtil.encrypt(shareId));
    }


    /**
     * 取消分享接口：逻辑删除分享主表、关联文件中间表，清除过期定时任务
     */
    @DeleteMapping("/share")
    public Result<String> deleteUserShare(@Validated @RequestBody DeleteShareParamVO deleteShareParam) {
        // 入参VO转业务上下文对象
        DeleteShareContext context = shareConvertor.deleteParamToDeleteContext(deleteShareParam);
        // 调用业务层执行取消分享逻辑
        shareService.deleteUserShare(context);
        // 返回加密分享ID
        return Result.success(deleteShareParam.getShareId());
    }

}
