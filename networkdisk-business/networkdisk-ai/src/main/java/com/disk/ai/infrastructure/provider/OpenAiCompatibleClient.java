package com.disk.ai.infrastructure.provider;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.config.AiProviderProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "com.disk.ai.provider.type", havingValue = "openai-compatible")
// OpenAI 兼容协议客户端。
// 兼容协议指请求/响应格式接近 OpenAI：messages、choices、embeddings 等字段都按这个格式组织。
public class OpenAiCompatibleClient {

    // AI 服务配置：baseUrl、apiKey、模型名、接口路径、超时时间等。
    private final AiProviderProperties properties;

    // Spring 6 的同步 HTTP 客户端，用来向模型服务发 POST 请求。
    private final RestClient restClient;
    /**
     * 构造器注入配置，初始化HTTP客户端并设置超时
     */
    public OpenAiCompatibleClient(AiProviderProperties properties) {
        this.properties = properties;

        // 给模型请求设置连接超时和读取超时，避免外部模型服务卡住时一直阻塞线程。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 建立连接超时
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMillis());
        // 读取响应数据超时（大模型返回长文本耗时久，需单独配置）
        requestFactory.setReadTimeout(properties.getReadTimeoutMillis());
        // 构建RestClient单例
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
    /**
     * 对话大模型接口调用
     * @param systemPrompt 系统提示词：定义模型角色、回答规则、输出格式
     * @param userPrompt 用户本次提问/待处理文本
     * @return 封装后的模型回答结果（统一内部实体，隔离第三方接口结构）
     */
    public ChatResult chatCompletion(String systemPrompt, String userPrompt) {
        // 先校验 baseUrl/apiKey，缺少关键配置时直接失败，避免发出无效请求。
        validateBaseConfiguration();
        // 构造 OpenAI 兼容的 chat completion 请求体。
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(properties.getChatModel());
        request.setTemperature(properties.getTemperature());
        request.setMaxTokens(properties.getMaxTokens());

        // // 对话消息列表：system角色 + user角色两段消息
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.add(new ChatMessage("user", userPrompt));
        request.setMessages(messages);

        try {
            // POST {baseUrl}{chatPath}
            // Header: Authorization: Bearer {apiKey}
            // Body: ChatCompletionRequest JSON
            // 发起POST请求，拼接完整接口地址，设置鉴权Header与JSON请求头
            ChatCompletionResponse response = restClient.post()
                    .uri(buildUrl(properties.getBaseUrl(), properties.getChatPath()))
                    .headers(headers -> {
                        // OpenAI标准鉴权头：Bearer + apiKey
                        headers.setBearerAuth(properties.getApiKey());
                        // 请求体JSON，接收返回JSON
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(request)   // 请求体自动序列化为JSON
                    .retrieve()   // 等待响应
                    .body(ChatCompletionResponse.class);// 自动反序列为响应实体

            // 兼容接口正常应返回 choices[0].message.content。
            if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                throw new AiException("Empty chat completion response", AiErrorCode.MODEL_REQUEST_FAILED);
            }

        // ====================== 重点：只取 choices.get(0) 第一条回答 =====================
            ChatChoice choice = response.getChoices().get(0);
            String content = choice.getMessage() == null ? null : choice.getMessage().getContent();
            if (StringUtils.isBlank(content)) {
                throw new AiException("The model response content is empty", AiErrorCode.MODEL_REQUEST_FAILED);
            }

            // 转换为项目内部统一返回对象，屏蔽第三方接口字段差异
            ChatResult result = new ChatResult();
            result.setModel(StringUtils.defaultIfBlank(response.getModel(), properties.getChatModel()));
            result.setContent(content.trim());
            return result;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to request chat completion from the configured AI provider", e, AiErrorCode.MODEL_REQUEST_FAILED);
        }
    }
    /**
     * 批量生成文本向量Embedding接口
     * @param texts 待向量化文本列表
     * @return 向量数组列表，顺序严格与输入文本一一对应
     */
    public List<float[]> createEmbeddings(List<String> texts) {
        // embedding 用来把文本转成向量，后续才能做“语义相似度搜索”。
        validateBaseConfiguration();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        // 构造 OpenAI 兼容的 embeddings 请求体。
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(properties.getEmbeddingModel());
        request.setInput(texts);
        request.setDimensions(properties.getEmbeddingDimension());
        request.setEncodingFormat("float");

        try {
            // POST {baseUrl}{embeddingsPath}，一次可批量生成多个文本的向量。
            EmbeddingResponse response = restClient.post()
                    .uri(buildUrl(properties.getBaseUrl(), properties.getEmbeddingsPath()))
                    .headers(headers -> {
                        headers.setBearerAuth(properties.getApiKey());
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    })
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);

            // data 里每一项对应一个输入文本的 embedding。
            if (response == null || response.getData() == null || response.getData().isEmpty()) {
                throw new AiException("Empty embeddings response", AiErrorCode.MODEL_REQUEST_FAILED);
            }
//           OpenAI 标准 embedding 批量接口规则： 接口返回的 data 数组里每一条 EmbeddingData 都带一个 index 字段：
            // 关键：按接口返回的index排序，保证向量顺序和输入文本顺序一致
            // 第三方接口返回data数组顺序不一定和输入对齐，依赖index字段映射
            return response.getData()//拿到接口返回的 List<EmbeddingData>
                    .stream()//转成 Java 流式操作
                    //.sorted(比较器)：按 index 从小到大排序
                    .sorted(Comparator.comparingInt(value -> value.getIndex() == null ? 0 : value.getIndex()))
                    .map(this::toFloatArray)
                    .toList();
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to request embeddings from the configured AI provider", e, AiErrorCode.MODEL_REQUEST_FAILED);
        }
    }
    /**
     * 向量类型转换：接口返回List<Double> → 数据库pgvector需要float[]
     */
    private float[] toFloatArray(EmbeddingData value) {
        // 模型接口返回 List<Double>，pgvector 写入更适合 float[]，这里做类型转换。
        if (value.getEmbedding() == null || value.getEmbedding().isEmpty()) {
            throw new AiException("The embedding vector is empty", AiErrorCode.MODEL_REQUEST_FAILED);
        }
        float[] vector = new float[value.getEmbedding().size()];
        for (int i = 0; i < value.getEmbedding().size(); i++) {
            vector[i] = value.getEmbedding().get(i).floatValue();
        }
        return vector;
    }

    private void validateBaseConfiguration() {
        // openai-compatible 模式至少需要服务地址和 API Key。
        if (StringUtils.isBlank(properties.getBaseUrl())) {
            throw new AiException("com.disk.ai.provider.base-url is required when using openai-compatible mode", AiErrorCode.MODEL_REQUEST_FAILED);
        }
        if (StringUtils.isBlank(properties.getApiKey())) {
            throw new AiException("com.disk.ai.provider.api-key is required when using openai-compatible mode", AiErrorCode.MODEL_REQUEST_FAILED);
        }
    }

    private String buildUrl(String baseUrl, String path) {
        // 统一处理 baseUrl/path 前后的 /，避免拼出双斜杠或少斜杠。
        String normalizedBaseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(baseUrl), "/");
        String normalizedPath = "/" + StringUtils.removeStart(StringUtils.trimToEmpty(path), "/");
        return normalizedBaseUrl + normalizedPath;
    }

    @Data
    // 内部返回对象：只保留业务层需要的模型名和回答文本。
    public static class ChatResult {

        private String model;

        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // 聊天请求体，对应 OpenAI 兼容接口的 JSON body。
    static class ChatCompletionRequest {

        private String model;

        private List<ChatMessage> messages;

        private Double temperature;

        // Java 字段叫 maxTokens，JSON 字段必须叫 max_tokens，所以用 @JsonProperty 指定。
        @JsonProperty("max_tokens")
        private Integer maxTokens;
    }

    @Data
    // 单条消息：role 可以是 system/user/assistant。
    static class ChatMessage {

        private String role;

        private String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // 聊天响应体；ignoreUnknown 表示接口多返回的字段直接忽略，不影响反序列化。
    static class ChatCompletionResponse {

        private String model;

        private List<ChatChoice> choices;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // choices 数组里的元素，当前只关心 message。
    static class ChatChoice {

        private ChatMessage message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // embedding 请求体：input 是文本列表，dimensions 是期望向量维度。
    static class EmbeddingRequest {

        private String model;

        private List<String> input;

        private Integer dimensions;

        // JSON 字段名是 encoding_format，不是 Java 驼峰。
        @JsonProperty("encoding_format")
        private String encodingFormat;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // embedding 响应体，data 中每项对应一个输入文本。
    static class EmbeddingResponse {

        private String model;

        private List<EmbeddingData> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    // 单条向量结果：index 表示它对应 input 列表里的第几个文本。
    static class EmbeddingData {

        private Integer index;

        private List<Double> embedding;
    }
}
