package com.gateflow.async;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.*;

class AsyncUnitTest {
    final EventReferenceCodec codec = new EventReferenceCodec(new ObjectMapper());

    AsyncProperties props() {
        return new AsyncProperties(
                true,
                false,
                false,
                20,
                1000,
                Duration.ofMillis(200),
                Duration.ofSeconds(3),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300)));
    }

    @Test
    void referenceContainsOnlySchemaAndEventId() {
        UUID id = UUID.randomUUID();
        assertThat(codec.decode(codec.encode(id), id.toString())).isEqualTo(id);
        assertThat(new String(codec.encode(id), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("organization", "password", "request", "actor");
    }

    @Test
    void malformedFieldsTypesVersionsIdentityAndTrailingDataFail() {
        UUID id = UUID.randomUUID();
        String valid = new String(codec.encode(id), java.nio.charset.StandardCharsets.UTF_8);
        for (String bad :
                List.of(
                        "null",
                        "{}",
                        valid + " {}",
                        valid.replace("\"schema\":1", "\"schema\":2"),
                        valid.replace("\"schema\":1", "\"schema\":1.0"),
                        valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                        valid.replace("\"schema\":1", "\"schema\":1,\"extra\":true"),
                        "x".repeat(257)))
            assertThatThrownBy(
                            () ->
                                    codec.decode(
                                            bad.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                            id.toString()))
                    .isInstanceOf(EventReferenceCodec.InvalidEvent.class);
        assertThatThrownBy(() -> codec.decode(codec.encode(id), UUID.randomUUID().toString()))
                .isInstanceOf(EventReferenceCodec.InvalidEvent.class);
    }

    @Test
    void attemptHeaderMustBeBoundedInteger() {
        for (Object bad : List.of(-1, 4, "1", 1L))
            assertThatThrownBy(() -> codec.attempt(bad))
                    .isInstanceOf(EventReferenceCodec.InvalidEvent.class);
        assertThatThrownBy(() -> codec.attempt(null))
                .isInstanceOf(EventReferenceCodec.InvalidEvent.class);
        for (int i = 0; i <= 3; i++) assertThat(codec.attempt(i)).isEqualTo(i);
    }

    @Test
    void durationAndLeaseValidation() {
        assertThat(props().isDurationsValid()).isTrue();
        assertThat(
                        new AsyncProperties(
                                        true,
                                        true,
                                        true,
                                        20,
                                        1000,
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(2),
                                        props().retryDelays())
                                .isDurationsValid())
                .isFalse();
    }

    @Test
    void backoffAlwaysBoundedAndPositive() {
        for (int attempt : List.of(1, 2, 10, Integer.MAX_VALUE))
            assertThat(OutboxRelay.backoff(attempt, props()))
                    .isBetween(props().retryBase(), props().retryMax());
    }

    @Test
    void confirmAckIsRequired() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(
                        a -> {
                            ((CorrelationData) a.getArgument(3))
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatCode(
                        () ->
                                new ConfirmedEventPublisher(rabbit, props())
                                        .send(
                                                "x",
                                                "r",
                                                ConfirmedEventPublisher.message(
                                                        codec.encode(UUID.randomUUID()),
                                                        "id",
                                                        0,
                                                        null)))
                .doesNotThrowAnyException();
    }

    @Test
    void nackAndUnavailableDoNotSucceed() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(
                        a -> {
                            ((CorrelationData) a.getArgument(3))
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(false, "fixture"));
                            return null;
                        })
                .when(rabbit)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(
                        () ->
                                new ConfirmedEventPublisher(rabbit, props())
                                        .send("x", "r", new Message(new byte[0])))
                .isInstanceOf(ConfirmedEventPublisher.PublishFailure.class);
    }

    @Test
    void confirmTimeoutIsFailure() {
        var rabbit = mock(RabbitTemplate.class);
        assertThatThrownBy(
                        () ->
                                new ConfirmedEventPublisher(rabbit, props())
                                        .send("x", "r", new Message(new byte[0])))
                .isInstanceOf(ConfirmedEventPublisher.PublishFailure.class);
    }

    @Test
    void relaySuccessOnlyMarksAfterConfirmedSend() {
        var repo = mock(OutboxRepository.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var c = new OutboxRepository.Claim(UUID.randomUUID(), UUID.randomUUID(), 1);
        when(repo.claim(any())).thenReturn(Optional.of(c), Optional.empty());
        when(repo.published(c)).thenReturn(true);
        var relay = new OutboxRelay(repo, publisher, codec, props());
        assertThat(relay.drainOnce()).isEqualTo(1);
        var order = inOrder(repo, publisher);
        order.verify(repo).claim(any());
        order.verify(publisher).send(anyString(), anyString(), any());
        order.verify(repo).published(c);
        verify(repo, never()).failed(any(), anyString(), any());
    }

    @Test
    void relayFailureSchedulesRetryWithoutMarkingPublished() {
        var repo = mock(OutboxRepository.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var c = new OutboxRepository.Claim(UUID.randomUUID(), UUID.randomUUID(), 1);
        when(repo.claim(any())).thenReturn(Optional.of(c), Optional.empty());
        doThrow(
                        new ConfirmedEventPublisher.PublishFailure(
                                ConfirmedEventPublisher.Reason.CONFIRM_TIMEOUT))
                .when(publisher)
                .send(anyString(), anyString(), any());
        assertThat(new OutboxRelay(repo, publisher, codec, props()).drainOnce()).isZero();
        verify(repo).failed(eq(c), eq("CONFIRM_TIMEOUT"), any());
        verify(repo, never()).published(any());
    }

    @Test
    void dbProcessingCompletesBeforeAck() throws Exception {
        var projector = mock(ActivityProjector.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        var id = UUID.randomUUID();
        var message = ConfirmedEventPublisher.message(codec.encode(id), id.toString(), 0, null);
        message.getMessageProperties().setDeliveryTag(1);
        new ActivityConsumer(projector, publisher, codec).receive(message, channel);
        var order = inOrder(projector, channel);
        order.verify(projector).process(id);
        order.verify(channel).basicAck(1, false);
        verifyNoInteractions(publisher);
    }

    @Test
    void transientFailureHandsOffConfirmedRetryBeforeAck() throws Exception {
        var projector = mock(ActivityProjector.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        var id = UUID.randomUUID();
        var message = ConfirmedEventPublisher.message(codec.encode(id), id.toString(), 0, null);
        doThrow(new DataAccessResourceFailureException("fixture")).when(projector).process(id);
        new ActivityConsumer(projector, publisher, codec).receive(message, channel);
        var order = inOrder(publisher, channel);
        order.verify(publisher).send(eq(AsyncConfiguration.RETRY), eq("retry.1"), any());
        order.verify(channel).basicAck(0, false);
    }

    @Test
    void poisonUsesDlqNotEndlessRetry() throws Exception {
        var projector = mock(ActivityProjector.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        new ActivityConsumer(projector, publisher, codec)
                .receive(
                        ConfirmedEventPublisher.message("invalid".getBytes(), "bad", 0, null),
                        channel);
        verifyNoInteractions(projector);
        verify(publisher).send(eq(AsyncConfiguration.DEAD), eq("dead"), any());
        verify(channel).basicAck(0, false);
    }

    @Test
    void failedHandoffKeepsOriginalAndClosesChannel() throws Exception {
        var publisher = mock(ConfirmedEventPublisher.class);
        doThrow(
                        new ConfirmedEventPublisher.PublishFailure(
                                ConfirmedEventPublisher.Reason.UNROUTABLE))
                .when(publisher)
                .send(anyString(), anyString(), any());
        var channel = mock(com.rabbitmq.client.Channel.class);
        new ActivityConsumer(mock(ActivityProjector.class), publisher, codec)
                .receive(
                        ConfirmedEventPublisher.message("invalid".getBytes(), "bad", 0, null),
                        channel);
        verify(channel).basicNack(0, false, true);
        verify(channel).close(200, "Async handoff unavailable");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void lostAckDoesNotPublishRetryForAlreadyCommittedWork() throws Exception {
        var projector = mock(ActivityProjector.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        var id = UUID.randomUUID();
        var message = ConfirmedEventPublisher.message(codec.encode(id), id.toString(), 0, null);
        doThrow(new java.io.IOException("fixture ACK connection loss"))
                .when(channel)
                .basicAck(0, false);
        assertThatThrownBy(
                        () ->
                                new ActivityConsumer(projector, publisher, codec)
                                        .receive(message, channel))
                .isInstanceOf(java.io.IOException.class);
        verify(projector).process(id);
        verifyNoInteractions(publisher);
    }

    @Test
    void unexpectedProcessingBugStillUsesBoundedRetry() throws Exception {
        var projector = mock(ActivityProjector.class);
        var publisher = mock(ConfirmedEventPublisher.class);
        var channel = mock(com.rabbitmq.client.Channel.class);
        var id = UUID.randomUUID();
        doThrow(new IllegalStateException("fixture implementation bug"))
                .when(projector)
                .process(id);
        new ActivityConsumer(projector, publisher, codec)
                .receive(
                        ConfirmedEventPublisher.message(codec.encode(id), id.toString(), 2, null),
                        channel);
        verify(publisher).send(eq(AsyncConfiguration.RETRY), eq("retry.3"), any());
        verify(channel).basicAck(0, false);
    }
}
