package com.gateflow.notifications;

import com.gateflow.async.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class NotificationProjector {
    public static final String CONSUMER = "notification_projection_v1";
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    private final PreferenceRepository preferences;
    private final NotificationPolicy policy;

    public NotificationProjector(
            JdbcTemplate jdbc,
            OutboxRepository outbox,
            PreferenceRepository preferences,
            NotificationPolicy policy) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.preferences = preferences;
        this.policy = policy;
    }

    private record Target(UUID member, UUID step) {}

    @Transactional
    public boolean process(UUID id) {
        var e = outbox.find(id).orElseThrow(EventReferenceCodec.InvalidEvent::new);
        if (jdbc.update(
                        "INSERT INTO processed_events(consumer_name,event_id,organization_id)"
                                + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                        CONSUMER,
                        id,
                        e.organizationId())
                == 0) return false;
        jdbc.queryForObject(
                "SELECT id FROM organizations WHERE id=? FOR SHARE",
                UUID.class,
                e.organizationId());
        var owners =
                jdbc.query(
                        "SELECT requester_membership_id FROM requests WHERE organization_id=? AND"
                                + " id=?",
                        (rs, n) -> new Target(rs.getObject(1, UUID.class), null),
                        e.organizationId(),
                        e.requestId());
        var targets = new ArrayList<>(owners);
        targets.addAll(
                jdbc.query(
                        "SELECT s.assigned_membership_id,s.id FROM requests r JOIN request_steps s"
                            + " ON s.organization_id=r.organization_id AND s.request_id=r.id AND"
                            + " s.state='ACTIVE' WHERE r.organization_id=? AND r.id=? AND"
                            + " r.row_version=? AND r.state='IN_REVIEW' AND"
                            + " s.assigned_membership_id IS NOT NULL AND"
                            + " s.assigned_membership_id<>r.requester_membership_id",
                        (rs, n) ->
                                new Target(
                                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                        e.organizationId(),
                        e.requestId(),
                        e.requestVersion()));
        for (var t : targets) {
            if (!policy.visible(e.organizationId(), t.member(), e.requestId())
                    || (t.step() != null
                            && !policy.actionable(
                                    e.organizationId(), t.member(), e.requestId(), t.step())))
                continue;
            var pref = preferences.find(e.organizationId(), t.member());
            if (!pref.inAppEnabled() && !pref.emailEnabled()) continue;
            UUID notification = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO"
                        + " notifications(id,organization_id,event_id,request_id,recipient_membership_id,step_id,kind,in_app)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    notification,
                    e.organizationId(),
                    id,
                    e.requestId(),
                    t.member(),
                    t.step(),
                    t.step() == null ? "STATUS_UPDATE" : "ACTION_REQUIRED",
                    pref.inAppEnabled());
            if (pref.emailEnabled())
                jdbc.update(
                        "INSERT INTO"
                            + " notification_email_deliveries(organization_id,notification_id,recipient_membership_id)"
                            + " VALUES(?,?,?)",
                        e.organizationId(),
                        notification,
                        t.member());
        }
        return true;
    }
}
