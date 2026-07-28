package com.disk.api.ai.message;
import lombok.Data;
@Data public class AiDocumentTaskMessage {private String eventId;private String taskKey;private Long userId;private Long userFileId;private String fileId;private String filename;private String fileVersion;private String taskType;}
