package com.gateflow.notifications;

import java.util.UUID;

/** Implemented by provider adapters; acceptance is not proof of mailbox delivery. */
public interface EmailSender {
    record EmailMessage(UUID deliveryId, String to) {}

    void send(EmailMessage message);
}
