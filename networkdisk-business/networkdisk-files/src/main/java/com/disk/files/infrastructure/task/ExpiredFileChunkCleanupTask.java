package com.disk.files.infrastructure.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.disk.file.core.StorageEngine;
import com.disk.files.domain.entity.FileChunkDO;
import com.disk.files.domain.service.FileChunkService;
import com.disk.lock.DistributeLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Cleans abandoned resumable-upload chunks.
 *
 * <p>All file-service instances schedule this task, but the distributed lock
 * makes one instance responsible for each run. Database records are removed
 * only after their temporary binaries were successfully removed.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredFileChunkCleanupTask {

    private final FileChunkService fileChunkService;
    private final StorageEngine storageEngine;

    @Scheduled(cron = "${com.disk.file.chunk-cleanup.cron:0 0/10 * * * ?}")
    @DistributeLock(scene = "EXPIRED_FILE_CHUNK_CLEANUP", key = "global", expireTime = 300000)
    public void cleanupExpiredChunks() {
        List<FileChunkDO> expiredChunks = fileChunkService.list(
                Wrappers.<FileChunkDO>lambdaQuery().lt(FileChunkDO::getExpirationTime, new Date()));
        if (expiredChunks.isEmpty()) {
            return;
        }

        try {
            storageEngine.cleanupTemporaryChunks(expiredChunks.stream()
                    .map(FileChunkDO::getRealPath)
                    .collect(Collectors.toList()));
            fileChunkService.removeChunkRecordsPhysically(expiredChunks.stream()
                    .map(FileChunkDO::getId)
                    .collect(Collectors.toList()));
        } catch (IOException exception) {
            // Keep DB records when physical cleanup fails so the next run can retry.
            log.warn("expired file chunks cleanup failed, count={}", expiredChunks.size(), exception);
        }
    }
}
