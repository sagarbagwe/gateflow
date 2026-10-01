package com.gateflow.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.icegreen.greenmail.util.*;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.mail.javamail.*;

import java.net.*;
import java.util.UUID;

class SmtpEmailSenderTest {
    JavaMailSenderImpl sender(int port) {
        var m = new JavaMailSenderImpl();
        m.setHost("127.0.0.1");
        m.setPort(port);
        m.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "500");
        m.getJavaMailProperties().setProperty("mail.smtp.timeout", "1000");
        m.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "1000");
        return m;
    }

    @Test
    void actualSmtpRetainsDeterministicMessageIdAndMinimalPlainText() throws Exception {
        var server = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        server.setUser("recipient@example.test", "recipient", "fixture-password");
        server.start();
        try {
            var id = UUID.randomUUID();
            new SmtpEmailSender(
                            sender(server.getSmtp().getPort()), NotificationUnitTest.props(true, 3))
                    .send(new EmailSender.EmailMessage(id, "recipient@example.test"));
            assertThat(server.waitForIncomingEmail(3000, 1)).isTrue();
            var received = server.getReceivedMessages()[0];
            assertThat(received.getMessageID()).isEqualTo(SmtpEmailSender.messageId(id));
            assertThat(received.getSubject()).isEqualTo(SmtpEmailSender.SUBJECT);
            assertThat(received.getContent().toString().trim()).isEqualTo(SmtpEmailSender.BODY);
            assertThat(received.getContentType()).startsWith("text/plain");
        } finally {
            server.stop();
        }
    }

    @Test
    void refusedConnectionIsRetryableBeforeAcceptance() throws Exception {
        int port;
        try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        assertThatThrownBy(
                        () ->
                                new SmtpEmailSender(
                                                sender(port), NotificationUnitTest.props(true, 3))
                                        .send(
                                                new EmailSender.EmailMessage(
                                                        UUID.randomUUID(),
                                                        "recipient@example.test")))
                .isInstanceOfSatisfying(
                        EmailSendFailure.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(EmailSendFailure.Kind.RETRYABLE);
                            assertThat(e.reason()).isEqualTo("SMTP_CONNECTION");
                            assertThat(e.getMessage()).doesNotContain("recipient@example.test");
                        });
    }

    @Test
    void realSmtpAuthenticationFailureDoesNotPretendAcceptance() throws Exception {
        var server = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        server.setUser("recipient@example.test", "recipient", "fixture-password");
        server.start();
        try {
            var mail = sender(server.getSmtp().getPort());
            mail.setUsername("recipient");
            mail.setPassword("incorrect-fixture-password");
            mail.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
            assertThatThrownBy(
                            () ->
                                    new SmtpEmailSender(mail, NotificationUnitTest.props(true, 3))
                                            .send(
                                                    new EmailSender.EmailMessage(
                                                            UUID.randomUUID(),
                                                            "recipient@example.test")))
                    .isInstanceOfSatisfying(
                            EmailSendFailure.class,
                            e -> assertThat(e.reason()).isEqualTo("SMTP_AUTH"));
            assertThat(server.getReceivedMessages()).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void addressHeaderInjectionIsRejectedBeforeNetworkIo() {
        var mail = mock(JavaMailSender.class);
        assertThatThrownBy(
                        () ->
                                new SmtpEmailSender(mail, NotificationUnitTest.props(true, 3))
                                        .send(
                                                new EmailSender.EmailMessage(
                                                        UUID.randomUUID(),
                                                        "recipient@example.test\r\n"
                                                                + "Bcc: injected@example.test")))
                .isInstanceOfSatisfying(
                        EmailSendFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(EmailSendFailure.Kind.PERMANENT));
        verifyNoInteractions(mail);
    }

    @Test
    void timeoutAfterPossibleAcceptanceIsUnknown() {
        var mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage())
                .thenReturn(new MimeMessage(Session.getInstance(new java.util.Properties())));
        doThrow(
                        new MailSendException(
                                "fixture",
                                new MessagingException(
                                        "fixture", new SocketTimeoutException("fixture"))))
                .when(mail)
                .send(any(MimeMessage.class));
        assertThatThrownBy(
                        () ->
                                new SmtpEmailSender(mail, NotificationUnitTest.props(true, 3))
                                        .send(
                                                new EmailSender.EmailMessage(
                                                        UUID.randomUUID(),
                                                        "recipient@example.test")))
                .isInstanceOfSatisfying(
                        EmailSendFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(EmailSendFailure.Kind.UNKNOWN));
    }
}
