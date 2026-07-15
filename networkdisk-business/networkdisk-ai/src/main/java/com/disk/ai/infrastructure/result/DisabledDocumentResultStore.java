package com.disk.ai.infrastructure.result;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnMissingBean(DocumentResultStore.class)
// 没有真实 DocumentResultStore 时启用的兜底实现。
// 它不保存、不读取任何摘要/标签，只是让业务层不用处理 null Bean。
public class DisabledDocumentResultStore implements DocumentResultStore {

    @Override
    public boolean isReady() {
        // 告诉业务层：结果存储不可用，别查缓存、别保存结果。
        return false;
    }

    @Override
    public StoredDocumentSummary getSummary(Long userId, Long userFileId) {
        // 不支持存储，所以永远查不到历史摘要。
        return null;
    }

    @Override
    public void saveSummary(Long userId, Long userFileId, String filename, String summary, String model, Boolean mocked) {
        // 空实现：调用了也不做任何事。
    }

    @Override
    public StoredDocumentTags getTags(Long userId, Long userFileId) {
        // 不支持存储，所以永远查不到历史标签。
        return null;
    }

    @Override
    public void saveTags(Long userId, Long userFileId, String filename, List<String> tags, String model, Boolean mocked) {
        // 空实现：调用了也不做任何事。
    }

    @Override
    public void clearDocumentResult(Long userId, Long userFileId) {
        // 空实现：没有存储，自然也没有内容需要清理。
    }
}
