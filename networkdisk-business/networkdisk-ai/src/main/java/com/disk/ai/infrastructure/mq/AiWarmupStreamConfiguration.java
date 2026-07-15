package com.disk.ai.infrastructure.mq;

import com.disk.ai.domain.service.AiApplicationService;
import com.disk.api.ai.message.AiDocumentWarmupMessage;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.mq.param.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Slf4j
@Configuration
@RequiredArgsConstructor
// AI 预热消息消费配置。
// files 服务上传/保存支持的文档后会发消息，AI 服务收到后提前建索引、生成摘要和标签。
public class AiWarmupStreamConfiguration {

    // JSON 工具，用来把消息体字符串转成 AiDocumentWarmupMessage。
    private final ObjectMapper objectMapper;

    // 复用 AI 业务服务，不在 MQ 层重复写索引/摘要/标签逻辑。
    private final AiApplicationService aiApplicationService;

    @Bean("aiWarmupConsumer")
    // Bean 名必须和 application.yml 里的 spring.cloud.function.definition=aiWarmupConsumer 对上。
    // Consumer<Message<?>> 表示这是一个消息消费者函数。交给 Spring Cloud Stream 自动调用
//    Consumer<Message<?>> 是 Java 函数式接口。接收一个参数不返回结果
//    接收一个 Spring Message 处理完不返回任何值

    public Consumer<Message<?>> aiWarmupConsumer() {
        // lambda 实现消费逻辑，收到消息自动执行该段代码
        return message -> {
            // 调用工具方法剥离外层包装，拿到真实业务参数
            AiDocumentWarmupMessage warmupMessage = readWarmupMessage(message);
            // 业务关键字段空校验：用户ID、文件ID缺失，消息无意义直接丢弃不重试
            if (warmupMessage == null || warmupMessage.getUserId() == null || warmupMessage.getUserFileId() == null) {
                log.warn("ignore invalid ai warmup message: {}", message == null ? null : message.getPayload());
                return;
            }
            // 日志埋点，链路追踪，记录本次预热的用户+文件标识
            log.info("consume ai warmup message, userId={}, userFileId={}",
                    warmupMessage.getUserId(), warmupMessage.getUserFileId());

            // 第一步：建文档向量索引。forceReindex=false 表示已有索引时不重复建。
            AiDocumentIndexRequest indexRequest = new AiDocumentIndexRequest();
            indexRequest.setUserId(warmupMessage.getUserId());
            indexRequest.setUserFileId(warmupMessage.getUserFileId());
            indexRequest.setFileId(warmupMessage.getFileId());
            indexRequest.setFilename(warmupMessage.getFilename());
            // false：已存在向量索引则跳过，不重复构建，节省资源
            indexRequest.setForceReindex(Boolean.FALSE);
            aiApplicationService.indexFile(indexRequest);

            // 第二步：生成默认摘要。默认摘要会被 PgDocumentResultStore 保存，前端打开时可直接读。
            AiDocumentSummaryRequest summaryRequest = new AiDocumentSummaryRequest();
            summaryRequest.setUserId(warmupMessage.getUserId());
            summaryRequest.setUserFileId(warmupMessage.getUserFileId());
            summaryRequest.setFileId(warmupMessage.getFileId());
            summaryRequest.setFilename(warmupMessage.getFilename());
            aiApplicationService.summarize(summaryRequest);

            // 第三步：生成默认标签。标签同样会保存，避免用户首次打开抽屉时等待。
            AiDocumentTagRequest tagRequest = new AiDocumentTagRequest();
            tagRequest.setUserId(warmupMessage.getUserId());
            tagRequest.setUserFileId(warmupMessage.getUserFileId());
            tagRequest.setFileId(warmupMessage.getFileId());
            tagRequest.setFilename(warmupMessage.getFilename());
            tagRequest.setTopK(warmupMessage.getTopK());
            aiApplicationService.generateTags(tagRequest);
        };
    }

    /**
     * 解析Stream收到的原始MQ消息，剥离外层统一包装MessageBody，得到真实AI预热业务对象
     * @param message Spring Stream标准消息载体
     * @return 解析完成的AI文档预热实体，解析失败抛异常、空数据返回null
     */
    private AiDocumentWarmupMessage readWarmupMessage(Message<?> message) {
        // 1. 基础空值校验：无消息/无载荷直接终止解析
        if (message == null || message.getPayload() == null) {
            return null;
        }

        // 2. 反序列化外层统一封装体 MessageBody
        // 生产者发送时所有业务消息都会套一层MessageBody，携带全局唯一追踪identifier，真实业务JSON存在body字段
        MessageBody messageBody = objectMapper.convertValue(message.getPayload(), MessageBody.class);
        // 外层载体为空 / 内部业务JSON为空，判定无效消息
        if (messageBody == null || StringUtils.isBlank(messageBody.getBody())) {
            return null;
        }

        try {
            // 3. 取出body内的业务JSON字符串，转为强类型AiDocumentWarmupMessage
            return objectMapper.readValue(messageBody.getBody(), AiDocumentWarmupMessage.class);
        } catch (Exception e) {
            // JSON格式错误、字段不匹配等序列化异常，抛出业务异常，触发消息重试
            throw new IllegalStateException("Failed to deserialize ai warmup message body", e);
        }
    }

}
