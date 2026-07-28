package com.disk.ai.infrastructure.mq;

import com.disk.ai.infrastructure.task.AiDocumentTaskRepository;
import com.disk.api.ai.message.AiDocumentTaskMessage;
import com.disk.api.ai.message.AiDocumentWarmupMessage;
import com.disk.mq.param.MessageBody;
import com.disk.mq.producer.StreamProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
public class AiWarmupStreamConfiguration {
    private final ObjectMapper json;
    private final StreamProducer producer;
    private final AiDocumentTaskRepository tasks;

    @Bean("aiWarmupConsumer")
    public Consumer<Message<?>> aiWarmupConsumer() {
        return message -> {
            try {
                MessageBody body = json.convertValue(message.getPayload(), MessageBody.class);
                AiDocumentWarmupMessage warmup = json.readValue(body.getBody(), AiDocumentWarmupMessage.class);
                String eventId = body.getIdentifier();
                String version = warmup.getFileId() == null ? String.valueOf(warmup.getUserFileId()) : warmup.getFileId();
                for (String type : new String[]{"INDEX", "SUMMARY", "TAGS"}) {
                    String key = warmup.getUserId() + ":" + warmup.getUserFileId() + ":" + version + ":" + type;
                    tasks.createIfAbsent(key, eventId, warmup.getUserId(), warmup.getUserFileId(),
                            warmup.getFileId(), warmup.getFilename(), version, type);
                    AiDocumentTaskMessage task = new AiDocumentTaskMessage();
                    task.setEventId(eventId); task.setTaskKey(key); task.setUserId(warmup.getUserId());
                    task.setUserFileId(warmup.getUserFileId()); task.setFileId(warmup.getFileId());
                    task.setFilename(warmup.getFilename()); task.setFileVersion(version); task.setTaskType(type);
                    if (!producer.send("aiTask-out-0", type, json.writeValueAsString(task))) {
                        throw new IllegalStateException("task dispatch returned false");
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("failed to create AI document tasks", exception);
            }
        };
    }
}
