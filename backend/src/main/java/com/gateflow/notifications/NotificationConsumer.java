package com.gateflow.notifications;

import com.gateflow.async.*;
import com.rabbitmq.client.Channel;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnExpression(
        "${gateflow.async.enabled:false} && ${gateflow.notifications.enabled:false}")
public class NotificationConsumer {
    private final ReferenceDeliveryHandler handler;

    public NotificationConsumer(
            NotificationProjector projector,
            ConfirmedEventPublisher publisher,
            EventReferenceCodec codec) {
        handler =
                new ReferenceDeliveryHandler(
                        projector::process,
                        publisher,
                        codec,
                        NotificationAsyncConfiguration.RETRY,
                        NotificationAsyncConfiguration.DEAD);
    }

    @RabbitListener(
            id = NotificationAsyncConfiguration.LISTENER,
            queues = NotificationAsyncConfiguration.QUEUE,
            containerFactory = "activityListenerFactory",
            autoStartup = "${gateflow.notifications.consumer-enabled:true}")
    public void receive(Message message, Channel channel) throws IOException {
        handler.receive(message, channel);
    }
}
