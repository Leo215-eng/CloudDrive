package com.disk.ai.controller;

import com.disk.ai.domain.request.DocumentIndexParamVO;
import com.disk.ai.domain.request.DocumentSummaryParamVO;
import com.disk.ai.domain.request.GenerateDocumentTagsParamVO;
import com.disk.ai.domain.request.SingleFileQuestionParamVO;
import com.disk.ai.domain.response.AiCapabilityVO;
import com.disk.ai.domain.response.DocumentIndexVO;
import com.disk.ai.domain.response.DocumentSummaryVO;
import com.disk.ai.domain.response.DocumentTagsVO;
import com.disk.ai.domain.response.SingleFileAnswerVO;
import com.disk.ai.domain.service.AiApplicationService;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiDocumentIndexData;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;
import com.disk.base.utils.IdUtil;
import com.disk.base.utils.UserIdUtil;
import com.disk.web.annotation.LoginIgnore;
import com.disk.web.vo.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
// Lombok 自动生成构造器，Spring 通过构造器把 AiApplicationService 注入进来。
@RequiredArgsConstructor
// 当前 Controller 下所有接口统一以 /api/v1/ai 开头，对应前端 ai.js 的 baseURL。
@RequestMapping("/api/v1/ai")
public class AiController {

    // AI 应用服务接口，Controller 不直接解析文件、不直接调用模型，只负责把请求交给业务层。
    private final AiApplicationService aiApplicationService;

    // 查询 AI 服务能力：当前服务名、模型名、向量库状态、支持哪些功能。
    // @LoginIgnore 表示这个状态接口不强制登录，前端打开抽屉时可以先查看服务状态。
    @LoginIgnore
    @GetMapping("/capabilities")
    public Result<AiCapabilityVO> capabilities() {
        return Result.success(aiApplicationService.getCapabilities());
    }

    // 重建或创建文件向量索引。
    // 前端传加密 fileId，后端解密成 userFileId，再让业务层解析文件、切块、生成 embedding、写入 pgvector。
    @PostMapping("/files/index")
    public Result<DocumentIndexVO> indexFile(@Valid @RequestBody DocumentIndexParamVO request) {
        // API 层请求对象转内部服务请求对象：补上当前登录用户和解密后的 userFileId。
        AiDocumentIndexRequest indexRequest = new AiDocumentIndexRequest();
        indexRequest.setUserId(UserIdUtil.get());
        indexRequest.setFileId(request.getFileId());
        indexRequest.setUserFileId(IdUtil.decrypt(request.getFileId()));
        indexRequest.setFilename(request.getFilename());
        indexRequest.setForceReindex(request.getForceReindex());

        AiDocumentIndexData data = aiApplicationService.indexFile(indexRequest);
        // Data 是业务层返回值，VO 是 Controller 返回给前端的视图对象。
        DocumentIndexVO response = new DocumentIndexVO();
        response.setFileId(data.getFileId());
        response.setFilename(data.getFilename());
        response.setMediaType(data.getMediaType());
        response.setParser(data.getParser());
        response.setBlockCount(data.getBlockCount());
        response.setChunkCount(data.getChunkCount());
        response.setVectorDimension(data.getVectorDimension());
        response.setContentLength(data.getContentLength());
        response.setIndexed(data.getIndexed());
        response.setReindexed(data.getReindexed());
        response.setVectorStoreEnabled(data.getVectorStoreEnabled());
        return Result.success(response);
    }

    /**
     * AI文档总结接口
     * @param request 前端传参：加密文件ID、文件名、自定义总结要求（prompt）
     * @return 总结结果：文件ID、文件名、总结内容、使用的模型、是否是模拟数据
     */
    @PostMapping("/files/summary")
    public Result<DocumentSummaryVO> summarize(@Valid @RequestBody DocumentSummaryParamVO request) {
        // 构造内部业务请求对象
        AiDocumentSummaryRequest summaryRequest = new AiDocumentSummaryRequest();
        // 设置当前登录用户ID
        summaryRequest.setUserId(UserIdUtil.get());
        // 存加密的文件ID（返回给前端用，不用再加密一次）
        summaryRequest.setFileId(request.getFileId());
        // 解密前端传的加密文件ID，得到user_file表的真实主键ID
        summaryRequest.setUserFileId(IdUtil.decrypt(request.getFileId()));
        // 文件名
        summaryRequest.setFilename(request.getFilename());
        // 自定义总结指令（比如“提炼3个核心要点”“用通俗语言总结”）
        summaryRequest.setPrompt(request.getPrompt());

        // 调用AI应用服务，生成总结
        AiSummaryData data = aiApplicationService.summarize(summaryRequest);

        // 封装成前端需要的视图对象
        DocumentSummaryVO response = new DocumentSummaryVO();
        response.setFileId(data.getFileId());
        response.setFilename(data.getFilename());
        response.setSummary(data.getSummary());
        response.setModel(data.getModel());
        response.setMocked(data.getMocked());

        return Result.success(response);
    }


    @PostMapping("/files/tags")
    public Result<DocumentTagsVO> generateTags(@Valid @RequestBody GenerateDocumentTagsParamVO request) {
        // 生成智能标签：先补用户身份，再交给服务层决定是读缓存还是调用模型。
        AiDocumentTagRequest tagRequest = new AiDocumentTagRequest();
        tagRequest.setUserId(UserIdUtil.get());
        tagRequest.setFileId(request.getFileId());
        tagRequest.setUserFileId(IdUtil.decrypt(request.getFileId()));
        tagRequest.setFilename(request.getFilename());
        tagRequest.setTopK(request.getTopK());

        AiTagData data = aiApplicationService.generateTags(tagRequest);
        DocumentTagsVO response = new DocumentTagsVO();
        response.setFileId(data.getFileId());
        response.setFilename(data.getFilename());
        response.setTags(data.getTags());
        response.setModel(data.getModel());
        response.setMocked(data.getMocked());
        return Result.success(response);
    }

    @PostMapping("/files/question")
    public Result<SingleFileAnswerVO> answerSingleFileQuestion(@Valid @RequestBody SingleFileQuestionParamVO request) {
        // 单文件问答：问题和文件 ID 来自前端，用户 ID 来自登录上下文。
        // 服务层会先做向量检索，再把相关文档片段和问题交给模型。
        AiFileQuestionRequest questionRequest = new AiFileQuestionRequest();
        questionRequest.setUserId(UserIdUtil.get());
        questionRequest.setFileId(request.getFileId());
        questionRequest.setUserFileId(IdUtil.decrypt(request.getFileId()));
        questionRequest.setFilename(request.getFilename());
        questionRequest.setQuestion(request.getQuestion());
        questionRequest.setIncludeReferences(request.getIncludeReferences());

        AiFileAnswerData data = aiApplicationService.answerSingleFileQuestion(questionRequest);
        SingleFileAnswerVO response = new SingleFileAnswerVO();
        response.setFileId(data.getFileId());
        response.setFilename(data.getFilename());
        response.setQuestion(data.getQuestion());
        response.setAnswer(data.getAnswer());
        response.setReferences(data.getReferences());
        response.setModel(data.getModel());
        response.setMocked(data.getMocked());
        return Result.success(response);
    }
}
