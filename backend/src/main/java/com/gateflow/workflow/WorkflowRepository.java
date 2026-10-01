package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class WorkflowRepository {
    private final JdbcTemplate jdbc;
    private final WorkflowJson json;

    public WorkflowRepository(JdbcTemplate jdbc, WorkflowJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static ApiException missing(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found");
    }

    public DefinitionView create(UUID org, DefinitionInput b) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO workflow_definitions(id,organization_id,name,description)"
                    + " VALUES(?,?,?,?)",
                id,
                org,
                b.name().trim(),
                b.description());
        return definition(org, id, false);
    }

    public DefinitionView definition(UUID org, UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT id,name,description FROM workflow_definitions WHERE"
                            + " organization_id=? AND id=? AND archived_at IS NULL"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new DefinitionView(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3)),
                        org,
                        id);
        if (rows.isEmpty()) throw missing("WORKFLOW_NOT_FOUND");
        return rows.getFirst();
    }

    public List<DefinitionView> page(UUID org, int limit, int offset) {
        return jdbc.query(
                "SELECT id,name,description FROM workflow_definitions WHERE organization_id=? AND"
                    + " archived_at IS NULL ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) ->
                        new DefinitionView(
                                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                org,
                limit,
                offset);
    }

    public UUID createVersion(UUID org, UUID definition, List<StepInput> steps) {
        int next =
                jdbc.queryForObject(
                        "SELECT COALESCE(max(version_number),0)+1 FROM workflow_versions WHERE"
                            + " organization_id=? AND workflow_definition_id=?",
                        Integer.class,
                        org,
                        definition);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " workflow_versions(id,organization_id,workflow_definition_id,version_number)"
                    + " VALUES(?,?,?,?)",
                id,
                org,
                definition,
                next);
        insertSteps(org, id, steps);
        return id;
    }

    public VersionView version(UUID org, UUID definition, UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT"
                            + " id,workflow_definition_id,version_number,status,row_version,published_at"
                            + " FROM workflow_versions WHERE organization_id=? AND"
                            + " workflow_definition_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new VersionView(
                                        rs.getObject(1, UUID.class),
                                        rs.getObject(2, UUID.class),
                                        rs.getInt(3),
                                        VersionStatus.valueOf(rs.getString(4)),
                                        rs.getLong(5),
                                        instant(rs, "published_at"),
                                        List.of()),
                        org,
                        definition,
                        id);
        if (rows.isEmpty()) throw missing("WORKFLOW_VERSION_NOT_FOUND");
        var v = rows.getFirst();
        return new VersionView(
                v.id(),
                v.workflowDefinitionId(),
                v.versionNumber(),
                v.status(),
                v.version(),
                v.publishedAt(),
                steps(org, id));
    }

    public VersionView published(UUID org, UUID id) {
        var defs =
                jdbc.query(
                        "SELECT v.workflow_definition_id FROM workflow_versions v JOIN"
                            + " workflow_definitions d ON d.organization_id=v.organization_id AND"
                            + " d.id=v.workflow_definition_id WHERE v.organization_id=? AND v.id=?"
                            + " AND d.archived_at IS NULL",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        org,
                        id);
        if (defs.isEmpty()) throw missing("WORKFLOW_VERSION_NOT_FOUND");
        var v = version(org, defs.getFirst(), id, false);
        if (v.status() != VersionStatus.PUBLISHED)
            throw WorkflowPolicy.conflict(
                    "WORKFLOW_NOT_PUBLISHED", "Submission requires a published workflow");
        return v;
    }

    private List<WorkflowStep> steps(UUID org, UUID version) {
        return jdbc.query(
                "SELECT id,position,name,approver_role_id,conditions::text FROM workflow_steps"
                    + " WHERE organization_id=? AND workflow_version_id=? ORDER BY position",
                (rs, n) ->
                        new WorkflowStep(
                                rs.getObject(1, UUID.class),
                                rs.getInt(2),
                                rs.getString(3),
                                rs.getObject(4, UUID.class),
                                json.condition(rs.getString(5))),
                org,
                version);
    }

    public void replace(UUID org, UUID id, long expected, List<StepInput> steps) {
        if (jdbc.update(
                        "UPDATE workflow_versions SET row_version=row_version+1 WHERE"
                            + " organization_id=? AND id=? AND row_version=?",
                        org,
                        id,
                        expected)
                != 1)
            throw WorkflowPolicy.conflict("VERSION_CONFLICT", "Resource changed; reload and retry");
        jdbc.update(
                "DELETE FROM workflow_steps WHERE organization_id=? AND workflow_version_id=?",
                org,
                id);
        insertSteps(org, id, steps);
    }

    private void insertSteps(UUID org, UUID version, List<StepInput> steps) {
        List<Object[]> values = new ArrayList<>();
        int position = 0;
        for (var step : steps)
            values.add(
                    new Object[] {
                        UUID.randomUUID(),
                        org,
                        version,
                        ++position,
                        step.name().trim(),
                        step.approverRoleId(),
                        json.encode(step.condition())
                    });
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " workflow_steps(id,organization_id,workflow_version_id,position,name,approver_role_id,conditions)"
                    + " VALUES(?,?,?,?,?,?,?::jsonb)",
                values);
    }

    public void publish(UUID org, UUID version, long expected) {
        if (jdbc.update(
                        "UPDATE workflow_versions SET"
                            + " status='PUBLISHED',published_at=clock_timestamp(),row_version=row_version+1"
                            + " WHERE organization_id=? AND id=? AND row_version=?",
                        org,
                        version,
                        expected)
                != 1)
            throw WorkflowPolicy.conflict("VERSION_CONFLICT", "Resource changed; reload and retry");
    }
}
