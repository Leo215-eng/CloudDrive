package com.disk.ai.infrastructure.result;

import com.disk.ai.exception.AiErrorCode;
import com.disk.ai.exception.AiException;
import com.disk.ai.infrastructure.vector.PgVectorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
// 只有 application.yml 中 com.disk.ai.pgvector.enabled=true 时，Spring 才会创建这个实现类。
// 当前项目开启了 pgvector，所以 AI 摘要/标签结果会存到 PostgreSQL。
@ConditionalOnProperty(name = "com.disk.ai.pgvector.enabled", havingValue = "true")
// DocumentResultStore：告诉业务层“我能读写 AI 摘要和标签结果”。
// InitializingBean：让 Spring 在对象创建完成后自动调用 afterPropertiesSet()，用于初始化表结构。
public class PgDocumentResultStore implements DocumentResultStore, InitializingBean {

    // Jackson 反序列化 List<String> 需要保留泛型信息；这里用于把 tags_json 转回标签列表。
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    // pgvector/PostgreSQL 配置，对应 application.yml 里的 com.disk.ai.pgvector。
    private final PgVectorProperties properties;

    // Spring JDBC 工具类，用它执行 SQL；这里注入的是 PgVectorConfiguration 里创建的 pgVectorJdbcTemplate。
    private final JdbcTemplate jdbcTemplate;

    // JSON 工具，用于标签列表和 jsonb 字符串之间转换。
    private final ObjectMapper objectMapper;

    // 构造器注入：Spring 创建 PgDocumentResultStore 时，会把配置、JdbcTemplate、ObjectMapper 传进来。
    public PgDocumentResultStore(PgVectorProperties properties,
                                 @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterPropertiesSet() {
        // init-schema=true 时，服务启动后自动创建 ai_document_result 表和索引。
        if (properties.isInitSchema()) {
            initializeSchema();
        }
    }

    @Override
    public boolean isReady() {
        // 这个实现被创建出来，就表示结果存储功能启用；这里不做数据库连通性检测。
        return true;
    }

    @Override
    public StoredDocumentSummary getSummary(Long userId, Long userFileId) {
        // 按“用户 + 用户文件”查询已经保存过的默认摘要。
        // summary_text is not null 表示只取确实生成过摘要的记录。
        List<StoredDocumentSummary> results = jdbcTemplate.query(
                """
                        select filename, summary_text, summary_model, summary_mocked
                        from ai_document_result
                        where user_id = ? and user_file_id = ? and summary_text is not null
                        """,
                (rs, rowNum) -> {
                    // RowMapper：把数据库返回的一行记录转换成 Java 对象。
                    StoredDocumentSummary summary = new StoredDocumentSummary();
                    summary.setFilename(rs.getString("filename"));
                    summary.setSummary(rs.getString("summary_text"));
                    summary.setModel(rs.getString("summary_model"));
                    summary.setMocked(rs.getObject("summary_mocked", Boolean.class));
                    return summary;
                },
//                跟在 Lambda 后面的两个参数，按顺序对应 SQL 里的两个 ?
                userId,
                userFileId
        );
        // 表上有 unique(user_id, user_file_id)，正常最多一条；查不到就返回 null，让业务层去重新调用 AI。
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void saveSummary(Long userId, Long userFileId, String filename, String summary, String model, Boolean mocked) {
        // 保存摘要结果。PostgreSQL 的 on conflict 表示“有就更新，没有就插入”。
        jdbcTemplate.update(
                """
                        insert into ai_document_result(
                            user_id, user_file_id, filename, summary_text, summary_model, summary_mocked, gmt_modified
                        ) values (?, ?, ?, ?, ?, ?, current_timestamp)
                        on conflict (user_id, user_file_id)
                        do update set filename = excluded.filename,
                                      summary_text = excluded.summary_text,
                                      summary_model = excluded.summary_model,
                                      summary_mocked = excluded.summary_mocked,
                                      gmt_modified = current_timestamp
                        """,
                userId,
                userFileId,
                filename,
                summary,
                model,
                mocked
        );
    }

    @Override
    public StoredDocumentTags getTags(Long userId, Long userFileId) {
        // 按“用户 + 用户文件”查询已经保存过的标签。
        // tags_json is not null 表示只取确实生成过标签的记录。
        List<StoredDocumentTags> results = jdbcTemplate.query(
                """
                        select filename, tags_json, tags_model, tags_mocked
                        from ai_document_result
                        where user_id = ? and user_file_id = ? and tags_json is not null
                        """,
                (rs, rowNum) -> {
                    // 数据库里 tags_json 是 jsonb，读出来后要转成 List<String>。
                    StoredDocumentTags tags = new StoredDocumentTags();
                    tags.setFilename(rs.getString("filename"));
                    tags.setTags(readTags(rs.getString("tags_json")));
                    tags.setModel(rs.getString("tags_model"));
                    tags.setMocked(rs.getObject("tags_mocked", Boolean.class));
                    return tags;
                },
                userId,
                userFileId
        );
        // 没查到返回 null，让业务层去重新调用 AI 生成标签。
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void saveTags(Long userId, Long userFileId, String filename, List<String> tags, String model, Boolean mocked) {
        // 保存标签结果。tags 是 Java List，入库前先转成 JSON 字符串，再 cast 成 PostgreSQL jsonb。
        jdbcTemplate.update(
                """
                        insert into ai_document_result(
                            user_id, user_file_id, filename, tags_json, tags_model, tags_mocked, gmt_modified
                        ) values (?, ?, ?, cast(? as jsonb), ?, ?, current_timestamp)
                        on conflict (user_id, user_file_id)
                        do update set filename = excluded.filename,
                                      tags_json = excluded.tags_json,
                                      tags_model = excluded.tags_model,
                                      tags_mocked = excluded.tags_mocked,
                                      gmt_modified = current_timestamp
                        """,
                userId,
                userFileId,
                filename,
                writeTags(tags),
                model,
                mocked
        );
    }

    @Override
    public void clearDocumentResult(Long userId, Long userFileId) {
        // 文件重新建索引时清掉旧摘要/旧标签，避免前端看到过期 AI 结果。
        jdbcTemplate.update(
                "delete from ai_document_result where user_id = ? and user_file_id = ?",
                userId,
                userFileId
        );
    }

    private List<String> readTags(String value) {
        // 数据库没有标签内容时，返回空列表，避免调用方处理 null。
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        try {
            // 把 jsonb 字符串反序列化成 Java 标签列表。
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (Exception e) {
            throw new AiException("Failed to deserialize stored document tags", e, AiErrorCode.DOCUMENT_INDEX_FAILED);
        }
    }

    private String writeTags(List<String> tags) {
        try {
            // null 标签按空数组保存，避免 JSON 序列化出 null。
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            throw new AiException("Failed to serialize document tags for persistence", e, AiErrorCode.DOCUMENT_INDEX_FAILED);
        }
    }

    private void initializeSchema() {
        // 创建 AI 结果表：同一用户的同一文件只保留一条结果，摘要和标签共用这一行。
        jdbcTemplate.execute("""
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
                )
                """);
        // 给 user_id + user_file_id 建索引，加快按文件查询摘要/标签的速度。
        jdbcTemplate.execute("create index if not exists idx_ai_document_result_owner on ai_document_result(user_id, user_file_id)");
    }
}
