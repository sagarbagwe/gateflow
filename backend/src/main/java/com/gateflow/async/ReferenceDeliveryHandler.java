package com.gateflow.async;

import com.rabbitmq.client.Channel;

import org.slf4j.*;
import org.springframework.amqp.core.Message;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Shared bounded at-least-once reference protocol for independent subscribers. */
public final class ReferenceDeliveryHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ReferenceDeliveryHandler.class);
    private final Consumer<UUID> processor;
    private final ConfirmedEventPublisher publisher;
    private final EventReferenceCodec codec;
    private final String retryExchange, deadExchange;

    public ReferenceDeliveryHandler(
            Consumer<UUID> processor,
            ConfirmedEventPublisher publisher,
            EventReferenceCodec codec,
            String retryExchange,
            String deadExchange) {
        this.processor = processor;
        this.publisher = publisher;
        this.codec = codec;
        this.retryExchange = retryExchange;
        this.deadExchange = deadExchange;
    }

    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        int attempt = 0;
        String reason;
        try {
            attempt = codec.attempt(message.getMessageProperties().getHeader("x-gf-attempt"));
            UUID id =
                    codec.decode(message.getBody(), message.getMessageProperties().getMessageId());
            processor.accept(id);
            channel.basicAck(tag, false);
            return;
        } catch (EventReferenceCodec.InvalidEvent poison) {
            reason = "INVALID_EVENT";
            attempt = 3;
        } catch (RuntimeException processingFailure) {
            reason = "PROCESSING_FAILED";
        }
        // Retry/DLQ handoff is itself publisher-confirmed before acknowledging the original.
        try {
            boolean dead = attempt >= 3;
            byte[] body =
                    Arrays.copyOf(
                            message.getBody(),
                            Math.min(message.getBody().length, EventReferenceCodec.MAX_BYTES));
            String id = message.getMessageProperties().getMessageId();
            if (id == null || !id.matches("[0-9a-f-]{36}")) id = UUID.randomUUID().toString();
            publisher.send(
                    dead ? deadExchange : retryExchange,
                    dead ? "dead" : "retry." + (attempt + 1),
                    ConfirmedEventPublisher.message(body, id, dead ? 3 : attempt + 1, reason));
            channel.basicAck(tag, false);
        } catch (ConfirmedEventPublisher.PublishFailure handoff) {
            LOG.warn("reference_handoff_deferred reason={}", handoff.reason());
            channel.basicNack(tag, false, true);
            try {
                channel.close(200, "Async handoff unavailable");
            } catch (Exception ignored) {
                /* Connection loss also leaves broker-owned deliveries recoverable. */
            }
        }
    }
}
