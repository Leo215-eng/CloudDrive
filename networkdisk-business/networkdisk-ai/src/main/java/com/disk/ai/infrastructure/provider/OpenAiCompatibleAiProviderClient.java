package com.disk.ai.infrastructure.provider;

import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "com.disk.ai.provider.type", havingValue = "openai-compatible")
// 只有配置 com.disk.ai.provider.type=openai-compatible 时，Spring 才创建这个类。
// 这一层不直接拼 HTTP，而是负责把“摘要/标签/问答”转换成对应的 system prompt 和 user prompt。
public class OpenAiCompatibleAiProviderClient implements AiProviderClient {

    // 底层 OpenAI 兼容 HTTP 客户端，真正负责请求 /chat/completions 和 /embeddings。
    private final OpenAiCompatibleClient openAiCompatibleClient;

    @Override
    public AiSummaryData summarize(AiDocumentSummaryRequest request, String documentContent) {
        // 摘要场景：system prompt 规定模型角色，user prompt 放文件名、额外要求和文档内容。
        // 调用兼容OpenAI接口的对话客户端生成摘要
        // 第一个参数systemPrompt：固定设定模型身份、输出规则（中文简洁摘要、抓重点/结构/结论）
        // 第二个参数userPrompt：拼接文件名、用户额外要求、完整文档正文
        OpenAiCompatibleClient.ChatResult result = openAiCompatibleClient.chatCompletion(
                "You are a document analysis assistant. Summarize the document in concise Chinese. Focus on key purpose, structure, and conclusions.",
                buildSummaryPrompt(request, documentContent)
        );

        // 把底层模型返回封装成业务层统一的 AiSummaryData。
        AiSummaryData response = new AiSummaryData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setSummary(result.getContent());
        response.setModel(result.getModel());
        // 标记不是本地模拟假数据，是真实AI接口生成
        response.setMocked(Boolean.FALSE);
        return response;
    }

    @Override
    public AiTagData generateTags(AiDocumentTagRequest request, String documentContent) {
        // 调用大模型对话接口生成标签
        // system提示词：固定角色，强制约束输出格式：只输出简短中文标签，逗号分隔
        OpenAiCompatibleClient.ChatResult result = openAiCompatibleClient.chatCompletion(
                "You are a document tagging assistant. Return only concise Chinese tags separated by commas.",
                buildTagPrompt(request, documentContent)
        );

        AiTagData response = new AiTagData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setTags(parseTags(result.getContent(), request.getTopK()));
        response.setModel(result.getModel());
        response.setMocked(Boolean.FALSE);
        return response;
    }

    @Override
    public AiFileAnswerData answerSingleFileQuestion(AiFileQuestionRequest request, String documentContent) {
        // 问答场景：要求模型只基于提供的文档上下文回答，不足时明确说明。
        OpenAiCompatibleClient.ChatResult result = openAiCompatibleClient.chatCompletion(
                "You are a single-file question answering assistant. Answer in Chinese and use only the provided document context. If the context is insufficient, say so explicitly.",
                buildQuestionPrompt(request, documentContent)
        );

        AiFileAnswerData response = new AiFileAnswerData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(resolveFilename(request.getFileId(), request.getFilename()));
        response.setQuestion(request.getQuestion());
        response.setAnswer(result.getContent());
        response.setModel(result.getModel());
        response.setMocked(Boolean.FALSE);
        return response;
    }

    /**
     * 组装摘要场景专属用户提示词
     * 把文件名、用户自定义附加要求、文档原文拼接成一段完整文本，作为Chat消息里user角色的内容
     * @param request 摘要请求入参，携带用户自定义prompt、文件ID、文件名
     * @param documentContent 清洗后的完整文档正文
     * @return 拼接完成的用户侧提示文本
     */
    private String buildSummaryPrompt(AiDocumentSummaryRequest request, String documentContent) {
        // user prompt 是普通字符串，最终会作为 messages 里的 user 消息发给模型。
        StringBuilder prompt = new StringBuilder();

        // 第一行：告知模型当前处理的文件名称，辅助模型区分文档上下文
        prompt.append("Filename: ").append(resolveFilename(request.getFileId(), request.getFilename())).append("\n");

        // 第二段：说明如果用户有额外自定义要求，必须优先遵守
        prompt.append("If an extra user prompt is present, follow it:\n");
        // 获取用户自定义摘要要求；如果用户没填，则填充默认文字 No extra prompt
        prompt.append(StringUtils.defaultIfBlank(request.getPrompt(), "No extra prompt")).append("\n\n");

        // 第三块：告知模型下面是完整文档正文
        prompt.append("Document content:\n");
        // 填充文档全文；极端文档为空时兜底填充 No content，防止传给模型空白文本
        prompt.append(StringUtils.defaultIfBlank(documentContent, "No content"));

        return prompt.toString();
    }

    /**
     * 组装生成标签场景的用户提示词
     * @param request 标签请求参数，携带topK最大标签数量
     * @param documentContent 文档正文
     * @return 拼接完成的user角色完整提示文本
     */
    private String buildTagPrompt(AiDocumentTagRequest request, String documentContent) {
        // topK 控制最多返回多少个标签；为空时默认 5 个。
        StringBuilder prompt = new StringBuilder();
        // 告知模型当前文档名称，辅助识别文档主题
        prompt.append("Filename: ").append(resolveFilename(request.getFileId(), request.getFilename())).append("\n");
        // 限制标签最大数量，未传topK则兜底5个
        prompt.append("Generate at most ").append(request.getTopK() == null ? 5 : request.getTopK()).append(" tags.\n");
        // 强制输出规范：只返回标签、逗号隔开，禁止额外解释、段落、换行
        prompt.append("Return only tags separated by commas, no explanation.\n\n");
        // 下文是文档原文
        prompt.append("Document content:\n");
        // 文档为空兜底文本，防止空白内容传给模型
        prompt.append(StringUtils.defaultIfBlank(documentContent, "No content"));
        return prompt.toString();
    }

    private String buildQuestionPrompt(AiFileQuestionRequest request, String documentContent) {
        // documentContent 通常来自向量检索命中的片段；没有索引时会退回全文截断内容。
        StringBuilder prompt = new StringBuilder();
        prompt.append("Filename: ").append(resolveFilename(request.getFileId(), request.getFilename())).append("\n");
        prompt.append("Question: ").append(StringUtils.defaultString(request.getQuestion())).append("\n\n");
        prompt.append("Document context:\n");
        prompt.append(StringUtils.defaultIfBlank(documentContent, "No content"));
        return prompt.toString();
    }

    private List<String> parseTags(String content, Integer topK) {
        // 模型返回是文本，这里做最小解析：逗号/分号转换行，去掉序号，去重并限制数量。
        int limit = topK == null ? 5 : topK;
        String normalized = StringUtils.defaultString(content)
                .replace(',', '\n')
                .replace(';', '\n');

        return Arrays.stream(normalized.split("\\n"))
                .map(String::trim)
                .map(value -> value.replaceAll("^[\\-*\\d.\\s]+", ""))
                .filter(StringUtils::isNotBlank)
                .map(value -> StringUtils.substring(value, 0, Math.min(value.length(), 30)))
                .distinct()
                .limit(limit)
                .toList();
    }

    private String resolveFilename(String fileId, String filename) {
        // 前端没传文件名时，用 fileId 构造一个兜底名字，避免 prompt 里文件名为空。
        if (StringUtils.isNotBlank(filename)) {
            return filename;
        }
        return "file-" + StringUtils.defaultIfBlank(fileId, "unknown");
    }
}
