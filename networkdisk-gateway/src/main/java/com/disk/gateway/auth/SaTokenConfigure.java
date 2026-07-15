package com.disk.gateway.auth;

// Sa-Token 内置异常：未登录、无权限、无角色
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
// Sa-Token 网关过滤器（Reactor 适配版，适配网关/响应式编程）
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
// Sa-Token 路由匹配工具：用于精准控制哪些接口需要鉴权
import cn.dev33.satoken.router.SaRouter;
// Sa-Token 核心工具类：登录校验、权限校验等
import cn.dev33.satoken.stp.StpUtil;
// Sa-Token 统一返回结果封装
import cn.dev33.satoken.util.SaResult;
// 项目自定义的权限、角色常量（比如ADMIN管理员、AUTH实名认证权限）
import com.disk.api.user.constant.UserPermission;
import com.disk.api.user.constant.UserRole;
// Spring 配置注解：标记这是一个配置类，会被Spring自动加载
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 核心配置类（零基础理解：这个类是“鉴权规则的总开关”）
 * 作用：配置请求拦截规则、鉴权逻辑、异常处理逻辑
 * 使用：SpringBoot启动时自动加载，无需手动调用，所有请求都会走这里的规则
 * @author weikunkun
 * @date 2024/10/20
 */
@Configuration // 告诉Spring：这是一个配置类，需要初始化加载
public class SaTokenConfigure {

    /**
     * 注册 Sa-Token 网关过滤器（零基础理解：这是“拦截请求的核心Bean”）
     * @return SaReactorFilter：Sa-Token 适配网关的过滤器，负责拦截所有请求
     */
    @Bean // 告诉Spring：把这个方法返回的对象交给Spring管理，全局生效
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                // 1. 拦截范围：/** 表示拦截所有请求（比如 /admin/user、/api/file 等）
                .addInclude("/**")
                // 2. 排除范围：/favicon.ico 是浏览器图标请求，无需鉴权（避免无效拦截）
                .addExclude("/favicon.ico")
                // 3. 核心鉴权逻辑：每次请求进入时执行的校验规则
                .setAuth(obj -> {
                    // 3.1 登录校验规则：
                    // SaRouter.match("/**")：匹配所有请求
                    // .notMatch("/auth/**")：排除 /auth/ 开头的请求（比如 /auth/login 登录接口，必须开放，否则无法登录）
                    // .check(r -> StpUtil.checkLogin())：校验用户是否登录，未登录则抛出 NotLoginException 异常
                    SaRouter.match("/**").notMatch("/auth/**").check(r -> StpUtil.checkLogin());

                    // 3.2 角色校验规则：
                    // 匹配 /admin/ 开头的所有请求（比如 /admin/user/list 管理员查询用户）
                    // 校验用户是否有 ADMIN 角色，无则抛出 NotRoleException 异常
                    SaRouter.match("/admin/**", r -> StpUtil.checkRole(UserRole.ADMIN.name()));
                })
                // 4. 异常处理逻辑：当上面的鉴权逻辑抛出异常时（比如未登录、无角色），执行这个方法返回友好提示
                .setError(this::getSaResult);
    }

    /**
     * 鉴权异常处理方法（零基础理解：统一处理鉴权失败的提示）
     * @param throwable 鉴权时抛出的异常（比如未登录、无权限）
     * @return SaResult：Sa-Token 封装的统一返回结果（JSON格式，前端可直接解析）
     */
    private SaResult getSaResult(Throwable throwable) {
        // 按异常类型分类处理，返回不同提示
        switch (throwable) {
            // 情况1：未登录异常（比如游客访问需要登录的接口）
            case NotLoginException notLoginException:
                return SaResult.error("请先登录"); // 返回JSON：{code:500,msg:"请先登录",data:null}
            // 情况2：无角色异常（比如普通用户访问 /admin/ 接口）
            case NotRoleException notRoleException:
                // 如果是缺少 ADMIN 角色，返回“请勿越权使用”；否则返回通用提示
                if (UserRole.ADMIN.name().equals(notRoleException.getRole())) {
                    return SaResult.error("请勿越权使用！");
                }
                return SaResult.error("您无权限进行此操作！");
            // 情况3：无权限异常（比如未实名认证用户访问需要认证的接口）
            case NotPermissionException notPermissionException:
                // 如果是缺少 AUTH（实名认证）权限，返回“请先完成实名认证”；否则返回通用提示
                if (UserPermission.AUTH.name().equals(notPermissionException.getPermission())) {
                    return SaResult.error("请先完成实名认证！");
                }
                return SaResult.error("您无权限进行此操作！");
            // 情况4：其他异常（比如系统错误），返回异常原始信息
            default:
                return SaResult.error(throwable.getMessage());
        }
    }
}
