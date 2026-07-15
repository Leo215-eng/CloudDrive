package com.disk.auth.domain.convertor;

import com.disk.auth.domain.context.LoginContext;
import com.disk.auth.domain.context.RegisterContext;
import com.disk.auth.param.LoginParamVO;
import com.disk.auth.param.RegisterParamVO;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;

/**
 * MapStruct 对象转换接口
 * 负责前端VO → 领域层Context对象的属性拷贝，替代手动get/set，编译时生成转换实现类
 */
// MapStruct核心注解
@Mapper(
        // 空值校验策略：ALWAYS 转换每个字段时都判断源字段是否为null，null则不赋值，避免覆盖目标原有值
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        // 手写的是接口和转换方法，真正的实现类由 MapStruct 在编译期生成。
        // 因为用了 componentModel = "spring"，生成的实现类会交给 Spring 管理，
        // 所以 Controller 才可以 @Autowired 注入它。
        // 它的作用不是业务处理，而是把前端 VO，比如注册请求参数，转换成 Service 层使用的 RegisterContext。
        componentModel = "spring"
)
//这是 MapStruct 的转换接口，MapStruct 是 Java 实体快速属性拷贝工具：
//只写接口定义映射规则，编译阶段自动生成接口实现类，底层原生 get/set，性能远高于 BeanUtils。
public interface AuthConvertor {

    /**
     * 登录VO 转 登录领域上下文
     * @param loginParamVO 前端传入登录参数VO
     * @return 业务层使用LoginContext
     */
    LoginContext loginParamToLoginContext(LoginParamVO loginParamVO);

    /**
     * 注册VO 转 注册领域上下文
     * @param registerParamVO 前端传入注册参数VO
     * @return 业务层使用RegisterContext
     */
    RegisterContext registerParamToRegisterContext(RegisterParamVO registerParamVO);
}
