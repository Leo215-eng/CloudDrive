package com.disk.ai.infrastructure.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restores tasks abandoned by a crashed worker after their execution lease expires. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiDocumentTaskLeaseRecovery {

    private final AiDocumentTaskRepository repository;

    @Scheduled(fixedDelayString = "${ai.task.lease-recovery-delay-ms:30000}")
    public void recoverExpiredLeases() {
        int recovered = repository.recoverExpired();
        if (recovered > 0) {
            log.warn("recovered expired AI task leases, count={}", recovered);
        }
    }
}
