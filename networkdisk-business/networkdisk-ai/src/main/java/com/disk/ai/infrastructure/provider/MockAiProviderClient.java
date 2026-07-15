package com.disk.ai.infrastructure.provider;

import com.disk.ai.infrastructure.config.AiProviderProperties;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnMissingBean(AiProviderClient.class)
// 如果当前没有任何 AiProviderClient 实现，才创建 MockAiProviderClient。
// 它不请求真实模型，只返回模拟摘要/标签/回答，方便没有 API Key 时本地开发。
public class MockAiProviderClient implements AiProviderClient {

    // 这里主要用配置里的 chatModel，返回给前端展示“当前模型名”。
    private final AiProviderProperties properties;

    @Override
    public AiSummaryData summarize(AiDocumentSummaryRequest request, String documentContent) {
        // 模拟摘要：不调用大模型，只根据文件名、prompt、内容长度拼一段固定文本。
        AiSummaryData response = new AiSummaryData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setSummary(buildSummary(request, documentContent));
        response.setModel(properties.getChatModel());
        response.setMocked(Boolean.TRUE);
        return response;
    }

    @Override
    public AiTagData generateTags(AiDocumentTagRequest request, String documentContent) {
        // 模拟标签：返回 mock/document/summary 等固定候选标签。
        AiTagData response = new AiTagData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setTags(buildTags(request, documentContent));
        response.setModel(properties.getChatModel());
        response.setMocked(Boolean.TRUE);
        return response;
    }

    @Override
    public AiFileAnswerData answerSingleFileQuestion(AiFileQuestionRequest request, String documentContent) {
        // 模拟问答：把用户问题拼进固定回答，证明链路已跑通。
        AiFileAnswerData response = new AiFileAnswerData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setQuestion(request.getQuestion());
        response.setAnswer(buildAnswer(request, documentContent));
        response.setReferences(buildReferences(request));
        response.setModel(properties.getChatModel());
        response.setMocked(Boolean.TRUE);
        return response;
    }

    private String buildSummary(AiDocumentSummaryRequest request, String documentContent) {
        // StringUtils.defaultIfBlank：如果 prompt 为空白，就用 default。
        String prompt = StringUtils.defaultIfBlank(request.getPrompt(), "default");
        int contentLength = StringUtils.length(documentContent);
        return "Mock summary for " + resolveFilename(request.getFileId(), request.getFilename())
                + ". Replace MockAiProviderClient with a real model provider that consumes indexed document content."
                + " Prompt: " + prompt + "."
                + " ContextLength: " + contentLength + ".";
    }

    private List<String> buildTags(AiDocumentTagRequest request, String documentContent) {
        // 候选标签用 List 收集，最后 distinct 去重、limit 控制数量。
        List<String> candidates = new ArrayList<>();
        candidates.add("mock");
        candidates.add("document");
        candidates.add("summary");
        candidates.add("qa");
        if (StringUtils.isNotBlank(documentContent)) {
            candidates.add("indexed-content");
        }

        String filename = resolveFilename(request.getFileId(), request.getFilename());
        if (StringUtils.isNotBlank(filename)) {
            candidates.add(filename.replace('.', '-'));
        }

        int topK = request.getTopK() == null ? 5 : request.getTopK();
        return candidates.stream().distinct().limit(topK).toList();
    }

    private String buildAnswer(AiFileQuestionRequest request, String documentContent) {
        return "Mock answer for question: " + request.getQuestion()
                + ". The indexing pipeline and retrieval references are wired, but real answer generation still needs a model provider."
                + " ContextLength: " + StringUtils.length(documentContent) + ".";
    }

    private List<String> buildReferences(AiFileQuestionRequest request) {
        // 前端要求不返回引用时，直接返回空列表。
        if (Boolean.FALSE.equals(request.getIncludeReferences())) {
            return List.of();
        }
        return List.of(
                "fileId:" + request.getFileId(),
                "filename:" + resolveFilename(request.getFileId(), request.getFilename())
        );
    }

    private String resolveFilename(String fileId, String filename) {
        if (StringUtils.isNotBlank(filename)) {
            return filename;
        }
        return "file-" + StringUtils.defaultIfBlank(fileId, "unknown");
    }
}
