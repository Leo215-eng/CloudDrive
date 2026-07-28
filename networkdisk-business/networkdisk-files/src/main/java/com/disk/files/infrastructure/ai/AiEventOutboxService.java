package com.disk.files.infrastructure.ai;

import com.disk.api.ai.message.AiDocumentWarmupMessage;
import com.disk.mq.producer.StreamProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j @Service @RequiredArgsConstructor
public class AiEventOutboxService {
  private final JdbcTemplate jdbc; private final ObjectMapper json; private final StreamProducer producer;
  public void append(Long userId, Long userFileId, String filename) {
    try { AiDocumentWarmupMessage m=new AiDocumentWarmupMessage(); m.setUserId(userId);m.setUserFileId(userFileId);m.setFileId(com.disk.base.utils.IdUtil.encrypt(userFileId));m.setFilename(filename);
      jdbc.update("insert into ai_event_outbox(id,event_id,aggregate_id,event_type,topic,message_tag,payload,status,retry_count,next_retry_time,create_time) values(?,?,?,?,?,?,?,?,?,?,now())", com.disk.base.utils.IdUtil.get(), UUID.randomUUID().toString().replace("-",""),String.valueOf(userFileId),"DocumentReady","aiWarmup-out-0","document-ai-warmup",json.writeValueAsString(m),"PENDING",0,new java.sql.Timestamp(System.currentTimeMillis()));
    } catch(Exception e){throw new IllegalStateException("ai outbox write failed",e);} }
  @Scheduled(fixedDelayString="${ai.outbox.dispatch-delay-ms:5000}") public void dispatch(){
    jdbc.update("update ai_event_outbox set status='RETRY_WAIT',next_retry_time=now(),last_error='sending lease expired' where status='SENDING' and next_retry_time<now()");
    for(Long id:jdbc.queryForList("select id from ai_event_outbox where status in ('PENDING','RETRY_WAIT') and (next_retry_time is null or next_retry_time<=now()) order by create_time limit 50",Long.class)) send(id); }
  private void send(Long id){ if(jdbc.update("update ai_event_outbox set status='SENDING',next_retry_time=date_add(now(),interval 1 minute) where id=? and status in ('PENDING','RETRY_WAIT')",id)!=1)return;
    try {Map<String,Object> r=jdbc.queryForMap("select payload,message_tag from ai_event_outbox where id=?",id); if(!producer.send("aiWarmup-out-0",(String)r.get("message_tag"),(String)r.get("payload")))throw new IllegalStateException("broker returned false"); jdbc.update("update ai_event_outbox set status='SENT',sent_time=now(),last_error=null where id=?",id);}
    catch(Exception e){Integer n=jdbc.queryForObject("select retry_count from ai_event_outbox where id=?",Integer.class,id); int retry=(n==null?0:n)+1; String s=retry>=5?"DEAD":"RETRY_WAIT"; jdbc.update("update ai_event_outbox set status=?,retry_count=?,next_retry_time=date_add(now(),interval ? minute),last_error=? where id=?",s,retry,Math.min(retry*retry,30),String.valueOf(e).substring(0,Math.min(900,String.valueOf(e).length())),id); log.warn("outbox dispatch id={} retry={} status={}",id,retry,s);}}
}
