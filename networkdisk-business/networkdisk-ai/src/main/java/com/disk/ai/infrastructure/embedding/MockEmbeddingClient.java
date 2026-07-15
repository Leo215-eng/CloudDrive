package com.disk.ai.infrastructure.embedding;

import com.disk.ai.infrastructure.config.AiProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnMissingBean(EmbeddingClient.class)
// 没有真实 EmbeddingClient 时启用的兜底实现。
// 它不具备真实语义理解能力，只是为了让索引/检索链路在本地能跑通。
public class MockEmbeddingClient implements EmbeddingClient {

    // 使用配置里的 embeddingDimension，保证假向量维度和 pgvector 表一致。
    private final AiProviderProperties aiProviderProperties;

    public MockEmbeddingClient(AiProviderProperties aiProviderProperties) {
        this.aiProviderProperties = aiProviderProperties;
    }

    @Override
    public int dimension() {
        return aiProviderProperties.getEmbeddingDimension();
    }

    @Override
    public float[] embed(String text) {
        // 简单把文本字节分桶累加成固定维度向量；这是 mock 算法，不是 AI 模型。
        int dimension = dimension();
        float[] vector = new float[dimension];
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            // 空文本给一个固定向量，避免全 0 向量。
            vector[0] = 1.0f;
            return vector;
        }
        for (int i = 0; i < bytes.length; i++) {
            int bucket = i % dimension;
            vector[bucket] += (bytes[i] & 0xFF) / 255.0f;
        }
        normalize(vector);
        return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        // 批量接口逐条调用单条 embed，够开发联调用。
        return texts.stream().map(this::embed).toList();
    }

    private void normalize(float[] vector) {
        // 归一化：把向量长度缩放到 1，便于距离计算。
        double sum = 0.0d;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0d) {
            vector[0] = 1.0f;
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}
