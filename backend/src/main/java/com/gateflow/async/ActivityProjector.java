package com.gateflow.async;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ActivityProjector {
    public static final String CONSUMER = "activity_projection_v1";
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;

    public ActivityProjector(JdbcTemplate jdbc, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public boolean process(UUID id) {
        var e = outbox.find(id).orElseThrow(EventReferenceCodec.InvalidEvent::new);
        if (jdbc.update(
                        "INSERT INTO processed_events(consumer_name,event_id,organization_id)"
                                + " VALUES(?,?,?) ON CONFLICT(consumer_name,event_id) DO NOTHING",
                        CONSUMER,
                        id,
                        e.organizationId())
                == 0) return false;
        jdbc.update(
                "INSERT INTO"
                    + " request_activity(event_id,organization_id,request_id,request_version,actor_membership_id,step_id,event_type,request_state,occurred_at)"
                    + " SELECT"
                    + " id,organization_id,request_id,request_version,actor_membership_id,step_id,event_type,request_state,occurred_at"
                    + " FROM outbox_events WHERE id=?",
                id);
        return true;
    }
}
