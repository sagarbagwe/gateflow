package com.gateflow.audit;

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
class AuditMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("gateflow_audit_upgrade_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void versionElevenPreservesLegacyEvidenceButRejectsNewOversizeWrites() {
        var before =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .target("10")
                        .load();
        assertThat(before.migrate().migrationsExecuted).isEqualTo(10);
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        var org = UUID.randomUUID();
        var user = UUID.randomUUID();
        var member = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?,'Existing"
                        + " org','notification-upgrade')",
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
        var audit = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " audit_logs(id,organization_id,actor_kind,actor_membership_id,action,resource_type,resource_id,new_value,correlation_id)"
                    + " VALUES(?,?,'USER',?,'ROLE_CHANGED','MEMBERSHIP',?,jsonb_build_object('name',repeat('x',70000)),'legacy-upgrade')",
                audit,
                org,
                member,
                member);
        var legacy =
                jdbc.queryForObject(
                        "SELECT new_value::text FROM audit_logs WHERE id=?", String.class, audit);
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
                        .target("11")
                        .load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT version,checksum FROM flyway_schema_history WHERE version"
                                        + " IS NOT NULL AND version<>'11' ORDER BY installed_rank"))
                .isEqualTo(checksums);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM memberships WHERE id=?",
                                Integer.class,
                                member))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT new_value::text FROM audit_logs WHERE id=?",
                                String.class,
                                audit))
                .isEqualTo(legacy);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_constraint WHERE"
                                        + " conrelid='audit_logs'::regclass AND conname"
                                        + " IN('ck_audit_old_size','ck_audit_new_size') AND NOT"
                                        + " convalidated",
                                Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO"
                                            + " audit_logs(organization_id,actor_kind,action,resource_type,resource_id,new_value,correlation_id)"
                                            + " VALUES(?,'SYSTEM','TEST','MEMBERSHIP',?,jsonb_build_object('name',repeat('x',70000)),'new-write')",
                                        org,
                                        member))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        for (String table :
                java.util.List.of(
                        "notification_preferences",
                        "notifications",
                        "notification_email_deliveries",
                        "notification_email_attempts"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))
                    .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.tables WHERE"
                                        + " table_schema='public' AND table_type='BASE TABLE' AND"
                                        + " table_name<>'flyway_schema_history'",
                                Integer.class))
                .isEqualTo(24);
    }
}
