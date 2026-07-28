-- AI pgvector schema. Keep the vector dimension aligned with com.disk.ai.pgvector.dimension.
create extension if not exists vector;

create table if not exists ai_document_index (
    id bigserial primary key,
    user_id bigint not null,
    user_file_id bigint not null,
    real_file_id bigint not null,
    filename varchar(512) not null,
    file_suffix varchar(64),
    media_type varchar(128),
    parser varchar(128),
    content_length bigint not null default 0,
    plain_text_chars integer not null default 0,
    block_count integer not null default 0,
    chunk_count integer not null default 0,
    gmt_create timestamp not null default current_timestamp,
    gmt_modified timestamp not null default current_timestamp,
    unique (user_id, user_file_id)
);

create table if not exists ai_document_chunk_vector (
    id bigserial primary key,
    user_id bigint not null,
    user_file_id bigint not null,
    real_file_id bigint not null,
    filename varchar(512) not null,
    file_suffix varchar(64),
    media_type varchar(128),
    parser varchar(128),
    block_index integer not null,
    chunk_index integer not null,
    start_offset integer not null default 0,
    end_offset integer not null default 0,
    token_estimate integer not null default 0,
    chunk_text text not null,
    embedding vector(768) not null,
    gmt_create timestamp not null default current_timestamp
);

create index if not exists idx_ai_document_chunk_owner
    on ai_document_chunk_vector(user_id, user_file_id);

create table if not exists ai_document_result (
    id bigserial primary key,
    user_id bigint not null,
    user_file_id bigint not null,
    filename varchar(512) not null,
    summary_text text,
    summary_model varchar(128),
    summary_mocked boolean,
    tags_json jsonb,
    tags_model varchar(128),
    tags_mocked boolean,
    gmt_create timestamp not null default current_timestamp,
    gmt_modified timestamp not null default current_timestamp,
    unique (user_id, user_file_id)
);

create index if not exists idx_ai_document_result_owner
    on ai_document_result(user_id, user_file_id);

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
