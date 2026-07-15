package com.disk.ai.infrastructure.embedding;

import java.util.List;

// 文本向量化统一接口。
// RAG 的第一步就是把“文档片段”和“用户问题”都变成向量，才能计算语义相似度。
public interface EmbeddingClient {

    // 当前 embedding 模型输出的向量维度，例如 768。
    int dimension();

    // 单条文本转向量。
    float[] embed(String text);

    // 多条文本批量转向量；建文档索引时会一次处理多个 chunk。
    List<float[]> embedAll(List<String> texts);
}
