package com.gateflow.workflow;

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
class WorkflowIntegrationTest {
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
                    .withDatabaseName("gateflow_workflow_test")
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

    @Test
    void sequentialApprovalsAdvanceOneStepAtATimeAndFinish() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        assertThat(r.get("state").asText()).isEqualTo("IN_REVIEW");
        assertThat(r.get("steps").get(0).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(r.get("steps").get(1).get("state").asText()).isEqualTo("WAITING");
        var next = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        assertThat(next.get("version").asInt()).isEqualTo(1);
        assertThat(next.get("steps").get(0).get("state").asText()).isEqualTo("APPROVED");
        assertThat(next.get("steps").get(1).get("state").asText()).isEqualTo("ACTIVE");
        var done = ok(decide(f.second(), f, next, 1, 1, "APPROVE", key()), 200);
        assertThat(done.get("state").asText()).isEqualTo("APPROVED");
        assertThat(done.get("completedAt").isNull()).isFalse();
        assertThat(done.get("version").asInt()).isEqualTo(2);
        assertThat(count("approval_decisions", f.org())).isEqualTo(2);
        problem(withdraw(f, done, 2, key()), 409, "REQUEST_NOT_IN_REVIEW");
        ok(f.requester().get(path(f, done)), 200);
    }

    @Test
    void rejectionTerminatesAndCancelsRemainingSteps() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var done = ok(decide(f.first(), f, r, 0, 0, "REJECT", key()), 200);
        assertThat(done.get("state").asText()).isEqualTo("REJECTED");
        assertThat(done.get("steps").get(1).get("state").asText()).isEqualTo("CANCELLED");
        assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        problem(decide(f.second(), f, done, 1, 1, "APPROVE", key()), 409, "REQUEST_NOT_IN_REVIEW");
    }

    @Test
    void ownerWithdrawalCancelsPendingWorkAndCannotBeAppliedToAnotherUsersRequest()
            throws Exception {
        var f = fixture(true);
        var r = submit(f);
        problem(
                f.admin()
                        .write(
                                "POST",
                                path(f, r) + "/withdraw",
                                Map.of("expectedVersion", 0),
                                key()),
                404,
                "REQUEST_NOT_FOUND");
        var done = ok(withdraw(f, r, 0, key()), 200);
        assertThat(done.get("state").asText()).isEqualTo("WITHDRAWN");
        for (var s : done.get("steps")) assertThat(s.get("state").asText()).isEqualTo("CANCELLED");
        assertThat(count("approval_decisions", f.org())).isZero();
    }

    @Test
    void publicationIsImmutableAndNewVersionDoesNotRepinInFlightRequests() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var old = f.version().get("id").asText();
        problem(
                f.admin()
                        .write(
                                "PUT",
                                vpath(f, old),
                                Map.of(
                                        "expectedVersion",
                                        1,
                                        "steps",
                                        List.of(step("Changed", f.roleB(), always()))),
                                null),
                409,
                "PUBLISHED_VERSION_IMMUTABLE");
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("New finance", f.roleB(), always()))),
                                        null),
                        201);
        assertThat(v.get("versionNumber").asInt()).isEqualTo(2);
        ok(
                f.admin()
                        .write(
                                "POST",
                                vpath(f, v.get("id").asText()) + "/publish",
                                Map.of("expectedVersion", 0),
                                null),
                200);
        var pinned = ok(f.requester().get(path(f, r)), 200);
        assertThat(pinned.get("workflowVersionId").asText()).isEqualTo(old);
        assertThat(pinned.get("steps").get(0).get("assignedMembershipId").asText())
                .isEqualTo(f.firstMember());
        ok(decide(f.first(), f, pinned, 0, 0, "APPROVE", key()), 200);
    }

    @Test
    void draftEditsRequireExpectedVersionAndPublishRevalidatesApproverGrants() throws Exception {
        var f = fixture(false);
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("Draft", f.roleA(), always()))),
                                        null),
                        201);
        String p = vpath(f, v.get("id").asText());
        var body =
                Map.of("expectedVersion", 0, "steps", List.of(step("Edited", f.roleA(), always())));
        assertThat(ok(f.admin().write("PUT", p, body, null), 200).get("version").asInt())
                .isEqualTo(1);
        problem(f.admin().write("PUT", p, body, null), 409, "VERSION_CONFLICT");
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/roles/" + f.roleA() + "/permissions",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "permissions",
                                        List.of("WORKFLOW_VIEW")),
                                null),
                200);
        problem(
                f.admin().write("POST", p + "/publish", Map.of("expectedVersion", 1), null),
                400,
                "INVALID_BUSINESS_RULE");
        assertThat(ok(f.admin().get(p), 200).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void currencyBoundThresholdsSkipLowValueReviewButCannotSilentlySkipForDifferentCurrency()
            throws Exception {
        var f = fixture(true);
        var steps =
                List.of(
                        step("Manager", f.roleA(), always()),
                        step(
                                "Finance above threshold",
                                f.roleB(),
                                Map.of(
                                        "type",
                                        "PURCHASE_AMOUNT_AT_LEAST",
                                        "amount",
                                        1000,
                                        "currency",
                                        "USD")));
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of("steps", steps),
                                        null),
                        201);
        String id = v.get("id").asText();
        ok(
                f.admin()
                        .write(
                                "POST",
                                vpath(f, id) + "/publish",
                                Map.of("expectedVersion", 0),
                                null),
                200);
        var p = payload(f);
        p.put("workflowVersionId", id);
        p.put("purchaseAmount", 500);
        var r = ok(f.requester().write("POST", base(f) + "/requests", p, key()), 201);
        assertThat(r.get("steps").get(1).get("state").asText()).isEqualTo("SKIPPED");
        assertThat(ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200).get("state").asText())
                .isEqualTo("APPROVED");
        p.put("purchaseAmount", 1000);
        var atThreshold = ok(f.requester().write("POST", base(f) + "/requests", p, key()), 201);
        assertThat(atThreshold.get("steps").get(1).get("state").asText()).isEqualTo("WAITING");
        p.put("currency", "EUR");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "INVALID_BUSINESS_RULE");
        assertThat(count("requests", f.org())).isEqualTo(2);
    }

    @Test
    void businessPayloadsAndConfiguredConditionsAreValidated() throws Exception {
        var f = fixture(false);
        var p = payload(f);
        p.remove("purchaseAmount");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "INVALID_BUSINESS_RULE");
        p = payload(f);
        p.put("purchaseAmount", new BigDecimal("1.001"));
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "VALIDATION_FAILED");
        p = payload(f);
        p.put("requestType", "SOFTWARE_ACCESS");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "INVALID_BUSINESS_RULE");
        p.remove("purchaseAmount");
        p.remove("currency");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "INVALID_BUSINESS_RULE");
        p.put("details", Map.of("softwareName", "Build service"));
        ok(f.requester().write("POST", base(f) + "/requests", p, key()), 201);
        var invalid =
                Map.of(
                        "steps",
                        List.of(
                                step(
                                        "Broken",
                                        f.roleA(),
                                        Map.of(
                                                "type",
                                                "ALWAYS",
                                                "amount",
                                                10,
                                                "currency",
                                                "USD"))));
        problem(
                f.admin()
                        .write(
                                "POST",
                                base(f) + "/workflows/" + f.definition() + "/versions",
                                invalid,
                                null),
                400,
                "INVALID_BUSINESS_RULE");
        invalid =
                Map.of(
                        "steps",
                        List.of(step("Unknown", f.roleA(), Map.of("type", "EXECUTE_SCRIPT"))));
        problem(
                f.admin()
                        .write(
                                "POST",
                                base(f) + "/workflows/" + f.definition() + "/versions",
                                invalid,
                                null),
                400,
                "INVALID_JSON");
    }

    @Test
    void missingReviewersAndNoApplicableStepsRollBackSubmission() throws Exception {
        var f = fixture(false);
        ok(
                f.admin()
                        .write(
                                "PATCH",
                                base(f) + "/memberships/" + f.firstMember() + "/status",
                                Map.of("expectedVersion", 0, "status", "SUSPENDED"),
                                null),
                200);
        int before = count("audit_logs", f.org());
        problem(
                f.requester().write("POST", base(f) + "/requests", payload(f), key()),
                409,
                "NO_ELIGIBLE_REVIEWER");
        assertThat(count("requests", f.org())).isZero();
        assertThat(count("audit_logs", f.org())).isEqualTo(before);
        assertThat(count("command_receipts", f.org())).isZero();
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(
                                                        step(
                                                                "Optional finance",
                                                                f.roleB(),
                                                                Map.of(
                                                                        "type",
                                                                        "PURCHASE_AMOUNT_AT_LEAST",
                                                                        "amount",
                                                                        9999,
                                                                        "currency",
                                                                        "USD")))),
                                        null),
                        201);
        ok(
                f.admin()
                        .write(
                                "POST",
                                vpath(f, v.get("id").asText()) + "/publish",
                                Map.of("expectedVersion", 0),
                                null),
                200);
        var p = payload(f);
        p.put("workflowVersionId", v.get("id").asText());
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                409,
                "NO_APPLICABLE_STEPS");
    }

    @Test
    void currentPermissionAssignmentAndSelfApprovalChecksAreIndependent() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        problem(decide(f.second(), f, r, 0, 0, "APPROVE", key()), 403, "NOT_ASSIGNED_REVIEWER");
        problem(decide(f.second(), f, r, 1, 0, "APPROVE", key()), 409, "STEP_NOT_ACTIVE");
        // Give requester approver privileges but never allow it to decide its own request.
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/memberships/" + f.requesterMember() + "/roles",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "roleIds",
                                        List.of(builtin(f.admin(), f.org(), "MANAGER"), f.roleA())),
                                null),
                200);
        problem(
                decide(f.requester(), f, r, 0, 0, "APPROVE", key()),
                403,
                "SELF_APPROVAL_FORBIDDEN");
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/memberships/" + f.firstMember() + "/roles",
                                Map.of("expectedVersion", 0, "roleIds", List.of(f.roleB())),
                                null),
                200);
        problem(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 403, "REVIEWER_INELIGIBLE");
        assertThat(count("approval_decisions", f.org())).isZero();
    }

    @Test
    void tenantAndRequestOwnershipAreEnforcedForReadsAndCommands() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var outsider = actor();
        problem(outsider.get(path(f, r)), 404, "ORGANIZATION_NOT_FOUND");
        enroll(f.admin(), f.org(), outsider, builtin(f.admin(), f.org(), "MEMBER"));
        problem(outsider.get(path(f, r)), 404, "REQUEST_NOT_FOUND");
        problem(
                outsider.write(
                        "POST", path(f, r) + "/withdraw", Map.of("expectedVersion", 0), key()),
                404,
                "REQUEST_NOT_FOUND");
        var other = fixture(false);
        problem(
                other.admin().get(base(other) + "/requests/" + r.get("id").asText()),
                404,
                "REQUEST_NOT_FOUND");
        var p = payload(other);
        p.put("workflowVersionId", f.version().get("id").asText());
        problem(
                other.requester().write("POST", base(other) + "/requests", p, key()),
                404,
                "WORKFLOW_VERSION_NOT_FOUND");
        problem(
                f.requester()
                        .write(
                                "POST",
                                base(f) + "/workflows",
                                Map.of("name", "Unauthorized"),
                                null),
                403,
                "PERMISSION_DENIED");
    }

    @Test
    void auditedReassignmentRecoversUnavailableCurrentReviewerWithoutBypassingEligibility()
            throws Exception {
        var f = fixture(false);
        var r = submit(f);
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/memberships/" + f.secondMember() + "/roles",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "roleIds",
                                        List.of(f.roleA(), f.roleB())),
                                null),
                200);
        ok(
                f.admin()
                        .write(
                                "PATCH",
                                base(f) + "/memberships/" + f.firstMember() + "/status",
                                Map.of("expectedVersion", 0, "status", "SUSPENDED"),
                                null),
                200);
        String target =
                path(f, r) + "/steps/" + r.get("steps").get(0).get("id").asText() + "/reassign";
        problem(
                f.admin()
                        .write(
                                "POST",
                                target,
                                Map.of("expectedVersion", 0, "membershipId", f.requesterMember()),
                                key()),
                409,
                "NO_ELIGIBLE_REVIEWER");
        problem(
                f.second()
                        .write(
                                "POST",
                                target,
                                Map.of("expectedVersion", 0, "membershipId", f.secondMember()),
                                key()),
                403,
                "PERMISSION_DENIED");
        var reassigned =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        target,
                                        Map.of(
                                                "expectedVersion",
                                                0,
                                                "membershipId",
                                                f.secondMember()),
                                        key()),
                        200);
        assertThat(reassigned.get("version").asInt()).isEqualTo(1);
        assertThat(reassigned.get("steps").get(0).get("assignedMembershipId").asText())
                .isEqualTo(f.secondMember());
        var done = ok(decide(f.second(), f, reassigned, 0, 1, "APPROVE", key()), 200);
        assertThat(done.get("state").asText()).isEqualTo("APPROVED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE organization_id=? AND"
                                    + " action='REQUEST_REVIEWER_REASSIGNED'",
                                Integer.class,
                                UUID.fromString(f.org())))
                .isEqualTo(1);
    }

    @Test
    void unavailableNextReviewerDoesNotPartiallyCommitCurrentDecision() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        ok(
                f.admin()
                        .write(
                                "PATCH",
                                base(f) + "/memberships/" + f.secondMember() + "/status",
                                Map.of("expectedVersion", 0, "status", "SUSPENDED"),
                                null),
                200);
        int before = count("audit_logs", f.org());
        problem(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 409, "REVIEWER_UNAVAILABLE");
        assertThat(count("approval_decisions", f.org())).isZero();
        assertThat(count("audit_logs", f.org())).isEqualTo(before);
        assertThat(ok(f.requester().get(path(f, r)), 200).get("version").asInt()).isZero();
        ok(
                f.admin()
                        .write(
                                "PATCH",
                                base(f) + "/memberships/" + f.secondMember() + "/status",
                                Map.of("expectedVersion", 1, "status", "ACTIVE"),
                                null),
                200);
        ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
    }

    @Test
    void idempotencyReplaysCurrentResourceAndRejectsChangedCommands() throws Exception {
        var f = fixture(false);
        var k = key();
        var p = payload(f);
        var first = f.requester().write("POST", base(f) + "/requests", p, k);
        var r = ok(first, 201);
        assertThat(first.headers().firstValue("Idempotent-Replay")).contains("false");
        var again = f.requester().write("POST", base(f) + "/requests", p, k);
        assertThat(ok(again, 200).get("id").asText()).isEqualTo(r.get("id").asText());
        assertThat(again.headers().firstValue("Idempotent-Replay")).contains("true");
        p.put("title", "Changed intent");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, k),
                409,
                "IDEMPOTENCY_CONFLICT");
        var dk = key();
        ok(decide(f.first(), f, r, 0, 0, "APPROVE", dk), 200);
        var replay = ok(decide(f.first(), f, r, 0, 0, "APPROVE", dk), 200);
        assertThat(replay.get("state").asText()).isEqualTo("APPROVED");
        problem(decide(f.first(), f, r, 0, 0, "REJECT", dk), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("requests", f.org())).isEqualTo(1);
        assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(2);
    }

    @Test
    void replayNeverRestoresRevokedAuthorizationAndKeysAreScopedPerActor() throws Exception {
        var f = fixture(false);
        var k = key();
        var r = ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 201);
        ok(decide(f.first(), f, r, 0, 0, "APPROVE", k), 200);
        assertThat(count("command_receipts", f.org())).isEqualTo(2);
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/memberships/" + f.firstMember() + "/roles",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "roleIds",
                                        List.of(builtin(f.admin(), f.org(), "VIEWER"))),
                                null),
                200);
        problem(decide(f.first(), f, r, 0, 0, "APPROVE", k), 403, "PERMISSION_DENIED");
    }

    @Test
    void invalidHeadersInputsForeignStepsAndStaleVersionsAreRejected() throws Exception {
        var f = fixture(false);
        problem(
                f.requester().write("POST", base(f) + "/requests", payload(f), null),
                400,
                "INVALID_PARAMETER");
        problem(
                f.requester().write("POST", base(f) + "/requests", payload(f), "short"),
                400,
                "INVALID_PARAMETER");
        var r = submit(f);
        problem(decide(f.first(), f, r, 0, 3, "APPROVE", key()), 409, "VERSION_CONFLICT");
        problem(
                f.first()
                        .write(
                                "POST",
                                path(f, r) + "/steps/" + key() + "/decisions",
                                Map.of("expectedVersion", 0, "decision", "APPROVE"),
                                key()),
                404,
                "REQUEST_STEP_NOT_FOUND");
        problem(
                f.requester()
                        .write(
                                "POST",
                                base(f) + "/requests",
                                Map.of(
                                        "workflowVersionId",
                                        f.version().get("id").asText(),
                                        "title",
                                        "Incomplete"),
                                key()),
                400,
                "VALIDATION_FAILED");
        assertThat(count("approval_decisions", f.org())).isZero();
    }

    @Test
    void draftCannotBeSubmittedAndInvalidRoleCannotBecomeApprover() throws Exception {
        var f = fixture(false);
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("Draft", f.roleA(), always()))),
                                        null),
                        201);
        var p = payload(f);
        p.put("workflowVersionId", v.get("id").asText());
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                409,
                "WORKFLOW_NOT_PUBLISHED");
        problem(
                f.admin()
                        .write(
                                "POST",
                                base(f) + "/workflows/" + f.definition() + "/versions",
                                Map.of(
                                        "steps",
                                        List.of(
                                                step(
                                                        "Not approver",
                                                        builtin(f.admin(), f.org(), "MEMBER"),
                                                        always()))),
                                null),
                400,
                "INVALID_BUSINESS_RULE");
        problem(
                f.admin()
                        .write(
                                "POST",
                                base(f) + "/workflows/" + f.definition() + "/versions",
                                Map.of("steps", List.of(step("Missing", key(), always()))),
                                null),
                404,
                "ROLE_NOT_FOUND");
    }

    @Test
    void auditFailureRollsBackRequestDecisionAndReceiptThenSameKeyCanRetry() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var k = key();
        jdbc.execute(
                "CREATE FUNCTION fail_workflow_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " IF NEW.action='REQUEST_DECIDED' THEN RAISE EXCEPTION 'fixture failure'"
                    + " USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_workflow_audit BEFORE INSERT ON audit_logs FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_workflow_audit()");
        try {
            problem(decide(f.first(), f, r, 0, 0, "APPROVE", k), 503, "SERVICE_UNAVAILABLE");
            assertThat(count("approval_decisions", f.org())).isZero();
            assertThat(count("command_receipts", f.org())).isEqualTo(1);
            var fresh = ok(f.requester().get(path(f, r)), 200);
            assertThat(fresh.get("state").asText()).isEqualTo("IN_REVIEW");
            assertThat(fresh.get("steps").get(0).get("state").asText()).isEqualTo("ACTIVE");
        } finally {
            jdbc.execute("DROP TRIGGER fail_workflow_audit ON audit_logs");
            jdbc.execute("DROP FUNCTION fail_workflow_audit()");
        }
        ok(decide(f.first(), f, r, 0, 0, "APPROVE", k), 200);
    }

    @Test
    void workflowAuditsCarryActorVersionAndTraceButNotPayloadOrComment() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var response = decide(f.first(), f, r, 0, 0, "APPROVE", key());
        ok(response, 200);
        var row =
                jdbc.queryForMap(
                        "SELECT actor_membership_id,correlation_id,old_value::text AS"
                            + " old_data,new_value::text AS new_data FROM audit_logs WHERE"
                            + " organization_id=? AND action='REQUEST_DECIDED'",
                        UUID.fromString(f.org()));
        assertThat(row.get("actor_membership_id").toString()).isEqualTo(f.firstMember());
        assertThat(row.get("correlation_id"))
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(row.get("new_data").toString())
                .contains("APPROVED", "version")
                .doesNotContain("Reviewed fixture", "Replace failing", "Fixture supplier");
    }

    @Test
    void parallelIdenticalSubmissionsCreateOneAggregateAndReceipt() throws Exception {
        var f = fixture(false);
        var other = fork(f.requester());
        var k = key();
        var p = payload(f);
        var pair =
                race(
                        () -> f.requester().write("POST", base(f) + "/requests", p, k),
                        () -> other.write("POST", base(f) + "/requests", p, k));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(201, 200);
        assertThat(json(pair.get(0)).get("id")).isEqualTo(json(pair.get(1)).get("id"));
        assertThat(count("requests", f.org())).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(1);
    }

    @Test
    void parallelIdenticalDecisionsCommitOnceAndBothRetryResponsesSucceed() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var other = fork(f.first());
        var k = key();
        var pair =
                race(
                        () -> decide(f.first(), f, r, 0, 0, "APPROVE", k),
                        () -> decide(other, f, r, 0, 0, "APPROVE", k));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList()).containsOnly(200);
        assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(2);
        assertThat(ok(f.requester().get(path(f, r)), 200).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void parallelDifferentDecisionKeysStillHaveOneWinner() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var other = fork(f.first());
        var pair =
                race(
                        () -> decide(f.first(), f, r, 0, 0, "APPROVE", key()),
                        () -> decide(other, f, r, 0, 0, "APPROVE", key()));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(2);
    }

    @Test
    void approvalVersusWithdrawalHasOneConsistentTerminalOutcome() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var pair =
                race(
                        () -> decide(f.first(), f, r, 0, 0, "APPROVE", key()),
                        () -> withdraw(f, r, 0, key()));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        var done = ok(f.requester().get(path(f, r)), 200);
        var state = done.get("state").asText();
        assertThat(state).isIn("APPROVED", "WITHDRAWN");
        assertThat(count("approval_decisions", f.org()))
                .isEqualTo(state.equals("APPROVED") ? 1 : 0);
        assertThat(done.get("steps").get(0).get("state").asText()).isIn("APPROVED", "CANCELLED");
    }

    @Test
    void publicationVersusDraftEditCannotPublishMixedSteps() throws Exception {
        var f = fixture(false);
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("Original", f.roleA(), always()))),
                                        null),
                        201);
        var other = fork(f.admin());
        String path = vpath(f, v.get("id").asText());
        var pair =
                race(
                        () ->
                                f.admin()
                                        .write(
                                                "POST",
                                                path + "/publish",
                                                Map.of("expectedVersion", 0),
                                                null),
                        () ->
                                other.write(
                                        "PUT",
                                        path,
                                        Map.of(
                                                "expectedVersion",
                                                0,
                                                "steps",
                                                List.of(step("Replaced", f.roleB(), always()))),
                                        null));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        var after = ok(f.admin().get(path), 200);
        assertThat(after.get("version").asInt()).isEqualTo(1);
        assertThat(after.get("steps").get(0).get("name").asText())
                .isEqualTo(
                        after.get("status").asText().equals("PUBLISHED") ? "Original" : "Replaced");
    }

    @Test
    void authorizationIsRecheckedAfterWaitingForAnRbacChange() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        try (var connection = datasource.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM organizations WHERE id=? FOR UPDATE")) {
                statement.setObject(1, UUID.fromString(f.org()));
                statement.executeQuery().close();
            }
            Future<HttpResponse<String>> pending =
                    pool.submit(() -> decide(f.first(), f, r, 0, 0, "APPROVE", key()));
            boolean waiting = false;
            for (int i = 0; i < 200; i++) {
                if (jdbc.queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'"
                                    + " AND query LIKE 'SELECT id FROM organizations%FOR SHARE%'",
                                Integer.class)
                        > 0) {
                    waiting = true;
                    break;
                }
                Thread.sleep(10);
            }
            try {
                assertThat(waiting).isTrue();
                try (var statement =
                        connection.prepareStatement(
                                "DELETE FROM membership_roles WHERE organization_id=? AND"
                                    + " membership_id=?")) {
                    statement.setObject(1, UUID.fromString(f.org()));
                    statement.setObject(2, UUID.fromString(f.firstMember()));
                    statement.executeUpdate();
                }
                connection.commit();
            } finally {
                connection.rollback();
            }
            problem(pending.get(15, TimeUnit.SECONDS), 403, "PERMISSION_DENIED");
            assertThat(count("approval_decisions", f.org())).isZero();
        }
    }

    private List<HttpResponse<String>> race(
            Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var fa =
                    pool.submit(
                            () -> {
                                gate.await();
                                return a.call();
                            });
            var fb =
                    pool.submit(
                            () -> {
                                gate.await();
                                return b.call();
                            });
            gate.countDown();
            return List.of(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void oversizedKnownLengthAndChunkedBodiesAreRejectedBeforeJsonAllocation() throws Exception {
        var f = fixture(false);
        var big = Map.of("description", "x".repeat(270000));
        problem(
                f.requester().write("POST", base(f) + "/requests", big, key()),
                413,
                "PAYLOAD_TOO_LARGE");
        byte[] bytes = mapper.writeValueAsBytes(big);
        var csrf = json(f.requester().get("/api/v1/auth/csrf"));
        var response =
                f.requester()
                        .http
                        .send(
                                HttpRequest.newBuilder(uri(base(f) + "/requests"))
                                        .header("Content-Type", "application/json")
                                        .header("Idempotency-Key", key())
                                        .header(
                                                csrf.get("headerName").asText(),
                                                csrf.get("token").asText())
                                        .POST(
                                                HttpRequest.BodyPublishers.ofInputStream(
                                                        () ->
                                                                new java.io.ByteArrayInputStream(
                                                                        bytes)))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        problem(response, 413, "PAYLOAD_TOO_LARGE");
        assertThat(count("requests", f.org())).isZero();
    }

    @Test
    void nulTextIsRejectedAsValidationErrorRatherThanDatabaseFailure() throws Exception {
        var f = fixture(false);
        var p = payload(f);
        p.put("description", "invalid\u0000text");
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "VALIDATION_FAILED");
        p = payload(f);
        p.put("details", Map.of("vendor", "invalid\u0000vendor"));
        problem(
                f.requester().write("POST", base(f) + "/requests", p, key()),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void reviewerWithoutReadAllSeesOnlyActiveAssignmentsAndItsDecisionHistory() throws Exception {
        var f = fixture(true);
        for (String role : List.of(f.roleA(), f.roleB()))
            ok(
                    f.admin()
                            .write(
                                    "PUT",
                                    base(f) + "/roles/" + role + "/permissions",
                                    Map.of(
                                            "expectedVersion",
                                            0,
                                            "permissions",
                                            List.of("REQUEST_APPROVE", "WORKFLOW_VIEW")),
                                    null),
                    200);
        var r = submit(f);
        ok(f.first().get(path(f, r)), 200);
        problem(f.second().get(path(f, r)), 404, "REQUEST_NOT_FOUND");
        var next = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        ok(f.first().get(path(f, r)), 200);
        ok(f.second().get(path(f, next)), 200);
        ok(decide(f.second(), f, next, 1, 1, "APPROVE", key()), 200);
        ok(f.second().get(path(f, r)), 200);
    }
}
