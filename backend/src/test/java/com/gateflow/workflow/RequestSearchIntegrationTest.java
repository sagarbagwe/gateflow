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
class RequestSearchIntegrationTest {
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
                    .withDatabaseName("gateflow_search_test")
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
        var grants = List.of("REQUEST_APPROVE", "WORKFLOW_VIEW");
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

    JsonNode page(Actor actor, Fixture f, String query) throws Exception {
        return ok(actor.get(base(f) + "/requests" + query), 200);
    }

    List<String> ids(JsonNode page) {
        var result = new ArrayList<String>();
        page.get("items").forEach(n -> result.add(n.get("id").asText()));
        return result;
    }

    String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    String draft(Fixture f, String member, String title, String description, String created) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " requests(id,organization_id,workflow_definition_id,requester_membership_id,title,description,request_type,request_data,created_at)"
                    + " VALUES(?,?,?,?,?,?,'SOFTWARE_ACCESS','{\"softwareName\":\"Fixture\"}'::jsonb,?::timestamptz)",
                id,
                UUID.fromString(f.org()),
                UUID.fromString(f.definition()),
                UUID.fromString(member),
                title,
                description,
                created);
        return id.toString();
    }

    @Test
    void ownedVisibleAndInboxUseTheSameDetailVisibility() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        String id = r.get("id").asText();
        assertThat(ids(page(f.requester(), f, ""))).containsExactly(id);
        assertThat(ids(page(f.first(), f, "/inbox"))).containsExactly(id);
        assertThat(ids(page(f.second(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(f.admin(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(f.admin(), f, ""))).containsExactly(id);
    }

    @Test
    void reviewerOwnScopeAndReadOnlyInboxAreForbidden() throws Exception {
        var f = fixture(false);
        problem(f.first().get(base(f) + "/requests?scope=OWN"), 403, "PERMISSION_DENIED");
        problem(f.requester().get(base(f) + "/requests/inbox"), 403, "PERMISSION_DENIED");
        var viewer = actor();
        enroll(f.admin(), f.org(), viewer, builtin(f.admin(), f.org(), "VIEWER"));
        problem(viewer.get(base(f) + "/requests/inbox"), 403, "PERMISSION_DENIED");
    }

    @Test
    void otherMembersCannotReadOwnedRequestsOrCrossTenants() throws Exception {
        var f = fixture(false);
        var outsider = actor();
        String om = enroll(f.admin(), f.org(), outsider, builtin(f.admin(), f.org(), "MEMBER"));
        var r = submit(f);
        draft(f, om, "Other member", "", "2020-01-01T00:00:00Z");
        assertThat(ids(page(f.requester(), f, ""))).containsExactly(r.get("id").asText());
        assertThat(ids(page(outsider, f, ""))).hasSize(1).doesNotContain(r.get("id").asText());
        problem(outsider.get(path(f, r)), 404, "REQUEST_NOT_FOUND");
        var external = actor();
        problem(external.get(base(f) + "/requests"), 404, "ORGANIZATION_NOT_FOUND");
        var other = fixture(false);
        assertThat(ids(page(other.admin(), other, "?workflowId=" + f.definition()))).isEmpty();
    }

    @Test
    void unauthenticatedCannotSearch() throws Exception {
        var f = fixture(false);
        problem(new Actor().get(base(f) + "/requests"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void fullTextSearchHasLiteralAndSemanticsAndSearchesDescription() throws Exception {
        var f = fixture(false);
        String id =
                draft(
                        f,
                        f.requesterMember(),
                        "Helios laptop",
                        "Engineering equipment café 東京",
                        "2020-01-01T00:00:00Z");
        for (String query : List.of("HELIOS laptop", "engineering equipment", "café", "東京"))
            assertThat(ids(page(f.admin(), f, "?q=" + enc(query)))).containsExactly(id);
        for (String query : List.of("helios missing", "helio", "' OR 1=1 --", "!!!"))
            assertThat(ids(page(f.admin(), f, "?q=" + enc(query)))).isEmpty();
        assertThat(ids(page(f.admin(), f, "?q=" + enc("  ")))).containsExactly(id);
    }

    @Test
    void summaryExcludesPayloadAndHydratesOnlyActiveStep() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var p = page(f.admin(), f, "");
        var item = p.get("items").get(0);
        assertThat(item.get("activeStep").get("name").asText()).isEqualTo("Manager review");
        assertThat(item.has("details")).isFalse();
        assertThat(item.has("description")).isFalse();
        assertThat(item.has("steps")).isFalse();
        assertThat(item.has("decisions")).isFalse();
        assertThat(item.has("email")).isFalse();
        assertThat(p.has("total")).isFalse();
        assertThat(p.get("createdAt")).isNull();
        assertThat(item.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void filtersCombineStatusTypeWorkflowAndDateBounds() throws Exception {
        var f = fixture(false);
        String a = draft(f, f.requesterMember(), "Before", "", "2019-12-31T23:59:59Z");
        String b = draft(f, f.requesterMember(), "Inside", "", "2020-01-01T00:00:00Z");
        draft(f, f.requesterMember(), "After", "", "2020-01-02T00:00:00Z");
        var r = submit(f);
        String query =
                "?status=DRAFT&status=IN_REVIEW&type=SOFTWARE_ACCESS&workflowId="
                        + f.definition()
                        + "&createdFrom="
                        + enc("2020-01-01T05:30:00+05:30")
                        + "&createdBefore="
                        + enc("2020-01-02T00:00:00Z");
        assertThat(ids(page(f.admin(), f, query))).containsExactly(b);
        assertThat(ids(page(f.admin(), f, "?type=PURCHASE&status=IN_REVIEW")))
                .containsExactly(r.get("id").asText());
        assertThat(ids(page(f.admin(), f, "?status=APPROVED&status=REJECTED"))).isEmpty();
    }

    @Test
    void tiedKeysetPagesHaveNoDuplicatesOrGapsInBothDirections() throws Exception {
        var f = fixture(false);
        var expected = new ArrayList<String>();
        for (int i = 0; i < 7; i++)
            expected.add(draft(f, f.requesterMember(), "Tie " + i, "", "2020-01-01T00:00:00Z"));
        expected.sort(String::compareTo);
        for (String sort : List.of("CREATED_ASC", "CREATED_DESC")) {
            var wanted = new ArrayList<>(expected);
            if (sort.endsWith("DESC")) Collections.reverse(wanted);
            var seen = new ArrayList<String>();
            String cursor = null;
            int pages = 0;
            do {
                var p =
                        page(
                                f.admin(),
                                f,
                                "?limit=2&sort="
                                        + sort
                                        + (cursor == null ? "" : "&cursor=" + cursor));
                seen.addAll(ids(p));
                cursor = p.get("nextCursor").isNull() ? null : p.get("nextCursor").asText();
                assertThat(p.get("hasMore").asBoolean()).isEqualTo(cursor != null);
                assertThat(++pages).isLessThan(6);
            } while (cursor != null);
            assertThat(seen).containsExactlyElementsOf(wanted);
        }
    }

    @Test
    void offsetIsBoundedAndSupportsDeterministicOrder() throws Exception {
        var f = fixture(false);
        var expected = new ArrayList<String>();
        for (int i = 0; i < 4; i++)
            expected.add(draft(f, f.requesterMember(), "Tie " + i, "", "2020-01-01T00:00:00Z"));
        expected.sort(String::compareTo);
        var p = page(f.admin(), f, "?pagination=OFFSET&sort=CREATED_ASC&limit=2&offset=1");
        assertThat(ids(p)).containsExactlyElementsOf(expected.subList(1, 3));
        assertThat(p.get("hasMore").asBoolean()).isTrue();
        assertThat(p.get("nextCursor").isNull()).isTrue();
        assertThat(p.get("offset").asInt()).isEqualTo(1);
        assertThat(ids(page(f.admin(), f, "?pagination=OFFSET&offset=10000"))).isEmpty();
    }

    @Test
    void newSubmissionsAfterCutoffAreExcludedFromContinuation() throws Exception {
        var f = fixture(false);
        for (int i = 0; i < 3; i++)
            draft(f, f.requesterMember(), "Older " + i, "", "2020-01-01T00:00:00Z");
        var first = page(f.admin(), f, "?limit=1&sort=CREATED_ASC");
        Thread.sleep(5);
        var newRequest = submit(f);
        var next =
                page(
                        f.admin(),
                        f,
                        "?limit=100&sort=CREATED_ASC&cursor=" + first.get("nextCursor").asText());
        assertThat(ids(next))
                .hasSize(2)
                .doesNotContain(newRequest.get("id").asText(), ids(first).getFirst());
    }

    @Test
    void cursorsRejectOtherActorScopeFilterSortAndMalformedInputs() throws Exception {
        var f = fixture(false);
        for (int i = 0; i < 3; i++)
            draft(f, f.requesterMember(), "Cursor", "", "2020-01-01T00:00:00Z");
        var p = page(f.admin(), f, "?limit=1");
        String c = p.get("nextCursor").asText();
        for (String extra :
                List.of(
                        "&scope=OWN",
                        "&sort=CREATED_ASC",
                        "&q=Cursor",
                        "&status=DRAFT",
                        "&type=SOFTWARE_ACCESS"))
            problem(
                    f.admin().get(base(f) + "/requests?cursor=" + c + extra),
                    400,
                    "INVALID_CURSOR");
        problem(f.requester().get(base(f) + "/requests?cursor=" + c), 400, "INVALID_CURSOR");
        for (String token : List.of("abc", "e30", "bnVsbA"))
            problem(f.admin().get(base(f) + "/requests?cursor=" + token), 400, "INVALID_CURSOR");
    }

    @Test
    void pageSizeAndEquivalentNormalizedFiltersCanChange() throws Exception {
        var f = fixture(false);
        for (int i = 0; i < 4; i++)
            draft(f, f.requesterMember(), "Normalization", "", "2020-01-01T00:00:00Z");
        String filter = "&status=DRAFT&status=IN_REVIEW&createdFrom=" + enc("2019-01-01T00:00:00Z");
        var p = page(f.admin(), f, "?limit=1" + filter);
        var next =
                page(
                        f.admin(),
                        f,
                        "?limit=100&status=IN_REVIEW&status=DRAFT&createdFrom="
                                + enc("2019-01-01T05:30:00+05:30")
                                + "&cursor="
                                + p.get("nextCursor").asText());
        assertThat(ids(next)).hasSize(3);
    }

    @Test
    void invalidParametersFailBeforeQueryingAndNeverEchoInput() throws Exception {
        var f = fixture(false);
        for (String query :
                List.of(
                        "limit=0",
                        "limit=101",
                        "limit=no",
                        "offset=0",
                        "pagination=OFFSET&offset=-1",
                        "pagination=OFFSET&offset=10001",
                        "pagination=OFFSET&cursor=abc",
                        "limit=2&limit=3",
                        "sort=" + enc("created_at; DROP TABLE requests"),
                        "scope=ALL",
                        "status=NOPE",
                        "status=DRAFT&status=DRAFT&status=DRAFT&status=DRAFT&status=DRAFT&status=DRAFT",
                        "type=NOPE",
                        "workflowId=bad",
                        "createdFrom=2020-01-01",
                        "createdFrom="
                                + enc("2020-01-02T00:00:00Z")
                                + "&createdBefore="
                                + enc("2020-01-01T00:00:00Z"),
                        "secret=bad",
                        "q=" + "x".repeat(201),
                        "q=%00",
                        "cursor=" + "a".repeat(769)))
            problem(f.admin().get(base(f) + "/requests?" + query), 400, "INVALID_SEARCH_QUERY");
        problem(f.first().get(base(f) + "/requests/inbox?scope=OWN"), 400, "INVALID_SEARCH_QUERY");
    }

    @Test
    void approvalMovesInboxAndRetainsDecisionHistoryWithoutReadAll() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var next = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        assertThat(ids(page(f.first(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(f.first(), f, ""))).containsExactly(r.get("id").asText());
        assertThat(ids(page(f.second(), f, "/inbox"))).containsExactly(r.get("id").asText());
        ok(decide(f.second(), f, next, 1, 1, "APPROVE", key()), 200);
        assertThat(ids(page(f.second(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(f.second(), f, ""))).containsExactly(r.get("id").asText());
    }

    @Test
    void revokedReviewerPermissionIsRecheckedOnNextPage() throws Exception {
        var f = fixture(false);
        submit(f);
        submit(f);
        var p = page(f.first(), f, "?limit=1");
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/roles/" + f.roleA() + "/permissions",
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "permissions",
                                        List.of("REQUEST_VIEW_OWN", "WORKFLOW_VIEW")),
                                null),
                200);
        assertThat(ids(page(f.first(), f, "?limit=1&cursor=" + p.get("nextCursor").asText())))
                .isEmpty();
        problem(f.first().get(base(f) + "/requests/inbox"), 403, "PERMISSION_DENIED");
    }

    @Test
    void requiredRoleLossHidesActiveAssignmentEvenWithApprovalPermission() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        String alternate =
                custom(
                        f.admin(),
                        f.org(),
                        "OTHER_REVIEW",
                        List.of("REQUEST_APPROVE", "WORKFLOW_VIEW"));
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/memberships/" + f.firstMember() + "/roles",
                                Map.of("expectedVersion", 0, "roleIds", List.of(alternate)),
                                null),
                200);
        assertThat(ids(page(f.first(), f, ""))).isEmpty();
        assertThat(ids(page(f.first(), f, "/inbox"))).isEmpty();
        problem(f.first().get(path(f, r)), 404, "REQUEST_NOT_FOUND");
    }

    @Test
    void reassignmentRefreshesReviewerInbox() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        var replacement = actor();
        String member = enroll(f.admin(), f.org(), replacement, f.roleA());
        ok(
                f.admin()
                        .write(
                                "POST",
                                base(f)
                                        + "/requests/"
                                        + r.get("id").asText()
                                        + "/steps/"
                                        + r.get("steps").get(0).get("id").asText()
                                        + "/reassign",
                                Map.of("expectedVersion", 0, "membershipId", member),
                                key()),
                200);
        assertThat(ids(page(f.first(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(replacement, f, "/inbox"))).containsExactly(r.get("id").asText());
    }

    @Test
    void maxLimitAndEmptyPageMetadataAreHonest() throws Exception {
        var f = fixture(false);
        var p = page(f.admin(), f, "?limit=100");
        assertThat(ids(p)).isEmpty();
        assertThat(p.get("hasMore").asBoolean()).isFalse();
        assertThat(p.get("nextCursor").isNull()).isTrue();
        assertThat(p.get("offset").isNull()).isTrue();
    }

    @Test
    void limitCapsActualRowsAndUsesSentinelWithoutCount() throws Exception {
        var f = fixture(false);
        for (int i = 0; i < 101; i++)
            draft(f, f.requesterMember(), "Bounded " + i, "", "2020-01-01T00:00:00Z");
        var p = page(f.admin(), f, "?limit=100");
        assertThat(ids(p)).hasSize(100);
        assertThat(p.get("hasMore").asBoolean()).isTrue();
        var next = page(f.admin(), f, "?limit=100&cursor=" + p.get("nextCursor").asText());
        assertThat(ids(next)).hasSize(1).doesNotContainAnyElementsOf(ids(p));
        assertThat(next.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void inboxCursorTraversesOnlyActiveEligibleAssignments() throws Exception {
        var f = fixture(true);
        var expected = new HashSet<String>();
        for (int i = 0; i < 3; i++) expected.add(submit(f).get("id").asText());
        var first = page(f.first(), f, "/inbox?limit=1");
        var rest =
                page(
                        f.first(),
                        f,
                        "?scope=INBOX&limit=100&cursor=" + first.get("nextCursor").asText());
        var seen = new ArrayList<>(ids(first));
        seen.addAll(ids(rest));
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(ids(page(f.second(), f, "/inbox"))).isEmpty();
    }

    @Test
    void inactiveMembershipRejectsExistingContinuation() throws Exception {
        var f = fixture(false);
        for (int i = 0; i < 3; i++)
            draft(f, f.requesterMember(), "Suspend", "", "2020-01-01T00:00:00Z");
        var first = page(f.requester(), f, "?limit=1");
        jdbc.update(
                "UPDATE memberships SET status='SUSPENDED' WHERE id=?",
                UUID.fromString(f.requesterMember()));
        problem(
                f.requester().get(base(f) + "/requests?cursor=" + first.get("nextCursor").asText()),
                404,
                "ORGANIZATION_NOT_FOUND");
    }

    @Test
    void multipleDecisionsBySameReviewerDoNotDuplicateHistoryRows() throws Exception {
        var f = fixture(false);
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(
                                                        step("Review one", f.roleA(), always()),
                                                        step("Review two", f.roleA(), always()))),
                                        null),
                        201);
        v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f)
                                                + "/workflows/"
                                                + f.definition()
                                                + "/versions/"
                                                + v.get("id").asText()
                                                + "/publish",
                                        Map.of("expectedVersion", 0),
                                        null),
                        200);
        var payload = payload(f);
        payload.put("workflowVersionId", v.get("id").asText());
        var r = ok(f.requester().write("POST", base(f) + "/requests", payload, key()), 201);
        var next = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        ok(decide(f.first(), f, next, 1, 1, "APPROVE", key()), 200);
        assertThat(ids(page(f.first(), f, "?status=APPROVED")))
                .containsExactly(r.get("id").asText());
        assertThat(ids(page(f.first(), f, "/inbox"))).isEmpty();
    }

    @Test
    void withdrawalUpdatesStatusAndRemovesPendingInbox() throws Exception {
        var f = fixture(false);
        var r = submit(f);
        ok(withdraw(f, r, 0, key()), 200);
        assertThat(ids(page(f.first(), f, "/inbox"))).isEmpty();
        assertThat(ids(page(f.requester(), f, "?status=WITHDRAWN")))
                .containsExactly(r.get("id").asText());
        assertThat(ids(page(f.requester(), f, "?status=IN_REVIEW"))).isEmpty();
    }
}
