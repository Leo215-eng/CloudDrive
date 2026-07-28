package com.disk.ai.infrastructure.task;

import com.disk.api.ai.message.AiDocumentTaskMessage;
import com.disk.mq.producer.StreamProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-publishes due retry tasks. DISPATCHING is a short delivery lease, not execution ownership. */
@Component
@RequiredArgsConstructor
public class AiRetryTaskDispatcher {
    private final AiDocumentTaskRepository tasks;
    private final StreamProducer producer;
    private final ObjectMapper json;

    @Scheduled(fixedDelayString = "${ai.task.retry-dispatch-delay-ms:5000}")
    public void dispatchDueTasks() {
        for (AiDocumentTaskMessage task : tasks.acquireDueRetries(50)) {
            try {
                if (!producer.send("aiTask-out-0", task.getTaskType(), json.writeValueAsString(task))) {
                    throw new IllegalStateException("broker returned false");
                }
            } catch (Exception exception) {
                tasks.releaseDispatch(task.getTaskKey());
            }
        }
    }
}
