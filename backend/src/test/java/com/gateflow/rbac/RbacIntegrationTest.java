package com.gateflow.rbac;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateflow.auth.cookie-secure=false",
            "gateflow.auth.signup-limit=1000",
            "gateflow.auth.login-limit=1000",
            "gateflow.rbac.max-organizations-per-user=2",
            "gateflow.rbac.max-roles-per-organization=8"
        })
class RbacIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withCommand(
                            "postgres",
                            "-c",
                            "log_error_verbosity=terse",
                            "-c",
                            "log_min_error_statement=panic",
                            "-c",
                            "log_parameter_max_length_on_error=0")
                    .withDatabaseName("gateflow_rbac_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    // Tests retain immutable audit fixtures until this entire disposable container is removed.
    final class Actor {
        final HttpClient http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        String user;

        HttpResponse<String> get(String p) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(p)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> write(String method, String p, Object body) throws Exception {
            var csrf = json(get("/api/v1/auth/csrf"));
            return http.send(
                    HttpRequest.newBuilder(uri(p))
                            .header("Content-Type", "application/json")
                            .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                            .method(
                                    method,
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    JsonNode json(HttpResponse<String> r) throws Exception {
        return mapper.readTree(r.body());
    }

    JsonNode ok(HttpResponse<String> r, int expected) throws Exception {
        assertThat(r.statusCode())
                .withFailMessage("HTTP %s: %s", r.statusCode(), r.body())
                .isEqualTo(expected);
        return json(r);
    }

    void problem(HttpResponse<String> r, int status, String code) throws Exception {
        var b = ok(r, status);
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("requestId").asText())
                .isEqualTo(r.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(r.body())
                .doesNotContain("password_hash", "token_hash", "SELECT ", "integration-password");
    }

    Actor actor() throws Exception {
        Actor a = new Actor();
        a.user =
                ok(
                                a.write(
                                        "POST",
                                        "/api/v1/auth/signup",
                                        Map.of(
                                                "email",
                                                UUID.randomUUID() + "@example.test",
                                                "displayName",
                                                "Fixture",
                                                "password",
                                                "integration-password-2026")),
                                201)
                        .get("id")
                        .asText();
        return a;
    }

    String org(Actor a) throws Exception {
        return ok(
                        a.write(
                                "POST",
                                "/api/v1/organizations",
                                Map.of(
                                        "name",
                                        "Fixture Organization",
                                        "slug",
                                        "org-" + UUID.randomUUID())),
                        201)
                .get("id")
                .asText();
    }

    String base(String org) {
        return "/api/v1/organizations/" + org;
    }

    String role(Actor a, String org, String code) throws Exception {
        for (var r : ok(a.get(base(org) + "/roles"), 200).get("items"))
            if (r.get("code").asText().equals(code)) return r.get("id").asText();
        throw new AssertionError("Missing role " + code);
    }

    JsonNode access(Actor a, String org) throws Exception {
        return ok(a.get(base(org) + "/me"), 200);
    }

    JsonNode enroll(Actor owner, String org, Actor member, String role) throws Exception {
        return ok(
                owner.write(
                        "POST",
                        base(org) + "/memberships",
                        Map.of("userId", member.user, "roleIds", List.of(role))),
                201);
    }

    JsonNode custom(Actor a, String org, String code, String... permissions) throws Exception {
        return ok(
                a.write(
                        "POST",
                        base(org) + "/roles",
                        Map.of("code", code, "name", code, "permissions", List.of(permissions))),
                201);
    }

    HttpResponse<String> change(Actor a, String org, String member, long version, String... roles)
            throws Exception {
        return a.write(
                "PUT",
                base(org) + "/memberships/" + member + "/roles",
                Map.of("expectedVersion", version, "roleIds", List.of(roles)));
    }

    HttpResponse<String> status(Actor a, String org, String member, long version, String status)
            throws Exception {
        return a.write(
                "PATCH",
                base(org) + "/memberships/" + member + "/status",
                Map.of("expectedVersion", version, "status", status));
    }

    int audits(String org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE organization_id=?",
                Integer.class,
                UUID.fromString(org));
    }

    @Test
    void bootstrapIsAtomicAndDefaultMatrixMatchesDatabaseCatalog() throws Exception {
        var a = actor();
        var o = org(a);
        var me = access(a, o);
        assertThat(me.get("userId").asText()).isEqualTo(a.user);
        assertThat(me.get("permissions").size()).isEqualTo(12);
        var list = ok(a.get(base(o) + "/roles"), 200).get("items");
        assertThat(list.size()).isEqualTo(4);
        for (var r : list) {
            assertThat(r.get("system").asBoolean()).isTrue();
            assertThat(r.get("permissions").size())
                    .isEqualTo(RolePolicy.DEFAULTS.get(r.get("code").asText()).size());
        }
        assertThat(ok(a.get(base(o) + "/permissions"), 200).size())
                .isEqualTo(Permission.values().length);
        assertThat(audits(o)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT created_by_user_id FROM organizations WHERE id=?",
                                UUID.class,
                                UUID.fromString(o)))
                .isEqualTo(UUID.fromString(a.user));
    }

    @Test
    void tenantIsolationIgnoresSpoofedHeadersAndForeignResourceIds() throws Exception {
        var a = actor();
        var b = actor();
        var oa = org(a);
        var ob = org(b);
        var rb = role(b, ob, "MEMBER");
        problem(b.get(base(oa)), 404, "ORGANIZATION_NOT_FOUND");
        var spoof =
                b.http.send(
                        HttpRequest.newBuilder(uri(base(oa) + "/roles"))
                                .header("X-Organization-ID", oa)
                                .header("X-Role", "ADMIN")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        problem(spoof, 404, "ORGANIZATION_NOT_FOUND");
        problem(a.get(base(oa) + "/roles/" + rb), 404, "ROLE_NOT_FOUND");
        problem(
                a.write(
                        "POST",
                        base(oa) + "/memberships",
                        Map.of("userId", b.user, "roleIds", List.of(rb))),
                404,
                "ROLE_NOT_FOUND");
        problem(
                change(a, oa, access(b, ob).get("membershipId").asText(), 0),
                404,
                "MEMBERSHIP_NOT_FOUND");
        assertThat(audits(oa)).isEqualTo(1);
        assertThat(ok(b.get("/api/v1/organizations"), 200).get("items").size()).isEqualTo(1);
    }

    @Test
    void sameUserHasDifferentTenantPrivilegesAndMemberCannotManageRoles() throws Exception {
        var owner = actor();
        var b = actor();
        var oa = org(owner);
        var ob = org(b);
        enroll(owner, oa, b, role(owner, oa, "MEMBER"));
        assertThat(access(b, oa).get("permissions").size()).isEqualTo(4);
        assertThat(access(b, ob).get("permissions").size()).isEqualTo(12);
        problem(b.get(base(oa) + "/roles"), 403, "PERMISSION_DENIED");
        problem(b.get(base(oa) + "/memberships"), 403, "PERMISSION_DENIED");
        assertThat(ok(b.get("/api/v1/organizations"), 200).get("items").size()).isEqualTo(2);
    }

    @Test
    void customRolesCanBeCreatedAndVersionedButDefaultsAndReservedCodesAreProtected()
            throws Exception {
        var a = actor();
        var o = org(a);
        var r = custom(a, o, "APPROVER", "REQUEST_APPROVE");
        var id = r.get("id").asText();
        var updated =
                ok(
                        a.write(
                                "PUT",
                                base(o) + "/roles/" + id + "/permissions",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "permissions",
                                        List.of("WORKFLOW_VIEW", "REQUEST_APPROVE"))),
                        200);
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        assertThat(updated.get("permissions").size()).isEqualTo(2);
        problem(
                a.write(
                        "PUT",
                        base(o) + "/roles/" + id + "/permissions",
                        Map.of("expectedVersion", 0, "permissions", List.of())),
                409,
                "VERSION_CONFLICT");
        problem(
                a.write(
                        "PUT",
                        base(o) + "/roles/" + role(a, o, "ADMIN") + "/permissions",
                        Map.of("expectedVersion", 0, "permissions", List.of())),
                409,
                "SYSTEM_ROLE_PROTECTED");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of("code", "ADMIN", "name", "Imposter", "permissions", List.of())),
                409,
                "RESERVED_ROLE_CODE");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of("code", "APPROVER", "name", "Duplicate", "permissions", List.of())),
                409,
                "ROLE_CONFLICT");
    }

    @Test
    void delegatedRoleManagerCannotGrantOrEditPrivilegesBeyondCeiling() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var limited = custom(a, o, "DELEGATE", "ROLE_MANAGE", "MEMBERSHIP_MANAGE", "WORKFLOW_VIEW");
        enroll(a, o, b, limited.get("id").asText());
        var powerful = custom(a, o, "POWERFUL", "REQUEST_APPROVE");
        problem(
                b.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of(
                                "code",
                                "ESCALATE",
                                "name",
                                "Escalate",
                                "permissions",
                                List.of("REQUEST_APPROVE"))),
                403,
                "DELEGATION_DENIED");
        problem(
                b.write(
                        "PUT",
                        base(o) + "/roles/" + powerful.get("id").asText() + "/permissions",
                        Map.of("expectedVersion", 0, "permissions", List.of())),
                403,
                "DELEGATION_DENIED");
        var safe = custom(b, o, "SAFE", "WORKFLOW_VIEW");
        assertThat(safe.get("permissions").size()).isEqualTo(1);
    }

    @Test
    void customAllPermissionsStillDoesNotBecomeProtectedAdministrator() throws Exception {
        var a = actor();
        var b = actor();
        var c = actor();
        var o = org(a);
        var all = Arrays.stream(Permission.values()).map(Enum::name).toArray(String[]::new);
        var r = custom(a, o, "CUSTOM_ALL", all);
        enroll(a, o, b, r.get("id").asText());
        problem(
                b.write(
                        "POST",
                        base(o) + "/memberships",
                        Map.of("userId", c.user, "roleIds", List.of(role(a, o, "ADMIN")))),
                403,
                "ADMIN_REQUIRED");
        problem(
                change(b, o, access(a, o).get("membershipId").asText(), 0, role(a, o, "MEMBER")),
                403,
                "ADMIN_REQUIRED");
    }

    @Test
    void revocationTakesEffectOnExistingSessionAndUnionDoesNotDuplicatePermissions()
            throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var r = custom(a, o, "DELEGATE", "ROLE_MANAGE", "WORKFLOW_VIEW");
        var m = enroll(a, o, b, r.get("id").asText());
        ok(change(a, o, m.get("id").asText(), 0, r.get("id").asText(), role(a, o, "VIEWER")), 200);
        assertThat(access(b, o).get("permissions").size()).isEqualTo(3);
        ok(b.get(base(o) + "/roles"), 200);
        ok(
                a.write(
                        "PUT",
                        base(o) + "/roles/" + r.get("id").asText() + "/permissions",
                        Map.of("expectedVersion", 0, "permissions", List.of("WORKFLOW_VIEW"))),
                200);
        problem(b.get(base(o) + "/roles"), 403, "PERMISSION_DENIED");
        ok(b.get("/api/v1/auth/me"), 200);
    }

    @Test
    void suspensionIsTenantLocalAndStaleMembershipChangesConflict() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var ob = org(b);
        var m = enroll(a, o, b, role(a, o, "MEMBER"));
        var mid = m.get("id").asText();
        ok(status(a, o, mid, 0, "SUSPENDED"), 200);
        problem(b.get(base(o) + "/me"), 404, "ORGANIZATION_NOT_FOUND");
        ok(b.get(base(ob) + "/me"), 200);
        ok(b.get("/api/v1/auth/me"), 200);
        problem(status(a, o, mid, 0, "ACTIVE"), 409, "VERSION_CONFLICT");
        ok(status(a, o, mid, 1, "ACTIVE"), 200);
        ok(b.get(base(o) + "/me"), 200);
        problem(change(a, o, mid, 1, role(a, o, "VIEWER")), 409, "VERSION_CONFLICT");
    }

    @Test
    void delegatedMemberManagementCannotSuspendOrRestoreHigherPrivileges() throws Exception {
        var a = actor();
        var b = actor();
        var c = actor();
        var o = org(a);
        var r = custom(a, o, "DELEGATE", "MEMBERSHIP_MANAGE");
        enroll(a, o, b, r.get("id").asText());
        var m = enroll(a, o, c, role(a, o, "MANAGER"));
        problem(status(b, o, m.get("id").asText(), 0, "SUSPENDED"), 403, "DELEGATION_DENIED");
        ok(status(a, o, m.get("id").asText(), 0, "SUSPENDED"), 200);
        problem(status(b, o, m.get("id").asText(), 1, "ACTIVE"), 403, "DELEGATION_DENIED");
    }

    @Test
    void lastAdministratorCannotDemoteOrSuspendItself() throws Exception {
        var a = actor();
        var o = org(a);
        var id = access(a, o).get("membershipId").asText();
        problem(change(a, o, id, 0, role(a, o, "MEMBER")), 409, "LAST_ADMIN");
        problem(status(a, o, id, 0, "SUSPENDED"), 409, "LAST_ADMIN");
        assertThat(audits(o)).isEqualTo(1);
        assertThat(access(a, o).get("permissions").size()).isEqualTo(12);
    }

    @Test
    void simultaneousAdministratorDemotionsLeaveExactlyOneAdministrator() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var admin = role(a, o, "ADMIN");
        var member = role(a, o, "MEMBER");
        var mb = enroll(a, o, b, admin).get("id").asText();
        var ma = access(a, o).get("membershipId").asText();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var fa =
                    pool.submit(
                            () -> {
                                gate.await();
                                return change(a, o, ma, 0, member);
                            });
            var fb =
                    pool.submit(
                            () -> {
                                gate.await();
                                return change(b, o, mb, 0, member);
                            });
            gate.countDown();
            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertThat(List.of(ra.statusCode(), rb.statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
            problem(ra.statusCode() == 409 ? ra : rb, 409, "LAST_ADMIN");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM membership_roles mr JOIN roles r ON"
                                    + " r.id=mr.role_id WHERE mr.organization_id=? AND r.is_system"
                                    + " AND r.code='ADMIN'",
                                Integer.class,
                                UUID.fromString(o)))
                .isEqualTo(1);
    }

    @Test
    void atomicAuditRecordsActorAllowedSnapshotsAndRequestId() throws Exception {
        var a = actor();
        var o = org(a);
        var response =
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of(
                                "code",
                                "AUDITOR",
                                "name",
                                "Auditor",
                                "permissions",
                                List.of("AUDIT_VIEW")));
        var r = ok(response, 201);
        var row =
                jdbc.queryForMap(
                        "SELECT actor_membership_id,action,resource_id,new_value::text AS"
                            + " snapshot,correlation_id FROM audit_logs WHERE organization_id=? AND"
                            + " action='ROLE_CREATED'",
                        UUID.fromString(o));
        assertThat(row.get("actor_membership_id").toString())
                .isEqualTo(access(a, o).get("membershipId").asText());
        assertThat(row.get("resource_id").toString()).isEqualTo(r.get("id").asText());
        assertThat(row.get("correlation_id"))
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(row.get("snapshot").toString())
                .contains("AUDIT_VIEW")
                .doesNotContain("password", "email");
    }

    @Test
    void failedEnrollmentAndDuplicateSlugHaveNoPartialChangesOrAudits() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var member = role(a, o, "MEMBER");
        problem(
                a.write(
                        "POST",
                        base(o) + "/memberships",
                        Map.of("userId", UUID.randomUUID(), "roleIds", List.of(member))),
                404,
                "USER_NOT_FOUND");
        assertThat(audits(o)).isEqualTo(1);
        enroll(a, o, b, member);
        problem(
                a.write(
                        "POST",
                        base(o) + "/memberships",
                        Map.of("userId", b.user, "roleIds", List.of(member))),
                409,
                "MEMBERSHIP_CONFLICT");
        assertThat(audits(o)).isEqualTo(2);
        var slug = ok(a.get(base(o)), 200).get("slug").asText();
        problem(
                b.write("POST", "/api/v1/organizations", Map.of("name", "Duplicate", "slug", slug)),
                409,
                "SLUG_CONFLICT");
        assertThat(ok(b.get("/api/v1/organizations"), 200).get("items").size()).isEqualTo(1);
    }

    @Test
    void boundedPaginationAndTypedInputsFailSafely() throws Exception {
        var a = actor();
        var o = org(a);
        var p = ok(a.get(base(o) + "/roles?limit=1"), 200);
        assertThat(p.get("items").size()).isEqualTo(1);
        assertThat(p.get("hasMore").asBoolean()).isTrue();
        for (String q : List.of("limit=0", "limit=101", "offset=-1", "offset=10001", "limit=oops"))
            problem(a.get(base(o) + "/roles?" + q), 400, "INVALID_PARAMETER");
        problem(a.get("/api/v1/organizations/not-a-uuid"), 400, "INVALID_PARAMETER");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of(
                                "code",
                                "UNKNOWN",
                                "name",
                                "Unknown",
                                "permissions",
                                List.of("ROOT_ACCESS"))),
                400,
                "INVALID_JSON");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of("code", "NUMERIC", "name", "Numeric", "permissions", List.of(0))),
                400,
                "INVALID_JSON");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of(
                                "code",
                                "NULL_GRANT",
                                "name",
                                "Null",
                                "permissions",
                                Arrays.asList((String) null))),
                400,
                "VALIDATION_FAILED");
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of(
                                "code",
                                "EXTRA",
                                "name",
                                "Extra",
                                "permissions",
                                List.of(),
                                "superAdmin",
                                true)),
                400,
                "INVALID_JSON");
        problem(
                a.write(
                        "PATCH",
                        base(o)
                                + "/memberships/"
                                + access(a, o).get("membershipId").asText()
                                + "/status",
                        Map.of("status", "SUSPENDED")),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void csrfIsRequiredForRbacMutations() throws Exception {
        var a = actor();
        var o = org(a);
        var r =
                a.http.send(
                        HttpRequest.newBuilder(uri(base(o) + "/roles"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        problem(r, 403, "ACCESS_DENIED");
        assertThat(audits(o)).isEqualTo(1);
    }

    @Test
    void creatorAndRoleQuotasAreEnforced() throws Exception {
        var a = actor();
        var o = org(a);
        org(a);
        problem(
                a.write(
                        "POST",
                        "/api/v1/organizations",
                        Map.of("name", "Third", "slug", "third-" + UUID.randomUUID())),
                409,
                "ORGANIZATION_LIMIT");
        for (int i = 0; i < 4; i++) custom(a, o, "EXTRA_" + i);
        problem(
                a.write(
                        "POST",
                        base(o) + "/roles",
                        Map.of("code", "OVER_LIMIT", "name", "Over", "permissions", List.of())),
                409,
                "ROLE_LIMIT");
        assertThat(audits(o)).isEqualTo(5);
    }

    @Test
    void disabledAccountsAndOrganizationsCannotUseExistingSessions() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        enroll(a, o, b, role(a, o, "MEMBER"));
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id=?", UUID.fromString(b.user));
        problem(b.get(base(o) + "/me"), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("UPDATE organizations SET status='DISABLED' WHERE id=?", UUID.fromString(o));
        problem(a.get(base(o) + "/me"), 404, "ORGANIZATION_NOT_FOUND");
        ok(a.get("/api/v1/auth/me"), 200);
    }

    @Test
    void legacyAdminNameDoesNotAcquireProtectedRoleAutomatically() throws Exception {
        var a = actor();
        var o = UUID.randomUUID();
        var rid = UUID.randomUUID();
        var mid = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations(id,name,slug) VALUES(?,?,?)",
                o,
                "Legacy",
                "legacy-" + o);
        jdbc.update(
                "INSERT INTO roles(id,organization_id,code,name) VALUES(?,?,'ADMIN','Legacy"
                    + " Admin')",
                rid,
                o);
        jdbc.update(
                "INSERT INTO memberships(id,organization_id,user_id) VALUES(?,?,?)",
                mid,
                o,
                UUID.fromString(a.user));
        jdbc.update(
                "INSERT INTO membership_roles(organization_id,membership_id,role_id) VALUES(?,?,?)",
                o,
                mid,
                rid);
        var me = access(a, o.toString());
        assertThat(me.get("permissions").size()).isZero();
        assertThat(me.get("roles").get(0).get("system").asBoolean()).isFalse();
        problem(a.get(base(o.toString()) + "/roles"), 403, "PERMISSION_DENIED");
    }

    @Test
    void auditStorageFailureRollsBackRoleAndPermissionWrites() throws Exception {
        var a = actor();
        var o = org(a);
        jdbc.execute(
                "CREATE FUNCTION fail_fixture_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.action='ROLE_CREATED' AND NEW.new_value->>'code'='FAIL_AUDIT' THEN"
                    + " RAISE EXCEPTION 'fixture failure' USING ERRCODE='23514'; END IF; RETURN"
                    + " NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_fixture_audit BEFORE INSERT ON audit_logs FOR EACH ROW EXECUTE"
                    + " FUNCTION fail_fixture_audit()");
        try {
            problem(
                    a.write(
                            "POST",
                            base(o) + "/roles",
                            Map.of(
                                    "code",
                                    "FAIL_AUDIT",
                                    "name",
                                    "Failure fixture",
                                    "permissions",
                                    List.of("WORKFLOW_VIEW"))),
                    503,
                    "SERVICE_UNAVAILABLE");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM roles WHERE organization_id=? AND"
                                        + " code='FAIL_AUDIT'",
                                    Integer.class,
                                    UUID.fromString(o)))
                    .isZero();
            assertThat(audits(o)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER fail_fixture_audit ON audit_logs");
            jdbc.execute("DROP FUNCTION fail_fixture_audit()");
        }
    }

    @Test
    void concurrentUpdatesWithSameMembershipVersionHaveOneWinner() throws Exception {
        var a = actor();
        var b = actor();
        var o = org(a);
        var mid = enroll(a, o, b, role(a, o, "MEMBER")).get("id").asText();
        var viewer = role(a, o, "VIEWER");
        // Distinct owner clients avoid concurrent CSRF bootstrap changing a shared cookie jar.
        var second = new Actor();
        ok(
                second.write(
                        "POST",
                        "/api/v1/auth/login",
                        Map.of(
                                "email",
                                ok(a.get("/api/v1/auth/me"), 200).get("email").asText(),
                                "password",
                                "integration-password-2026")),
                200);
        // Login rotates only the supplied previous cookie; the original independent session remains
        // valid.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var fa =
                    pool.submit(
                            () -> {
                                gate.await();
                                return change(a, o, mid, 0, viewer);
                            });
            var fb =
                    pool.submit(
                            () -> {
                                gate.await();
                                return change(second, o, mid, 0, viewer);
                            });
            gate.countDown();
            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertThat(List.of(ra.statusCode(), rb.statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
            problem(ra.statusCode() == 409 ? ra : rb, 409, "VERSION_CONFLICT");
        }
    }
}
