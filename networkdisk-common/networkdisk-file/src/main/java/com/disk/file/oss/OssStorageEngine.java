package com.disk.file.oss;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson2.JSONObject;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadResult;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadResult;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.UploadPartRequest;
import com.aliyun.oss.model.UploadPartResult;
import com.disk.base.constant.BaseConstant;
import com.disk.base.exception.SystemException;
import com.disk.base.utils.EmptyUtil;
import com.disk.base.utils.FileUtil;
import com.disk.base.utils.UUIDUtil;
import com.disk.file.config.OssStorageEngineConfig;
import com.disk.file.context.DeleteFileContext;
import com.disk.file.context.MergeFileContext;
import com.disk.file.context.ReadFileContext;
import com.disk.file.context.StoreFileChunkContext;
import com.disk.file.context.StoreFileContext;
import com.disk.file.core.AbstractStorageEngine;
import com.disk.lock.DistributeLock;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * OSS 存储实现。
 *
 * <p>把 {@link com.disk.file.core.StorageEngine} 的统一操作转换为阿里云 OSS SDK 调用。
 * 普通文件一次上传；大文件使用 OSS 的 multipart upload：初始化得到 {@code uploadId}，
 * 上传每个 part，最后按 {@code PartETag} 列表完成合并。</p>
 *
 * <p>{@code @Component} 让 Spring 创建该 Bean；{@code @ConditionalOnBean(OSSClient.class)}
 * 表示只有配置了 OSS 客户端时才启用它。</p>
 */
// 注册为 Spring 组件，供业务层按 StorageEngine 注入。
@Component
// 仅当 Spring 容器已有 OSSClient 时才创建该组件。
@ConditionalOnBean(OSSClient.class)
public class OssStorageEngine extends AbstractStorageEngine {

    // OSS 单次 tmultipar upload 最多允许 10,000 个分片。
    private static final Integer TEN_THOUSAND_INT = 10000;

    // 缓存键模板：用文件 MD5 和用户 ID 唯一定位一次上传任务。
    private static final String CACHE_KEY_TEMPLATE = "oss_cache_upload_id_%s_%s";

    // 内部路径参数名：文件唯一标识。
    private static final String IDENTIFIER_KEY = "identifier";

    // 内部路径参数名：OSS 分片上传任务 ID。
    private static final String UPLOAD_ID_KEY = "uploadId";

    // 内部路径参数名：上传用户 ID。
    private static final String USER_ID_KEY = "userId";

    // 内部路径参数名：OSS 分片序号。
    private static final String PART_NUMBER_KEY = "partNumber";

    // 内部路径参数名：OSS 返回的分片校验回执。
    private static final String E_TAG_KEY = "eTag";

    // 内部路径参数名：该分片的字节数。
    private static final String PART_SIZE_KEY = "partSize";

    // 内部路径参数名：OSS 计算的分片 CRC 校验值。
    private static final String PART_CRC_KEY = "partCRC";

    // 注入 application.yml 中绑定的 bucket、endpoint 等 OSS 配置。
    @Autowired
    private OssStorageEngineConfig config;

    // 注入阿里云 OSS SDK 客户端，所有远程存储请求都由它发出。
    @Autowired
    private OSSClient client;

    /** 一次性上传普通文件。{@code putObject(bucket, key, stream)} 会把输入流写入 OSS。 */
    // 覆盖父类定义的普通文件存储钩子。
    @Override
    protected void doStore(StoreFileContext context) throws IOException {
        // 从原始文件名取后缀，并生成不重复的 OSS object key。
        String realPath = getFilePath(FileUtil.getFileSuffix(context.getFilename()));
        // 调用 SDK：把输入流的字节一次性写入指定 bucket/key。
        client.putObject(config.getBucketName(), realPath, context.getInputStream());
        // 回填 object key，业务层会把它保存到 file.real_path。
        context.setRealPath(realPath);
    }

    /**
     * 删除真实文件；若路径带有分片元数据，则取消未完成的 multipart upload。
     * 分片路径形如 {@code objectKey?uploadId=...&partNumber=...}，不是 OSS 的真实 URL，
     * 只是本服务用于在数据库中保存合并所需信息的字符串。
     */
    // 覆盖父类定义的物理删除钩子。
    @Override
    protected void doDelete(DeleteFileContext context) throws IOException {
        // 一个删除请求可以包含多个真实路径。
        List<String> realFilePathList = context.getRealFilePathList();
        // 对每个路径执行相应的删除操作。
        realFilePathList.stream().forEach(realPath -> {

            // 有查询参数说明是尚未合并的分片记录。
            if (checkHaveParams(realPath)) {
                // 将内部路径中的 uploadId 等参数解析出来。
                JSONObject params = analysisUrlParams(realPath);
                // 参数完整时才可以安全取消 OSS 上传任务。
                if (Objects.nonNull(params) && !params.isEmpty()) {
                    // 取出 OSS 用于定位上传任务的 uploadId。
                    String uploadId = params.getString(UPLOAD_ID_KEY);
                    // 取出文件标识，用于删除对应缓存。
                    String identifier = params.getString(IDENTIFIER_KEY);
                    // 取出用户 ID，避免不同用户的任务冲突。
                    Long userId = params.getLong(USER_ID_KEY);
                    // 重新计算该上传任务的缓存键。
                    String cacheKey = getCacheKey(identifier, userId);

                    // 上传任务取消后，uploadId 不能再复用。
                    getCache().evict(cacheKey);

                    try {
                        // 构造“取消分片上传”请求：bucket、object key、uploadId 缺一不可。
                        AbortMultipartUploadRequest request = new AbortMultipartUploadRequest(config.getBucketName(), getBaseUrl(realPath), uploadId);
                        // 调用 OSS 丢弃已上传但尚未合并的所有分片。
                        client.abortMultipartUpload(request);
                    } catch (Exception e) {
                        // 取消失败不阻断其余文件删除；原始异常由 OSS 侧任务过期机制兜底。
                    }
                }
            }
            // 完整文件的 realPath 就是 OSS object key。
            else {
                // 调用 OSS 删除已经完成的完整对象。
                client.deleteObject(config.getBucketName(), realPath);
            }

        });
    }

    /**
     * 上传一个 OSS 分片。
     * {@code @DistributeLock} 以“用户 + 文件标识”加锁，避免并发请求重复初始化上传任务；
     * 缓存保存同一文件共用的 {@code uploadId} 和 {@code objectKey}。
     */
    // 通过 AOP 加分布式锁；SpEL 表达式从 context 取用户和文件标识生成锁键。
    @DistributeLock(scene = "ossFile",  keyExpression = "#context.userId + '-' +context.identifier", expireTime = 10000)
    // 覆盖父类定义的分片存储钩子。
    @Override
    protected void doStoreChunk(StoreFileChunkContext context) throws IOException {

        // 提前校验 OSS 的最大分片数限制，避免发出必然失败的远程请求。
        if (context.getTotalChunks() > TEN_THOUSAND_INT) {
            // 超限即以业务异常终止上传。
            throw new SystemException("分片数超过了限制，分片数不得大于： " + TEN_THOUSAND_INT);
        }

        // 同一用户的同一文件必须使用同一个 uploadId。
        String cacheKey = getCacheKey(context.getIdentifier(), context.getUserId());

        // 从缓存获取此前初始化的 uploadId 和 object key。
        ChunkUploadEntity entity = getCache().get(cacheKey, ChunkUploadEntity.class);

        // 首个分片没有缓存时，先在 OSS 创建一个 multipart upload。
        if (EmptyUtil.isEmpty(entity)) {
            // 初始化后会把新任务信息写回缓存。
            entity = initChunkUpload(context.getFilename(), cacheKey);
        }

        // UploadPartRequest 描述“把哪一段流上传到哪个 OSS 上传任务”。
        UploadPartRequest request = new UploadPartRequest();
        // 指定目标 bucket。
        request.setBucketName(config.getBucketName());
        // 指定最终对象的 key。
        request.setKey(entity.getObjectKey());
        // 指定当前分片所属的 OSS 上传任务。
        request.setUploadId(entity.getUploadId());
        // 指定本次 HTTP 请求携带的分片字节流。
        request.setInputStream(context.getInputStream());
        // 指定本分片大小，而不是整个文件大小。
        request.setPartSize(context.getCurrentChunkSize());
        // 指定本分片编号，OSS 合并时依此排序。
        request.setPartNumber(context.getChunkNumber());

        // 向 OSS 上传该分片并取得服务端回执。
        UploadPartResult result = client.uploadPart(request);

        // 空回执说明 OSS 未接受该分片。
        if (EmptyUtil.isEmpty(result)) {
            // 交由上层返回上传失败。
            throw new SystemException("文件分片上传失败");
        }

        // OSS 返回的 ETag 是该分片的回执；完成合并时必须原样提交。
        PartETag partETag = result.getPartETag();

        // 将合并所需回执编码进路径字符串，作为 file_chunk.real_path 保存。
        JSONObject params = new JSONObject();
        // 保存文件标识，供清理缓存时定位任务。
        params.put(IDENTIFIER_KEY, context.getIdentifier());
        // 保存同一 multipart upload 的任务 ID。
        params.put(UPLOAD_ID_KEY, entity.getUploadId());
        // 保存上传者，确保缓存键可复原。
        params.put(USER_ID_KEY, context.getUserId());
        // 保存分片编号，合并时恢复正确顺序。
        params.put(PART_NUMBER_KEY, partETag.getPartNumber());
        // 保存 OSS 要求提交回去的 ETag。
        params.put(E_TAG_KEY, partETag.getETag());
        // 保存分片大小，构造 PartETag 时需要。
        params.put(PART_SIZE_KEY, partETag.getPartSize());
        // 保存 CRC 校验值，构造 PartETag 时需要。
        params.put(PART_CRC_KEY, partETag.getPartCRC());

        // 将 object key 和分片回执合成可落库的内部路径。
        String realPath = assembleUrl(entity.getObjectKey(), params);

        // 回填分片记录的 realPath，后续 mergeFile 会读取它。
        context.setRealPath(realPath);
    }

    /**
     * 完成分片上传。解析各分片保存的 ETag，调用 OSS 合并；成功后 objectKey 成为完整文件路径。
     */
    // 覆盖父类定义的分片合并钩子。
    @Override
    protected void doMergeFile(MergeFileContext context) throws IOException {

        // 用同样的规则找到分片上传阶段写入的缓存。
        String cacheKey = getCacheKey(context.getIdentifier(), context.getUserId());

        // 读取 uploadId 和最终 object key。
        ChunkUploadEntity entity = getCache().get(cacheKey, ChunkUploadEntity.class);

        // 缓存丢失时无法告诉 OSS 要合并哪次上传任务。
        if (EmptyUtil.isEmpty(entity)) {
            // 因而直接终止合并，避免生成不完整文件。
            throw new SystemException("文件分片合并失败，文件的唯一标识为：" + context.getIdentifier());
        }

        // 每个 chunkPath 都携带一个 part 的 ETag 等回执信息。
        List<String> chunkPaths = context.getRealPathList();
        // 创建提交给 OSS 的分片回执集合。
        List<PartETag> partETags = Lists.newArrayList();
        // 仅在有分片记录时执行解析。
        if (CollectionUtils.isNotEmpty(chunkPaths)) {
            // Stream 按步骤过滤并转换所有内部路径为 OSS PartETag。
            partETags = chunkPaths.stream()
                    // 跳过空路径，避免解析异常。
                    .filter(StringUtils::isNotBlank)
                    // 从内部路径解析 JSON 参数。
                    .map(this::analysisUrlParams)
                    // 排除解析失败的空对象。
                    .filter(Objects::nonNull)
                    // 排除不包含任何参数的对象。
                    .filter(jsonObject -> !jsonObject.isEmpty())
                    // 用保存的编号、ETag、大小和 CRC 恢复 OSS 回执。
                    .map(jsonObject -> new PartETag(jsonObject.getIntValue(PART_NUMBER_KEY),
                            jsonObject.getString(E_TAG_KEY),
                            jsonObject.getLongValue(PART_SIZE_KEY),
                            jsonObject.getLong(PART_CRC_KEY)
                    // 收集为 OSS SDK 接受的 List。
                    )).collect(Collectors.toList());
        }

        // 构造“完成分片上传”请求，OSS 会按 PartETag 列表拼出最终对象。
        CompleteMultipartUploadRequest request = new CompleteMultipartUploadRequest(config.getBucketName(), entity.getObjectKey(), entity.uploadId, partETags);
        // 发起最终合并请求。
        CompleteMultipartUploadResult result = client.completeMultipartUpload(request);
        // OSS 未返回合并结果时，文件不能视为可用。
        if (EmptyUtil.isEmpty(result)) {
            // 抛出业务异常，保留现场供后续重试或清理。
            throw new SystemException("文件分片合并失败，文件的唯一标识为：" + context.getIdentifier());
        }

        // OSS 已完成合并，uploadId 生命周期结束。
        getCache().evict(cacheKey);

        // 合并后的真实路径不再携带临时参数，只保留完整对象 key。
        context.setRealPath(entity.getObjectKey());
    }

    /** 从 OSS 获取对象流，并复制到调用方的输出流（下载、预览或 AI 解析）。 */
    // 覆盖父类定义的文件读取钩子。
    @Override
    protected void doReadFile(ReadFileContext context) throws IOException {
        // 按 bucket 与 object key 从 OSS 打开对象流。
        OSSObject ossObject = client.getObject(config.getBucketName(), context.getRealPath());
        // 对象不存在或读取失败时，SDK 返回空结果。
        if (EmptyUtil.isEmpty(ossObject)) {
            // 抛出统一业务异常。
            throw new SystemException("文件读取失败，文件的名称为：" + context.getRealPath());
        }
        // 流式复制 OSS 输入流到 HTTP 响应、文件流或 AI 内存流。
        FileUtil.writeStreamToStreamNormal(ossObject.getObjectContent(), context.getOutputStream());
    }

    /*****************************************private*****************************************/

    /** 生成 OSS object key：年/月/日/UUID + 后缀，避免同名文件冲突。 */
    private String getFilePath(String fileSuffix) {
        // StringBuffer 用于逐段拼接 object key。
        return new StringBuffer()
                // 第一段：当前年份。
                .append(DateUtil.thisYear())
                // 加目录分隔符。
                .append(BaseConstant.SLASH_STR)
                // 第二段：当前月份；月份从 0 开始，所以加 1。
                .append(DateUtil.thisMonth() + 1)
                // 加目录分隔符。
                .append(BaseConstant.SLASH_STR)
                // 第三段：当前日期。
                .append(DateUtil.thisDayOfMonth())
                // 加目录分隔符。
                .append(BaseConstant.SLASH_STR)
                // 用 UUID 避免两个同名文件覆盖。
                .append(UUIDUtil.getUUID())
                // 拼接文件后缀，保留文件类型。
                .append(fileSuffix)
                // 将 StringBuffer 转为 String 返回。
                .toString();
    }

    /** 将分片回执编码为 {@code objectKey?key=value&key=value} 形式的内部路径。 */
    private String assembleUrl(String baseUrl, JSONObject params) {
        // 无分片回执时直接返回 object key。
        if (EmptyUtil.isEmpty(params) || params.isEmpty()) {
            // 提前返回，避免生成无意义的问号。
            return baseUrl;
        }
        // 创建用于拼接最终内部路径的缓冲区。
        StringBuffer urlStringBuffer = new StringBuffer(baseUrl);
        // 在 object key 后添加查询参数起始符 ?。
        urlStringBuffer.append(BaseConstant.QUESTION_MARK_STR);
        // 保存每一个 key=value 参数。
        List<String> paramsList = Lists.newArrayList();
        // 复用缓冲区构建单个参数，减少临时对象。
        StringBuffer urlParamsStringBuffer = new StringBuffer();
        // 遍历 JSON 中保存的所有分片回执字段。
        params.entrySet().forEach(entry -> {
            // 清空上一次循环写入的字符。
            urlParamsStringBuffer.setLength(BaseConstant.ZERO_INT);
            // 写入参数名。
            urlParamsStringBuffer.append(entry.getKey());
            // 写入等号分隔符。
            urlParamsStringBuffer.append(BaseConstant.EQUALS_MARK_STR);
            // 写入参数值。
            urlParamsStringBuffer.append(entry.getValue());
            // 将完整 key=value 加入列表。
            paramsList.add(urlParamsStringBuffer.toString());
        });
        // 用 & 连接参数并追加到 object key 后，得到内部路径。
        return urlStringBuffer.append(Joiner.on(BaseConstant.AND_MARK_STR).join(paramsList)).toString();
    }

    /** 去掉内部路径中的查询参数，取得真正的 OSS object key。 */
    private String getBaseUrl(String url) {
        // 空字符串没有 object key，直接返回空值。
        if (StringUtils.isBlank(url)) {
            // 使用项目统一的空字符串常量。
            return BaseConstant.EMPTY_STR;
        }
        // 带参数的是内部路径，需要去掉 ? 后的数据。
        if (checkHaveParams(url)) {
            // split 后第 0 段即真实 object key。
            return url.split(getSplitMark(BaseConstant.QUESTION_MARK_STR))[0];
        }
        // 普通完整文件路径本身就是 object key。
        return url;
    }

    /**
     * {@link String#split(String)} 参数是正则表达式；用字符组包住分隔符，按字面量切分。
     */
    private String getSplitMark(String mark) {
        // 创建正则字符组的起始方括号。
        return new StringBuffer(BaseConstant.LEFT_BRACKET_STR)
                // 写入要按字面量识别的分隔符，例如 ? 或 =。
                .append(mark)
                // 补上字符组的结束方括号。
                .append(BaseConstant.RIGHT_BRACKET_STR)
                // 返回供 split 使用的正则字符串。
                .toString();
    }

    /** 解析内部路径的查询参数，供合并或取消上传使用。 */
    private JSONObject analysisUrlParams(String url) {
        // 创建结果对象；不含参数的路径会返回这个空对象。
        JSONObject result = new JSONObject();
        // 完整文件路径不需要、也不能解析分片回执。
        if (!checkHaveParams(url)) {
            // 直接返回空 JSON。
            return result;
        }
        // 取 ? 之后的 key=value&key=value 参数段。
        String paramsPart = url.split(getSplitMark(BaseConstant.QUESTION_MARK_STR))[1];
        // 仅在参数段非空时继续拆分。
        if (StringUtils.isNotBlank(paramsPart)) {
            // 先按 & 拆成多个 key=value 字符串。
            List<String> paramPairList = Splitter.on(BaseConstant.AND_MARK_STR).splitToList(paramsPart);
            // 逐个解析每个参数对。
            paramPairList.stream().forEach(paramPair -> {
                // 再按 = 拆出参数名和参数值。
                String[] paramArr = paramPair.split(getSplitMark(BaseConstant.EQUALS_MARK_STR));
                // 仅接受恰好包含两段的合法参数。
                if (paramArr != null && paramArr.length == BaseConstant.TWO_INT) {
                    // 以 key -> value 形式放进 JSON，供调用方按名称读取。
                    result.put(paramArr[0], paramArr[1]);
                }
            });
        }
        // 返回解析后的分片回执参数。
        return result;
    }

    /** 判断路径是否携带分片回执。 */
    private boolean checkHaveParams(String url) {
        // 非空且包含 ? 时，约定该路径携带分片元数据。
        return StringUtils.isNotBlank(url) && url.indexOf(BaseConstant.QUESTION_MARK_STR) != BaseConstant.MINUS_ONE_INT;
    }

    /**
     * 向 OSS 创建 multipart upload，并缓存返回的 uploadId。
     * 后续每片上传和最终合并都要带这个 uploadId。
     */
    private ChunkUploadEntity initChunkUpload(String filename, String cacheKey) {
        // 生成此次上传最终对象的 key。
        String filePath = getFilePath(filename);

        // 构造 OSS “初始化分片上传”请求。
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(config.getBucketName(), filePath);
        // OSS 返回本次 multipart upload 的 uploadId。
        InitiateMultipartUploadResult result = client.initiateMultipartUpload(request);

        // 没有初始化结果时，后续分片无处可传。
        if (EmptyUtil.isEmpty(result)) {
            // 抛出业务异常中断当前上传。
            throw new SystemException("文件分片上传初始化失败");
        }

        // 创建应用侧缓存对象，保存本次任务的关键信息。
        ChunkUploadEntity entity = new ChunkUploadEntity();
        // 保存最终 OSS object key。
        entity.setObjectKey(filePath);
        // 保存 OSS 分配的上传任务 ID。
        entity.setUploadId(result.getUploadId());

        // 用“用户 + 文件标识”缓存任务，供后续分片复用。
        getCache().put(cacheKey, entity);

        // 把初始化结果返回给当前分片上传。
        return entity;
    }

    /** 同一 multipart upload 共享的 OSS 任务信息。 */
    // Lombok 自动生成全参构造器。
    @AllArgsConstructor
    // Lombok 自动生成无参构造器，缓存反序列化时需要。
    @NoArgsConstructor
    // Lombok 为字段生成 getter。
    @Getter
    // Lombok 为字段生成 setter。
    @Setter
    // Lombok 按字段生成 equals 和 hashCode。
    @EqualsAndHashCode
    // Lombok 生成便于日志查看的 toString。
    @ToString
    public static class ChunkUploadEntity implements Serializable {

        // 标记该字段是 Java 序列化版本号，避免类结构变化时误反序列化。
        @Serial
        private static final long serialVersionUID = -1646644290507942123L;

        /** OSS 为本次分片上传分配的唯一任务 ID。 */
        private String uploadId;

        /** OSS 中最终完整文件的 object key。 */
        private String objectKey;

    }

    /** 用“文件标识 + 用户”隔离不同上传任务的缓存。 */
    private String getCacheKey(String identifier, Long userId) {
        // 用 String.format 将两个占位符替换为文件标识和用户 ID。
        return String.format(CACHE_KEY_TEMPLATE, identifier, userId);
    }
}
