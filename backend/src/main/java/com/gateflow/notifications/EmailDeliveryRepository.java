package com.gateflow.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

import java.time.Duration;
import java.util.*;

@Repository
public class EmailDeliveryRepository {
    public record Claim(
            UUID id, UUID org, UUID notification, UUID member, UUID token, int attempt) {}

    private final JdbcTemplate jdbc;

    public EmailDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(Duration lease) {
        // Expired external-send leases cannot safely be retried: their outcome is unknown.
        jdbc.update(
                "WITH expired AS (SELECT id,lease_token FROM notification_email_deliveries WHERE"
                    + " status='PROCESSING' AND lease_until<=clock_timestamp() ORDER BY"
                    + " lease_until,id LIMIT 100 FOR UPDATE SKIP LOCKED), moved AS (UPDATE"
                    + " notification_email_deliveries d SET"
                    + " status='UNKNOWN',lease_token=NULL,lease_until=NULL,last_failure='LEASE_EXPIRED',completed_at=clock_timestamp()"
                    + " FROM expired x WHERE d.id=x.id RETURNING"
                    + " d.id,d.organization_id,d.attempts,x.lease_token) INSERT INTO"
                    + " notification_email_attempts(organization_id,delivery_id,attempt_number,lease_token,outcome,reason)"
                    + " SELECT organization_id,id,attempts,lease_token,'UNKNOWN','LEASE_EXPIRED'"
                    + " FROM moved");
        UUID token = UUID.randomUUID();
        return jdbc
                .query(
                        "WITH candidate AS (SELECT id FROM notification_email_deliveries WHERE"
                            + " status IN('PENDING','RETRY') AND available_at<=clock_timestamp()"
                            + " ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE"
                            + " notification_email_deliveries d SET"
                            + " status='PROCESSING',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+(?*interval"
                            + " '1 millisecond'),last_failure=NULL FROM candidate c WHERE d.id=c.id"
                            + " RETURNING"
                            + " d.id,d.organization_id,d.notification_id,d.recipient_membership_id,d.attempts",
                        (rs, n) ->
                                new Claim(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getObject(3, UUID.class),
                                        rs.getObject(4, UUID.class),
                                        token,
                                        rs.getInt(5)),
                        token,
                        lease.toMillis())
                .stream()
                .findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(Claim c, String outcome, String reason, Duration retry) {
        int changed =
                jdbc.update(
                        "UPDATE notification_email_deliveries SET"
                            + " status=?,last_failure=?,lease_token=NULL,lease_until=NULL,available_at=clock_timestamp()+(?*interval"
                            + " '1 millisecond'),completed_at=CASE WHEN ?='RETRY' THEN NULL ELSE"
                            + " clock_timestamp() END WHERE id=? AND organization_id=? AND"
                            + " status='PROCESSING' AND lease_token=?",
                        outcome,
                        reason,
                        retry.toMillis(),
                        outcome,
                        c.id(),
                        c.org(),
                        c.token());
        if (changed == 0) return false;
        jdbc.update(
                "INSERT INTO"
                    + " notification_email_attempts(organization_id,delivery_id,attempt_number,lease_token,outcome,reason)"
                    + " VALUES(?,?,?,?,?,?)",
                c.org(),
                c.id(),
                c.attempt(),
                c.token(),
                outcome,
                reason);
        return true;
    }
}
