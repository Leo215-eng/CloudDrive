package com.disk.ai.infrastructure.embedding;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.config.AiProviderProperties;
import com.disk.ai.infrastructure.provider.OpenAiCompatibleClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "com.disk.ai.provider.type", havingValue = "openai-compatible")
// 真实 embedding 实现：provider.type=openai-compatible 时启用，调用模型服务的 embeddings 接口。
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    // 复用底层 OpenAI 兼容客户端发 HTTP 请求。
    private final OpenAiCompatibleClient openAiCompatibleClient;

    // 读取 embeddingDimension 等配置。
    private final AiProviderProperties properties;

    @Override
    public int dimension() {
        // 向量维度来自 application.yml，必须和 pgvector 表的 vector(n) 维度一致。
        return properties.getEmbeddingDimension();
    }

    @Override
    public float[] embed(String text) {
        // 单条文本也复用批量接口，避免两套逻辑。
        // List.of(text) 把单个字符串包装成只有一个元素的集合，调用批量向量化方法
        List<float[]> results = embedAll(List.of(text));
        // 兜底判断：如果返回空列表，返回空向量数组；否则取第一条向量（唯一文本对应第一条）
        return results.isEmpty() ? new float[dimension()] : results.get(0);
    }
    /**
     * 批量文本向量化接口，支持一次传入多个文本批量生成向量
     * @param texts 待向量化文本列表
     * @return 向量数组列表，顺序和输入文本一一对应
     */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        // 空输入直接返回空列表，不请求模型。
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 调用模型生成向量，并逐个校验维度。
        // 1. 调用OpenAI兼容客户端批量请求向量接口
        // 2. 对流中每一条向量执行维度校验，防止模型返回向量维度和配置不匹配
        // 3. 收集校验完成后的向量集合返回
        return openAiCompatibleClient.createEmbeddings(texts).stream()
                .map(this::validateDimension)
                .toList();
    }

    private float[] validateDimension(float[] vector) {
        // 维度不一致会导致 pgvector 写入失败，也说明配置和模型不匹配。
        if (vector.length != dimension()) {
            throw new AiException(
                    "Configured embedding dimension " + dimension() + " does not match provider response dimension " + vector.length,
                    AiErrorCode.MODEL_REQUEST_FAILED
            );
        }
        return vector;
    }
}
