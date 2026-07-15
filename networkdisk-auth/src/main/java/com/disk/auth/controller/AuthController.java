package com.disk.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.ShearCaptcha;
import com.disk.auth.domain.context.LoginContext;
import com.disk.auth.domain.context.RegisterContext;
import com.disk.auth.domain.convertor.AuthConvertor;
import com.disk.auth.domain.service.AuthService;
import com.disk.auth.infrastructure.AuthConstant;
import com.disk.auth.param.LoginParamVO;
import com.disk.auth.param.RegisterParamVO;
import com.disk.base.utils.IdUtil;
import com.disk.web.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import javax.swing.Spring;

/**
 * 类描述: TODO
 *
 * @author 
 */
// 技术栈说明：
//  * - Sa-Token：轻量级权限认证框架，用于登录态管理
//  * - Hutool：提供图形验证码生成能力
//  * - MapStruct：通过AuthConvertor实现VO与Context的自动映射
//  * - Dubbo3：通过@DubboReference调用远程服务

@Slf4j
// `@RestController` 等价于 `@Controller + @ResponseBody`，
// 表示这个类的方法返回值直接写入 HTTP 响应体，通常会自动转成 JSON。前后端分离项目基本都用它
@RestController
@RequiredArgsConstructor
// `@RequestMapping` 用来建立请求路径和 Java 方法之间的映射。
// 它可以写在类上，也可以写在方法上。写在类上表示统一前缀，写在方法上表示具体接口
@RequestMapping("/api/v1/auth")
public class AuthController {

// 更推荐注入接口，虽然字段类型是接口，但 Spring 真正放进去的是它的实现类对象
// 你要一个 AuthService 类型的 Bean？
// 容器里有没有哪个对象实现了 AuthService？
// 找到了 AuthServiceImpl。
// 那我就把 AuthServiceImpl 对象注入进去。
// Spring Boot 不是只能注入实现类。
// Spring 创建的是实现类对象。
// 但注入时可以按接口类型接收。
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthConvertor authConvertor;



    /**
     * 发送验证码
     */
    @GetMapping("/send-captcha")
    public void sendCaptcha(HttpServletRequest request, HttpServletResponse response, @RequestParam("type") Integer type) throws IOException {
        //定义图形验证码的长、宽、验证码字符数、干扰线宽度
        ShearCaptcha shearCaptcha = CaptchaUtil.createShearCaptcha(150, 50, AuthConstant.CAPTCHA_LENGTH, 3);
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", AuthConstant.CAPTCHA_EXPIRES);
        response.setContentType("image/jpeg");
        shearCaptcha.write(response.getOutputStream());
        if (type == null || type.equals(0)) {
            request.getSession().setAttribute(AuthConstant.CAPTCHA_VERIFY_LOGIN, shearCaptcha.getCode());
            return;
        }
        request.getSession().setAttribute(AuthConstant.CAPTCHA_VERIFY_EMAIL, shearCaptcha.getCode());
    }


    /**
     * 注册
     */
    //我（后端）接收前端发来的POST请求，POST来了我处理
    // RequestBody	"从Body拿数据"	从请求体提取JSON/XML
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterParamVO registerParam) {
        //TODO 验证码校验，暂时不做

        //View Object，视图对象。 专门用来接收前端传过来的请求 JSON，或者给前端返回数据。
        // registerParam 是前端JSON转出来的VO对象，只适合Controller接收前端参数
        //1. authConvertor.registerParamToRegisterContext(registerParam) 这一行核心作用
        //
        //    对象属性拷贝
        //    把 RegisterParamVO（前端 VO）里的 email、password、nickName、checkCode 全部复制到全新 RegisterContext 对象。
        //    底层是 MapStruct 编译生成的 get/set 赋值，不用手动写一堆 set。
        //    分层隔离，解耦
        //
        //    RegisterParamVO：只给前端用，字段、校验规则跟着前端页面走；
        //    RegisterContext：只给 Service 业务层用，是业务逻辑专用载体。
        //
        //如果以后前端新增 / 删除入参、修改字段名，只改 VO，Service 完全不用动，业务代码不受前端变动影响。

        RegisterContext registerContext = authConvertor.registerParamToRegisterContext(registerParam);
        Long userId = authService.register(registerContext);
        return Result.success(IdUtil.encrypt(userId));
    }


    /**
     * 登陆
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginParamVO loginParam) {
        //TODO 验证码校验
        LoginContext loginContext = authConvertor.loginParamToLoginContext(loginParam);
        String loginToken = authService.login(loginContext);
        //登录
        return Result.success(loginToken);
    }


    /**
     * 登出
     */
    @PostMapping("/logout")
    public Result<Boolean> logout() {
        // TODO 改为sa-token方式
        authService.logout();
        return Result.success(true);
    }
}
