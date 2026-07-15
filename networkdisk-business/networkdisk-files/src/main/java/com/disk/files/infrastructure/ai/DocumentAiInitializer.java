package com.disk.files.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.disk.api.ai.message.AiDocumentWarmupMessage;
import com.disk.base.utils.FileUtil;
import com.disk.base.utils.IdUtil;
import com.disk.mq.producer.StreamProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
// 文档 AI 初始化器，属于 files 服务。
// 作用：文件保存成功后，如果后缀支持 AI，就发一条预热消息给 networkdisk-ai 服务。
public class DocumentAiInitializer {

    // Spring Cloud Stream 输出通道名，对应 files 服务 application.yml 里的 aiWarmup-out-0。
    private static final String AI_WARMUP_BINDING = "aiWarmup-out-0";

    // 消息标签，方便 MQ 侧或日志侧识别这是文档 AI 预热消息。
    private static final String AI_WARMUP_TAG = "document-ai-warmup";

    // 支持自动 AI 预热的文件后缀。
    // 不支持的文件不发消息，避免 AI 服务解析失败或浪费模型调用。
    private static final Set<String> SUPPORTED_SUFFIXES = Set.of(
            ".pdf",
            ".doc",
            ".docx",
            ".txt",
            ".md",
            ".markdown",
            ".csv",
            ".xls",
            ".xlsx",
            ".ppt",
            ".pptx",
            ".html",
            ".htm",
            ".xml",
            ".json",
            ".sql",
            ".java",
            ".js",
            ".css"
    );

    // 项目封装的消息发送器。
    private final StreamProducer streamProducer;

    // 把 AiDocumentWarmupMessage 转成 JSON 字符串。
    private final ObjectMapper objectMapper;

    /**
     * 调度文档AI初始化预热任务
     * 核心解决事务未提交就发消息导致的空数据问题，同时过滤不支持解析的文件
     * @param userId 文件归属用户ID
     * @param userFileId 用户文件记录主键ID
     * @param filename 文件名称（用于判断后缀是否支持AI解析）
     */
    public void scheduleInitialize(Long userId, Long userFileId, String filename) {
        // 1. 参数校验 + 文件格式过滤
        // 用户ID、文件ID为空 或 文件后缀不支持AI解析，直接终止，不发送MQ消息
        if (userId == null || userFileId == null || !supports(filename)) {
            return;
        }

        // 2. 判断当前线程是否存在活跃数据库事务
        // 原因是：saveUserFile(...) 刚插入 user_file，如果事务还没提交，
        // AI 服务立刻消费 MQ 去读这个文件，可能查不到 user_file 记录。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 注册事务同步回调，等待事务执行完成
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 事务成功提交后执行
                 * 只有数据库数据真正落库，才发送AI解析消息
                 */
//                等 user_file 真正提交成功。再发送 AI 预热消息
                @Override
                public void afterCommit() {
                    publishWarmupMessage(userId, userFileId, filename);
                }
            });
            // 注册完回调直接返回，不再往下执行同步发消息逻辑
            return;
        }
        // 3. 当前无事务环境，文件记录已持久化，直接发送MQ消息触发AI预热
        publishWarmupMessage(userId, userFileId, filename);
    }


    /**
     * 推送AI文档预热消息
     * 场景：文件上传后发送预热通知，AI服务提前加载文档做解析/向量缓存
     * @param userId 用户ID
     * @param userFileId 文件原始主键ID
     * @param filename 文件名称
     */
    private void publishWarmupMessage(Long userId, Long userFileId, String filename) {
        try {
            // 封装轻量预热消息：仅携带AI服务必需的定位字段，减少消息体积
            AiDocumentWarmupMessage message = new AiDocumentWarmupMessage();
            message.setUserId(userId);
            message.setUserFileId(userFileId);
            // 对外统一使用加密fileId，和前端/AI接口参数格式保持一致，避免ID泄露
            message.setFileId(IdUtil.encrypt(userFileId));
            message.setFilename(filename);

            // StreamBridge发送消息，binding绑定输出通道aiWarmup-out-0，映射RocketMQ主题ai-document-warmup
            // 参数1：绑定通道名；参数2：消息TAG；参数3：消息实体序列化JSON字符串
//            通道（binding）是 Stream 的抽象管道 xxx-out-0，通过配置映射真实 RocketMQ Topic，作用是解耦主题、屏蔽中间件差异。
            boolean sent = streamProducer.send(
                    AI_WARMUP_BINDING,
                    AI_WARMUP_TAG,
                    objectMapper.writeValueAsString(message)
            );
            // send返回false代表投递未成功，打印告警日志便于排查
            if (!sent) {
                log.warn("publish document ai warmup message returned false, userId={}, userFileId={}", userId, userFileId);
            }
        } catch (Exception e) {
            // 序列化/网络/中间件异常捕获，仅告警不阻断主流程，可搭配监控告警
            log.warn("publish document ai warmup message failed, userId={}, userFileId={}", userId, userFileId, e);
        }
    }


    private boolean supports(String filename) {
        // 提取文件后缀并统一小写，例如 合同.PDF -> .pdf。
        String fileSuffix = StringUtils.lowerCase(StringUtils.trimToEmpty(FileUtil.getFileSuffix(filename)), Locale.ROOT);
        return SUPPORTED_SUFFIXES.contains(fileSuffix);
    }
}
