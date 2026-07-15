package com.disk.files.facade;

import com.disk.api.files.request.UserFileOperateReqest;
import com.disk.api.files.request.UserFileQueryRequest;
import com.disk.api.files.request.condition.UserRootFolderQueryCondition;
import com.disk.api.files.response.UserFileOperateResponse;
import com.disk.api.files.response.UserFileQueryResponse;
import com.disk.api.files.response.data.UserFileData;
import com.disk.api.files.service.UserFileFacadeService;
import com.disk.api.user.response.data.UserInfo;
import com.disk.files.domain.context.CreateFolderContext;
import com.disk.files.domain.entity.UserFileDO;
import com.disk.files.domain.entity.convertor.FileConvertor;
import com.disk.files.domain.service.UserFileService;
import com.disk.files.infrastructure.enums.FolderFlagEnum;
import com.disk.rpc.facade.Facade;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 类描述: TODO
 *
 * @author weikunkun
 */
@DubboService(version = "1.0.0")
public class UserFileFacadeImpl implements UserFileFacadeService {

    @Autowired
    private UserFileService userFileService;

    @Autowired
    private FileConvertor fileConvertor;


    @Facade
    @Override
    public UserFileQueryResponse<UserFileData> getUserFileInfo(UserFileQueryRequest request) {
        UserFileDO userFileDO = switch (request.getQueryCondition()) {
            case UserRootFolderQueryCondition queryCondition:
                yield userFileService.getUserRootInfo(queryCondition.getUserId(), FolderFlagEnum.YES);
            default:
                throw new UnsupportedOperationException(request.getQueryCondition() + "'' is not supported");
        };

        UserFileQueryResponse<UserFileData> response = new UserFileQueryResponse();
        response.setSuccess(true);
        UserFileData userFileData = fileConvertor.userFileDOToUserFileData(userFileDO);
        response.setData(userFileData);
        return response;
    }

    @Facade//门面注解，分层开发规范注解：标识当前类是门面层 Facade，介于业务注册层和底层文件 userFileService 之间；
// 入参：UserFileOperateReqest 上层传过来的创建文件夹请求DTO
    @Override
    public UserFileOperateResponse<Long> createUserRootFile(UserFileOperateReqest request) {
        // 1. 创建上下文对象 CreateFolderContext（领域上下文，内部业务传参载体）
        CreateFolderContext createFolderContext = new CreateFolderContext();
        // 把外层请求DTO的参数，转存到内部业务上下文
        createFolderContext.setFolderName(request.getName());
        createFolderContext.setUserId(request.getUserId());
        createFolderContext.setParentId(request.getParentId());

        // 2. 调用底层文件核心service，真正执行创建文件夹逻辑，返回新建文件夹ID
        Long folder = userFileService.createFolder(createFolderContext);

        // 3. 实例化统一包装响应对象（就是前面讲的外层包裹对象）
        UserFileOperateResponse<Long> response = new UserFileOperateResponse();
        // 标记接口执行成功
        response.setSuccess(true);
        // 将生成的文件夹ID塞入响应的data中
        response.setData(folder);
        // 返回包装好的标准响应给上层注册业务
        return response;
    }

}
