package com.disk.ai.domain.service.impl;

import com.disk.ai.domain.response.AiCapabilityVO;
import com.disk.ai.domain.service.AiApplicationService;
import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.chunking.ParagraphTextChunker;
import com.disk.ai.infrastructure.chunking.TextChunk;
import com.disk.ai.infrastructure.config.AiIndexProperties;
import com.disk.ai.infrastructure.config.AiProviderProperties;
import com.disk.ai.infrastructure.embedding.EmbeddingClient;
import com.disk.ai.infrastructure.file.AiSourceFile;
import com.disk.ai.infrastructure.file.AiSourceFileLoader;
import com.disk.ai.infrastructure.parser.ParsedDocument;
import com.disk.ai.infrastructure.parser.TikaDocumentParser;
import com.disk.ai.infrastructure.provider.AiProviderClient;
import com.disk.ai.infrastructure.result.DocumentResultStore;
import com.disk.ai.infrastructure.result.StoredDocumentSummary;
import com.disk.ai.infrastructure.result.StoredDocumentTags;
import com.disk.ai.infrastructure.vector.DocumentIndexSummary;
import com.disk.ai.infrastructure.vector.PgVectorDocumentChunk;
import com.disk.ai.infrastructure.vector.PgVectorSearchResult;
import com.disk.ai.infrastructure.vector.VectorStore;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.api.ai.request.AiFileQuestionRequest;
import com.disk.api.ai.response.data.AiDocumentIndexData;
import com.disk.api.ai.response.data.AiFileAnswerData;
import com.disk.api.ai.response.data.AiSummaryData;
import com.disk.api.ai.response.data.AiTagData;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
// Lombok 生成包含所有 final 字段的构造器，Spring 用构造器注入下面这些依赖。
@RequiredArgsConstructor
// AI 应用服务的核心实现类：Controller 进来后，真正的摘要、标签、索引、问答都在这里编排。
public class AiApplicationServiceImpl implements AiApplicationService {

    // 大模型客户端接口：当前配置下实际实现是 OpenAiCompatibleAiProviderClient。
    private final AiProviderClient aiProviderClient;

    // AI 服务商配置：模型名、接口地址、token 限制、超时时间等。
    private final AiProviderProperties aiProviderProperties;

    // 文档切块和检索配置：chunk 大小、重叠大小、检索 topK。
    private final AiIndexProperties aiIndexProperties;

    // 文件加载器：根据 userId/fileId/userFileId 从网盘文件存储里拿原始文件。
    private final AiSourceFileLoader aiSourceFileLoader;

    // 文档解析器：用 Apache Tika 从 PDF/Word/Excel/TXT 等文件中抽取文本。
    private final TikaDocumentParser tikaDocumentParser;

    // 文本切块器：把长文档拆成适合 embedding 和检索的小片段。
    private final ParagraphTextChunker paragraphTextChunker;

    // 向量模型客户端：把文本转成 embedding 向量。
    private final EmbeddingClient embeddingClient;

    // 向量存储接口：当前配置下实际实现是 PgVectorVectorStore。
    private final VectorStore vectorStore;

    // AI 结果存储接口：保存摘要和标签，当前配置下实际实现是 PgDocumentResultStore。
    private final DocumentResultStore documentResultStore;

    @Override
    public AiCapabilityVO getCapabilities() {
        // 1. 新建一个返回给前端的 VO 对象
        AiCapabilityVO response = new AiCapabilityVO();

        // 2. 服务标识：标明这是网盘AI服务，多服务场景下做区分
        response.setService("networkdisk-ai");

        // 3. AI 服务商：从配置文件中读取（比如通义千问、DeepSeek、OpenAI等）
        // aiProviderProperties 是配置属性类，绑定 yml 里的 ai.provider 配置段
        response.setProvider(aiProviderProperties.getType());

        // 4. 对话模型名称：从配置读取，比如 qwen-plus、gpt-3.5-turbo
        // 前端展示的「模型标签」就是这个字段
        response.setChatModel(aiProviderProperties.getChatModel());

        // 5. 是否为 Mock 模拟环境：通过实现类名判断
        // 如果当前注入的 aiProviderClient 实现类名字包含 "Mock"，说明是模拟假数据
        // 前端拿到后会显示 "mock" 标记，方便开发调试
        response.setMockEnabled(aiProviderClient.getClass().getSimpleName().contains("Mock"));

        // 6. 向量存储是否就绪：当前实现是否声明向量检索可用
        // 注意：当前 PgVectorVectorStore.isReady() 固定返回 true，不是每次都 ping 数据库
        // 前端的「向量检索已启用/未启用」标签就是这个字段
        response.setVectorStoreEnabled(vectorStore.isReady());

        // 7. 嵌入向量维度：配置文件指定，比如 1536、1024
        // 向量检索的核心参数，决定向量数据的维度大小
        response.setEmbeddingDimension(aiProviderProperties.getEmbeddingDimension());

        // 8. 文档分块大小：文档切片时每块的字符数
        // 构建向量索引时，会把长文档切成 chunkSize 大小的片段
        response.setChunkSize(aiIndexProperties.getChunkSize());

        // 9. 分块重叠大小：相邻两个文档块的重叠字符数
        // 避免切块切断语义，提升检索准确率
        response.setChunkOverlap(aiIndexProperties.getChunkOverlap());

        // 10. 支持的功能清单：硬编码返回当前系统支持的AI能力列表
        // 前端可以根据这个列表做功能开关、权限判断
        response.setFeatures(List.of(
                "file-indexing",       // 文档索引构建
                "document-summary",    // 文档摘要生成
                "document-tags",       // 智能标签生成
                "single-file-question",// 单文件问答
                "pgvector-retrieval"   // 向量检索
        ));

        return response;
    }

    /**
     * 构建/重建文档向量索引核心方法
     * 完整流程：校验向量库状态 → 判断是否已建索引且无需强制重建 → 加载并解析文件 → 文本分块 → 批量生成向量Embedding
     *  → 组装向量数据 → 替换库内旧向量并更新索引摘要 → 清空过期缓存 → 封装结果返回
     * @param request 建索引请求：携带用户、文件ID、是否强制重建标识
     * @return 索引结果摘要（对外返回，不含原始向量）
     */
    @Override
    public AiDocumentIndexData indexFile(AiDocumentIndexRequest request) {
        // 建索引必须依赖向量库；如果当前是 DisabledVectorStore，就直接报错。
        // 1. 前置校验：向量存储是否启用可用
        // VectorStore有两个实现：PgVectorVectorStore(真实pg向量库实现) / DisabledVectorStore(空实现、关闭向量功能)
        // 只有注入真实PgVector实现，isReady()才返回true；禁用实现直接返回false
//        isReady() 不是“数据库状态检测”，它只是“当前 Spring 注入的是 pgvector 实现，还是 disabled 实现”的判断
        if (!vectorStore.isReady()) {
            throw new AiException(AiErrorCode.VECTOR_STORE_DISABLED);
        }

        // 2. 查询当前文件是否已存在向量索引记录
        // 先查这个文件是否已经建过索引；不是强制重建时，直接返回已有索引信息。
        DocumentIndexSummary existingSummary = vectorStore.getIndexSummary(request.getUserId(), request.getUserFileId());
        // 分支：已有索引 且 不强制重建forceReindex=false，直接复用旧索引，跳过全部解析、向量化逻辑
        if (existingSummary != null && !Boolean.TRUE.equals(request.getForceReindex())) {
            // indexed=true：已有索引；reindexed=false：本次没有重新构建
            return toIndexData(request, existingSummary, Boolean.TRUE, Boolean.FALSE);
        }

        // 3. 加载云盘原始文件资源（二进制、文件名、后缀等元数据）
        // 读取原始文件 -> 解析成纯文本和段落块 -> 按配置切成多个 TextChunk。
        AiSourceFile sourceFile = aiSourceFileLoader.load(request.getUserId(), request.getFileId(), request.getUserFileId());
        // 4. Tika通用文档解析：pdf/word/txt等转结构化文档对象，提取纯文本、分段落块
        ParsedDocument parsedDocument = tikaDocumentParser.parse(sourceFile);
        // 5. 文本切块：长文本按照固定token长度切分成多个小TextChunk，一段对应一条向量
        List<TextChunk> chunks = paragraphTextChunker.chunk(parsedDocument);
        if (chunks.isEmpty()) {
            throw new AiException(AiErrorCode.DOCUMENT_EMPTY);
        }

        // 批量把每个文本块转成 embedding 向量；向量数量必须和文本块数量一致。
        List<float[]> embeddings = embeddingClient.embedAll(chunks.stream() // 1. 将集合转成 Stream
                                                                .map(TextChunk::getText)// 2. 提取每个 TextChunk 对象的 text 字段
                                                                    .toList());       // 3. 将结果收集成一个 List
        if (embeddings.size() != chunks.size()) {
            throw new AiException(AiErrorCode.DOCUMENT_INDEX_FAILED);
        }

        // 把“文本块 + 向量 + 文件元信息”组装成 PgVectorDocumentChunk，准备写入 pgvector 表。
        // 最终入库实体集合：每条记录对应数据库 pgvector 向量表一行数据
        List<PgVectorDocumentChunk> vectorChunks = new ArrayList<>();
        // 循环遍历切块列表，下标i用来同步匹配「文本块」和「对应向量」
        for (int i = 0; i < chunks.size(); i++) {
            // 当前循环第i个文本分片（前面chunk方法切出来的TextChunk）
            TextChunk chunk = chunks.get(i);
            // 数据库存储实体：整合文件信息、段落偏移、文本、向量、元数据
            PgVectorDocumentChunk vectorChunk = new PgVectorDocumentChunk();

            // 归属用户、文件唯一标识：用于分库分表/权限过滤，查询时只查当前用户文件
            vectorChunk.setUserId(sourceFile.getUserId());
            vectorChunk.setUserFileId(sourceFile.getUserFileId());
            vectorChunk.setRealFileId(sourceFile.getRealFileId());

            // 文件基础信息，用于前端展示、文件筛选
            vectorChunk.setFilename(sourceFile.getFilename());
            vectorChunk.setFileSuffix(sourceFile.getFileSuffix());
            vectorChunk.setMediaType(parsedDocument.getMediaType());
            // 记录本次使用的Tika解析器，日志排查解析异常用
            vectorChunk.setParser(parsedDocument.getParser());

            // 段落、分片序号：用于区分同一文件内不同段落、不同切块
            vectorChunk.setBlockIndex(chunk.getBlockIndex());
            vectorChunk.setChunkIndex(chunk.getChunkIndex());

            // 文本在全文中的起止偏移：检索命中后，定位原文、高亮片段
            vectorChunk.setStartOffset(chunk.getStartOffset());
            vectorChunk.setEndOffset(chunk.getEndOffset());

            // 粗略token数量，用于前端展示、模型入参长度预估
            vectorChunk.setTokenEstimate(chunk.getTokenEstimate());
            // 存入切块原始文本，向量检索命中后直接返回原文片段
            vectorChunk.setChunkText(chunk.getText());

            // 核心：embeddings和chunks顺序一一对应，i下标同步取本条文本对应的向量
            vectorChunk.setEmbedding(embeddings.get(i));

            // 组装完成，加入入库列表，批量插入数据库
            vectorChunks.add(vectorChunk);
        }

        // 替换旧索引：先删这个文件旧的向量块，再插入新的向量块和索引摘要。
//        ① sourceFile：AiSourceFile 原始文件信息。
//        ② parsedDocument：ParsedDocument 文档解析结果
//        ③ vectorChunks：List<PgVectorDocumentChunk> 待入库向量分片集合
        vectorStore.replaceDocument(sourceFile, parsedDocument, vectorChunks);
        // 文件内容变了，旧摘要/旧标签可能过期，所以清掉结果缓存。
        documentResultStore.clearDocumentResult(sourceFile.getUserId(), sourceFile.getUserFileId());

        // 返回给前端的索引结果摘要，不返回具体向量内容。
        DocumentIndexSummary summary = new DocumentIndexSummary();
        summary.setFilename(sourceFile.getFilename());
        summary.setMediaType(parsedDocument.getMediaType());
        summary.setParser(parsedDocument.getParser());
        summary.setBlockCount(parsedDocument.getBlocks().size());
        summary.setChunkCount(chunks.size());
        summary.setVectorDimension(embeddingClient.dimension());
        summary.setContentLength((long) sourceFile.getBytes().length);
        return toIndexData(request, summary, Boolean.TRUE, existingSummary != null);
    }

    /**
     * 生成文档总结核心方法
     * @param request 总结请求参数
     * @return 总结结果数据
     */
    @Override
    public AiSummaryData summarize(AiDocumentSummaryRequest request) {
        // ========== 第一步：先查缓存，有现成结果就直接返回 ==========
        // 判断当前场景是否可以使用已存储的总结结果
        if (canUseStoredSummary(request)) {
            // 从结果存储库中，取出该用户该文件的历史总结
            StoredDocumentSummary storedSummary = documentResultStore.getSummary(
                    request.getUserId(),
                    request.getUserFileId()
            );
            // 缓存存在且内容不为空，就直接封装成返回结果，不用调大模型
            if (storedSummary != null && StringUtils.isNotBlank(storedSummary.getSummary())) {
                return buildStoredSummaryResponse(request, storedSummary);
            }
        }

        // ========== 第二步：没有缓存，加载文档的纯文本内容 ==========
        // 把PDF/Word/Excel等文件里的文字提取出来，变成纯文本，才能传给大模型
        // 内部会做：文件类型校验、文本提取、长度截断、缓存文本内容
        DocumentPromptContext documentContext = loadDocumentContext(
                request.getUserId(),
                request.getFileId(),
                request.getUserFileId()
        );

        // 把文件名补充到prompt指令里，让大模型知道总结的是什么文件
        enrichFilename(request, documentContext.filename());

        // ========== 第三步：调用大模型客户端，生成总结内容 ==========
        AiSummaryData response = aiProviderClient.summarize(request, documentContext.content());

        // ========== 第四步：判断是否需要把生成的结果存进缓存 ==========
        if (canPersistSummary(request, response)) {
            // 把总结结果保存到存储库，下次同一个用户同一个文件就直接走缓存
            documentResultStore.saveSummary(
                    request.getUserId(),
                    request.getUserFileId(),
                    response.getFilename(),
                    response.getSummary(),
                    response.getModel(),
                    response.getMocked()
            );
        }

        // 返回最终结果
        return response;
    }


    @Override
    public AiTagData generateTags(AiDocumentTagRequest request) {
        // 标签也先尝试复用已保存结果；标签数量足够时不用再次调用模型。
        if (canUseStoredTags(request)) {
            StoredDocumentTags storedTags = documentResultStore.getTags(request.getUserId(), request.getUserFileId());
            if (canSatisfyRequestedTagCount(storedTags, request.getTopK())) {
                return buildStoredTagResponse(request, storedTags);
            }
        }

        // 没有可用缓存时，加载文档文本并调用模型生成标签。
        DocumentPromptContext documentContext = loadDocumentContext(request.getUserId(), request.getFileId(), request.getUserFileId());
        enrichFilename(request, documentContext.filename());
        AiTagData response = aiProviderClient.generateTags(request, documentContext.content());
        // 默认标签结果会落库，下次同一文件可直接复用。
        if (canPersistTags(request, response)) {
            documentResultStore.saveTags(
                    request.getUserId(),
                    request.getUserFileId(),
                    response.getFilename(),
                    response.getTags(),
                    response.getModel(),
                    response.getMocked()
            );
        }
        return response;
    }

    @Override
    public AiFileAnswerData answerSingleFileQuestion(AiFileQuestionRequest request) {
        // RAG 第一步：把问题转向量，去 pgvector 查当前文件最相关的文本块。
//        得到这个TOP k个对象
//        chunkIndex：命中的第几个文本切块
//        blockIndex：这个 chunk 属于原文第几个段落块
//        chunkText：命中的原文片段内容
//        similarity：相似度分数，越高越相关
        List<PgVectorSearchResult> hits = searchRelevantChunks(request);
        // RAG 第二步：如果查到相关块，就只把相关块给模型；查不到才退回整篇文档上下文。
        DocumentPromptContext questionContext = resolveQuestionContext(request, hits);
        enrichFilename(request, questionContext.filename());

        // RAG 第三步：将用户提问 + 筛选后的文档上下文 送入大模型生成回答
        AiFileAnswerData data = aiProviderClient.answerSingleFileQuestion(request, questionContext.content());
        // 分支1：前端配置不需要展示原文引用，直接清空引用列表返回结果，引用就是检索到的文档
        if (Boolean.FALSE.equals(request.getIncludeReferences())) {
            data.setReferences(List.of());
            return data;
        }
        // 分支2：存在检索命中的文本块，组装引用信息（段落、分片、相似度、原文片段）返回前端
        if (!hits.isEmpty()) {
            data.setReferences(buildReferences(hits));
        }
        return data;
    }

    private List<PgVectorSearchResult> searchRelevantChunks(AiFileQuestionRequest request) {
        // 向量库不可用、缺用户或缺文件 ID 时，直接不做检索，后续会退回全文解析。
        if (!vectorStore.isReady() || request.getUserId() == null || request.getUserFileId() == null) {
            return List.of();
        }
        // 文件没有建过索引时，也无法做向量检索。
        DocumentIndexSummary summary = vectorStore.getIndexSummary(request.getUserId(), request.getUserFileId());
        if (summary == null) {
            return List.of();
        }
        // 查询问题向量最相似的前 topK 个文档块。
        return vectorStore.search(
                request.getUserId(),
                request.getUserFileId(),
                embeddingClient.embed(request.getQuestion()),
                aiIndexProperties.getRetrievalTopK()
        );
    }

    private DocumentPromptContext resolveQuestionContext(AiFileQuestionRequest request, List<PgVectorSearchResult> hits) {
        // 有检索结果：只拼接语义相关片段，大幅减少输入token，省钱、减少无关信息干扰模型
        if (!hits.isEmpty()) {
            return new DocumentPromptContext(
                    request.getFilename(),
                    // 取出所有命中分片文本，拼接并做长度截断，防止上下文超长超限
                    mergeWithLimit(hits.stream().map(PgVectorSearchResult::getChunkText).toList())
            );
        }
        // 极端情况：向量检索无任何相似片段，兜底加载整篇文档全文作为上下文
        // 缺点：token消耗高，无关内容多，回答精准度下降；优点：不会完全无法回答
        return loadDocumentContext(request.getUserId(), request.getFileId(), request.getUserFileId());
    }

    /**
     * 加载文档的纯文本上下文，用于传给大模型
     * 优先级：向量库已索引的文本 > 当场解析原文件
     * @param userId 用户ID
     * @param fileId 加密文件ID
     * @param userFileId 解密后的用户文件ID
     * @return 封装好的文件名+纯文本内容
     */
    private DocumentPromptContext loadDocumentContext(Long userId, String fileId, Long userFileId) {
        // ========== 第一分支：优先从向量库拿已解析好的文本 ==========
        // 向量库就绪 + 参数合法，才尝试走向量库路径
        if (vectorStore.isReady() && userId != null && userFileId != null) {
            // 先查这个文件有没有被索引过（有没有做过向量化处理）
            DocumentIndexSummary summary = vectorStore.getIndexSummary(userId, userFileId);

            if (summary != null) {
                // 已经索引过：把该文件所有的文本分块从向量库加载出来
                String indexedContent = mergeWithLimit(
                        vectorStore.loadDocumentChunks(userId, userFileId)
                );

                // 合并后的文本不为空，直接返回，不用再解析原文件
                if (StringUtils.isNotBlank(indexedContent)) {
                    return new DocumentPromptContext(summary.getFilename(), indexedContent);
                }
            }
        }

        // ========== 第二分支：向量库没有，兜底走原始文件解析 ==========
        // 1. 从文件存储系统加载原始文件（拿到文件流、文件名等信息）
        AiSourceFile sourceFile = aiSourceFileLoader.load(userId, fileId, userFileId);
        // 2. 用 Tika 工具解析文件，提取纯文本（支持PDF/Word/PPT/TXT等多种格式）
        ParsedDocument parsedDocument = tikaDocumentParser.parse(sourceFile);

        // 3. 截断文本到最大长度，防止超出大模型的上下文窗口限制
        String limitedContent = limitContent(parsedDocument.getPlainText());
        return new DocumentPromptContext(sourceFile.getFilename(), limitedContent);
    }


    /**
     * 补全请求里的文件名
     * 前端可能只传了文件ID没传文件名，这里用实际加载出来的真实文件名补上
     * @param request 总结请求
     * @param filename 实际加载到的文件名
     */
    private void enrichFilename(AiDocumentSummaryRequest request, String filename) {
        // 请求里的文件名为空，且实际加载到了文件名，就赋值进去
        if (StringUtils.isBlank(request.getFilename()) && StringUtils.isNotBlank(filename)) {
            request.setFilename(filename);
        }
    }


    private void enrichFilename(AiDocumentTagRequest request, String filename) {
        if (StringUtils.isBlank(request.getFilename()) && StringUtils.isNotBlank(filename)) {
            request.setFilename(filename);
        }
    }

    private void enrichFilename(AiFileQuestionRequest request, String filename) {
        if (StringUtils.isBlank(request.getFilename()) && StringUtils.isNotBlank(filename)) {
            request.setFilename(filename);
        }
    }

    /**
     * 判断当前请求是否可以使用已存储的历史总结结果
     * 满足所有条件才允许走缓存，避免返回不符合要求的结果
     * @param request 总结请求参数
     * @return true=可以用缓存，false=必须重新生成
     */
    private boolean canUseStoredSummary(AiDocumentSummaryRequest request) {
        return documentResultStore.isReady()
                // 条件2：用户ID不为空，能定位到是谁的总结
                && request.getUserId() != null
                // 条件3：用户文件ID不为空，能定位到是哪个文件的总结
                && request.getUserFileId() != null
                // 条件4：用户没有传自定义 prompt。自定义要求不同，旧摘要不能安全复用。
                && StringUtils.isBlank(request.getPrompt());
    }


    private boolean canPersistSummary(AiDocumentSummaryRequest request, AiSummaryData response) {
        // 只有“可复用的默认摘要”才保存；自定义 prompt 的摘要不落库，避免下次误用。
        return canUseStoredSummary(request) && StringUtils.isNotBlank(response.getSummary());
    }

    private AiSummaryData buildStoredSummaryResponse(AiDocumentSummaryRequest request, StoredDocumentSummary storedSummary) {
        // 数据库里的 StoredDocumentSummary 转成业务接口统一返回的 AiSummaryData。
        AiSummaryData response = new AiSummaryData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(StringUtils.defaultIfBlank(request.getFilename(), storedSummary.getFilename()));
        response.setSummary(storedSummary.getSummary());
        response.setModel(storedSummary.getModel());
        response.setMocked(storedSummary.getMocked());
        return response;
    }

    private boolean canUseStoredTags(AiDocumentTagRequest request) {
        // 标签没有自定义 prompt，只有存储可用且能定位用户文件，就可以尝试复用。
        return documentResultStore.isReady()
                && request.getUserId() != null
                && request.getUserFileId() != null;
    }

    private boolean canPersistTags(AiDocumentTagRequest request, AiTagData response) {
        // 标签非空才值得保存。
        return canUseStoredTags(request) && response.getTags() != null && !response.getTags().isEmpty();
    }

    private boolean canSatisfyRequestedTagCount(StoredDocumentTags storedTags, Integer topK) {
        // 已存标签数量要 >= 本次需要的数量，才算缓存可用。
        return storedTags != null
                && storedTags.getTags() != null
                && !storedTags.getTags().isEmpty()
                && storedTags.getTags().size() >= (topK == null ? 5 : topK);
    }

    private AiTagData buildStoredTagResponse(AiDocumentTagRequest request, StoredDocumentTags storedTags) {
        // 如果本次只要 3 个标签，而库里有 5 个，就只返回前 3 个。
        int limit = request.getTopK() == null ? 5 : request.getTopK();
        AiTagData response = new AiTagData();
        response.setUserId(request.getUserId());
        response.setFileId(request.getFileId());
        response.setFilename(StringUtils.defaultIfBlank(request.getFilename(), storedTags.getFilename()));
        response.setTags(storedTags.getTags().stream().limit(limit).toList());
        response.setModel(storedTags.getModel());
        response.setMocked(storedTags.getMocked());
        return response;
    }

    /**
     * 合并多段文档文本，同时严格控制总字符上限，避免传给大模型的上下文超长报错
     * 拼接规则：段落之间用两个换行分隔；超出最大字符后截断不再追加内容
     * @param texts 文档拆分后的文本块列表
     * @return 拼接完成、不超限的完整上下文字符串
     */
    private String mergeWithLimit(List<String> texts) {
        // 文本块为空集合，直接返回空字符串
        if (texts == null || texts.isEmpty()) {
            return StringUtils.EMPTY;
        }
        // 字符串拼接容器
        StringBuilder builder = new StringBuilder();
        // 读取配置文件中配置的文档最大允许字符长度，未配置则设为整数最大值（不限制）
        int maxChars = aiProviderProperties.getMaxDocumentChars() == null
                ? Integer.MAX_VALUE
                : aiProviderProperties.getMaxDocumentChars();

        // 循环遍历每一段文本
        for (String text : texts) {
            // 跳过空白文本 / 当前已达到字符上限，不再处理后续文本
            if (StringUtils.isBlank(text) || builder.length() >= maxChars) {
                continue;
            }
            // 已有内容时，段落之间添加两个换行分隔，区分不同文本块
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            // 计算当前还能容纳多少字符
            int remaining = maxChars - builder.length();
            if (text.length() <= remaining) {
                // 当前段落完整放入剩余空间，直接全量拼接
                builder.append(text);
            } else {
                // 本段文本过长，只截取剩余容量对应的字符，防止超限
                builder.append(text, 0, Math.max(0, remaining));
            }
        }
        // 去除首尾空白后返回最终拼接文本
        return builder.toString().trim();
    }


    private String limitContent(String text) {
        // 全文兜底路径也要限制长度，避免超出模型上下文窗口或请求体过大。
        String content = StringUtils.defaultString(text);
        int maxChars = aiProviderProperties.getMaxDocumentChars() == null ? Integer.MAX_VALUE : aiProviderProperties.getMaxDocumentChars();
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars);
    }

    private List<String> buildReferences(List<PgVectorSearchResult> hits) {
        // 返回给前端的引用说明，不是完整文档，只给 chunk 编号、相似度和 120 字摘要。
        return hits.stream()
                .map(hit -> "chunk#" + hit.getChunkIndex() + " score=" + String.format("%.4f", hit.getSimilarity())
                        + " text=" + StringUtils.abbreviate(hit.getChunkText(), 120))
                .toList();
    }

    private AiDocumentIndexData toIndexData(AiDocumentIndexRequest request,
                                            DocumentIndexSummary summary,
                                            Boolean indexed,
                                            Boolean reindexed) {
        // 把向量库内部索引摘要转换成接口返回对象。
        AiDocumentIndexData data = new AiDocumentIndexData();
        data.setUserId(request.getUserId());
        data.setFileId(request.getFileId());
        data.setUserFileId(request.getUserFileId());
        data.setFilename(summary.getFilename());
        data.setMediaType(summary.getMediaType());
        data.setParser(summary.getParser());
        data.setBlockCount(summary.getBlockCount());
        data.setChunkCount(summary.getChunkCount());
        data.setVectorDimension(summary.getVectorDimension());
        data.setContentLength(summary.getContentLength());
        data.setIndexed(indexed);
        data.setReindexed(reindexed);
        data.setVectorStoreEnabled(vectorStore.isReady());
        return data;
    }

    // record 是 Java 的轻量数据载体，这里只用来临时携带“文件名 + 给模型的文本内容”。
    private record DocumentPromptContext(String filename, String content) {
    }
}
