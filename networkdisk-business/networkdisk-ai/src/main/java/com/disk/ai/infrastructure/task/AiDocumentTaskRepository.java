package com.disk.ai.infrastructure.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.List;
import com.disk.api.ai.message.AiDocumentTaskMessage;

/** PostgreSQL task state operations; UPDATE predicates are the multi-instance execution fence. */
@Repository
public class AiDocumentTaskRepository {
  private final JdbcTemplate jdbc;
  public AiDocumentTaskRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbc){this.jdbc=jdbc;}
  public void createIfAbsent(String key,String eventId,Long userId,Long userFileId,String fileId,String filename,String version,String type){jdbc.update("insert into ai_document_task(task_key,event_id,user_id,user_file_id,file_id,filename,file_version,task_type,status,next_retry_time) values(?,?,?,?,?,?,?,?, 'PENDING',now()) on conflict(task_key) do nothing",key,eventId,userId,userFileId,fileId,filename,version,type);}
  public boolean claim(String key,String worker){return jdbc.update("update ai_document_task set status='RUNNING',worker_id=?,lease_expire_time=now()+interval '5 minutes',started_at=coalesce(started_at,now()),gmt_modified=now() where task_key=? and ((status='PENDING' and (next_retry_time is null or next_retry_time<=now())) or status='DISPATCHING' or (status='RETRY_WAIT' and next_retry_time<=now()) or (status='RUNNING' and lease_expire_time<now()))",worker,key)==1;}
  public void success(String key){jdbc.update("update ai_document_task set status='SUCCESS',finished_at=now(),lease_expire_time=null,gmt_modified=now() where task_key=?",key);}
  public void failure(String key,boolean permanent,String code,String message){jdbc.update("update ai_document_task set retry_count=retry_count+1,status=case when ? or retry_count+1>=max_retry_count then 'DEAD' else 'RETRY_WAIT' end,next_retry_time=case when ? then null else now()+interval '1 minute' end,last_error_code=?,last_error_message=?,lease_expire_time=null,gmt_modified=now() where task_key=?",permanent,permanent,code,message==null?null:message.substring(0,Math.min(900,message.length())),key);}
  public int recoverExpired(){return jdbc.update("update ai_document_task set status='RETRY_WAIT',worker_id=null,lease_expire_time=null,next_retry_time=now(),gmt_modified=now() where status in ('RUNNING','DISPATCHING') and lease_expire_time<now()");}
  public List<AiDocumentTaskMessage> acquireDueRetries(int limit){return jdbc.query("with due as (select id from ai_document_task where status='RETRY_WAIT' and next_retry_time<=now() order by next_retry_time limit ? for update skip locked) update ai_document_task t set status='DISPATCHING',lease_expire_time=now()+interval '1 minute',gmt_modified=now() from due where t.id=due.id returning t.event_id,t.task_key,t.user_id,t.user_file_id,t.file_id,t.filename,t.file_version,t.task_type",(rs,n)->{AiDocumentTaskMessage m=new AiDocumentTaskMessage();m.setEventId(rs.getString(1));m.setTaskKey(rs.getString(2));m.setUserId(rs.getLong(3));m.setUserFileId(rs.getLong(4));m.setFileId(rs.getString(5));m.setFilename(rs.getString(6));m.setFileVersion(rs.getString(7));m.setTaskType(rs.getString(8));return m;},limit);}
  public void releaseDispatch(String key){jdbc.update("update ai_document_task set status='RETRY_WAIT',next_retry_time=now()+interval '1 minute',lease_expire_time=null,gmt_modified=now() where task_key=? and status='DISPATCHING'",key);}
}
