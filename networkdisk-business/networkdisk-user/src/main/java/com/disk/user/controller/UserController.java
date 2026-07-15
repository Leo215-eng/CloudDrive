package com.disk.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.disk.api.user.request.UserQueryRequest;
import com.disk.api.user.response.data.UserInfo;
import com.disk.base.utils.UserIdUtil;
import com.disk.user.domain.entity.UserDO;
import com.disk.user.domain.entity.convertor.UserConvertor;
import com.disk.user.domain.response.UserInfoVO;
import com.disk.user.domain.service.UserService;
import com.disk.user.infrastructure.exception.UserException;
import com.disk.web.vo.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.disk.user.infrastructure.exception.UserErrorCode.USER_NOT_EXIST;

/**
 * 类描述: 用户信息
 *
 * @author weikunkun
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserService userService;

    /**
     * 获取当前登录用户信息接口
     * @return 全局统一响应，data为用户信息VO
     */
    @GetMapping("/get-user-info")
    public Result<UserInfoVO> getUserInfo() {
        // 工具类从请求token中解析当前登录用户ID
        Long userId = UserIdUtil.get();
        // 调用业务层，根据用户ID查询组装用户展示信息
        UserInfoVO userInfo = userService.getUserInfo(userId);
        // 封装成功统一响应返回前端
        return Result.success(userInfo);
    }


    @GetMapping("/test/{id}")
    public Result<UserDO> test(@PathVariable Long id) {
        UserDO userDO = userService.findById(Long.valueOf(id));
        return Result.success(userDO);
    }

}
