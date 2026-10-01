package com.gateflow.async;

import org.springframework.amqp.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(prefix = "gateflow.async", name = "enabled", havingValue = "true")
public class ConfirmedEventPublisher {
    public enum Reason {
        BROKER_UNAVAILABLE,
        UNROUTABLE,
        CONFIRM_TIMEOUT,
        CONFIRM_NACK
    }

    public static class PublishFailure extends RuntimeException {
        private final Reason reason;

        public PublishFailure(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final RabbitTemplate rabbit;
    private final AsyncProperties p;

    public ConfirmedEventPublisher(RabbitTemplate rabbit, AsyncProperties p) {
        this.rabbit = rabbit;
        this.p = p;
    }

    public void send(String exchange, String route, Message message) {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange, route, message, correlation);
            var confirm =
                    correlation
                            .getFuture()
                            .get(p.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) throw new PublishFailure(Reason.CONFIRM_NACK);
            if (correlation.getReturned() != null) throw new PublishFailure(Reason.UNROUTABLE);
        } catch (PublishFailure e) {
            throw e;
        } catch (TimeoutException e) {
            throw new PublishFailure(Reason.CONFIRM_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailure(Reason.BROKER_UNAVAILABLE);
        } catch (AmqpException | ExecutionException e) {
            throw new PublishFailure(Reason.BROKER_UNAVAILABLE);
        }
    }

    public static Message message(byte[] body, String id, int attempt, String reason) {
        var props = new MessageProperties();
        props.setMessageId(id);
        props.setContentType("application/json");
        props.setContentEncoding("UTF-8");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader("x-gf-attempt", attempt);
        if (reason != null) props.setHeader("x-gf-error", reason);
        return new Message(body, props);
    }
}
