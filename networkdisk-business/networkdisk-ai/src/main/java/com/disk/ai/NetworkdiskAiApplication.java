package com.disk.ai;

import com.disk.ai.infrastructure.config.AiIndexProperties;
import com.disk.ai.infrastructure.config.AiProviderProperties;
import com.disk.ai.infrastructure.vector.PgVectorProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableDubbo
// Spring Boot 启动类。scanBasePackages="com.disk" 表示扫描 com.disk 下的 Controller/Service/Component。
@SpringBootApplication(scanBasePackages = "com.disk")
// 显式启用这几个 @ConfigurationProperties 配置类，让 application.yml 中的配置能绑定到 Java 对象。
@EnableConfigurationProperties({
        AiProviderProperties.class,
        AiIndexProperties.class,
        PgVectorProperties.class
})
public class NetworkdiskAiApplication {

    public static void main(String[] args) {
        // 启动 networkdisk-ai 服务。
        SpringApplication.run(NetworkdiskAiApplication.class, args);
    }
}
