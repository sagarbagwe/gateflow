package com.gateflow.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.*;

class NotificationUnitTest {
    static NotificationProperties props(boolean email, int attempts) {
        return new NotificationProperties(
                true,
                false,
                email,
                false,
                attempts,
                5,
                1000,
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofHours(48),
                "no-reply@gateflow.invalid");
    }

    EmailDeliveryRepository.Claim claim(int attempt) {
        return new EmailDeliveryRepository.Claim(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                attempt);
    }

    @Test
    void propertiesRequireSafeLeaseAndBoundedAge() {
        assertThat(props(true, 3).isDurationsValid()).isTrue();
        var p = props(true, 3);
        assertThat(
                        new NotificationProperties(
                                        true,
                                        true,
                                        true,
                                        true,
                                        3,
                                        5,
                                        1000,
                                        Duration.ofSeconds(2),
                                        p.retryBase(),
                                        p.retryMax(),
                                        p.maxAge(),
                                        p.from())
                                .isDurationsValid())
                .isFalse();
        assertThat(
                        new NotificationProperties(
                                        true,
                                        true,
                                        true,
                                        true,
                                        3,
                                        5,
                                        1000,
                                        p.leaseDuration(),
                                        p.retryBase(),
                                        p.retryMax(),
                                        Duration.ofDays(8),
                                        p.from())
                                .isDurationsValid())
                .isFalse();
    }

    @Test
    void backoffIsPositiveBoundedAtExtremeAttempts() {
        for (int attempt : List.of(1, 2, 20, Integer.MAX_VALUE))
            assertThat(EmailDeliveryWorker.backoff(attempt, props(true, 3)))
                    .isBetween(Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    @Test
    void disabledEmailNeverClaimsOrCallsProvider() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        assertThat(new EmailDeliveryWorker(r, p, s, props(false, 3)).drainOnce()).isZero();
        verifyNoInteractions(r, p, s);
    }

    @Test
    void providerAcceptanceIsMarkedOnlyAfterSend() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(1);
        var m = new EmailSender.EmailMessage(c.id(), "fixture@example.test");
        when(p.prepare(c)).thenReturn(new EmailDeliveryPolicy.Prepared(m, null));
        new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c);
        var order = inOrder(p, s, r);
        order.verify(p).prepare(c);
        order.verify(s).send(m);
        order.verify(r).finish(c, "ACCEPTED", null, Duration.ZERO);
    }

    @Test
    void staleOrRevokedRecipientIsSkippedWithoutProviderCall() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(1);
        when(p.prepare(c))
                .thenReturn(new EmailDeliveryPolicy.Prepared(null, "PREFERENCES_OR_ACCESS"));
        new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c);
        verifyNoInteractions(s);
        verify(r).finish(c, "SKIPPED", "PREFERENCES_OR_ACCESS", Duration.ZERO);
    }

    @Test
    void sourceUnavailableBeforeSendCanRetrySafely() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(1);
        when(p.prepare(c)).thenThrow(new DataAccessResourceFailureException("fixture"));
        new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c);
        verifyNoInteractions(s);
        verify(r).finish(eq(c), eq("RETRY"), eq("SOURCE_UNAVAILABLE"), any());
    }

    @Test
    void retryLimitProducesDeadNotAnotherSend() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(3);
        when(p.prepare(c))
                .thenReturn(
                        new EmailDeliveryPolicy.Prepared(
                                new EmailSender.EmailMessage(c.id(), "fixture@example.test"),
                                null));
        doThrow(new EmailSendFailure(EmailSendFailure.Kind.RETRYABLE, "PROVIDER_RETRYABLE"))
                .when(s)
                .send(any());
        new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c);
        verify(r).finish(c, "DEAD", "PROVIDER_RETRYABLE", Duration.ZERO);
    }

    @Test
    void unexpectedProviderBugIsUnknownNotRetried() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(1);
        when(p.prepare(c))
                .thenReturn(
                        new EmailDeliveryPolicy.Prepared(
                                new EmailSender.EmailMessage(c.id(), "fixture@example.test"),
                                null));
        doThrow(new IllegalStateException("fixture")).when(s).send(any());
        new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c);
        verify(r).finish(c, "UNKNOWN", "WORKER_UNCERTAIN", Duration.ZERO);
    }

    @Test
    void databaseFailureAfterProviderAcceptanceLeavesLeaseUnmodified() {
        var r = mock(EmailDeliveryRepository.class);
        var p = mock(EmailDeliveryPolicy.class);
        var s = mock(EmailSender.class);
        var c = claim(1);
        when(p.prepare(c))
                .thenReturn(
                        new EmailDeliveryPolicy.Prepared(
                                new EmailSender.EmailMessage(c.id(), "fixture@example.test"),
                                null));
        when(r.finish(c, "ACCEPTED", null, Duration.ZERO))
                .thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThatThrownBy(() -> new EmailDeliveryWorker(r, p, s, props(true, 3)).process(c))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(r, times(1)).finish(any(), anyString(), any(), any());
    }

    @Test
    void deterministicMessageIdsContainNoRecipientOrTenant() {
        var id = UUID.randomUUID();
        assertThat(SmtpEmailSender.messageId(id))
                .isEqualTo("<delivery-" + id + "@gateflow.invalid>");
        assertThat(SmtpEmailSender.BODY)
                .doesNotContain("requestId", "organization", "amount", "Engineering laptop");
    }

    @Test
    void providerFailureCodesCannotLeakRawExceptionMessages() {
        assertThatThrownBy(
                        () ->
                                new EmailSendFailure(
                                        EmailSendFailure.Kind.RETRYABLE,
                                        "recipient@example.test: SMTP error"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
