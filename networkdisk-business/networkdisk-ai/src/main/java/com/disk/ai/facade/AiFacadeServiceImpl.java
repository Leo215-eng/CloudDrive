package com.disk.ai.facade;

import com.disk.ai.domain.service.AiApplicationService;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.AiOperationResponse;
import com.disk.api.ai.response.data.AiDocumentIndexData;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;
import com.disk.api.ai.service.AiFacadeService;
import com.disk.rpc.facade.Facade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService(version = "1.0.0")
@RequiredArgsConstructor
// AI Dubbo 远程服务实现。
// 如果其他后端服务不走 HTTP Controller，也可以通过这个 Facade 调用 AI 能力。
public class AiFacadeServiceImpl implements AiFacadeService {

    // 远程层只做包装，真正业务仍然交给 AiApplicationServiceImpl。
    private final AiApplicationService aiApplicationService;

    @Facade
    @Override
    public AiOperationResponse<AiDocumentIndexData> indexFile(AiDocumentIndexRequest request) {
        // 远程调用：建文档向量索引。
        return AiOperationResponse.success(aiApplicationService.indexFile(request));
    }

    @Facade
    @Override
    public AiOperationResponse<AiSummaryData> summarize(AiDocumentSummaryRequest request) {
        // 远程调用：生成或读取文档摘要。
        return AiOperationResponse.success(aiApplicationService.summarize(request));
    }

    @Facade
    @Override
    public AiOperationResponse<AiTagData> generateTags(AiDocumentTagRequest request) {
        // 远程调用：生成或读取文档标签。
        return AiOperationResponse.success(aiApplicationService.generateTags(request));
    }

    @Facade
    @Override
    public AiOperationResponse<AiFileAnswerData> answerSingleFileQuestion(AiFileQuestionRequest request) {
        // 远程调用：单文件问答。
        return AiOperationResponse.success(aiApplicationService.answerSingleFileQuestion(request));
    }
}
