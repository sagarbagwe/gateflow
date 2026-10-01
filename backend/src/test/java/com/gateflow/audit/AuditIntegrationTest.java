package com.gateflow.audit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gateflow.workflow.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateflow.auth.cookie-secure=false",
            "gateflow.auth.signup-limit=1000",
            "gateflow.auth.login-limit=1000"
        })
class AuditIntegrationTest {
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
                    .withDatabaseName("gateflow_audit_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource datasource;
    static final String PASSWORD = "workflow-fixture-password-2026";

    final class Actor {
        final HttpClient http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        String user, email;

        HttpResponse<String> get(String p) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(p)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> write(String method, String p, Object body, String key)
                throws Exception {
            var csrf = json(get("/api/v1/auth/csrf"));
            var request =
                    HttpRequest.newBuilder(uri(p))
                            .header("Content-Type", "application/json")
                            .header(csrf.get("headerName").asText(), csrf.get("token").asText());
            if (key != null) request.header("Idempotency-Key", key);
            return http.send(
                    request.method(
                                    method,
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    record Fixture(
            Actor admin,
            Actor requester,
            Actor first,
            Actor second,
            String org,
            String definition,
            JsonNode version,
            String roleA,
            String roleB,
            String requesterMember,
            String firstMember,
            String secondMember) {}

    URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    JsonNode json(HttpResponse<String> r) throws Exception {
        return mapper.readTree(r.body());
    }

    JsonNode ok(HttpResponse<String> r, int status) throws Exception {
        assertThat(r.statusCode())
                .withFailMessage("HTTP %s: %s", r.statusCode(), r.body())
                .isEqualTo(status);
        return json(r);
    }

    void problem(HttpResponse<String> r, int status, String code) throws Exception {
        var b = ok(r, status);
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("requestId").asText())
                .isEqualTo(r.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(r.body()).doesNotContain(PASSWORD, "password_hash", "token_hash", "SELECT ");
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    String base(Fixture f) {
        return "/api/v1/organizations/" + f.org();
    }

    Actor actor() throws Exception {
        var a = new Actor();
        a.email = UUID.randomUUID() + "@example.test";
        a.user =
                ok(
                                a.write(
                                        "POST",
                                        "/api/v1/auth/signup",
                                        Map.of(
                                                "email",
                                                a.email,
                                                "displayName",
                                                "Workflow fixture",
                                                "password",
                                                PASSWORD),
                                        null),
                                201)
                        .get("id")
                        .asText();
        return a;
    }

    Actor fork(Actor a) throws Exception {
        var b = new Actor();
        b.user = a.user;
        b.email = a.email;
        ok(
                b.write(
                        "POST",
                        "/api/v1/auth/login",
                        Map.of("email", a.email, "password", PASSWORD),
                        null),
                200);
        return b;
    }

    String builtin(Actor a, String org, String code) throws Exception {
        for (var r : ok(a.get("/api/v1/organizations/" + org + "/roles"), 200).get("items"))
            if (r.get("code").asText().equals(code)) return r.get("id").asText();
        throw new AssertionError("Role missing");
    }

    String custom(Actor a, String org, String code, List<String> perms) throws Exception {
        return ok(
                        a.write(
                                "POST",
                                "/api/v1/organizations/" + org + "/roles",
                                Map.of("code", code, "name", code, "permissions", perms),
                                null),
                        201)
                .get("id")
                .asText();
    }

    String enroll(Actor a, String org, Actor member, String role) throws Exception {
        return ok(
                        a.write(
                                "POST",
                                "/api/v1/organizations/" + org + "/memberships",
                                Map.of("userId", member.user, "roleIds", List.of(role)),
                                null),
                        201)
                .get("id")
                .asText();
    }

    Map<String, Object> step(String name, String role, Map<String, Object> condition) {
        return Map.of("name", name, "approverRoleId", role, "condition", condition);
    }

    Map<String, Object> always() {
        return Map.of("type", "ALWAYS");
    }

    Fixture fixture(boolean two) throws Exception {
        var admin = actor();
        var requester = actor();
        var first = actor();
        var second = actor();
        var org =
                ok(
                                admin.write(
                                        "POST",
                                        "/api/v1/organizations",
                                        Map.of("name", "Workflow tenant", "slug", "wf-" + key()),
                                        null),
                                201)
                        .get("id")
                        .asText();
        var grants = List.of("REQUEST_APPROVE", "REQUEST_VIEW_ALL", "WORKFLOW_VIEW");
        var ra = custom(admin, org, "REVIEW_A", grants);
        var rb = custom(admin, org, "REVIEW_B", grants);
        var rm = enroll(admin, org, requester, builtin(admin, org, "MEMBER"));
        var fm = enroll(admin, org, first, ra);
        var sm = enroll(admin, org, second, rb);
        var p = "/api/v1/organizations/" + org + "/workflows";
        var def =
                ok(
                                admin.write(
                                        "POST",
                                        p,
                                        Map.of(
                                                "name",
                                                "Procurement",
                                                "description",
                                                "Business approval policy"),
                                        null),
                                201)
                        .get("id")
                        .asText();
        var steps = new ArrayList<Map<String, Object>>();
        steps.add(step("Manager review", ra, always()));
        if (two) steps.add(step("Finance review", rb, always()));
        var v =
                ok(
                        admin.write(
                                "POST", p + "/" + def + "/versions", Map.of("steps", steps), null),
                        201);
        v =
                ok(
                        admin.write(
                                "POST",
                                p + "/" + def + "/versions/" + v.get("id").asText() + "/publish",
                                Map.of("expectedVersion", 0),
                                null),
                        200);
        return new Fixture(admin, requester, first, second, org, def, v, ra, rb, rm, fm, sm);
    }

    Map<String, Object> payload(Fixture f) {
        var p = new LinkedHashMap<String, Object>();
        p.put("workflowVersionId", f.version().get("id").asText());
        p.put("title", "Engineering laptop");
        p.put("description", "Replace failing development equipment");
        p.put("requestType", "PURCHASE");
        p.put("purchaseAmount", new BigDecimal("1200.00"));
        p.put("currency", "USD");
        p.put("details", Map.of("vendor", "Fixture supplier"));
        return p;
    }

    JsonNode submit(Fixture f) throws Exception {
        return ok(f.requester().write("POST", base(f) + "/requests", payload(f), key()), 201);
    }

    HttpResponse<String> decide(
            Actor a,
            Fixture f,
            JsonNode request,
            int position,
            long version,
            String decision,
            String key)
            throws Exception {
        return a.write(
                "POST",
                base(f)
                        + "/requests/"
                        + request.get("id").asText()
                        + "/steps/"
                        + request.get("steps").get(position).get("id").asText()
                        + "/decisions",
                Map.of(
                        "expectedVersion",
                        version,
                        "decision",
                        decision,
                        "comment",
                        "Reviewed fixture"),
                key);
    }

    HttpResponse<String> withdraw(Fixture f, JsonNode request, long version, String key)
            throws Exception {
        return f.requester()
                .write(
                        "POST",
                        base(f) + "/requests/" + request.get("id").asText() + "/withdraw",
                        Map.of("expectedVersion", version),
                        key);
    }

    int count(String table, String org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id=?",
                Integer.class,
                UUID.fromString(org));
    }

    String vpath(Fixture f, String version) {
        return base(f) + "/workflows/" + f.definition() + "/versions/" + version;
    }

    String path(Fixture f, JsonNode req) {
        return base(f) + "/requests/" + req.get("id").asText();
    }

    String audits(Fixture f) {
        return base(f) + "/audit-logs";
    }

    JsonNode event(Fixture f, JsonNode request) throws Exception {
        return ok(
                        f.admin()
                                .get(
                                        audits(f)
                                                + "?action=REQUEST_SUBMITTED&resourceId="
                                                + request.get("id").asText()),
                        200)
                .get("items")
                .get(0);
    }

    @Test
    void privilegedFeedIsBoundedAndDoesNotHydrateSnapshots() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        var page = ok(f.admin().get(audits(f) + "?limit=2"), 200);
        assertThat(page.get("items").size()).isEqualTo(2);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        assertThat(page.toString())
                .doesNotContain("oldValue", "newValue", PASSWORD, f.requester().email);
        var e = event(f, request);
        assertThat(e.get("actorMembershipId").asText()).isEqualTo(f.requesterMember());
        assertThat(e.get("action").asText()).isEqualTo("REQUEST_SUBMITTED");
        assertThat(e.get("requestId").asText()).matches("[0-9a-f-]{36}");
    }

    @Test
    void memberWithoutAuditPermissionCannotReadEvenOwnRequestEvidence() throws Exception {
        var f = fixture(false);
        var e = event(f, submit(f));
        problem(f.requester().get(audits(f)), 403, "PERMISSION_DENIED");
        problem(
                f.requester().get(audits(f) + "/" + e.get("id").asText()),
                403,
                "PERMISSION_DENIED");
    }

    @Test
    void foreignOrgAndForeignEntryAreConcealed() throws Exception {
        var a = fixture(false);
        var b = fixture(false);
        var entry = event(b, submit(b));
        problem(a.admin().get(audits(b)), 404, "ORGANIZATION_NOT_FOUND");
        problem(a.admin().get(audits(a) + "/" + entry.get("id").asText()), 404, "AUDIT_NOT_FOUND");
    }

    @Test
    void permissionRevocationImmediatelyBlocksReads() throws Exception {
        var f = fixture(false);
        var reader = actor();
        var role = custom(f.admin(), f.org(), "AUDITOR", List.of("AUDIT_VIEW"));
        enroll(f.admin(), f.org(), reader, role);
        ok(reader.get(audits(f)), 200);
        jdbc.update(
                "DELETE FROM role_permissions WHERE organization_id=? AND role_id=? AND"
                        + " permission_code='AUDIT_VIEW'",
                UUID.fromString(f.org()),
                UUID.fromString(role));
        problem(reader.get(audits(f)), 403, "PERMISSION_DENIED");
    }

    @Test
    void filtersCombineByActorResourceActionRequestIdAndHalfOpenTime() throws Exception {
        var f = fixture(false);
        var req = submit(f);
        var e = event(f, req);
        String q =
                "?action=REQUEST_SUBMITTED&resourceType=REQUEST&resourceId="
                        + req.get("id").asText()
                        + "&actorMembershipId="
                        + f.requesterMember()
                        + "&requestId="
                        + e.get("requestId").asText();
        assertThat(ok(f.admin().get(audits(f) + q), 200).get("items").size()).isEqualTo(1);
        String instant = e.get("occurredAt").asText();
        assertThat(ok(f.admin().get(audits(f) + q + "&from=" + instant), 200).get("items").size())
                .isEqualTo(1);
        assertThat(ok(f.admin().get(audits(f) + q + "&before=" + instant), 200).get("items").size())
                .isZero();
    }

    @Test
    void badBoundsRangesCodesAndTypesAreRejected() throws Exception {
        var f = fixture(false);
        for (String query :
                List.of(
                        "limit=0",
                        "limit=101",
                        "offset=10001",
                        "action=wrong",
                        "resourceType=REQUEST%27",
                        "from=2026-10-02T00:00:00Z&before=2026-10-01T00:00:00Z"))
            problem(f.admin().get(audits(f) + "?" + query), 400, "INVALID_AUDIT_QUERY");
        assertThat(f.admin().get(audits(f) + "?resourceId=not-a-uuid").statusCode()).isEqualTo(400);
    }

    @Test
    void detailContainsWhoWhatWhenResourceAndActualStateChanges() throws Exception {
        var f = fixture(false);
        var req = submit(f);
        ok(decide(f.first(), f, req, 0, 0, "APPROVE", key()), 200);
        var e =
                ok(
                                f.admin()
                                        .get(
                                                audits(f)
                                                        + "?action=REQUEST_DECIDED&resourceId="
                                                        + req.get("id").asText()),
                                200)
                        .get("items")
                        .get(0);
        var d = ok(f.admin().get(audits(f) + "/" + e.get("id").asText()), 200);
        assertThat(d.get("oldValue").get("state").asText()).isEqualTo("IN_REVIEW");
        assertThat(d.get("newValue").get("state").asText()).isEqualTo("APPROVED");
        assertThat(d.get("entry").get("actorMembershipId").asText()).isEqualTo(f.firstMember());
        assertThat(d.get("snapshotRedacted").asBoolean()).isFalse();
    }

    @Test
    void policyEditsCaptureExactOldAndNewStepEvidenceNotJustCounts() throws Exception {
        var f = fixture(false);
        String p = base(f) + "/workflows/" + f.definition() + "/versions";
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        p,
                                        Map.of(
                                                "steps",
                                                List.of(step("Before", f.roleA(), always()))),
                                        null),
                        201);
        var after =
                ok(
                        f.admin()
                                .write(
                                        "PUT",
                                        p + "/" + v.get("id").asText(),
                                        Map.of(
                                                "expectedVersion",
                                                0,
                                                "steps",
                                                List.of(step("After", f.roleA(), always()))),
                                        null),
                        200);
        var e =
                ok(
                                f.admin()
                                        .get(
                                                audits(f)
                                                        + "?action=WORKFLOW_VERSION_UPDATED&resourceId="
                                                        + v.get("id").asText()),
                                200)
                        .get("items")
                        .get(0);
        var d = ok(f.admin().get(audits(f) + "/" + e.get("id").asText()), 200);
        assertThat(d.get("oldValue").get("steps").get(0).get("name").asText()).isEqualTo("Before");
        assertThat(d.get("newValue").get("steps").get(0).get("name").asText()).isEqualTo("After");
        assertThat(d.get("newValue").get("steps").get(0).get("id"))
                .isEqualTo(after.get("steps").get(0).get("id"));
    }

    @Test
    void legacyUnknownAndNestedSensitiveFieldsAreRedacted() throws Exception {
        var f = fixture(false);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " audit_logs(id,organization_id,actor_kind,actor_membership_id,action,resource_type,resource_id,new_value,correlation_id)"
                    + " VALUES(?,?,'USER',?,'LEGACY','REQUEST',?,?::jsonb,'legacy-test')",
                id,
                UUID.fromString(f.org()),
                UUID.fromString(f.requesterMember()),
                UUID.randomUUID(),
                "{\"state\":\"IN_REVIEW\",\"password\":\"fixture-secret\",\"condition\":{\"token\":\"fixture-token\"}}");
        var d = ok(f.admin().get(audits(f) + "/" + id), 200);
        assertThat(d.get("snapshotRedacted").asBoolean()).isTrue();
        assertThat(d.toString())
                .doesNotContain("fixture-secret", "fixture-token", "password", "token");
    }

    @Test
    void auditHasNoCreateUpdateDeleteRoutesAndDatabaseHistoryIsImmutable() throws Exception {
        var f = fixture(false);
        var id = event(f, submit(f)).get("id").asText();
        assertThat(f.admin().write("DELETE", audits(f) + "/" + id, null, null).statusCode())
                .isEqualTo(405);
        assertThat(
                        f.admin()
                                .write("POST", audits(f), Map.of("action", "FORGED"), null)
                                .statusCode())
                .isEqualTo(405);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE audit_logs SET action='FORGED' WHERE id=?",
                                        UUID.fromString(id)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM audit_logs WHERE id=?", UUID.fromString(id)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void idempotentReplayDoesNotDuplicateAuditEvidence() throws Exception {
        var f = fixture(false);
        String k = key();
        ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 201);
        ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 200);
        assertThat(
                        ok(f.admin().get(audits(f) + "?action=REQUEST_SUBMITTED"), 200)
                                .get("items")
                                .size())
                .isEqualTo(1);
    }

    @Test
    void suspensionAndAnonymousAccessAreDenied() throws Exception {
        var f = fixture(false);
        var reader = actor();
        String role = custom(f.admin(), f.org(), "AUDITOR", List.of("AUDIT_VIEW"));
        String member = enroll(f.admin(), f.org(), reader, role);
        jdbc.update(
                "UPDATE memberships SET status='SUSPENDED' WHERE id=?", UUID.fromString(member));
        problem(reader.get(audits(f)), 404, "ORGANIZATION_NOT_FOUND");
        assertThat(
                        HttpClient.newHttpClient()
                                .send(
                                        HttpRequest.newBuilder(uri(audits(f))).GET().build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(401);
    }
}
