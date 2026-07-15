package com.disk.ai.infrastructure.result;

import java.util.List;

// AI 结果存储统一接口。
// 这里存的是模型结果：摘要和标签；不是存原始文件，也不是存向量。
public interface DocumentResultStore {

    // 当前结果存储是否启用。PgDocumentResultStore 返回 true，DisabledDocumentResultStore 返回 false。
    boolean isReady();

    // 读取某个用户文件已经保存过的摘要。
    StoredDocumentSummary getSummary(Long userId, Long userFileId);

    // 保存某个用户文件的摘要、模型名和是否 mock。
    void saveSummary(Long userId, Long userFileId, String filename, String summary, String model, Boolean mocked);

    // 读取某个用户文件已经保存过的标签。
    StoredDocumentTags getTags(Long userId, Long userFileId);

    // 保存某个用户文件的标签、模型名和是否 mock。
    void saveTags(Long userId, Long userFileId, String filename, List<String> tags, String model, Boolean mocked);

    // 文件重新建索引时清理旧结果，避免旧摘要/旧标签误用。
    void clearDocumentResult(Long userId, Long userFileId);
}
