package com.disk.files;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableDubbo
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.disk")
public class NetworkdiskFilesApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetworkdiskFilesApplication.class, args);
    }
}
