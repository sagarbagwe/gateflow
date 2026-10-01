package com.gateflow.workflow;

import static com.gateflow.rbac.Permission.*;

import com.gateflow.http.ApiException;
import com.gateflow.rbac.RbacDtos.AccessView;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** Authoritative SQL visibility shared by detail and list/command prechecks. */
@Component
public class RequestAccessPolicy {
    private final NamedParameterJdbcTemplate jdbc;

    public RequestAccessPolicy(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Predicate(String sql, Map<String, Object> params) {
        public Predicate {
            params = Map.copyOf(params);
        }
    }

    public Predicate visible(AccessView actor) {
        var parts = new ArrayList<String>();
        if (actor.permissions().contains(REQUEST_VIEW_ALL)) parts.add("TRUE");
        else {
            if (actor.permissions().contains(REQUEST_VIEW_OWN))
                parts.add("r.requester_membership_id=:actor");
            if (actor.permissions().contains(REQUEST_APPROVE)) {
                parts.add(
                        "EXISTS(SELECT 1 FROM approval_decisions d WHERE"
                            + " d.organization_id=r.organization_id AND d.request_id=r.id AND"
                            + " d.reviewer_membership_id=:actor)");
                parts.add(assigned());
            }
        }
        return new Predicate(
                parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")",
                Map.of("org", actor.organizationId(), "actor", actor.membershipId()));
    }

    public String pendingAssignee() {
        return """
s.state='ACTIVE' AND s.assigned_membership_id=:actor AND r.requester_membership_id<>:actor
  AND EXISTS(SELECT 1 FROM membership_roles mr WHERE mr.organization_id=r.organization_id
    AND mr.membership_id=:actor AND mr.role_id=w.approver_role_id)
""";
    }

    public String assigned() {
        return "EXISTS(SELECT 1 FROM request_steps s JOIN workflow_steps w ON"
                   + " w.organization_id=s.organization_id AND"
                   + " w.workflow_version_id=s.workflow_version_id AND w.id=s.workflow_step_id"
                   + " WHERE s.organization_id=r.organization_id AND s.request_id=r.id AND "
                + pendingAssignee()
                + ")";
    }

    public void requireSearchAccess(AccessView actor, RequestSearchQuery.Scope scope) {
        boolean permitted =
                switch (scope) {
                    case INBOX -> actor.permissions().contains(REQUEST_APPROVE);
                    case OWN ->
                            actor.permissions().contains(REQUEST_VIEW_OWN)
                                    || actor.permissions().contains(REQUEST_VIEW_ALL);
                    case VISIBLE ->
                            actor.permissions().contains(REQUEST_VIEW_ALL)
                                    || actor.permissions().contains(REQUEST_VIEW_OWN)
                                    || actor.permissions().contains(REQUEST_APPROVE);
                };
        if (!permitted)
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Request search permission denied");
    }

    public void requireVisible(AccessView actor, UUID request) {
        var predicate = visible(actor);
        var params = new MapSqlParameterSource(predicate.params()).addValue("request", request);
        boolean found =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM requests r WHERE"
                                    + " r.organization_id=:org AND r.id=:request AND "
                                        + predicate.sql()
                                        + ")",
                                params,
                                Boolean.class));
        if (!found) throw WorkflowRepository.missing("REQUEST_NOT_FOUND");
    }
}
