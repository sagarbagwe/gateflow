package com.gateflow.workflow;

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
class WorkflowMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("gateflow_upgrade_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void upgradeAddsNewPermissionOnlyToProtectedAdminAndPreservesOldChecksums() {
        var before =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .target("5")
                        .load();
        assertThat(before.migrate().migrationsExecuted).isEqualTo(5);
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword()));
        UUID org = UUID.randomUUID(),
                other = UUID.randomUUID(),
                admin = UUID.randomUUID(),
                custom = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?, 'Old"
                    + " tenant','old-tenant'),(?,'Legacy tenant','legacy-tenant')",
                org,
                other);
        jdbc.update(
                "INSERT INTO roles(id,organization_id,code,name,is_system)"
                    + " VALUES(?,?,'ADMIN','Protected Admin',true),(?,?,'ADMIN','Legacy"
                    + " name',false)",
                admin,
                org,
                custom,
                other);
        jdbc.update(
                "INSERT INTO role_permissions(organization_id,role_id,permission_code) SELECT"
                    + " ?,?,code FROM permissions",
                org,
                admin);
        jdbc.update(
                "INSERT INTO role_permissions(organization_id,role_id,permission_code)"
                    + " VALUES(?,?,'WORKFLOW_VIEW')",
                other,
                custom);
        var after =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .load();
        assertThat(after.migrate().migrationsExecuted).isEqualTo(1);
        after.validate();
        assertThat(after.migrate().migrationsExecuted).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM role_permissions WHERE role_id=?",
                                Integer.class,
                                admin))
                .isEqualTo(13);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM role_permissions WHERE role_id=?",
                                Integer.class,
                                custom))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM role_permissions WHERE role_id=? AND"
                                    + " permission_code='REQUEST_REASSIGN'",
                                Integer.class,
                                custom))
                .isZero();
    }
}
