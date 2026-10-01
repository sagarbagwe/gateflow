package com.gateflow.async;

import com.gateflow.http.PageSlice;
import com.gateflow.rbac.AuthorizationService;
import com.gateflow.workflow.RequestAccessPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.UUID;

@Service
public class RequestActivityService {
    public record Item(
            UUID eventId,
            long requestVersion,
            UUID actorMembershipId,
            UUID stepId,
            String type,
            String state,
            Instant occurredAt,
            Instant projectedAt) {}

    public record Page(PageSlice<Item> activity, boolean eventuallyConsistent) {}

    private final AuthorizationService auth;
    private final RequestAccessPolicy access;
    private final JdbcTemplate jdbc;

    public RequestActivityService(
            AuthorizationService auth, RequestAccessPolicy access, JdbcTemplate jdbc) {
        this.auth = auth;
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page read(UUID user, UUID org, UUID request, int limit, int offset) {
        var actor = auth.requireMember(user, org);
        access.requireVisible(actor, request);
        var rows =
                jdbc.query(
                        "SELECT"
                            + " event_id,request_version,actor_membership_id,step_id,event_type,request_state,occurred_at,projected_at"
                            + " FROM request_activity WHERE organization_id=? AND request_id=?"
                            + " ORDER BY request_version DESC LIMIT ? OFFSET ?",
                        (rs, n) ->
                                new Item(
                                        rs.getObject(1, UUID.class),
                                        rs.getLong(2),
                                        rs.getObject(3, UUID.class),
                                        rs.getObject(4, UUID.class),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getTimestamp(7).toInstant(),
                                        rs.getTimestamp(8).toInstant()),
                        org,
                        request,
                        limit + 1,
                        offset);
        return new Page(PageSlice.from(rows, limit, offset), true);
    }
}
