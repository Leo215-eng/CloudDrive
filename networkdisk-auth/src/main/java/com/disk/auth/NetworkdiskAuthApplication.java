package com.disk.auth;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//    启动 Spring 容器 — 创建并初始化 ApplicationContext
//    自动扫描组件 — 在 com.disk.auth 包下找：
//        @Controller / @RestController（HTTP 接口）
//        @Service / @DubboService（业务逻辑）
//        @Component, @Repository 等
//        @Configuration 配置类
//    执行自动配置 — 根据 application.yml / application.properties 里的配置，自动装配数据源、Redis、Dubbo 等
//    启动内嵌 Tomcat — 开始监听 HTTP 端口（默认 8080）
//    暴露 Dubbo 服务 — 把 @DubboService 标记的类注册到注册中心（如 Nacos、ZooKeeper）

//TODO  main 只做一件事：启动 Spring 容器。之后所有业务逻辑都是容器通过注解扫描 + 依赖注入 + 请求路由自动驱动的，不需要你在 main 里写任何调用代码。
@EnableDubbo
@SpringBootApplication(scanBasePackages = {"com.disk.auth"})
public class NetworkdiskAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetworkdiskAuthApplication.class, args);
    }

}
