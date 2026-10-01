package com.gateflow.notifications;

import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class EmailDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(EmailDeliveryWorker.class);
    private final EmailDeliveryRepository repository;
    private final EmailDeliveryPolicy policy;
    private final EmailSender sender;
    private final NotificationProperties p;
    private final ReentrantLock running = new ReentrantLock();

    public EmailDeliveryWorker(
            EmailDeliveryRepository repository,
            EmailDeliveryPolicy policy,
            EmailSender sender,
            NotificationProperties p) {
        this.repository = repository;
        this.policy = policy;
        this.sender = sender;
        this.p = p;
    }

    @Scheduled(fixedDelayString = "${gateflow.notifications.poll-interval-ms:1000}")
    public void scheduled() {
        if (!p.enabled() || !p.emailEnabled() || !p.emailWorkerEnabled()) return;
        try {
            drainOnce();
        } catch (DataAccessException failure) {
            LOG.warn("email_source_deferred cause={}", failure.getClass().getSimpleName());
        }
    }

    public int drainOnce() {
        if (!p.emailEnabled() || !running.tryLock()) return 0;
        int count = 0;
        try {
            for (int i = 0; i < p.batchSize(); i++) {
                var next = repository.claim(p.leaseDuration());
                if (next.isEmpty()) break;
                process(next.get());
                count++;
            }
            return count;
        } finally {
            running.unlock();
        }
    }

    void process(EmailDeliveryRepository.Claim c) {
        EmailDeliveryPolicy.Prepared prepared;
        try {
            prepared = policy.prepare(c);
        } catch (DataAccessException unavailable) {
            finishFailure(
                    c, new EmailSendFailure(EmailSendFailure.Kind.RETRYABLE, "SOURCE_UNAVAILABLE"));
            return;
        }
        if (prepared.skipReason() != null) {
            repository.finish(c, "SKIPPED", prepared.skipReason(), Duration.ZERO);
            return;
        }
        try {
            sender.send(prepared.message());
        } catch (EmailSendFailure failure) {
            finishFailure(c, failure);
            return;
        } catch (RuntimeException unexpected) {
            finishFailure(
                    c, new EmailSendFailure(EmailSendFailure.Kind.UNKNOWN, "WORKER_UNCERTAIN"));
            return;
        }
        // If this DB commit fails after SMTP acceptance, leave the lease; expiry quarantines
        // UNKNOWN.
        repository.finish(c, "ACCEPTED", null, Duration.ZERO);
    }

    private void finishFailure(EmailDeliveryRepository.Claim c, EmailSendFailure failure) {
        String status =
                switch (failure.kind()) {
                    case UNKNOWN -> "UNKNOWN";
                    case PERMANENT -> "DEAD";
                    case RETRYABLE -> c.attempt() >= p.maxAttempts() ? "DEAD" : "RETRY";
                };
        repository.finish(
                c,
                status,
                failure.reason(),
                status.equals("RETRY") ? backoff(c.attempt(), p) : Duration.ZERO);
        LOG.warn("email_delivery_outcome status={} reason={}", status, failure.reason());
    }

    static Duration backoff(int attempt, NotificationProperties p) {
        long base =
                Math.min(
                        p.retryMax().toMillis(),
                        p.retryBase().toMillis() * (1L << Math.min(20, Math.max(0, attempt - 1))));
        return Duration.ofMillis(
                Math.min(
                        p.retryMax().toMillis(),
                        base + ThreadLocalRandom.current().nextLong(base / 5 + 1)));
    }
}
