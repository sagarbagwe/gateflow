package com.gateflow.async;

import static com.gateflow.workflow.WorkflowDtos.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Repository
public class OutboxRepository {
    public enum EventType {
        REQUEST_SUBMITTED,
        STEP_APPROVED,
        REQUEST_REJECTED,
        REQUEST_WITHDRAWN,
        REVIEWER_REASSIGNED
    }

    public record Event(
            UUID id,
            UUID organizationId,
            UUID requestId,
            long requestVersion,
            UUID actorMembershipId,
            UUID stepId,
            EventType type,
            RequestState state,
            String correlationId,
            Instant occurredAt) {}

    public record Claim(UUID eventId, UUID token, int attempt) {}

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            UUID org, UUID actor, EventType type, RequestView result, UUID step, String trace) {
        jdbc.update(
                "INSERT INTO"
                    + " outbox_events(organization_id,request_id,request_version,actor_membership_id,step_id,event_type,request_state,correlation_id)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                org,
                result.id(),
                result.version(),
                actor,
                step,
                type.name(),
                result.state().name(),
                trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(Duration lease) {
        UUID token = UUID.randomUUID();
        var rows =
                jdbc.query(
                        """
WITH candidate AS (SELECT id FROM outbox_events WHERE published_at IS NULL AND available_at<=clock_timestamp()
  AND (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY occurred_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
UPDATE outbox_events e SET lease_token=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempts=attempts+1
FROM candidate c WHERE e.id=c.id RETURNING e.id,e.attempts
""",
                        (rs, n) -> new Claim(rs.getObject(1, UUID.class), token, rs.getInt(2)),
                        token,
                        lease.toMillis());
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(Claim c) {
        return jdbc.update(
                        "UPDATE outbox_events SET"
                            + " published_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,last_failure=NULL"
                            + " WHERE id=? AND lease_token=? AND published_at IS NULL",
                        c.eventId(),
                        c.token())
                == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Claim c, String reason, Duration delay) {
        jdbc.update(
                "UPDATE outbox_events SET"
                    + " lease_token=NULL,lease_until=NULL,last_failure=?,available_at=clock_timestamp()+(?*interval"
                    + " '1 millisecond') WHERE id=? AND lease_token=? AND published_at IS NULL",
                reason,
                delay.toMillis(),
                c.eventId(),
                c.token());
    }

    public Optional<Event> find(UUID id) {
        return jdbc
                .query(
                        "SELECT"
                            + " id,organization_id,request_id,request_version,actor_membership_id,step_id,event_type,request_state,correlation_id,occurred_at"
                            + " FROM outbox_events WHERE id=?",
                        (rs, n) ->
                                new Event(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getObject(3, UUID.class),
                                        rs.getLong(4),
                                        rs.getObject(5, UUID.class),
                                        rs.getObject(6, UUID.class),
                                        EventType.valueOf(rs.getString(7)),
                                        RequestState.valueOf(rs.getString(8)),
                                        rs.getString(9),
                                        rs.getTimestamp(10).toInstant()),
                        id)
                .stream()
                .findFirst();
    }
}
