package com.gateflow.async;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

@Testcontainers
class AsyncMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("gateflow_async_upgrade_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void versionNineUpgradePreservesLegacyRowsAndOldChecksumsWithoutInventingHistory() {
        var before =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .target("8")
                        .load();
        assertThat(before.migrate().migrationsExecuted).isEqualTo(8);
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        UUID org = UUID.randomUUID(),
                user = UUID.randomUUID(),
                member = UUID.randomUUID(),
                definition = UUID.randomUUID(),
                request = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?,'Legacy org','legacy-upgrade')",
                org);
        jdbc.update(
                "INSERT INTO users(id,email,display_name,password_hash)"
                        + " VALUES(?,'legacy@example.test','Legacy"
                        + " user','test-fixture-not-a-real-password-hash')",
                user);
        jdbc.update(
                "INSERT INTO memberships(id,organization_id,user_id) VALUES(?,?,?)",
                member,
                org,
                user);
        jdbc.update(
                "INSERT INTO workflow_definitions(id,organization_id,name)" + " VALUES(?,?,?)",
                definition,
                org,
                "Legacy workflow");
        jdbc.update(
                "INSERT INTO"
                    + " requests(id,organization_id,requester_membership_id,workflow_definition_id,title,request_type)"
                    + " VALUES(?,?,?,?,'Legacy request','SOFTWARE_ACCESS')",
                request,
                org,
                member,
                definition);
        var checksums =
                jdbc.queryForList(
                        "SELECT version,checksum FROM flyway_schema_history WHERE version IS NOT"
                                + " NULL ORDER BY installed_rank");
        var after =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .target("9")
                        .load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE version"
                                        + " IS NOT NULL AND version<>'9' ORDER BY installed_rank"))
                .isEqualTo(checksums);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM requests WHERE id=?", String.class, request))
                .isEqualTo("Legacy request");
        for (String table :
                java.util.List.of("outbox_events", "processed_events", "request_activity"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.tables WHERE"
                                        + " table_schema='public' AND table_type='BASE TABLE' AND"
                                        + " table_name<>'flyway_schema_history'",
                                Integer.class))
                .isEqualTo(20);
    }
}
