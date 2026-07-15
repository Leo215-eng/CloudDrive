package com.disk.auth.domain.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.disk.api.files.request.UserFileOperateReqest;
import com.disk.api.files.response.UserFileOperateResponse;
import com.disk.api.files.service.UserFileFacadeService;
import com.disk.api.user.request.UserQueryRequest;
import com.disk.api.user.request.UserRegisterRequest;
import com.disk.api.user.response.UserOperatorResponse;
import com.disk.api.user.response.UserQueryResponse;
import com.disk.api.user.response.data.UserInfo;
import com.disk.api.user.service.UserFacadeService;
import com.disk.auth.domain.context.LoginContext;
import com.disk.auth.domain.context.RegisterContext;
import com.disk.auth.domain.service.AuthService;
import com.disk.auth.exception.AuthErrorCode;
import com.disk.auth.exception.AuthException;
import com.disk.auth.infrastructure.AuthConstant;
import com.disk.base.constant.BaseConstant;
import com.disk.base.utils.EmptyUtil;
import com.disk.base.utils.IdUtil;
import com.disk.base.utils.JWTUtil;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现类
 * 处理用户登录、注册、登出等核心认证业务逻辑
 *
 * @author weikunkun
 */
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * Redis操作模板，用于存储用户登录态
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Dubbo远程调用 - 用户服务接口
     * 版本号：1.0.0
     */
    @DubboReference(version = "1.0.0")
    private UserFacadeService userFacadeService;

    /**
     * Dubbo远程调用 - 用户文件服务接口
     * 版本号：1.0.0
     */
    @DubboReference(version = "1.0.0")
    private UserFileFacadeService userFileFacadeService;

    /**
     * 用户登录逻辑
     * 1. 根据邮箱查询用户信息
     * 2. 验证用户是否存在
     * 3. 生成登录token并存储到Redis
     * 4. 返回登录token
     *
     * @param loginContext 登录上下文（包含邮箱等登录信息）
     * @return 登录成功后的访问令牌
     * @throws AuthException 用户不存在时抛出异常
     */
    @Override
    public String login(LoginContext loginContext) {
        // 构建用户查询请求对象（根据邮箱查询）
        UserQueryRequest userQueryRequest = new UserQueryRequest(loginContext.getEmail());
        // 调用用户服务查询用户信息
        UserQueryResponse<UserInfo> userQueryResponse = userFacadeService.query(userQueryRequest);
        UserInfo userInfo = userQueryResponse.getData();

        // 用户不存在则抛出异常
        if (EmptyUtil.isEmpty(userInfo)) {
            throw new AuthException(AuthErrorCode.USER_NOT_EXIST);
        }

        // Sa-Token登录（记录用户登录状态）
        StpUtil.login(userInfo.getUserId());
        // 生成JWT令牌（有效期1天）
        String accessToken = JWTUtil.generateToken(
                userInfo.getNickName(),
                BaseConstant.LOGIN_USER_ID,
                userInfo.getUserId(),
                AuthConstant.ONE_DAY_TIME_MILLS
        );
        // 将token存入Redis（key：登录前缀+用户ID）
        stringRedisTemplate.opsForValue().set(BaseConstant.USER_LOGIN_PREFIX + userInfo.getUserId(), accessToken);

        return accessToken;
    }

    /**
     * 用户注册逻辑
     * 注意：需添加事务控制，避免用户信息和根文件信息数据不一致
     * 1. 验证注册上下文非空
     * 2. 调用用户服务完成用户注册
     * 3. 为新用户创建根文件目录
     * 4. 返回新用户ID
     *
     * @param registerContext 注册上下文（包含邮箱、密码、昵称等信息）
     * @return 注册成功后的用户ID
     * @throws AuthException 注册失败（用户注册/根文件创建失败）时抛出异常
     */
    @Override
    public Long register(RegisterContext registerContext) {
        // 注册上下文为空则抛出异常
        if (registerContext == null) {
            throw new AuthException(AuthErrorCode.USER_REGISTER_FAIL);
        }

        // 构建用户注册请求对象
        UserRegisterRequest userRegisterRequest = new UserRegisterRequest();
        userRegisterRequest.setEmail(registerContext.getEmail());
        userRegisterRequest.setPassword(registerContext.getPassword());
        userRegisterRequest.setNickname(registerContext.getNickName());

        // 调用用户服务完成注册
        UserOperatorResponse<UserInfo> register = userFacadeService.register(userRegisterRequest);
        // 注册结果校验（结果为空/失败/无用户信息均视为注册失败）
        if (register == null || Boolean.FALSE.equals(register.getSuccess()) || register.getData() == null) {
            throw new AuthException(AuthErrorCode.USER_REGISTER_FAIL);
        }

        // 获取注册成功的用户信息
        UserInfo userInfo = register.getData();

        // 构建用户根文件创建请求
        // UserFileOperateReqest：入参请求实体（DTO），用来封装创建文件夹需要的全部参数
        UserFileOperateReqest userFileOperateReqest = new UserFileOperateReqest();
        // 设置文件夹名称：常量统一规定用户根目录名称（比如“我的网盘”）
        userFileOperateReqest.setName(BaseConstant.USER_ROOT_FILE);
        // 设置当前注册用户ID，标记这个文件夹归属哪个用户
        userFileOperateReqest.setUserId(userInfo.getUserId());
        // 设置父目录ID：常量ROOT_PARENT_ID代表顶级根节点，说明这是用户第一层根目录
        userFileOperateReqest.setParentId(BaseConstant.ROOT_PARENT_ID);


        // 调用文件服务创建用户根文件目录
        UserFileOperateResponse<Long> userRootFile = userFileFacadeService.createUserRootFile(userFileOperateReqest);
        // 根文件创建失败则抛出异常
        if (userRootFile == null || Boolean.FALSE.equals(userRootFile.getSuccess())) {
            throw new AuthException(AuthErrorCode.USER_REGISTER_FAIL);
        }

        return userInfo.getUserId();
    }

    /**
     * 用户登出逻辑
     * 1. 获取当前登录用户ID
     * 2. 删除Redis中存储的登录token
     * 3. Sa-Token登出（清除登录状态）
     */
    @Override
    public void logout() {
        // 获取当前登录用户ID
        Long userId = IdUtil.get();
        // 删除Redis中的登录态
        stringRedisTemplate.delete(BaseConstant.USER_LOGIN_PREFIX + userId);
        // Sa-Token登出
        StpUtil.logout(userId);
    }
}
