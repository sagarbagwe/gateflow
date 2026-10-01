package com.gateflow.audit;

import com.gateflow.http.*;
import com.gateflow.rbac.*;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

@Service
public class AuditService {
    public record Entry(
            UUID id,
            String actorKind,
            UUID actorMembershipId,
            String action,
            String resourceType,
            UUID resourceId,
            String requestId,
            Instant occurredAt) {}

    public record Detail(
            Entry entry,
            com.fasterxml.jackson.databind.JsonNode oldValue,
            com.fasterxml.jackson.databind.JsonNode newValue,
            boolean snapshotRedacted) {}

    private final AuthorizationService auth;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditData data;

    public AuditService(
            AuthorizationService auth, NamedParameterJdbcTemplate jdbc, AuditData data) {
        this.auth = auth;
        this.jdbc = jdbc;
        this.data = data;
    }

    private static final String FIELDS =
            "id,actor_kind,actor_membership_id,action,resource_type,resource_id,correlation_id,occurred_at";

    private static Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getString(5),
                rs.getObject(6, UUID.class),
                rs.getString(7),
                rs.getTimestamp(8).toInstant());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageSlice<Entry> list(UUID user, UUID org, AuditQuery q) {
        auth.require(user, org, Permission.AUDIT_VIEW);
        var p =
                new MapSqlParameterSource("org", org)
                        .addValue("fetch", q.limit() + 1)
                        .addValue("offset", q.offset());
        var where = new ArrayList<String>();
        where.add("organization_id=:org");
        add(where, p, "action", q.action());
        add(where, p, "resource_type", q.resourceType());
        add(where, p, "resource_id", q.resourceId());
        add(where, p, "actor_membership_id", q.actorMembershipId());
        add(where, p, "correlation_id", q.requestId());
        if (q.from() != null) {
            where.add("occurred_at>=:from_time");
            p.addValue("from_time", q.from().atOffset(ZoneOffset.UTC));
        }
        if (q.before() != null) {
            where.add("occurred_at<:before_time");
            p.addValue("before_time", q.before().atOffset(ZoneOffset.UTC));
        }
        var rows =
                jdbc.query(
                        "SELECT "
                                + FIELDS
                                + " FROM audit_logs WHERE "
                                + String.join(" AND ", where)
                                + " ORDER BY occurred_at DESC,id DESC LIMIT :fetch OFFSET :offset",
                        p,
                        (rs, n) -> entry(rs));
        return PageSlice.from(rows, q.limit(), q.offset());
    }

    private static void add(List<String> w, MapSqlParameterSource p, String col, Object v) {
        if (v != null) {
            w.add(col + "=:" + col);
            p.addValue(col, v);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(UUID user, UUID org, UUID id) {
        auth.require(user, org, Permission.AUDIT_VIEW);
        var rows =
                jdbc.query(
                        "SELECT "
                                + FIELDS
                                + ",CASE WHEN octet_length(old_value::text)<=65536 THEN"
                                + " old_value::text END,CASE WHEN"
                                + " octet_length(new_value::text)<=65536 THEN new_value::text"
                                + " END,COALESCE(octet_length(old_value::text)>65536 OR"
                                + " octet_length(new_value::text)>65536,false) FROM audit_logs"
                                + " WHERE organization_id=:org AND id=:id",
                        new MapSqlParameterSource("org", org).addValue("id", id),
                        (rs, n) -> {
                            var old = data.read(rs.getString(9));
                            var next = data.read(rs.getString(10));
                            return new Detail(
                                    entry(rs),
                                    old.value(),
                                    next.value(),
                                    rs.getBoolean(11) || old.redacted() || next.redacted());
                        });
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", "Audit entry not found");
        return rows.getFirst();
    }
}
