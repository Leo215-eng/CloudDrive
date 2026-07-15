package com.disk.rpc.facade;

import com.alibaba.fastjson2.JSON;
import com.disk.base.exception.BizException;
import com.disk.base.exception.SystemException;
import com.disk.base.response.BaseResponse;
import com.disk.base.response.ResponseCode;
import com.disk.base.utils.BeanValidator;
import jakarta.validation.ValidationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Facade的切面处理类，统一统计进行参数校验及异常捕获
 *
 * @author weikunkun
 */
//这是一个基于 Spring AOP 实现的切面类（FacadeAspect），
//核心作用是对标注了 @Facade 自定义注解的方法进行统一的横切逻辑处理，
//包括：参数校验、耗时统计、日志标准化打印、异常统一捕获与响应封装、响应结果补全。

@Aspect    // 声明为AOP切面类，标识该类包含切面逻辑
@Component // 交给Spring容器管理，使切面生效
public class FacadeAspect {
    // 日志对象，用来打印统一格式的日志
    private static final Logger LOGGER = LoggerFactory.getLogger(FacadeAspect.class);

//    TODO 通过 @Around 实现环绕通知，拦截所有标注 @Facade 注解的方法，是整个切面的核心逻辑入口。
//    只要某个方法上加了 @Facade 注解，这个方法被 Spring 调用时，就先进入 FacadeAspect.facade(...)
//    @Around  AOP 通知类型：在目标方法前后包裹执行
//    @annotation(...)	切点表达式：匹配带有指定注解的方法
//    com.disk.rpc.facade.Facade	自定义注解的全限定类名
    /**
     * 环绕切点：拦截所有标注了 @Facade 注解的方法
     * @param pjp 被拦截的目标方法封装对象
     * @return 处理后的返回结果
     */
    @Around("@annotation(com.disk.rpc.facade.Facade)")
    public Object facade(ProceedingJoinPoint pjp) throws Exception {
        // ========== 1. 初始化计时器，统计方法执行耗时 ==========
        // StopWatch 是 Apache 提供的简易计时器，start 开始计时
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // ========== 2. 获取目标方法的元信息：方法对象、入参数组 ==========
        // 从切点签名里拿到方法对象（包含方法名、参数类型、返回值类型等信息）
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        // 获取调用目标方法时传入的所有参数
        Object[] args = pjp.getArgs();

        // 打印入口日志：记录开始执行、方法名、入参内容
        LOGGER.info("start to execute , method = " + method.getName() + " , args = " + JSON.toJSONString(args));

        // 获取目标方法的返回值类型（异常时要根据类型构造失败响应）
        Class returnType = ((MethodSignature) pjp.getSignature()).getMethod().getReturnType();

        // ========== 3. 统一参数校验：遍历所有入参，逐个校验 ==========
        for (Object parameter : args) {
            try {
                // 调用校验工具，执行参数上的 JSR380 校验注解（@NotNull/@NotBlank等）
                BeanValidator.validateObject(parameter);
            } catch (ValidationException e) {
                // 校验失败：打印失败日志，直接返回校验失败的标准响应，不执行业务方法
                printLog(stopWatch, method, args, "failed to validate", null, e);
                return getFailedResponse(returnType, e);
            }
        }

        try {
            // ========== 4. 执行目标业务方法（真正的业务逻辑在这里执行） ==========
            // pjp.proceed() 就是调用原来的方法，拿到返回结果
            Object response = pjp.proceed();

            // ========== 5. 方法执行成功：补全响应的状态码等信息 ==========
            enrichObject(response);

            // 打印成功结束日志，返回结果
            printLog(stopWatch, method, args, "end to execute", response, null);
            return response;

        } catch (Throwable throwable) {
            // ========== 6. 方法执行异常：统一捕获，封装成标准失败响应 ==========
            // 打印异常日志
            printLog(stopWatch, method, args, "failed to execute", null, throwable);
            // 构造并返回失败响应
            return getFailedResponse(returnType, throwable);
        }
    }

    /**
     * 统一日志打印方法
     * @param stopWatch 计时器（用来算耗时）
     * @param method 目标方法
     * @param args 入参
     * @param action 行为描述（开始/校验失败/执行失败/结束）
     * @param response 响应结果
     * @param throwable 异常对象
     */
    private void printLog(StopWatch stopWatch, Method method, Object[] args, String action, Object response,
                          Throwable throwable) {
        try {
            // 调用拼接方法生成日志内容，打印 info 级别日志
            // 第二个参数 throwable 会打印异常堆栈
            LOGGER.info(getInfoMessage(action, stopWatch, method, args, response, throwable), throwable);
        } catch (Exception e1) {
            // 重点：日志打印本身失败了，只打错误日志，绝对不能影响主业务
            // 比如参数序列化报错，不能导致业务方法也失败
            LOGGER.error("log failed", e1);
        }
    }


    /**
     * 拼接标准化日志字符串
     * 注意：这里的格式和日志监控系统对齐，改格式要同步改监控规则
     * @return 拼接好的完整日志文本
     */
    private String getInfoMessage(String action, StopWatch stopWatch, Method method, Object[] args, Object response,
                                  Throwable exception) {
        StringBuilder stringBuilder = new StringBuilder(action);
        // 1. 方法名
        stringBuilder.append(" ,method = ");
        stringBuilder.append(method.getName());
        // 2. 执行耗时（毫秒）
        stringBuilder.append(" ,cost = ");
        stringBuilder.append(stopWatch.getTime()).append(" ms");

        // 3. 如果响应是标准响应体，追加成功/失败状态
        if (response instanceof BaseResponse) {
            stringBuilder.append(" ,success = ");
            stringBuilder.append(((BaseResponse) response).getSuccess());
        }

        // 4. 有异常的话，标记 success=false
        if (exception != null) {
            stringBuilder.append(" ,success = ");
            stringBuilder.append(false);
        }

        // 5. 入参（转JSON字符串）
        stringBuilder.append(" ,args = ");
        stringBuilder.append(JSON.toJSONString(Arrays.toString(args)));

        // 6. 响应结果（转JSON字符串）
        if (response != null) {
            stringBuilder.append(" ,resp = ");
            stringBuilder.append(JSON.toJSONString(response));
        }

        // 7. 异常信息
        if (exception != null) {
            stringBuilder.append(" ,exception = ");
            stringBuilder.append(exception.getMessage());
        }

        // 8. 响应失败时追加失败标记，方便日志监控统计失败率
        if (response instanceof BaseResponse) {
            BaseResponse baseResponse = (BaseResponse) response;
            if (!baseResponse.getSuccess()) {
                stringBuilder.append(" , execute_failed");
            }
        }

        return stringBuilder.toString();
    }

    /**
     * 补全响应对象的状态码
     * 业务代码只需要返回结果，不用每次都手动设成功/失败码，切面自动补
     * @param response 目标方法返回的响应对象
     */
    private void enrichObject(Object response) {
        // 只处理标准响应体 BaseResponse 及其子类
        if (response instanceof BaseResponse) {
            if (((BaseResponse) response).getSuccess()) {
                // 成功状态：如果没手动设响应码，默认填充 SUCCESS
                if (StringUtils.isEmpty(((BaseResponse) response).getResponseCode())) {
                    ((BaseResponse) response).setResponseCode(ResponseCode.SUCCESS.name());
                }
            } else {
                // 失败状态：如果没手动设响应码，默认填充 BIZ_ERROR
                if (StringUtils.isEmpty(((BaseResponse) response).getResponseCode())) {
                    ((BaseResponse) response).setResponseCode(ResponseCode.BIZ_ERROR.name());
                }
            }
        }
    }


    /**
     * 根据异常类型，构造标准化的失败响应
     * @param returnType 目标方法的返回值类型
     * @param throwable 捕获到的异常
     * @return 封装好的失败响应对象
     */
    private Object getFailedResponse(Class returnType, Throwable throwable)
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {

        // 只处理 BaseResponse 及其子类的返回值
        if (returnType.getDeclaredConstructor().newInstance() instanceof BaseResponse) {
            // 通过反射创建返回值类型的实例（保证和原方法返回类型一致）
            BaseResponse response = (BaseResponse) returnType.getDeclaredConstructor().newInstance();
            // 统一设为失败
            response.setSuccess(false);

            // 区分不同异常类型，设置对应的错误码和错误信息
            if (throwable instanceof BizException bizException) {
                // 业务异常：取自定义的错误码和错误消息
                response.setResponseMessage(bizException.getErrorCode().getMessage());
                response.setResponseCode(bizException.getErrorCode().getCode());
            } else if (throwable instanceof SystemException systemException) {
                // 系统异常：取系统错误码和消息
                response.setResponseMessage(systemException.getErrorCode().getMessage());
                response.setResponseCode(systemException.getErrorCode().getCode());
            } else {
                // 其他未知异常：默认业务错误，异常信息直接 toString
                response.setResponseMessage(throwable.toString());
                response.setResponseCode(ResponseCode.BIZ_ERROR.name());
            }
            return response;
        }

        // 返回值不是 BaseResponse 类型，打错误日志，返回 null
        LOGGER.error(
                "failed to getFailedResponse , returnType (" + returnType + ") is not instanceof BaseResponse");
        return null;
    }

}
