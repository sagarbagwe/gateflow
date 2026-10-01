package com.gateflow.notifications;

import com.gateflow.async.*;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "${gateflow.async.enabled:false} && ${gateflow.notifications.enabled:false}")
public class NotificationAsyncConfiguration {
    public static final String QUEUE = "gateflow.notifications.v1",
            WORK = "gateflow.notifications.work.v1",
            RETRY = "gateflow.notifications.retry.v1",
            DEAD = "gateflow.notifications.dead.v1",
            LISTENER = "gateflow-notifications-v1";

    @Bean
    Declarables notificationTopology(AsyncProperties p) {
        var events = new DirectExchange(AsyncConfiguration.EVENTS, true, false);
        var work = new DirectExchange(WORK, true, false);
        var retry = new DirectExchange(RETRY, true, false);
        var dead = new DirectExchange(DEAD, true, false);
        var main =
                AsyncConfiguration.queue(
                        QUEUE,
                        Map.of(
                                "x-dead-letter-exchange",
                                DEAD,
                                "x-dead-letter-routing-key",
                                "dead",
                                "x-dead-letter-strategy",
                                "at-least-once",
                                "x-delivery-limit",
                                50));
        var dlq = AsyncConfiguration.queue(DEAD, Map.of());
        var list =
                new ArrayList<Declarable>(
                        List.of(
                                events,
                                work,
                                retry,
                                dead,
                                main,
                                dlq,
                                BindingBuilder.bind(main)
                                        .to(events)
                                        .with(AsyncConfiguration.EVENT_ROUTE),
                                BindingBuilder.bind(main).to(work).with("notification"),
                                BindingBuilder.bind(dlq).to(dead).with("dead")));
        for (int i = 0; i < 3; i++) {
            var q =
                    AsyncConfiguration.queue(
                            QUEUE + ".retry." + (i + 1),
                            Map.of(
                                    "x-message-ttl",
                                    p.retryDelays().get(i).toMillis(),
                                    "x-dead-letter-exchange",
                                    WORK,
                                    "x-dead-letter-routing-key",
                                    "notification",
                                    "x-dead-letter-strategy",
                                    "at-least-once"));
            list.add(q);
            list.add(BindingBuilder.bind(q).to(retry).with("retry." + (i + 1)));
        }
        return new Declarables(list);
    }
}
