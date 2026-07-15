package com.disk.ai.infrastructure.vector;

import com.disk.ai.infrastructure.file.AiSourceFile;
import com.disk.ai.infrastructure.parser.ParsedDocument;

import java.util.List;

// 向量存储统一接口。
// 业务层只依赖 VectorStore，不关心底层是 pgvector，还是禁用状态。
public interface VectorStore {

    // 当前向量存储是否可用。PgVectorVectorStore 返回 true，DisabledVectorStore 返回 false。
    boolean isReady();

    // 查询某个用户文件是否已经建立过索引。
    DocumentIndexSummary getIndexSummary(Long userId, Long userFileId);

    // 加载某个文件的所有文本块，给摘要/标签兜底复用。
    List<String> loadDocumentChunks(Long userId, Long userFileId);

    // 替换某个文件的向量索引：旧 chunk 删除，新 chunk 写入。
    void replaceDocument(AiSourceFile sourceFile,
                         ParsedDocument parsedDocument,
                         List<PgVectorDocumentChunk> chunks);

    // 用问题向量搜索当前文件最相似的 topK 个文本块。
    List<PgVectorSearchResult> search(Long userId, Long userFileId, float[] queryVector, int topK);
}
