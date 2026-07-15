package com.disk.ai.infrastructure.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "com.disk.ai.pgvector")
// pgvector/PostgreSQL 配置类。
// 对应 application.yml 中 com.disk.ai.pgvector 下面的配置。
public class PgVectorProperties {

    // 是否启用 pgvector 向量库。false 时会走 DisabledVectorStore。
    private boolean enabled = false;

    // 是否启动时自动创建扩展和表结构。
    private boolean initSchema = false;

    // PostgreSQL 连接地址。
    private String url = "jdbc:postgresql://127.0.0.1:5432/networkdisk_ai";

    // 数据库用户名。
    private String username = "postgres";

    // 数据库密码。
    private String password = "postgres";

    // 向量维度，必须和 embedding 模型输出维度一致。
    private Integer dimension = 768;

    // Hikari 数据库连接池最大连接数。
    private Integer maximumPoolSize = 4;

    // Hikari 数据库连接池最小空闲连接数。
    private Integer minimumIdle = 1;
}
