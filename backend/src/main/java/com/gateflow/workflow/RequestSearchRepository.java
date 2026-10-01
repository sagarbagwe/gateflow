package com.gateflow.workflow;

import static com.gateflow.workflow.RequestSearchDtos.*;
import static com.gateflow.workflow.WorkflowDtos.*;
import static com.gateflow.workflow.WorkflowRepository.instant;

import com.gateflow.rbac.RbacDtos.AccessView;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.*;

@Repository
public class RequestSearchRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RequestAccessPolicy access;

    public RequestSearchRepository(NamedParameterJdbcTemplate jdbc, RequestAccessPolicy access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record Statement(String sql, MapSqlParameterSource params) {}

    public record Row(Summary item, Instant asOf) {}

    // Also used by the EXPLAIN integration test so evidence measures the real SQL builder.
    public Statement statement(
            AccessView actor, RequestSearchQuery q, SearchCursor.Position cursor) {
        var visibility = access.visible(actor);
        var p = new MapSqlParameterSource(visibility.params());
        String direction = q.sort() == RequestSearchQuery.Sort.CREATED_DESC ? "DESC" : "ASC";
        var where = new ArrayList<String>();
        where.add("r.organization_id=:org");
        if (q.scope() != RequestSearchQuery.Scope.INBOX) where.add(visibility.sql());
        if (q.scope() == RequestSearchQuery.Scope.OWN)
            where.add("r.requester_membership_id=:actor");
        if (q.scope() == RequestSearchQuery.Scope.INBOX) {
            where.add("r.state='IN_REVIEW'");
            where.add(access.pendingAssignee());
        }
        if (!q.statuses().isEmpty()) {
            where.add("r.state IN (:statuses)");
            p.addValue("statuses", q.statuses().stream().map(Enum::name).toList());
        }
        if (q.type() != null) {
            where.add("r.request_type=:type");
            p.addValue("type", q.type().name());
        }
        if (q.workflowId() != null) {
            where.add("r.workflow_definition_id=:workflow");
            p.addValue("workflow", q.workflowId());
        }
        if (q.createdFrom() != null) {
            where.add("r.created_at>=:createdFrom");
            p.addValue("createdFrom", q.createdFrom().atOffset(ZoneOffset.UTC));
        }
        if (q.createdBefore() != null) {
            where.add("r.created_at<:createdBefore");
            p.addValue("createdBefore", q.createdBefore().atOffset(ZoneOffset.UTC));
        }
        if (q.q() != null) {
            where.add("r.search_document @@ plainto_tsquery('simple'::regconfig,:q)");
            p.addValue("q", q.q());
        }
        where.add("r.created_at<=COALESCE(CAST(:asOf AS timestamptz),statement_timestamp())");
        p.addValue("asOf", cursor == null ? null : cursor.asOf().atOffset(ZoneOffset.UTC));
        if (cursor != null) {
            where.add(
                    "(r.created_at,r.id)"
                            + (direction.equals("DESC") ? "<" : ">")
                            + "(:afterCreatedAt,:afterId)");
            p.addValue("afterCreatedAt", cursor.afterCreatedAt().atOffset(ZoneOffset.UTC));
            p.addValue("afterId", cursor.afterId());
        }
        p.addValue("fetch", q.limit() + 1);
        p.addValue("offset", q.offset());
        String join = q.scope() == RequestSearchQuery.Scope.INBOX ? "JOIN" : "LEFT JOIN";
        String sql =
                """
SELECT r.id,r.workflow_definition_id,r.workflow_version_id,r.requester_membership_id,r.title,r.request_type,r.purchase_amount,r.currency,r.state,r.row_version,r.created_at,r.submitted_at,r.completed_at,
  s.id AS active_id,w.position AS active_position,w.name AS active_name,s.assigned_membership_id,s.activated_at,
  COALESCE(CAST(:asOf AS timestamptz),statement_timestamp()) AS as_of
FROM requests r %s request_steps s ON s.organization_id=r.organization_id AND s.request_id=r.id AND s.state='ACTIVE'
  %s workflow_steps w ON w.organization_id=s.organization_id AND w.workflow_version_id=s.workflow_version_id AND w.id=s.workflow_step_id
WHERE
"""
                                .formatted(join, join)
                        + String.join(" AND ", where)
                        + " ORDER BY r.created_at "
                        + direction
                        + ",r.id "
                        + direction
                        + " LIMIT :fetch"
                        + (q.pagination() == RequestSearchQuery.Pagination.OFFSET
                                ? " OFFSET :offset"
                                : "");
        return new Statement(sql, p);
    }

    public List<Row> search(AccessView actor, RequestSearchQuery q, SearchCursor.Position cursor) {
        var statement = statement(actor, q, cursor);
        return jdbc.query(
                statement.sql(),
                statement.params(),
                (rs, n) -> {
                    UUID active = rs.getObject("active_id", UUID.class);
                    var summary =
                            new Summary(
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("workflow_definition_id", UUID.class),
                                    rs.getObject("workflow_version_id", UUID.class),
                                    rs.getObject("requester_membership_id", UUID.class),
                                    rs.getString("title"),
                                    RequestType.valueOf(rs.getString("request_type")),
                                    rs.getBigDecimal("purchase_amount"),
                                    rs.getString("currency"),
                                    RequestState.valueOf(rs.getString("state")),
                                    rs.getLong("row_version"),
                                    instant(rs, "created_at"),
                                    instant(rs, "submitted_at"),
                                    instant(rs, "completed_at"),
                                    active == null
                                            ? null
                                            : new ActiveStep(
                                                    active,
                                                    rs.getInt("active_position"),
                                                    rs.getString("active_name"),
                                                    rs.getObject(
                                                            "assigned_membership_id", UUID.class),
                                                    instant(rs, "activated_at")));
                    return new Row(summary, instant(rs, "as_of"));
                });
    }
}
