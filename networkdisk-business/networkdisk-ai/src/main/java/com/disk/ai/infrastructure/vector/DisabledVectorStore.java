package com.disk.ai.infrastructure.vector;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.file.AiSourceFile;
import com.disk.ai.infrastructure.parser.ParsedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@ConditionalOnMissingBean(VectorStore.class)
// 没有任何 VectorStore 实现时启用的兜底版本。
// 作用是让服务在未配置 pgvector 时也能启动，但向量索引/检索功能不可用。
public class DisabledVectorStore implements VectorStore {

    @Override
    public boolean isReady() {
        // 告诉业务层：当前没有可用向量库。
        return false;
    }

    @Override
    public DocumentIndexSummary getIndexSummary(Long userId, Long userFileId) {
        // 没有向量库，自然查不到索引摘要。
        return null;
    }

    @Override
    public List<String> loadDocumentChunks(Long userId, Long userFileId) {
        // 没有向量库，也没有文档块可加载。
        return Collections.emptyList();
    }

    @Override
    public void replaceDocument(AiSourceFile sourceFile, ParsedDocument parsedDocument, List<PgVectorDocumentChunk> chunks) {
        // 建索引是强依赖向量库的操作，禁用时直接抛业务异常。
        throw new AiException(AiErrorCode.VECTOR_STORE_DISABLED);
    }

    @Override
    public List<PgVectorSearchResult> search(Long userId, Long userFileId, float[] queryVector, int topK) {
        // 检索也是强依赖向量库的操作，禁用时直接抛业务异常。
        throw new AiException(AiErrorCode.VECTOR_STORE_DISABLED);
    }
}
