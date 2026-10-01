package com.gateflow.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class EmailDeliveryPolicy {
    public record Prepared(EmailSender.EmailMessage message, String skipReason) {}

    private final JdbcTemplate jdbc;
    private final PreferenceRepository preferences;
    private final NotificationPolicy policy;
    private final NotificationProperties properties;

    public EmailDeliveryPolicy(
            JdbcTemplate jdbc,
            PreferenceRepository preferences,
            NotificationPolicy policy,
            NotificationProperties properties) {
        this.jdbc = jdbc;
        this.preferences = preferences;
        this.policy = policy;
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Prepared prepare(EmailDeliveryRepository.Claim c) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " n.request_id,n.step_id,n.kind,u.email,(clock_timestamp()-e.occurred_at)>(?*interval"
                            + " '1 millisecond') AS expired FROM notification_email_deliveries d"
                            + " JOIN notifications n ON n.organization_id=d.organization_id AND"
                            + " n.id=d.notification_id JOIN memberships m ON"
                            + " m.organization_id=n.organization_id AND"
                            + " m.id=n.recipient_membership_id JOIN users u ON u.id=m.user_id JOIN"
                            + " outbox_events e ON e.organization_id=n.organization_id AND"
                            + " e.id=n.event_id WHERE d.id=? AND d.organization_id=? AND"
                            + " d.lease_token=? AND d.status='PROCESSING'",
                        (rs, n) ->
                                new Row(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getBoolean(5)),
                        properties.maxAge().toMillis(),
                        c.id(),
                        c.org(),
                        c.token());
        if (rows.isEmpty()) return new Prepared(null, "PREFERENCES_OR_ACCESS");
        var r = rows.getFirst();
        if (r.expired()) return new Prepared(null, "EXPIRED");
        if (!preferences.find(c.org(), c.member()).emailEnabled()
                || !policy.visible(c.org(), c.member(), r.request()))
            return new Prepared(null, "PREFERENCES_OR_ACCESS");
        if (r.kind().equals("ACTION_REQUIRED")
                && !policy.actionable(c.org(), c.member(), r.request(), r.step()))
            return new Prepared(null, "STALE_ACTION");
        return new Prepared(new EmailSender.EmailMessage(c.id(), r.email()), null);
    }

    private record Row(UUID request, UUID step, String kind, String email, boolean expired) {}
}
