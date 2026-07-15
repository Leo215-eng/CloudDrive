package com.disk.ai.domain.service;

import com.disk.ai.domain.response.AiCapabilityVO;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiDocumentIndexData;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;

// AI 应用服务接口。
// Controller、Dubbo Facade、MQ 消费者都通过它调用 AI 能力。
public interface AiApplicationService {

    // 查询当前 AI 服务能力和配置状态。
    AiCapabilityVO getCapabilities();

    // 创建或重建文档向量索引。
    AiDocumentIndexData indexFile(AiDocumentIndexRequest request);

    // 生成或读取文档摘要。
    AiSummaryData summarize(AiDocumentSummaryRequest request);

    // 生成或读取文档标签。
    AiTagData generateTags(AiDocumentTagRequest request);

    // 基于单个文件进行问答。
    AiFileAnswerData answerSingleFileQuestion(AiFileQuestionRequest request);
}
