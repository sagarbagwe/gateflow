package com.gateflow.workflow;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gateflow.rbac.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequestSearchPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("search_upgrade")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;
    static UUID org = UUID.randomUUID(),
            member = UUID.randomUUID(),
            user = UUID.randomUUID(),
            role = UUID.randomUUID(),
            definition = UUID.randomUUID(),
            version = UUID.randomUUID(),
            step = UUID.randomUUID(),
            terminal = UUID.randomUUID(),
            draft = UUID.randomUUID(),
            reviewer = UUID.randomUUID(),
            reviewerUser = UUID.randomUUID();

    static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load();
    }

    @BeforeAll
    static void legacyData() {
        assertThat(flyway("6").migrate().migrationsExecuted).isEqualTo(6);
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        named = new NamedParameterJdbcTemplate(jdbc);
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?,'Upgrade"
                        + " tenant','search-upgrade')",
                org);
        jdbc.update(
                "INSERT INTO users(id,email,display_name,password_hash)"
                    + " VALUES(?,'upgrade@example.test','Fixture','fixture-not-a-real-password-hash'),(?,'reviewer@example.test','Fixture','fixture-not-a-real-password-hash')",
                user,
                reviewerUser);
        jdbc.update(
                "INSERT INTO memberships(id,organization_id,user_id) VALUES(?,?,?),(?,?,?)",
                member,
                org,
                user,
                reviewer,
                org,
                reviewerUser);
        jdbc.update(
                "INSERT INTO roles(id,organization_id,code,name) VALUES(?,?,'REVIEWER','Reviewer')",
                role,
                org);
        jdbc.update(
                "INSERT INTO workflow_definitions(id,organization_id,name) VALUES(?,?,'Upgrade"
                        + " workflow')",
                definition,
                org);
        jdbc.update(
                "INSERT INTO"
                    + " workflow_versions(id,organization_id,workflow_definition_id,version_number)"
                    + " VALUES(?,?,?,1)",
                version,
                org,
                definition);
        jdbc.update(
                "INSERT INTO"
                    + " workflow_steps(id,organization_id,workflow_version_id,position,name,approver_role_id)"
                    + " VALUES(?,?,?,1,'Review',?)",
                step,
                org,
                version,
                role);
        jdbc.update(
                "UPDATE workflow_versions SET status='PUBLISHED',published_at=now() WHERE id=?",
                version);
        jdbc.update(
                "INSERT INTO"
                    + " requests(id,organization_id,requester_membership_id,workflow_definition_id,workflow_version_id,title,description,request_type,state,submitted_at,completed_at,created_at)"
                    + " VALUES(?,?,?,?,?,'Existing Helios','Approved"
                    + " equipment','SOFTWARE_ACCESS','APPROVED','2020-01-01T00:00:00Z','2020-01-02T00:00:00Z','2020-01-01T00:00:00Z')",
                terminal,
                org,
                member,
                definition,
                version);
        UUID instance = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " request_steps(id,organization_id,request_id,workflow_version_id,workflow_step_id,assigned_membership_id,state,activated_at,completed_at)"
                    + " VALUES(?,?,?,?,?,?,'APPROVED','2020-01-01T00:00:00Z','2020-01-02T00:00:00Z')",
                instance,
                org,
                terminal,
                version,
                step,
                reviewer);
        jdbc.update(
                "INSERT INTO"
                    + " approval_decisions(organization_id,request_id,request_step_id,reviewer_membership_id,decision)"
                    + " VALUES(?,?,?,?,'APPROVE')",
                org,
                terminal,
                instance,
                reviewer);
        jdbc.update(
                "INSERT INTO"
                    + " requests(id,organization_id,requester_membership_id,workflow_definition_id,title,description,request_type)"
                    + " VALUES(?,?,?,?,'Mutable draft','Old description','SOFTWARE_ACCESS')",
                draft,
                org,
                member,
                definition);
    }

    @Test
    @Order(1)
    void additiveUpgradeBackfillsImmutableTerminalAndDraftRows() {
        var after = flyway("8");
        assertThat(after.migrate().migrationsExecuted).isEqualTo(2);
        after.validate();
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_document @@ plainto_tsquery('simple','helios"
                                        + " equipment') FROM requests WHERE id=?",
                                Boolean.class,
                                terminal))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM requests WHERE id=?", String.class, terminal))
                .isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM approval_decisions WHERE request_id=?",
                                Integer.class,
                                terminal))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_document @@ plainto_tsquery('simple','mutable"
                                        + " description') FROM requests WHERE id=?",
                                Boolean.class,
                                draft))
                .isTrue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT indexname FROM pg_indexes WHERE schemaname='public'",
                                String.class))
                .contains(
                        "ix_requests_search_document",
                        "ix_requests_org_created",
                        "ix_decisions_org_reviewer_request");
    }

    String sqlstate(Runnable action) {
        try {
            action.run();
            return "SUCCESS";
        } catch (org.springframework.dao.DataAccessException error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            return ((java.sql.SQLException) cause).getSQLState();
        }
    }

    @Test
    @Order(2)
    void generatedDocumentUpdatesButCoordinatesAndTerminalPayloadStayImmutable() {
        assertThat(
                        sqlstate(
                                () ->
                                        jdbc.update(
                                                "UPDATE requests SET id=? WHERE id=?",
                                                UUID.randomUUID(),
                                                draft)))
                .isEqualTo("55000");
        jdbc.update(
                "UPDATE requests SET title='New Neptune',description='Replacement' WHERE id=?",
                draft);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_document @@ plainto_tsquery('simple','neptune"
                                        + " replacement') FROM requests WHERE id=?",
                                Boolean.class,
                                draft))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT search_document @@ plainto_tsquery('simple','mutable') FROM"
                                        + " requests WHERE id=?",
                                Boolean.class,
                                draft))
                .isFalse();
        assertThat(
                        sqlstate(
                                () ->
                                        jdbc.update(
                                                "UPDATE requests SET created_at=created_at+interval"
                                                        + " '1 second' WHERE id=?",
                                                draft)))
                .isEqualTo("55000");
        assertThat(
                        sqlstate(
                                () ->
                                        jdbc.update(
                                                "UPDATE requests SET"
                                                    + " search_document=to_tsvector('simple','forged')"
                                                    + " WHERE id=?",
                                                draft)))
                .isEqualTo("428C9");
        assertThat(
                        sqlstate(
                                () ->
                                        jdbc.update(
                                                "UPDATE requests SET title='Tampered' WHERE id=?",
                                                terminal)))
                .isEqualTo("55000");
    }

    JsonNode plan(RequestSearchRepository.Statement statement) throws Exception {
        String raw =
                named.queryForObject(
                        "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + statement.sql(),
                        statement.params(),
                        String.class);
        return new ObjectMapper().readTree(raw);
    }

    void indexes(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            if (node.has("Index Name")) names.add(node.get("Index Name").asText());
            node.elements().forEachRemaining(n -> indexes(n, names));
        } else if (node.isArray()) node.forEach(n -> indexes(n, names));
    }

    RequestSearchQuery query(String... args) {
        var p = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < args.length; i += 2) p.add(args[i], args[i + 1]);
        return RequestSearchQuery.parse(p, null);
    }

    @Test
    @Order(3)
    void actualSearchFeedAndDeepCursorQueriesUseIndexesOnSeededData() throws Exception {
        UUID other = UUID.randomUUID(),
                otherMember = UUID.randomUUID(),
                otherDef = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?,'Other tenant','plan-other')",
                other);
        jdbc.update(
                "INSERT INTO memberships(id,organization_id,user_id) VALUES(?,?,?)",
                otherMember,
                other,
                user);
        jdbc.update(
                "INSERT INTO workflow_definitions(id,organization_id,name) VALUES(?,?,'Plan"
                        + " workflow')",
                otherDef,
                other);
        for (var row :
                List.of(List.of(org, member, definition), List.of(other, otherMember, otherDef)))
            jdbc.update(
                    "INSERT INTO"
                        + " requests(organization_id,requester_membership_id,workflow_definition_id,title,description,request_type,created_at)"
                        + " SELECT ?,?,?,CASE WHEN n%500=0 THEN 'Helios equipment' ELSE 'General"
                        + " equipment' END,'Synthetic plan"
                        + " fixture','SOFTWARE_ACCESS','2020-01-01T00:00:00Z'::timestamptz+n*interval"
                        + " '1 second' FROM generate_series(1,15000) AS n",
                    row.get(0), row.get(1), row.get(2));
        // Bulk fixture inserts fill the GIN pending list. Normal vacuum maintenance flushes it.
        jdbc.execute("VACUUM (ANALYZE) requests");
        jdbc.execute("ANALYZE request_steps");
        jdbc.execute("ANALYZE workflow_steps");
        var access = new RequestAccessPolicy(named);
        var repository = new RequestSearchRepository(named, access);
        var actor =
                new RbacDtos.AccessView(
                        org, member, user, List.of(), Set.of(Permission.REQUEST_VIEW_ALL));
        var anchor =
                jdbc.queryForMap(
                        "SELECT id,created_at FROM requests WHERE organization_id=? ORDER BY"
                                + " created_at DESC,id DESC LIMIT 1 OFFSET 10000",
                        org);
        var cursor =
                new SearchCursor.Position(
                        1,
                        Instant.parse("2021-01-01T00:00:00Z"),
                        ((java.sql.Timestamp) anchor.get("created_at")).toInstant(),
                        (UUID) anchor.get("id"),
                        "plan-fixture");
        Map<String, JsonNode> evidence = new LinkedHashMap<>();
        evidence.put(
                "rare_full_text", plan(repository.statement(actor, query("q", "helios"), null)));
        evidence.put("latest_feed", plan(repository.statement(actor, query(), null)));
        evidence.put("deep_keyset", plan(repository.statement(actor, query(), cursor)));
        for (String key : evidence.keySet()) {
            var names = new TreeSet<String>();
            indexes(evidence.get(key), names);
            assertThat(names)
                    .as(key)
                    .contains(
                            key.equals("rare_full_text")
                                    ? "ix_requests_search_document"
                                    : "ix_requests_org_created");
            assertThat(evidence.get(key).get(0).get("Plan").get("Actual Rows").asInt())
                    .isLessThanOrEqualTo(21);
        }
        assertThat(repository.search(actor, query("q", "helios", "limit", "100"), null))
                .hasSize(31); // 30 synthetic + preserved terminal
        for (var row : repository.search(actor, query(), cursor))
            assertThat(row.item().createdAt()).isBeforeOrEqualTo(cursor.afterCreatedAt());
        Path path = Path.of("target/query-plans/search-plans.json");
        Files.createDirectories(path.getParent());
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        path.toFile(),
                        Map.of(
                                "provenance",
                                Map.of(
                                        "postgres",
                                        POSTGRES.getDockerImageName(),
                                        "fixtureRows",
                                        30002,
                                        "scope",
                                        "two tenants; synthetic drafts plus upgrade records",
                                        "sql",
                                        "RequestSearchRepository.statement; no forced planner"
                                                + " settings",
                                        "productionBenchmark",
                                        false),
                                "plans",
                                evidence));
    }
}
