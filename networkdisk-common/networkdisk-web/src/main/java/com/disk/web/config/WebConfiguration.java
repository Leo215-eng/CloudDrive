package com.disk.web.config;

import com.disk.web.filter.TokenFilter;
import com.disk.web.handler.GlobalWebExceptionHandler;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web层自动装配配置类
 * 提供：全局异常处理器、Token Servlet过滤器注册、全局CORS跨域配置
 * @AutoConfiguration 代表这是starter自动配置类，被SpringBoot自动加载
 */
@AutoConfiguration
// 仅在Web环境（servlet容器模式）下才生效；非web项目不会实例化此类
@ConditionalOnWebApplication
public class WebConfiguration implements WebMvcConfigurer {

    /**
     * 注册全局Web异常处理器
     * @ConditionalOnMissingBean：如果业务代码自己定义了GlobalWebExceptionHandler，
     * 优先使用业务自定义的，不再创建这个默认实例
     */
    @Bean
    @ConditionalOnMissingBean
    GlobalWebExceptionHandler globalWebExceptionHandler() {
        return new GlobalWebExceptionHandler();
    }

    /**
     * 注册Token鉴权过滤器（Servlet Filter，不是SpringMVC拦截器！划重点）
     * Filter 在 Servlet 容器层面执行，早于 SpringMVC DispatcherServlet
     * @param redissonClient Redisson Redis客户端，用于校验token会话
     * @return 过滤器注册Bean
     */
    @Bean
    public FilterRegistrationBean<TokenFilter> tokenFilter(RedissonClient redissonClient) {
        FilterRegistrationBean<TokenFilter> registrationBean = new FilterRegistrationBean<>();

        // 设置过滤器实例，注入Redisson用于查询Redis校验token
        registrationBean.setFilter(new TokenFilter(redissonClient));
        // 【白名单模式】只对 /trade/buy 这一条路径执行Token过滤鉴权
        registrationBean.addUrlPatterns("/trade/buy");
        // 设置过滤器执行顺序，数值越小越先执行
        registrationBean.setOrder(10);

        return registrationBean;
    }

    /**
     * SpringMVC全局跨域配置 CORS
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowedOriginPatterns 支持通配符，允许 localhost下任意端口，新版Spring推荐
                .allowedOriginPatterns("http://localhost:*")
                // 允许的HTTP请求方式
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许前端携带所有自定义请求头（Authorization 放行关键）
                .allowedHeaders("*")
                // 允许携带凭证（Cookie等）
                .allowCredentials(true);
        // maxAge注释掉，会使用Spring默认预检缓存时长
    }
}
