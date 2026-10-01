package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;
import static com.gateflow.workflow.WorkflowRepository.instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.util.*;

@Repository
public class RequestRepository {
    private final JdbcTemplate jdbc;
    private final WorkflowJson json;

    public RequestRepository(JdbcTemplate jdbc, WorkflowJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public RequestView find(UUID org, UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM requests WHERE organization_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) -> map(rs),
                        org,
                        id);
        if (rows.isEmpty()) throw WorkflowRepository.missing("REQUEST_NOT_FOUND");
        var r = rows.getFirst();
        return new RequestView(
                r.id(),
                r.workflowDefinitionId(),
                r.workflowVersionId(),
                r.requesterMembershipId(),
                r.title(),
                r.description(),
                r.requestType(),
                r.details(),
                r.purchaseAmount(),
                r.currency(),
                r.state(),
                r.version(),
                r.submittedAt(),
                r.completedAt(),
                steps(org, id));
    }

    private RequestView map(ResultSet rs) throws SQLException {
        return new RequestView(
                rs.getObject("id", UUID.class),
                rs.getObject("workflow_definition_id", UUID.class),
                rs.getObject("workflow_version_id", UUID.class),
                rs.getObject("requester_membership_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                RequestType.valueOf(rs.getString("request_type")),
                json.details(rs.getString("request_data")),
                rs.getBigDecimal("purchase_amount"),
                rs.getString("currency"),
                RequestState.valueOf(rs.getString("state")),
                rs.getLong("row_version"),
                instant(rs, "submitted_at"),
                instant(rs, "completed_at"),
                List.of());
    }

    private List<ExecutionStep> steps(UUID org, UUID id) {
        return jdbc.query(
                """
SELECT s.*,w.position,w.name,w.approver_role_id,d.reviewer_membership_id,d.decision,d.comment,d.decided_at
FROM request_steps s JOIN workflow_steps w ON w.organization_id=s.organization_id AND w.workflow_version_id=s.workflow_version_id AND w.id=s.workflow_step_id
LEFT JOIN approval_decisions d ON d.organization_id=s.organization_id AND d.request_step_id=s.id
WHERE s.organization_id=? AND s.request_id=? ORDER BY w.position
""",
                (rs, n) ->
                        new ExecutionStep(
                                rs.getObject("id", UUID.class),
                                rs.getObject("workflow_step_id", UUID.class),
                                rs.getInt("position"),
                                rs.getString("name"),
                                rs.getObject("approver_role_id", UUID.class),
                                rs.getObject("assigned_membership_id", UUID.class),
                                StepState.valueOf(rs.getString("state")),
                                rs.getLong("row_version"),
                                instant(rs, "activated_at"),
                                instant(rs, "completed_at"),
                                rs.getString("decision") == null
                                        ? null
                                        : new DecisionView(
                                                rs.getObject("reviewer_membership_id", UUID.class),
                                                Decision.valueOf(rs.getString("decision")),
                                                rs.getString("comment"),
                                                instant(rs, "decided_at"))),
                org,
                id);
    }

    public UUID create(UUID org, UUID requester, VersionView version, SubmitRequest b) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
INSERT INTO requests(id,organization_id,requester_membership_id,workflow_definition_id,workflow_version_id,title,description,request_type,request_data,purchase_amount,currency,state,submitted_at)
VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,'IN_REVIEW',clock_timestamp())
""",
                id,
                org,
                requester,
                version.workflowDefinitionId(),
                version.id(),
                b.title().trim(),
                b.description(),
                b.requestType().name(),
                json.encode(b.details()),
                b.purchaseAmount(),
                b.currency());
        return id;
    }

    public void createStep(
            UUID org,
            UUID request,
            UUID version,
            WorkflowStep step,
            UUID reviewer,
            StepState state) {
        jdbc.update(
                """
INSERT INTO request_steps(id,organization_id,request_id,workflow_version_id,workflow_step_id,assigned_membership_id,state,activated_at,completed_at)
VALUES(?,?,?,?,?,?,?,CASE WHEN ?='ACTIVE' THEN clock_timestamp() END,CASE WHEN ?='SKIPPED' THEN clock_timestamp() END)
""",
                UUID.randomUUID(),
                org,
                request,
                version,
                step.id(),
                reviewer,
                state.name(),
                state.name(),
                state.name());
    }

    public void decide(UUID org, UUID request, ExecutionStep step, UUID member, DecideRequest b) {
        // Insert while the step is ACTIVE, then finish it in the same aggregate transaction.
        jdbc.update(
                "INSERT INTO"
                    + " approval_decisions(organization_id,request_id,request_step_id,reviewer_membership_id,decision,comment)"
                    + " VALUES(?,?,?,?,?,?)",
                org,
                request,
                step.id(),
                member,
                b.decision().name(),
                b.comment());
        jdbc.update(
                "UPDATE request_steps SET"
                    + " state=?,completed_at=clock_timestamp(),row_version=row_version+1 WHERE"
                    + " organization_id=? AND request_id=? AND id=? AND state='ACTIVE'",
                b.decision() == Decision.APPROVE ? "APPROVED" : "REJECTED",
                org,
                request,
                step.id());
    }

    public void activate(UUID org, UUID request, UUID step) {
        jdbc.update(
                "UPDATE request_steps SET"
                    + " state='ACTIVE',activated_at=clock_timestamp(),row_version=row_version+1"
                    + " WHERE organization_id=? AND request_id=? AND id=? AND state='WAITING'",
                org,
                request,
                step);
    }

    public void cancelPending(UUID org, UUID request) {
        jdbc.update(
                "UPDATE request_steps SET"
                    + " state='CANCELLED',completed_at=clock_timestamp(),row_version=row_version+1"
                    + " WHERE organization_id=? AND request_id=? AND state IN ('WAITING','ACTIVE')",
                org,
                request);
    }

    public void transition(UUID org, UUID request, long expected, RequestState state) {
        int changed =
                jdbc.update(
                        "UPDATE requests SET state=?,row_version=row_version+1,completed_at=CASE"
                            + " WHEN ?='IN_REVIEW' THEN NULL ELSE clock_timestamp() END WHERE"
                            + " organization_id=? AND id=? AND row_version=? AND state='IN_REVIEW'",
                        state.name(),
                        state.name(),
                        org,
                        request,
                        expected);
        if (changed != 1)
            throw WorkflowPolicy.conflict("VERSION_CONFLICT", "Resource changed; reload and retry");
    }

    public void reassign(UUID org, UUID request, UUID step, UUID reviewer) {
        jdbc.update(
                "UPDATE request_steps SET assigned_membership_id=?,row_version=row_version+1 WHERE"
                    + " organization_id=? AND request_id=? AND id=? AND state IN"
                    + " ('ACTIVE','WAITING')",
                reviewer,
                org,
                request,
                step);
    }
}
