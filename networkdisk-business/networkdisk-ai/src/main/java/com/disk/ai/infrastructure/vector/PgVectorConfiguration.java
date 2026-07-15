package com.disk.ai.infrastructure.vector;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "com.disk.ai.pgvector.enabled", havingValue = "true")
// pgvector 专用数据库配置。
// 它单独创建 DataSource 和 JdbcTemplate，避免和项目里其他 MySQL/业务库连接混在一起。
public class PgVectorConfiguration {

    @Bean(name = "pgVectorDataSource")
// HikariDataSource 是第三方 jar 包中的类，我们没法修改它的源码加 @Component，因此用 @Bean 方式手动构造、配置并注册到容器中。
// @Bean：声明该方法的返回值会被注册为 Spring IoC 容器中的一个 Bean，由 Spring 统一管理生命周期。

//参数 PgVectorProperties properties：Spring 会自动注入配置属性类，和 yml 中 com.disk.ai.pgvector 配置项一一绑定。
//所有参数从配置文件读取，实现代码与环境配置分离，不同环境（开发 / 测试 / 生产）改配置即可，不用动代码。
//ataSource 就是负责提供和管理数据库连接的对象。
//    什么是数据库连接池？
//    每次操作数据库都临时新建、销毁连接，会消耗大量 CPU 和网络资源。连接池会提前创建一批数据库连接并长期维持，用的时候直接取，用完放回池里复用，能大幅提升数据库操作的性能。
    public DataSource pgVectorDataSource(PgVectorProperties properties) {
//        创建 HikariCP 连接池实例。HikariCP 是 Spring Boot 默认的数据库连接池，以极致性能、低资源开销著称，是当前 Java 生态的工业级标准连接池。
        HikariDataSource dataSource = new HikariDataSource();
        //指定 PostgreSQL 数据库的 JDBC 驱动全类名，是 JDBC 规范的固定写法，告诉程序用哪个驱动去连接数据库。
        dataSource.setDriverClassName("org.postgresql.Driver");
//        从配置中读取数据库连接 URL，格式为 jdbc:postgresql://IP地址:端口/数据库名，
//        对应配置里的 jdbc:postgresql://127.0.0.1:5432/networkdisk_ai。
        dataSource.setJdbcUrl(properties.getUrl());
        // 设置数据库账号密码
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        // 设置连接池最大连接数
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        // 设置最小空闲连接数
        dataSource.setMinimumIdle(properties.getMinimumIdle());
        return dataSource;
    }

    @Bean(name = "pgVectorJdbcTemplate")
    // 创建名为 pgVectorJdbcTemplate 的 SQL 工具。
    // @Qualifier("pgVectorDataSource") 明确指定使用上面那个 PostgreSQL 数据源。
//    @Qualifier("pgVectorDataSource") 的作用就是按 Bean 名称精确指定：告诉 Spring，我就要名字叫 pgVectorDataSource 的那个数据源，不要别的。
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
