package com.disk.files.infrastructure.canal.sync;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.disk.files.infrastructure.canal.config.CanalSyncProperties;
import com.disk.files.infrastructure.es.entity.UserFileESEntity;
import com.disk.files.infrastructure.es.mapper.UserFileESMapper;
import jakarta.annotation.PreDestroy;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Canal 的 user_file -> ES 增量同步任务
 */
@Component
//@EnableConfigurationProperties：开启配置类，自动读取配置文件里 Canal 的地址、端口、订阅表等配置。
@EnableConfigurationProperties(CanalSyncProperties.class)
//@ConditionalOnProperty：条件注解—— 只有配置文件里 com.disk.canal.enable = true 时，这个类才会生效。
@ConditionalOnProperty(prefix = "com.disk.canal", name = "enable", havingValue = "true")
//@ConditionalOnBean：条件注解—— 只有 ES 的 Mapper 存在（也就是 ES 配置成功、可用）时，才启动同步
@ConditionalOnBean(UserFileESMapper.class)
//implements Runnable：实现 Runnable 接口，因为同步是死循环监听，必须开单独后台线程跑，不能阻塞主程序。
public class CanalUserFileEsSyncRunner implements Runnable {

    // 日志工具
    private static final Logger log = LoggerFactory.getLogger(CanalUserFileEsSyncRunner.class);

    // Canal 配置类：存地址、端口、用户名、订阅表名等配置
    private final CanalSyncProperties properties;
    // ES 操作 Mapper：同步数据到 ES 用
    private final UserFileESMapper userFileESMapper;

    // 运行标记：volatile 保证多线程可见性，用来优雅停止循环
    private volatile boolean running = true;
    // 工作线程：单独开一个线程跑同步死循环
    private Thread workerThread;
    // Canal 连接器：负责和 Canal 服务建立连接、拉取消息
    private CanalConnector connector;

//
//    项目一启动，这个 Bean 初始化时就会自动开一个后台线程，开始跑同步逻辑；
//    守护线程的作用：程序关闭时不用手动停线程，不会阻止程序退出。
    // 构造方法：Spring 注入配置和 Mapper，启动同步线程
    public CanalUserFileEsSyncRunner(CanalSyncProperties properties, UserFileESMapper userFileESMapper) {
        this.properties = properties;
        this.userFileESMapper = userFileESMapper;
        startWorker();
    }
    // 启动后台工作线程
    private void startWorker() {
        workerThread = new Thread(this, "canal-user-file-es-sync");
        workerThread.setDaemon(true); // 设置为守护线程：主程序关闭，线程自动跟着关闭
        workerThread.start();
    }
//    ack /rollback 机制
//    这是 Canal 保证数据不丢的核心设计：
//    你拉到消息后，处理成功了，调用 ack 告诉 Canal：“这批我处理完了”，Canal 就会推进进度，下次给你新的；
//    处理失败了，调用 rollback，Canal 下次还会把这批消息再发给你，直到你处理成功为止；
//    就像快递签收：签收回单了，快递员就走了；没签收，下次还会再来送。
    @Override
    public void run() {
        // 第一步：创建 Canal 连接器，指定 Canal 服务地址、端口、实例名、账号密码
        connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(properties.getHost(), properties.getPort()),
                properties.getDestination(),
                StringUtils.defaultString(properties.getUsername()),
                StringUtils.defaultString(properties.getPassword())
        );

        try {
            // 第二步：连接 Canal 服务
            connector.connect();
            // 订阅要监听的表（配置里一般是 "coder_pan.user_file"，只监听文件表）
            connector.subscribe(properties.getSubscribeFilter());
            // 回滚到上次确认的位置，从上次没处理的地方开始，避免丢数据
            connector.rollback();
            log.info("canal sync started. destination={}, filter={}", properties.getDestination(), properties.getSubscribeFilter());

            // 第三步：死循环，持续拉取 binlog 消息
            while (running) {
                // 拉取一批消息，不自动确认（getWithoutAck）
                // batchSize：一次最多拉多少条
                Message message = connector.getWithoutAck(properties.getBatchSize());
                long batchId = message.getId();
                List<CanalEntry.Entry> entries = message.getEntries();

                // 没拉到数据，睡一会再拉，避免空转浪费CPU
                if (batchId == -1 || CollectionUtils.isEmpty(entries)) {
                    sleepQuietly(properties.getPollingIntervalMs());
                    continue;
                }

                boolean success = false;
                try {
                    // 第四步：处理这一批消息（解析增删改，同步到ES）
                    handleEntries(entries);
                    success = true;
                } catch (Exception e) {
                    log.error("canal sync batch handle error, batchId={}", batchId, e);
                }

                // 第五步：消息确认机制
                if (success) {
                    // 处理成功 → 确认 ack，Canal 就不会再发这批消息了
                    connector.ack(batchId);
                } else {
                    // 处理失败 → 回滚 rollback，下次还会重新拉这批消息，保证不丢数据
                    connector.rollback(batchId);
                    sleepQuietly(properties.getPollingIntervalMs());
                }
            }
        } catch (Exception e) {
            log.error("canal sync stopped unexpectedly", e);
        } finally {
            // 最终一定要断开连接
            if (connector != null) {
                connector.disconnect();
            }
        }
    }


    private void handleEntries(List<CanalEntry.Entry> entries) throws Exception {
        // 遍历每一条 binlog 条目
        for (CanalEntry.Entry entry : entries) {
            // 只处理「行数据变化」类型，事务开头/结尾这类事件直接跳过
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }

            CanalEntry.Header header = entry.getHeader();
            // 只处理 user_file 表的变化，其他表不关心
            if (!"user_file".equalsIgnoreCase(header.getTableName())) {
                continue;
            }

            // 解析行变化数据
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            // 获取事件类型：新增 / 修改 / 删除
            CanalEntry.EventType eventType = rowChange.getEventType();

            // 遍历每一行变化的数据
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                if (eventType == CanalEntry.EventType.DELETE) {
                    // 删除操作：拿删除前的数据，去 ES 删对应文档
                    handleDelete(rowData.getBeforeColumnsList());
                    continue;
                }
                if (eventType == CanalEntry.EventType.INSERT || eventType == CanalEntry.EventType.UPDATE) {
                    // 新增/修改操作：拿最新的数据，更新或插入 ES
                    handleUpsert(rowData.getAfterColumnsList());
                }
            }
        }
    }


    private void handleDelete(List<CanalEntry.Column> columns) {
        // 把列列表转成 <字段名:字段值> 的 Map，方便取值
        Map<String, String> values = toValueMap(columns);
        // 拿到数据主键 ID
        Long id = asLong(values.get("id"));
        if (id == null) {
            return;
        }
        // 根据 ID 删除 ES 里对应的文档
        userFileESMapper.deleteById(id);
    }

//    为什么要「先更新，失败再插入」？
//    这就是经典的 Upsert（更新或插入） 逻辑：
//    如果是 MySQL 更新操作，ES 里本来就有这条数据，直接更新就行；
//    如果是 MySQL 新增操作，ES 里没有，更新会返回 0，这时再执行插入；
//    一套逻辑同时兼容新增和修改，不用判断事件类型，简单可靠，保证数据最终一致。
    private void handleUpsert(List<CanalEntry.Column> columns) {
        Map<String, String> values = toValueMap(columns);
        Long id = asLong(values.get("id"));
        if (id == null) {
            return;
        }

        // 把所有字段封装成 ES 实体对象
        UserFileESEntity entity = new UserFileESEntity();
        entity.setId(id);
        entity.setUserId(asLong(values.get("user_id")));
        entity.setParentId(asLong(values.get("parent_id")));
        entity.setRealFileId(asLong(values.get("real_file_id")));
        entity.setFilename(values.get("filename"));
        entity.setFolderFlag(asInteger(values.get("folder_flag")));
        entity.setFileSizeDesc(values.get("file_size_desc"));
        entity.setFileType(asInteger(values.get("file_type")));
        entity.setCreateUser(values.get("create_user"));
        entity.setUpdateUser(values.get("update_user"));
        entity.setGmtCreate(asDate(values.get("gmt_create")));
        entity.setGmtModified(asDate(values.get("gmt_modified")));
        entity.setDeleted(asInteger(values.get("deleted")));
        entity.setLockVersion(asInteger(values.get("lock_version")));

        // 先尝试按 ID 更新 ES 文档
        Integer updated = userFileESMapper.updateById(entity);
        // 更新失败（说明 ES 里没有这条数据，是新增的），就执行插入
        if (updated == null || updated <= 0) {
            userFileESMapper.insert(entity);
        }
    }


    // 把 Canal 的列列表转成 Map，方便按字段名取值
    private Map<String, String> toValueMap(List<CanalEntry.Column> columns) {
        Map<String, String> result = new HashMap<>();
        for (CanalEntry.Column column : columns) {
            result.put(column.getName(), column.getValue());
        }
        return result;
    }

    // 字符串转 Long
    private Long asLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Long.valueOf(value);
    }

    // 字符串转 Integer
    private Integer asInteger(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Integer.valueOf(value);
    }

    // 字符串转 Date，兼容两种格式，做了容错
    private java.util.Date asDate(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        try {
            // 先尝试直接转时间戳格式
            return Timestamp.valueOf(normalized);
        } catch (Exception ignore) {
        }
        try {
            // 失败再尝试 "yyyy-MM-dd HH:mm:ss" 格式
            LocalDateTime dateTime = LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return Timestamp.valueOf(dateTime);
        } catch (Exception ignore) {
            return null;
        }
    }

    // 安静睡眠：捕获中断异常，不抛出，不打断循环
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    // Spring 销毁 Bean 时自动执行（比如程序关闭时）
    @PreDestroy
    public void shutdown() {
        running = false; // 把运行标记设为 false，让循环自己退出
        if (workerThread != null) {
            workerThread.interrupt(); // 中断线程，唤醒睡眠中的循环
        }
    }

}
