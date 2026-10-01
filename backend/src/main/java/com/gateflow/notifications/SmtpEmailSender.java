package com.gateflow.notifications;

import jakarta.mail.*;
import jakarta.mail.internet.*;

import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.util.*;

@Component
public class SmtpEmailSender implements EmailSender {
    public static final String SUBJECT = "GateFlow notification",
            BODY =
                    "You have a new notification in GateFlow. Sign in to view your authorized"
                            + " notification inbox.";
    private final JavaMailSender mail;
    private final NotificationProperties properties;

    public SmtpEmailSender(JavaMailSender mail, NotificationProperties properties) {
        this.mail = mail;
        this.properties = properties;
    }

    public static String messageId(UUID id) {
        return "<delivery-" + id + "@gateflow.invalid>";
    }

    @Override
    public void send(EmailMessage message) {
        try {
            var to = new InternetAddress(message.to(), true);
            to.validate();
            var from = new InternetAddress(properties.from(), true);
            from.validate();
            if (message.to().contains("\r")
                    || message.to().contains("\n")
                    || properties.from().contains("\r")
                    || properties.from().contains("\n")) throw new AddressException();
            var mime = mail.createMimeMessage();
            mime.setFrom(from);
            mime.setRecipient(Message.RecipientType.TO, to);
            mime.setSubject(SUBJECT, "UTF-8");
            mime.setText(BODY, "UTF-8");
            mime.setHeader("Message-ID", messageId(message.deliveryId()));
            mail.send(mime);
        } catch (AddressException | MailParseException invalid) {
            throw new EmailSendFailure(EmailSendFailure.Kind.PERMANENT, "INVALID_MESSAGE");
        } catch (MailAuthenticationException auth) {
            throw new EmailSendFailure(EmailSendFailure.Kind.RETRYABLE, "SMTP_AUTH");
        } catch (MailException failure) {
            boolean refused = connectionRefused(failure);
            throw new EmailSendFailure(
                    refused ? EmailSendFailure.Kind.RETRYABLE : EmailSendFailure.Kind.UNKNOWN,
                    refused ? "SMTP_CONNECTION" : "SMTP_UNCERTAIN");
        } catch (MessagingException failure) {
            throw new EmailSendFailure(EmailSendFailure.Kind.PERMANENT, "INVALID_MESSAGE");
        }
    }

    static boolean connectionRefused(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        queue.add(error);
        while (!queue.isEmpty() && seen.size() < 32) {
            var next = queue.removeFirst();
            if (!seen.add(next)) continue;
            if (next instanceof ConnectException) return true;
            if (next.getCause() != null) queue.add(next.getCause());
            if (next instanceof MessagingException m && m.getNextException() != null)
                queue.add(m.getNextException());
            if (next instanceof MailSendException m)
                for (var e : m.getFailedMessages().values()) queue.add(e);
        }
        return false;
    }
}
