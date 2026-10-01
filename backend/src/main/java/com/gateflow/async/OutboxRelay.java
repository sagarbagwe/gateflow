package com.gateflow.async;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ConditionalOnProperty(prefix = "gateflow.async", name = "enabled", havingValue = "true")
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository repo;
    private final ConfirmedEventPublisher publisher;
    private final EventReferenceCodec codec;
    private final AsyncProperties p;
    private final ReentrantLock running = new ReentrantLock();

    public OutboxRelay(
            OutboxRepository repo,
            ConfirmedEventPublisher publisher,
            EventReferenceCodec codec,
            AsyncProperties p) {
        this.repo = repo;
        this.publisher = publisher;
        this.codec = codec;
        this.p = p;
    }

    @Scheduled(fixedDelayString = "${gateflow.async.poll-interval-ms:1000}")
    public void scheduled() {
        if (!p.relayEnabled()) return;
        try {
            drainOnce();
        } catch (DataAccessException failure) {
            LOG.warn("outbox_source_unavailable cause={}", failure.getClass().getSimpleName());
        }
    }

    public int drainOnce() {
        if (!running.tryLock()) return 0;
        int count = 0;
        try {
            for (int i = 0; i < p.batchSize(); i++) {
                var next = repo.claim(p.leaseDuration());
                if (next.isEmpty()) break;
                var c = next.get();
                try {
                    publisher.send(
                            AsyncConfiguration.EVENTS,
                            AsyncConfiguration.EVENT_ROUTE,
                            ConfirmedEventPublisher.message(
                                    codec.encode(c.eventId()), c.eventId().toString(), 0, null));
                    if (repo.published(c)) count++;
                } catch (ConfirmedEventPublisher.PublishFailure failure) {
                    repo.failed(c, failure.reason().name(), backoff(c.attempt(), p));
                    LOG.warn("outbox_publish_deferred reason={}", failure.reason());
                }
            }
            return count;
        } finally {
            running.unlock();
        }
    }

    static Duration backoff(int attempt, AsyncProperties p) {
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
