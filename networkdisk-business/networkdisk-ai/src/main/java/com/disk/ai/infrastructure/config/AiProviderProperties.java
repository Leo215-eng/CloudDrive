package com.disk.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Lombok 注解，自动生成 getter/setter/toString 等方法。
@Data
// AI 服务商配置类。
// Spring Boot 会读取 application.yml 里的 com.disk.ai.provider，并按字段名自动绑定到这个类。
@ConfigurationProperties(prefix = "com.disk.ai.provider")
public class AiProviderProperties {

    // AI 服务类型：mock 表示假数据，openai-compatible 表示调用真实兼容 OpenAI 格式的接口
    private String type = "mock";

    // AI 服务的基础地址，例如阿里 DashScope 的 compatible-mode 地址
    private String baseUrl;

    // AI 服务密钥，用来调用真实模型接口
    private String apiKey;

    // 聊天/摘要/问答接口路径
    private String chatPath = "/v1/chat/completions";

    // 向量生成接口路径
    private String embeddingsPath = "/v1/embeddings";

    // 对话模型名称，例如 qwen3.5-plus
    private String chatModel = "mock-chat-model";

    // 向量模型名称
    private String embeddingModel = "mock-embedding-model";

    // 向量维度，必须和向量数据库表里的维度一致
    private Integer embeddingDimension = 768;

    // 是否启用 mock 模拟模式
    private boolean mockEnabled = true;

    // 单个文档最多处理多少字符，防止文档太大导致模型请求过长
    private Integer maxDocumentChars = 120000;

    // 模型回答随机性，越低越稳定
    private Double temperature = 0.2d;

    // 模型最多输出多少 token
    private Integer maxTokens = 1024;

    // 连接 AI 服务的超时时间
    private Integer connectTimeoutMillis = 5000;

    // 读取 AI 服务响应的超时时间
    private Integer readTimeoutMillis = 60000;
}
