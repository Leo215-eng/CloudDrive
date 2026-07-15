package com.disk.gateway.auth;

// Sa-Token 接口：自定义权限/角色获取逻辑（必须实现这个接口，框架才会调用）
import cn.dev33.satoken.stp.StpInterface;
// Sa-Token 会话工具类：获取用户会话中的信息
import cn.dev33.satoken.stp.StpUtil;
// 项目自定义的权限、角色、用户状态常量
import com.disk.api.user.constant.UserPermission;
import com.disk.api.user.constant.UserRole;
import com.disk.api.user.constant.UserStateEnum;
// 项目自定义的用户信息实体（存储用户角色、状态等）
import com.disk.api.user.response.data.UserInfo;
// Google 工具类：快速创建集合
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Objects;

/**
 * Sa-Token 自定义权限/角色获取接口实现类（零基础理解：告诉框架“这个用户有哪些权限/角色”）
 * 原理：当调用 StpUtil.checkRole()/checkPermission() 时，框架会自动调用这个类的方法，获取当前用户的角色/权限列表，然后对比校验
 * 使用：框架自动调用，无需手动触发
 * @author weikunkun
 * @date 2024/10/20
 */
public class StpInterfaceImpl implements StpInterface {

    /**
     * 获取用户的权限列表（零基础理解：返回当前用户能做哪些事，比如“基础操作”“实名认证操作”）
     * @param loginId 登录用户的唯一标识（比如用户ID：1001）
     * @param loginType 登录类型（Sa-Token 支持多端登录，比如PC、APP，这里默认一个类型）
     * @return 权限列表（比如 [BASIC, AUTH] 表示有基础权限+实名认证权限）
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 1. 从 Sa-Token 会话中获取用户信息：
        // StpUtil.getSessionByLoginId(loginId)：根据用户ID获取会话（会话是Sa-Token存储用户信息的地方，登录后自动创建）
        // .get((String) loginId)：从会话中取出用户信息（UserInfo是项目自定义的，存储角色、状态等）
        UserInfo userInfo = (UserInfo) StpUtil.getSessionByLoginId(loginId).get((String) loginId);

        // 2. 根据用户角色/状态判断权限：
        // 情况1：管理员 或 状态为“激活”/“已认证” → 拥有基础权限(BASIC)+实名认证权限(AUTH)
        if (Objects.equals(userInfo.getUserRole(), UserRole.ADMIN)
                || Objects.equals(userInfo.getState(), UserStateEnum.ACTIVE.name())
                || Objects.equals(userInfo.getState(), UserStateEnum.AUTH.name())) {
            return Lists.newArrayList(UserPermission.BASIC.name(), UserPermission.AUTH.name());
        }

        // 情况2：用户状态为“初始”（刚注册未完善信息）→ 仅拥有基础权限(BASIC)
        if (Objects.equals(userInfo.getState(), UserStateEnum.INIT.name())) {
            return Lists.newArrayList(UserPermission.BASIC.name());
        }

        // 情况3：用户状态为“冻结” → 仅拥有冻结权限(FROZEN)（实际业务中可理解为“无有效权限”）
        if (Objects.equals(userInfo.getState(), UserStateEnum.FROZEN.name())) {
            return Lists.newArrayList(UserPermission.FROZEN.name());
        }

        // 情况4：其他状态 → 无任何权限(NONE)
        return Lists.newArrayList(UserPermission.NONE.name());
    }

    /**
     * 获取用户的角色列表（零基础理解：返回当前用户是什么身份，比如“管理员”“普通用户”）
     * @param loginId 登录用户的唯一标识
     * @param loginType 登录类型
     * @return 角色列表（比如 [ADMIN] 表示管理员，[CUSTOMER] 表示普通用户）
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 1. 从会话中获取用户信息（和上面权限逻辑一致）
        UserInfo userInfo = (UserInfo) StpUtil.getSessionByLoginId(loginId).get((String) loginId);

        // 2. 判断是否是管理员角色：是则返回ADMIN，否则返回普通用户CUSTOMER
        if (Objects.equals(UserRole.ADMIN.name(), userInfo.getUserRole())) {
            return Lists.newArrayList(UserRole.ADMIN.name());
        }
        return Lists.newArrayList(UserRole.CUSTOMER.name());
    }
}
