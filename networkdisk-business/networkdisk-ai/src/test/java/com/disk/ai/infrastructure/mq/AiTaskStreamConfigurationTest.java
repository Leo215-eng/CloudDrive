package com.disk.ai.infrastructure.mq;

import com.disk.ai.domain.service.AiApplicationService;
import com.disk.ai.infrastructure.task.AiDocumentTaskRepository;
import com.disk.api.ai.message.AiDocumentTaskMessage;
import com.disk.mq.param.MessageBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiTaskStreamConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AiDocumentTaskRepository tasks = mock(AiDocumentTaskRepository.class);
    private final AiApplicationService ai = mock(AiApplicationService.class);
    private final AiTaskStreamConfiguration config = new AiTaskStreamConfiguration(json, tasks, ai);

    @Test void wrappedIndexMessageClaimsAndSucceeds() throws Exception {
        when(tasks.claim(anyString(), anyString())).thenReturn(true);
        config.aiTaskConsumer().accept(wrapped("INDEX"));
        verify(ai).indexFile(any()); verify(tasks).success("key"); verify(tasks, never()).failure(any(), anyBoolean(), any(), any());
    }
    @Test void claimFailureDoesNotExecute() throws Exception {
        when(tasks.claim(anyString(), anyString())).thenReturn(false);
        config.aiTaskConsumer().accept(wrapped("INDEX"));
        verifyNoInteractions(ai); verify(tasks, never()).success(any());
    }
    @Test void unknownTypeIsPermanentFailure() throws Exception {
        when(tasks.claim(anyString(), anyString())).thenReturn(true);
        config.aiTaskConsumer().accept(wrapped("OTHER"));
        verify(tasks).failure(eq("key"), eq(true), eq("INVALID_TASK"), contains("unknown")); verifyNoInteractions(ai);
    }
    @Test void missingTaskKeyIsRejectedBeforeClaim() throws Exception {
        AiDocumentTaskMessage task=new AiDocumentTaskMessage(); task.setTaskType("INDEX"); task.setUserId(1L); task.setUserFileId(2L);
        MessageBody body=new MessageBody(); body.setBody(json.writeValueAsString(task));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> config.aiTaskConsumer().accept(MessageBuilder.withPayload(body).build()));
        verify(tasks, never()).claim(anyString(), anyString()); verifyNoInteractions(ai);
    }
    private org.springframework.messaging.Message<?> wrapped(String type) throws Exception {
        AiDocumentTaskMessage task=new AiDocumentTaskMessage();task.setTaskKey("key");task.setTaskType(type);task.setUserId(1L);task.setUserFileId(2L);task.setFilename("a.txt");
        MessageBody body=new MessageBody();body.setBody(json.writeValueAsString(task)); return MessageBuilder.withPayload(body).build();
    }
}
