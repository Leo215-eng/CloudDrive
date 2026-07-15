package com.disk.ai.infrastructure.provider;

import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;

// AI 模型供应商统一接口。
// 业务层只依赖这个接口，不关心底层是真模型、Mock，还是以后换成其他模型服务商。
public interface AiProviderClient {

    // 文档摘要：输入业务请求 + 文档纯文本，输出摘要结果。
    AiSummaryData summarize(AiDocumentSummaryRequest request, String documentContent);

    // 智能标签：输入业务请求 + 文档纯文本，输出标签列表。
    AiTagData generateTags(AiDocumentTagRequest request, String documentContent);

    // 单文件问答：输入问题请求 + 文档上下文，输出模型回答。
    AiFileAnswerData answerSingleFileQuestion(AiFileQuestionRequest request, String documentContent);
}
