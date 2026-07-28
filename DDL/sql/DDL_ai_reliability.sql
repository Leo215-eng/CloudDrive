-- Files service: apply to the same MySQL schema that stores user_file.
create table if not exists ai_event_outbox (
    id bigint not null primary key,
    event_id varchar(64) not null,
    aggregate_id varchar(128) not null,
    event_type varchar(64) not null,
    topic varchar(128) not null,
    message_tag varchar(64) null,
    payload text not null,
    status varchar(32) not null,
    retry_count int not null default 0,
    next_retry_time datetime null,
    last_error varchar(1000) null,
    create_time datetime not null default current_timestamp,
    sent_time datetime null,
    unique key uk_ai_event_outbox_event_id(event_id),
    key idx_ai_event_outbox_dispatch(status, next_retry_time)
) engine=InnoDB default charset=utf8mb4;

-- AI service: apply to PostgreSQL/pgvector database.
create table if not exists ai_document_task (
    id bigserial primary key,
    task_key varchar(255) not null unique,
    event_id varchar(64) not null,
    user_id bigint not null,
    user_file_id bigint not null,
    file_id varchar(128) null,
    filename varchar(512) null,
    file_version varchar(64) not null,
    task_type varchar(32) not null,
    status varchar(32) not null,
    retry_count int not null default 0,
    max_retry_count int not null default 5,
    next_retry_time timestamp null,
    worker_id varchar(128) null,
    lease_expire_time timestamp null,
    last_error_code varchar(64) null,
    last_error_message varchar(1000) null,
    started_at timestamp null,
    finished_at timestamp null,
    gmt_create timestamp not null default current_timestamp,
    gmt_modified timestamp not null default current_timestamp
);

create index if not exists idx_ai_document_task_dispatch
    on ai_document_task(status, next_retry_time, lease_expire_time);

alter table ai_document_task add column if not exists file_id varchar(128);
alter table ai_document_task add column if not exists filename varchar(512);
