package com.disk.files.infrastructure.ai;

import com.disk.base.utils.FileUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Set;

/** Writes DocumentReady into the caller transaction; it deliberately never sends afterCommit. */
@Component @RequiredArgsConstructor
public class DocumentAiInitializer {
  private static final Set<String> SUPPORTED=Set.of(".pdf",".doc",".docx",".txt",".md",".csv",".xls",".xlsx",".ppt",".pptx",".html",".xml",".json",".sql",".java",".js",".css");
  private final AiEventOutboxService outbox;
  public void scheduleInitialize(Long userId,Long userFileId,String filename){if(userId!=null&&userFileId!=null&&SUPPORTED.contains(StringUtils.lowerCase(StringUtils.trimToEmpty(FileUtil.getFileSuffix(filename)), Locale.ROOT)))outbox.append(userId,userFileId,filename);}
}
