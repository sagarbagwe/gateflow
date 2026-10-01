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
class ConcurrencyIntegrationTest {
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
                    .withDatabaseName("gateflow_concurrency_test")
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
                    HttpRequest.newBuilder(uri(p))
                            .timeout(java.time.Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> write(String method, String p, Object body, String key)
                throws Exception {
            var csrf = json(get("/api/v1/auth/csrf"));
            var request =
                    HttpRequest.newBuilder(uri(p))
                            .timeout(java.time.Duration.ofSeconds(20))
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

    // Observe actual server lock waits, not merely simultaneous client starts.
    List<HttpResponse<String>> blockedRace(
            String lockSql,
            List<Object> lockArgs,
            String waitingSqlPattern,
            Callable<HttpResponse<String>> left,
            Callable<HttpResponse<String>> right)
            throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try (var c = datasource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var st = c.prepareStatement(lockSql)) {
                    for (int i = 0; i < lockArgs.size(); i++) st.setObject(i + 1, lockArgs.get(i));
                    st.executeQuery().close();
                }
                var a = pool.submit(left);
                var b = pool.submit(right);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                int observed = 0;
                while (System.nanoTime() < deadline) {
                    observed =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE"
                                        + " datname=current_database() AND wait_event_type='Lock'"
                                        + " AND query LIKE ?",
                                    Integer.class,
                                    waitingSqlPattern);
                    if (observed == 2) break;
                    Thread.sleep(20);
                }
                assertThat(observed)
                        .as("Both independent HTTP transactions must reach the database barrier")
                        .isEqualTo(2);
                c.commit();
                return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            } finally {
                c.rollback();
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
        }
    }

    List<HttpResponse<String>> requestRace(
            Fixture f,
            JsonNode r,
            Callable<HttpResponse<String>> a,
            Callable<HttpResponse<String>> b)
            throws Exception {
        return blockedRace(
                "SELECT id FROM requests WHERE organization_id=? AND id=? FOR UPDATE",
                List.of(UUID.fromString(f.org()), UUID.fromString(r.get("id").asText())),
                "SELECT * FROM requests%FOR UPDATE",
                a,
                b);
    }

    void oneSuccessfulMutation(Fixture f, JsonNode r, List<HttpResponse<String>> pair)
            throws Exception {
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        var winner = pair.stream().filter(x -> x.statusCode() == 200).findFirst().orElseThrow();
        var loser = pair.stream().filter(x -> x.statusCode() == 409).findFirst().orElseThrow();
        assertThat(json(loser).get("code").asText())
                .isIn("VERSION_CONFLICT", "REQUEST_NOT_IN_REVIEW");
        assertThat(loser.body()).doesNotContain(PASSWORD, "SELECT ", "password_hash", "token_hash");
        assertThat(json(loser).get("requestId").asText())
                .isEqualTo(loser.headers().firstValue("X-Request-ID").orElseThrow());
        var done = ok(f.requester().get(path(f, r)), 200);
        assertThat(done.get("version").asInt()).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(2); // submit plus winning mutation
        assertThat(count("outbox_events", f.org())).isEqualTo(2);
        var evidence =
                jdbc.queryForList(
                        "SELECT correlation_id,new_value::text AS evidence FROM audit_logs WHERE"
                            + " organization_id=? AND resource_type='REQUEST' AND resource_id=? AND"
                            + " action<>'REQUEST_SUBMITTED'",
                        UUID.fromString(f.org()),
                        UUID.fromString(r.get("id").asText()));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().get("correlation_id"))
                .isEqualTo(winner.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(evidence.getFirst().get("evidence").toString()).contains("\"version\": 1");
    }

    @Test
    void twoDifferentApprovalKeysHaveOneDecisionAuditAndEvent() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var other = fork(f.first());
        var pair =
                requestRace(
                        f,
                        r,
                        () -> decide(f.first(), f, r, 0, 0, "APPROVE", key()),
                        () -> decide(other, f, r, 0, 0, "APPROVE", key()));
        oneSuccessfulMutation(f, r, pair);
        assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        assertThat(ok(f.requester().get(path(f, r)), 200).get("state").asText())
                .isEqualTo("APPROVED");
    }

    @Test
    void approvalVersusWithdrawalCannotCommitBothOutcomes() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var pair =
                requestRace(
                        f,
                        r,
                        () -> decide(f.first(), f, r, 0, 0, "APPROVE", key()),
                        () -> withdraw(f, r, 0, key()));
        oneSuccessfulMutation(f, r, pair);
        var done = ok(f.requester().get(path(f, r)), 200);
        var approved = done.get("state").asText().equals("APPROVED");
        assertThat(done.get("state").asText()).isIn("APPROVED", "WITHDRAWN");
        assertThat(count("approval_decisions", f.org())).isEqualTo(approved ? 1 : 0);
        assertThat(done.get("steps").get(0).get("state").asText())
                .isEqualTo(approved ? "APPROVED" : "CANCELLED");
    }

    @Test
    void reassignmentVersusOldReviewerApprovalHasOneVersionWinner() throws Exception {
        var f = fixture(false);
        var replacement = actor();
        var member = enroll(f.admin(), f.org(), replacement, f.roleA());
        var r = submit(f);
        // Assignment is selected deterministically at submit; explicitly use its actual reviewer.
        var assigned = r.get("steps").get(0).get("assignedMembershipId").asText();
        var reviewer = assigned.equals(f.firstMember()) ? f.first() : replacement;
        var target = assigned.equals(f.firstMember()) ? member : f.firstMember();
        String endpoint =
                path(f, r) + "/steps/" + r.get("steps").get(0).get("id").asText() + "/reassign";
        var pair =
                requestRace(
                        f,
                        r,
                        () -> decide(reviewer, f, r, 0, 0, "APPROVE", key()),
                        () ->
                                f.admin()
                                        .write(
                                                "POST",
                                                endpoint,
                                                Map.of(
                                                        "expectedVersion",
                                                        0,
                                                        "membershipId",
                                                        target),
                                                key()));
        oneSuccessfulMutation(f, r, pair);
        var done = ok(f.requester().get(path(f, r)), 200);
        if (done.get("state").asText().equals("APPROVED"))
            assertThat(count("approval_decisions", f.org())).isEqualTo(1);
        else {
            assertThat(done.get("state").asText()).isEqualTo("IN_REVIEW");
            assertThat(done.get("steps").get(0).get("assignedMembershipId").asText())
                    .isEqualTo(target);
            assertThat(count("approval_decisions", f.org())).isZero();
        }
    }

    List<HttpResponse<String>> submitRace(
            Fixture f, String k, Map<String, Object> a, Map<String, Object> b) throws Exception {
        var other = fork(f.requester());
        return blockedRace(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                List.of(f.org() + ":" + f.requesterMember() + ":" + k),
                "SELECT pg_advisory_xact_lock%",
                () -> f.requester().write("POST", base(f) + "/requests", a, k),
                () -> other.write("POST", base(f) + "/requests", b, k));
    }

    void singleSubmission(Fixture f) {
        assertThat(count("requests", f.org())).isEqualTo(1);
        assertThat(count("command_receipts", f.org())).isEqualTo(1);
        assertThat(count("outbox_events", f.org())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE organization_id=? AND"
                                        + " action='REQUEST_SUBMITTED'",
                                Integer.class,
                                UUID.fromString(f.org())))
                .isEqualTo(1);
    }

    @Test
    void simultaneousIdenticalRetryKeysReplayOneDurableSubmission() throws Exception {
        var f = fixture(false);
        var pair = submitRace(f, key(), payload(f), payload(f));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(201, 200);
        assertThat(json(pair.get(0)).get("id")).isEqualTo(json(pair.get(1)).get("id"));
        singleSubmission(f);
    }

    @Test
    void simultaneousConflictingPayloadsCannotReuseAnIdempotencyKey() throws Exception {
        var f = fixture(false);
        var changed = new LinkedHashMap<>(payload(f));
        changed.put("title", "Another laptop");
        var pair = submitRace(f, key(), payload(f), changed);
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(201, 409);
        var loser = pair.stream().filter(x -> x.statusCode() == 409).findFirst().orElseThrow();
        problem(loser, 409, "IDEMPOTENCY_CONFLICT");
        singleSubmission(f);
        var winner = pair.stream().filter(x -> x.statusCode() == 201).findFirst().orElseThrow();
        var done = ok(f.requester().get(path(f, json(winner))), 200);
        assertThat(done.get("title")).isEqualTo(json(winner).get("title"));
    }

    @Test
    void draftEditVersusPublishCannotPublishMixedPolicyEvidence() throws Exception {
        var f = fixture(false);
        var other = fork(f.admin());
        var draft =
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
        var endpoint = vpath(f, draft.get("id").asText());
        int audits = count("audit_logs", f.org());
        // Both commands acquire the definition lock before the version lock.
        var pair =
                blockedRace(
                        "SELECT id FROM workflow_definitions WHERE organization_id=? AND id=? FOR"
                                + " UPDATE",
                        List.of(UUID.fromString(f.org()), UUID.fromString(f.definition())),
                        "SELECT id,name,description FROM workflow_definitions%FOR UPDATE",
                        () ->
                                f.admin()
                                        .write(
                                                "POST",
                                                endpoint + "/publish",
                                                Map.of("expectedVersion", 0),
                                                null),
                        () ->
                                other.write(
                                        "PUT",
                                        endpoint,
                                        Map.of(
                                                "expectedVersion",
                                                0,
                                                "steps",
                                                List.of(step("Replacement", f.roleB(), always()))),
                                        null));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        var done = ok(f.admin().get(endpoint), 200);
        assertThat(done.get("version").asInt()).isEqualTo(1);
        var published = done.get("status").asText().equals("PUBLISHED");
        assertThat(done.get("steps").get(0).get("name").asText())
                .isEqualTo(published ? "Original" : "Replacement");
        assertThat(done.get("steps").get(0).get("approverRoleId").asText())
                .isEqualTo(published ? f.roleA() : f.roleB());
        assertThat(count("audit_logs", f.org())).isEqualTo(audits + 1);
    }

    @Test
    void preferenceCompareAndSetRejectsLostUpdateAndAuditsOnlyWinner() throws Exception {
        var f = fixture(false);
        var other = fork(f.requester());
        var endpoint = base(f) + "/notifications/preferences";
        ok(
                f.requester()
                        .write(
                                "PUT",
                                endpoint,
                                Map.of(
                                        "inAppEnabled",
                                        true,
                                        "emailEnabled",
                                        false,
                                        "expectedVersion",
                                        0),
                                null),
                200);
        int audits = count("audit_logs", f.org());
        var pair =
                blockedRace(
                        "SELECT membership_id FROM notification_preferences WHERE organization_id=?"
                                + " AND membership_id=? FOR UPDATE",
                        List.of(UUID.fromString(f.org()), UUID.fromString(f.requesterMember())),
                        "UPDATE notification_preferences%",
                        () ->
                                f.requester()
                                        .write(
                                                "PUT",
                                                endpoint,
                                                Map.of(
                                                        "inAppEnabled",
                                                        false,
                                                        "emailEnabled",
                                                        false,
                                                        "expectedVersion",
                                                        1),
                                                null),
                        () ->
                                other.write(
                                        "PUT",
                                        endpoint,
                                        Map.of(
                                                "inAppEnabled",
                                                true,
                                                "emailEnabled",
                                                true,
                                                "expectedVersion",
                                                1),
                                        null));
        assertThat(pair.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(200, 409);
        var winner = pair.stream().filter(x -> x.statusCode() == 200).findFirst().orElseThrow();
        var done = ok(f.requester().get(endpoint), 200);
        assertThat(done).isEqualTo(json(winner));
        assertThat(done.get("version").asInt()).isEqualTo(2);
        assertThat(count("audit_logs", f.org())).isEqualTo(audits + 1);
        assertThat(count("outbox_events", f.org())).isZero();
    }
}
