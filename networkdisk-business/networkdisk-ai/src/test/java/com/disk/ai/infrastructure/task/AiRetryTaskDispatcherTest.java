package com.disk.ai.infrastructure.task;

import com.disk.api.ai.message.AiDocumentTaskMessage;
import com.disk.mq.producer.StreamProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiRetryTaskDispatcherTest {

    @Test
    void successfulDispatchLeavesTaskDispatchingForConsumerClaim() {
        AiDocumentTaskRepository repository = mock(AiDocumentTaskRepository.class);
        StreamProducer producer = mock(StreamProducer.class);
        AiDocumentTaskMessage task = task();
        when(repository.acquireDueRetries(50)).thenReturn(List.of(task));
        when(producer.send(eq("aiTask-out-0"), eq("INDEX"), anyString())).thenReturn(true);

        new AiRetryTaskDispatcher(repository, producer, new ObjectMapper()).dispatchDueTasks();

        verify(producer).send(eq("aiTask-out-0"), eq("INDEX"), anyString());
        verify(repository, never()).releaseDispatch(anyString());
    }

    @Test
    void failedDispatchReturnsTaskToRetryWait() {
        AiDocumentTaskRepository repository = mock(AiDocumentTaskRepository.class);
        StreamProducer producer = mock(StreamProducer.class);
        AiDocumentTaskMessage task = task();
        when(repository.acquireDueRetries(50)).thenReturn(List.of(task));
        when(producer.send(anyString(), anyString(), anyString())).thenReturn(false);

        new AiRetryTaskDispatcher(repository, producer, new ObjectMapper()).dispatchDueTasks();

        verify(repository).releaseDispatch("task-1");
    }

    @Test
    void noDueTaskDoesNotPublish() {
        AiDocumentTaskRepository repository = mock(AiDocumentTaskRepository.class);
        StreamProducer producer = mock(StreamProducer.class);
        when(repository.acquireDueRetries(50)).thenReturn(List.of());

        new AiRetryTaskDispatcher(repository, producer, new ObjectMapper()).dispatchDueTasks();

        verifyNoInteractions(producer);
    }

    private AiDocumentTaskMessage task() {
        AiDocumentTaskMessage task = new AiDocumentTaskMessage();
        task.setTaskKey("task-1");
        task.setTaskType("INDEX");
        return task;
    }
}
