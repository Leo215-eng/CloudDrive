package com.disk.ai.infrastructure.vector;

import com.disk.ai.infrastructure.file.AiSourceFile;
import com.disk.ai.infrastructure.parser.ParsedDocument;
import com.pgvector.PGvector;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Component
@ConditionalOnProperty(name = "com.disk.ai.pgvector.enabled", havingValue = "true")
// PostgreSQL + pgvector 的向量存储实现。
// 它负责保存文档切片 embedding，并根据用户问题向量找最相似的文档片段。
public class PgVectorVectorStore implements VectorStore, InitializingBean {

    // pgvector 配置：数据库地址、账号、向量维度、是否自动建表等。
    private final PgVectorProperties properties;

    // 连接 PostgreSQL 的 SQL 工具，来自 PgVectorConfiguration 里的 pgVectorJdbcTemplate。
    private final JdbcTemplate jdbcTemplate;

    public PgVectorVectorStore(PgVectorProperties properties,
                               @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        // Spring 创建完 Bean 后调用；init-schema=true 时自动创建扩展和表。
        if (properties.isInitSchema()) {
            initializeSchema();
        }
    }

    @Override
    public boolean isReady() {
        // 当前实现被创建出来就代表配置启用；这里不做实时数据库健康检查。
        return true;
    }

    @Override
    public DocumentIndexSummary getIndexSummary(Long userId, Long userFileId) {
        // 查询某个文件是否已经建过索引；ai_document_index 是每个文件一行的索引摘要表。
//        执行的 SQL；查到的数据行，交给中间那段 (rs, rowNum) -> {} 转换成 Java 实体类；
//        所有转换好的对象放进 List 列表返回。
        List<DocumentIndexSummary> results = jdbcTemplate.query(
                "select filename, media_type, parser, block_count, chunk_count, content_length from ai_document_index where user_id = ? and user_file_id = ?",

//                数据库查出来是原始行数据 ResultSet（简称 rs），不能直接返回给上层业务。
//                这段代码作用：把数据库一行数据，封装成实体 DocumentIndexSummary。
//               rs 全称 ResultSet 数据库查询返回的结果集对象，存本次查到的一行数据。
//                rowNum 当前行号 rowNum
                (rs, rowNum) -> {
                    // 把数据库一行转换成 DocumentIndexSummary 对象。
                    DocumentIndexSummary summary = new DocumentIndexSummary();
                    summary.setFilename(rs.getString("filename"));
                    summary.setMediaType(rs.getString("media_type"));
                    summary.setParser(rs.getString("parser"));
                    summary.setBlockCount(rs.getInt("block_count"));
                    summary.setChunkCount(rs.getInt("chunk_count"));
                    summary.setContentLength(rs.getLong("content_length"));
                    summary.setVectorDimension(properties.getDimension());
                    return summary;
                },
                userId,
                userFileId
        );
        // user_id + user_file_id 有唯一约束，正常最多一条；没有索引就返回 null。
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<String> loadDocumentChunks(Long userId, Long userFileId) {
        // 按 chunk_index 顺序读出文件所有文本块，用于摘要/标签复用已解析文本。
        return jdbcTemplate.queryForList(
                "select chunk_text from ai_document_chunk_vector where user_id = ? and user_file_id = ? order by chunk_index asc",
                String.class,
                userId,
                userFileId
        );
    }

    @Override
    public void replaceDocument(AiSourceFile sourceFile, ParsedDocument parsedDocument, List<PgVectorDocumentChunk> chunks) {
        // 重建索引时先删除旧文本块，避免同一个文件残留旧向量。
        jdbcTemplate.update("delete from ai_document_chunk_vector where user_id = ? and user_file_id = ?", sourceFile.getUserId(), sourceFile.getUserFileId());

        // 写入或更新文件级索引摘要：记录文件名、解析器、块数、文本长度等元信息。
        // 2. 插入/更新文件总表 ai_document_index（文件一级摘要表，一个文件一行）
        // PostgreSQL 特有语法 on conflict ... do update：唯一键冲突时执行更新，不存在则插入（UPSERT）
//        插入 ai_document_index 时，如果 user_id + user_file_id 已经存在，不要报错，改成更新这行数据。
//        excluded 是 PostgreSQL 里的特殊名字。它表示：这次本来想 insert 进去的新数据，有就更新，没有就插入
        jdbcTemplate.update("""
                insert into ai_document_index(
                    user_id, user_file_id, real_file_id, filename, file_suffix, media_type, parser,
                    content_length, plain_text_chars, block_count, chunk_count, gmt_modified
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                
                on conflict (user_id, user_file_id)
                do update set real_file_id = excluded.real_file_id,
                              filename = excluded.filename,
                              file_suffix = excluded.file_suffix,
                              media_type = excluded.media_type,
                              parser = excluded.parser,
                              content_length = excluded.content_length,
                              plain_text_chars = excluded.plain_text_chars,
                              block_count = excluded.block_count,
                              chunk_count = excluded.chunk_count,
                              gmt_modified = current_timestamp
                """,
                sourceFile.getUserId(),
                sourceFile.getUserFileId(),
                sourceFile.getRealFileId(),
                sourceFile.getFilename(),
                sourceFile.getFileSuffix(),
                parsedDocument.getMediaType(),
                parsedDocument.getParser(),
                (long) sourceFile.getBytes().length,
                parsedDocument.getPlainText().length(),
                parsedDocument.getBlocks().size(),
                chunks.size()
        );

        // 3. 批量插入所有向量分片到 ai_document_chunk_vector（向量检索核心表）
        // batchUpdate 批量操作，减少多次数据库网络往返，大幅提升大量分片插入性能
        jdbcTemplate.batchUpdate("""
                insert into ai_document_chunk_vector(
                    user_id, user_file_id, real_file_id, filename, file_suffix, media_type, parser,
                    block_index, chunk_index, start_offset, end_offset, token_estimate, chunk_text, embedding
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
//                创建一个匿名内部类。它要实现两个方法：1. 一共要插入多少行？2. 每一行的参数怎么填？
//                getBatchSize()框架内部会根据这个数字循环调用 setValues，循环次数 = 批量行数。
                new BatchPreparedStatementSetter() {
            @Override
//         Spring 先把那条 insert SQL 发给数据库，生成一个预编译模板，得到 ps 对象；
//         PreparedStatement ps预编译 SQL 对象，上方 insert 语句会被数据库预编译一次；
//         ps 是数据库预编译 SQL 的载体，你通过 ps.set数字(值) 给 SQL 里的 ? 占位符填每条分片的数据，框架靠它实现批量插入。
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                // setValues 会被调用 chunks.size() 次，每次把第 i 个 chunk 填进 SQL 占位符。
                // 依次给sql 1~14号?占位符赋值
                PgVectorDocumentChunk chunk = chunks.get(i);
                ps.setLong(1, chunk.getUserId());
                ps.setLong(2, chunk.getUserFileId());
                ps.setLong(3, chunk.getRealFileId());
                ps.setString(4, chunk.getFilename());
                ps.setString(5, chunk.getFileSuffix());
                ps.setString(6, chunk.getMediaType());
                ps.setString(7, chunk.getParser());
                ps.setInt(8, chunk.getBlockIndex());
                ps.setInt(9, chunk.getChunkIndex());
                ps.setInt(10, chunk.getStartOffset());
                ps.setInt(11, chunk.getEndOffset());
                ps.setInt(12, chunk.getTokenEstimate());
                ps.setString(13, chunk.getChunkText());
                // pgvector Java 类型，把 float[] 包成 PostgreSQL 的 vector 类型。
                ps.setObject(14, new PGvector(chunk.getEmbedding()));
            }

            @Override
            public int getBatchSize() {
                // 告诉 JdbcTemplate 一共要插入多少条。
                return chunks.size();
            }
        });
    }

    /**
     * 向量相似度检索：根据用户提问向量，查询指定用户、指定文件下语义最相似的文本切块
     * @param userId 当前操作用户ID，数据隔离
     * @param userFileId 目标文件唯一ID，只检索该文件内向量
     * @param queryVector 用户问题向量化后的浮点数组，用来做相似度匹配
     * @param topK 返回相似度最高的前N条结果
     * @return 相似度匹配结果列表，携带切块编号、原文、相似度分数
     */
    @Override
    public List<PgVectorSearchResult> search(Long userId, Long userFileId, float[] queryVector, int topK) {
        // 数据库pgvector字段是vector类型，Java float[]不能直接传入SQL，需要包装成PGvector驱动对象
        PGvector vector = new PGvector(queryVector);

        // jdbcTemplate.query 重载：两个参数
        // 1. ConnectionCallback：手动创建PreparedStatement、填充SQL占位符
        // 2. RowMapper：遍历查询结果集，把每行数据映射为业务实体 PgVectorSearchResult
//        这个 <=> 也是 pgvector 提供的向量距离运算符。
//        距离转相似度分数：距离 0 → 相似度 1.0；距离越大，相似度越趋近 0，业务上直观展示 0~1 匹配度
        return jdbcTemplate.query(
                connection -> {
                    // 手写查询SQL，pgvector专属向量相似度语法
                    PreparedStatement ps = connection.prepareStatement("""
                        select chunk_index, block_index, chunk_text, 1 - (embedding <=> ?) as similarity
                        from ai_document_chunk_vector
                        where user_id = ? and user_file_id = ?
                        order by embedding <=> ?
                        limit ?
                        """);
                    // 填充5个?占位符，序号从1开始
                    ps.setObject(1, vector); // 计算相似度用的查询向量
                    ps.setLong(2, userId);
                    ps.setLong(3, userFileId);
                    ps.setObject(4, vector); // 排序依据用的查询向量
                    ps.setInt(5, topK);
                    return ps;
                },
                // RowMapper 行映射器：每查到一行数据，回调此方法封装实体
                (rs, rowNum) -> {
                    PgVectorSearchResult result = new PgVectorSearchResult();
                    result.setChunkIndex(rs.getInt("chunk_index"));
                    result.setBlockIndex(rs.getInt("block_index"));
                    result.setChunkText(rs.getString("chunk_text"));
                    // 取出SQL计算好的相似度分数
                    result.setSimilarity(rs.getDouble("similarity"));
                    return result;
                }
        );
    }


    private void initializeSchema() {
        // 安装 pgvector 扩展，让 PostgreSQL 支持 vector 字段和向量距离计算。
        jdbcTemplate.execute("create extension if not exists vector");
        // 文件级索引摘要表：一个用户文件一行。
        jdbcTemplate.execute("""
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
                )
                """);
        // 文档块向量表：一个文本块一行，embedding vector(n) 存该块的语义向量。
        jdbcTemplate.execute(String.format("""
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
                    embedding vector(%d) not null,
                    gmt_create timestamp not null default current_timestamp
                )
                """, properties.getDimension()));
        // 普通索引：加快按 user_id + user_file_id 查某个文件所有 chunk 的速度。
        jdbcTemplate.execute("create index if not exists idx_ai_document_chunk_owner on ai_document_chunk_vector(user_id, user_file_id)");
    }
}
