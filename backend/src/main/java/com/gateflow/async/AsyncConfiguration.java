package com.gateflow.async;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AsyncProperties.class)
public class AsyncConfiguration {
    public static final String EVENTS = "gateflow.workflow.events.v1",
            EVENT_ROUTE = "request.changed.v1",
            WORK = "gateflow.activity.work.v1",
            RETRY = "gateflow.activity.retry.v1",
            DEAD = "gateflow.activity.dead.v1",
            QUEUE = "gateflow.activity.v1",
            LISTENER = "gateflow-activity-v1";

    public static org.springframework.amqp.core.Queue queue(
            String name, Map<String, Object> extra) {
        var a = new HashMap<String, Object>();
        a.put("x-queue-type", "quorum");
        a.put("x-overflow", "reject-publish");
        a.put("x-max-length", 10000);
        a.put("x-max-length-bytes", 16777216);
        a.putAll(extra);
        return new org.springframework.amqp.core.Queue(name, true, false, false, a);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateflow.async", name = "enabled", havingValue = "true")
    Declarables activityTopology(AsyncProperties p) {
        var events = new DirectExchange(EVENTS, true, false);
        var work = new DirectExchange(WORK, true, false);
        var retry = new DirectExchange(RETRY, true, false);
        var dead = new DirectExchange(DEAD, true, false);
        var main =
                queue(
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
        var dlq = queue(DEAD, Map.of());
        var declarations =
                new ArrayList<Declarable>(
                        List.of(
                                events,
                                work,
                                retry,
                                dead,
                                main,
                                dlq,
                                BindingBuilder.bind(main).to(events).with(EVENT_ROUTE),
                                BindingBuilder.bind(main).to(work).with("activity"),
                                BindingBuilder.bind(dlq).to(dead).with("dead")));
        for (int i = 0; i < 3; i++) {
            var q =
                    queue(
                            QUEUE + ".retry." + (i + 1),
                            Map.of(
                                    "x-message-ttl",
                                    p.retryDelays().get(i).toMillis(),
                                    "x-dead-letter-exchange",
                                    WORK,
                                    "x-dead-letter-routing-key",
                                    "activity",
                                    "x-dead-letter-strategy",
                                    "at-least-once"));
            declarations.add(q);
            declarations.add(BindingBuilder.bind(q).to(retry).with("retry." + (i + 1)));
        }
        return new Declarables(declarations);
    }

    @Bean(name = "activityListenerFactory")
    @ConditionalOnProperty(prefix = "gateflow.async", name = "enabled", havingValue = "true")
    SimpleRabbitListenerContainerFactory activityListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        var f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, connectionFactory);
        f.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        f.setPrefetchCount(1);
        f.setConcurrentConsumers(1);
        f.setMaxConcurrentConsumers(2);
        f.setDefaultRequeueRejected(false);
        f.setRecoveryInterval(1000L);
        return f;
    }
}
