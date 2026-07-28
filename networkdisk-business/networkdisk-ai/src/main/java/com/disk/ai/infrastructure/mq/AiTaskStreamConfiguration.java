package com.disk.ai.infrastructure.mq;

import com.disk.ai.domain.service.AiApplicationService;
import com.disk.ai.infrastructure.task.AiDocumentTaskRepository;
import com.disk.api.ai.message.AiDocumentTaskMessage;
import com.disk.api.ai.request.AiDocumentIndexRequest;
import com.disk.api.ai.request.AiDocumentSummaryRequest;
import com.disk.api.ai.request.AiDocumentTagRequest;
import com.disk.mq.param.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
public class AiTaskStreamConfiguration {
    private final ObjectMapper json;
    private final AiDocumentTaskRepository tasks;
    private final AiApplicationService ai;

    @Bean("aiTaskConsumer")
    public Consumer<Message<?>> aiTaskConsumer() {
        return message -> {
            AiDocumentTaskMessage task = read(message);
            if (!tasks.claim(task.getTaskKey(), "ai-task")) return;
            try {
                switch (task.getTaskType()) {
                    case "INDEX" -> index(task);
                    case "SUMMARY" -> summary(task);
                    case "TAGS" -> tags(task);
                    default -> throw new IllegalArgumentException("unknown task type: " + task.getTaskType());
                }
                tasks.success(task.getTaskKey());
            } catch (Exception e) {
                boolean permanent = e instanceof IllegalArgumentException;
                tasks.failure(task.getTaskKey(), permanent, permanent ? "INVALID_TASK" : "TASK_FAILED", e.getMessage());
                if (!permanent) throw new IllegalStateException(e);
            }
        };
    }

    private AiDocumentTaskMessage read(Message<?> message) {
        try {
            MessageBody body = json.convertValue(message.getPayload(), MessageBody.class);
            if (body == null || StringUtils.isBlank(body.getBody())) throw new IllegalArgumentException("empty task body");
            AiDocumentTaskMessage task = json.readValue(body.getBody(), AiDocumentTaskMessage.class);
            if (StringUtils.isBlank(task.getTaskKey()) || task.getUserId() == null || task.getUserFileId() == null || StringUtils.isBlank(task.getTaskType())) {
                throw new IllegalArgumentException("missing required task fields");
            }
            return task;
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) { throw new IllegalArgumentException("invalid task message", e); }
    }
    private void index(AiDocumentTaskMessage t) { AiDocumentIndexRequest r=new AiDocumentIndexRequest(); r.setUserId(t.getUserId());r.setUserFileId(t.getUserFileId());r.setFileId(t.getFileId());r.setFilename(t.getFilename());r.setForceReindex(false);ai.indexFile(r); }
    private void summary(AiDocumentTaskMessage t) { AiDocumentSummaryRequest r=new AiDocumentSummaryRequest(); r.setUserId(t.getUserId());r.setUserFileId(t.getUserFileId());r.setFileId(t.getFileId());r.setFilename(t.getFilename());ai.summarize(r); }
    private void tags(AiDocumentTaskMessage t) { AiDocumentTagRequest r=new AiDocumentTagRequest(); r.setUserId(t.getUserId());r.setUserFileId(t.getUserFileId());r.setFileId(t.getFileId());r.setFilename(t.getFilename());ai.generateTags(r); }
}
