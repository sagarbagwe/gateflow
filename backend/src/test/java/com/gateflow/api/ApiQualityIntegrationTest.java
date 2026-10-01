package com.gateflow.api;

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
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true",
            "gateflow.auth.cookie-secure=false",
            "gateflow.auth.signup-limit=1000",
            "gateflow.auth.login-limit=1000"
        })
class ApiQualityIntegrationTest {
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
                    .withDatabaseName("gateflow_api_quality_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

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

    JsonNode specification(Actor a) throws Exception {
        return ok(a.get("/v3/api-docs/gateflow"), 200);
    }

    HttpResponse<String> raw(
            Actor a,
            String method,
            String path,
            String body,
            String content,
            String accept,
            boolean csrf)
            throws Exception {
        var b = HttpRequest.newBuilder(uri(path)).timeout(java.time.Duration.ofSeconds(15));
        if (content != null) b.header("Content-Type", content);
        if (accept != null) b.header("Accept", accept);
        if (csrf) {
            var t = json(a.get("/api/v1/auth/csrf"));
            b.header(t.get("headerName").asText(), t.get("token").asText());
        }
        return a.http.send(
                b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void safeProblem(HttpResponse<String> x, int status, String code) throws Exception {
        problem(x, status, code);
        assertThat(x.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        var p = json(x);
        assertThat(p.get("type").asText()).isEqualTo("about:blank");
        assertThat(p.get("status").asInt()).isEqualTo(status);
        assertThat(p.get("instance").asText()).doesNotContain("?");
    }

    @Test
    void schemaCoversExactlyAllImplementedVersionedOperations() throws Exception {
        var doc = specification(actor());
        assertThat(doc.get("openapi").asText()).startsWith("3.0.");
        var expected = new TreeSet<String>();
        mappings.getHandlerMethods()
                .forEach(
                        (mapping, handler) -> {
                            if (mapping.getPathPatternsCondition() != null)
                                for (var p : mapping.getPathPatternsCondition().getPatterns())
                                    if (p.getPatternString().startsWith("/api/v1/"))
                                        for (var m : mapping.getMethodsCondition().getMethods())
                                            expected.add(
                                                    m.name().toLowerCase()
                                                            + " "
                                                            + p.getPatternString());
                        });
        var actual = new TreeSet<String>();
        var ids = new HashSet<String>();
        doc.get("paths")
                .properties()
                .forEach(
                        p ->
                                p.getValue()
                                        .properties()
                                        .forEach(
                                                m -> {
                                                    if (Set.of(
                                                                    "get", "post", "put", "patch",
                                                                    "delete")
                                                            .contains(m.getKey())) {
                                                        actual.add(m.getKey() + " " + p.getKey());
                                                        assertThat(
                                                                        ids.add(
                                                                                m.getValue()
                                                                                        .get(
                                                                                                "operationId")
                                                                                        .asText()))
                                                                .isTrue();
                                                    }
                                                }));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.size()).isGreaterThan(35);
        assertThat(doc.get("servers").get(0).get("url").asText()).isEqualTo("/");
        assertThat(doc.toString())
                .doesNotContain("password_hash", "token_hash", "/error", "bearerFormat");
        verifyRefs(doc, doc);
    }

    void verifyRefs(JsonNode n, JsonNode root) {
        if (n.isObject() && n.has("$ref")) {
            String ref = n.get("$ref").asText();
            assertThat(ref).startsWith("#/components/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        if (n.isContainerNode()) n.forEach(x -> verifyRefs(x, root));
    }

    @Test
    void securityModelsCookieAndCsrfAsAndNotAlternativeCredentials() throws Exception {
        var doc = specification(actor());
        assertThat(doc.at("/components/securitySchemes/SessionCookie/name").asText())
                .isEqualTo("GATEFLOW_SESSION");
        var op = doc.get("paths").get("/api/v1/organizations/{orgId}/requests").get("post");
        assertThat(op.get("security").size()).isEqualTo(1);
        assertThat(op.get("security").get(0).has("SessionCookie")).isTrue();
        assertThat(op.get("security").get(0).has("CsrfHeader")).isTrue();
        var login = doc.get("paths").get("/api/v1/auth/login").get("post");
        assertThat(login.get("security").get(0).has("SessionCookie")).isFalse();
        assertThat(login.get("security").get(0).has("CsrfHeader")).isTrue();
        assertThat(doc.at("/components/schemas/Signup/properties/password/writeOnly").asBoolean())
                .isTrue();
        assertThat(op.get("responses").get("201").get("headers").has("Location")).isTrue();
        assertThat(op.get("responses").get("200").get("content").toString())
                .contains("RequestView")
                .doesNotContain("ApiProblem");
        assertThat(op.get("responses").get("400").get("content").toString())
                .contains("application/problem+json", "ApiProblem");
    }

    @Test
    void searchMapKeysAreExplicitAndBounded() throws Exception {
        var doc = specification(actor());
        var ps =
                doc.get("paths")
                        .get("/api/v1/organizations/{orgId}/requests")
                        .get("get")
                        .get("parameters");
        var keys = new HashSet<String>();
        for (var p : ps) {
            keys.add(p.get("name").asText());
            if (p.get("name").asText().equals("limit"))
                assertThat(p.at("/schema/maximum").asInt()).isEqualTo(100);
            if (p.get("name").asText().equals("status")) {
                assertThat(p.get("explode").asBoolean()).isTrue();
                assertThat(p.at("/schema/type").asText()).isEqualTo("array");
            }
        }
        assertThat(keys)
                .contains(
                        "orgId",
                        "scope",
                        "sort",
                        "pagination",
                        "limit",
                        "offset",
                        "q",
                        "status",
                        "type",
                        "workflowId",
                        "createdFrom",
                        "createdBefore",
                        "cursor");
        assertThat(keys).doesNotContain("query", "user", "principal");
    }

    @Test
    void docsAndSwaggerAreSessionProtectedAndAssetsAreLocal() throws Exception {
        var anonymous = new Actor();
        safeProblem(anonymous.get("/v3/api-docs/gateflow"), 401, "AUTHENTICATION_REQUIRED");
        safeProblem(anonymous.get("/swagger-ui/index.html"), 401, "AUTHENTICATION_REQUIRED");
        var a = actor();
        var html = a.get("/swagger-ui/index.html");
        assertThat(html.statusCode()).isEqualTo(200);
        assertThat(html.body())
                .contains("swagger-ui-bundle.js")
                .doesNotContain("petstore.swagger.io");
        var config = ok(a.get("/v3/api-docs/swagger-config"), 200);
        assertThat(config.path("persistAuthorization").asBoolean()).isFalse();
        var initializer = a.get("/swagger-ui/swagger-initializer.js");
        assertThat(initializer.statusCode()).isEqualTo(200);
        assertThat(initializer.body())
                .contains("requestInterceptor", "XSRF-TOKEN", "X-XSRF-TOKEN", "isSameOrigin");
        assertThat(config.path("validatorUrl").asText()).isEmpty();
    }

    @Test
    void missingSessionAndCsrfRemainSanitizedAndCorrelated() throws Exception {
        var a = new Actor();
        safeProblem(a.get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        safeProblem(
                raw(a, "POST", "/api/v1/auth/login", "{}", "application/json", null, false),
                403,
                "ACCESS_DENIED");
    }

    @Test
    void duplicateAndTrailingJsonAreRejectedWithoutEchoingValues() throws Exception {
        var a = actor();
        for (String body :
                List.of(
                        "{\"name\":\"a\",\"name\":\"secret-value\",\"slug\":\"safe-slug\"}",
                        "{\"name\":\"a\",\"slug\":\"safe-slug\"} {}")) {
            var x = raw(a, "POST", "/api/v1/organizations", body, "application/json", null, true);
            safeProblem(x, 400, "INVALID_JSON");
            assertThat(x.body()).doesNotContain("secret-value");
        }
    }

    @Test
    void malformedUuidUnknownFieldsAndValidationHaveSafeErrors() throws Exception {
        var a = actor();
        safeProblem(a.get("/api/v1/organizations/not-a-uuid"), 400, "INVALID_PARAMETER");
        var x =
                raw(
                        a,
                        "POST",
                        "/api/v1/organizations",
                        "{\"name\":\"x\",\"slug\":\"okay\",\"role\":\"ADMIN\"}",
                        "application/json",
                        null,
                        true);
        safeProblem(x, 400, "INVALID_JSON");
        var invalid =
                raw(
                        a,
                        "POST",
                        "/api/v1/organizations",
                        "{\"name\":\"\",\"slug\":\"bad slug\"}",
                        "application/json",
                        null,
                        true);
        safeProblem(invalid, 400, "VALIDATION_FAILED");
        assertThat(json(invalid).get("errors").isArray()).isTrue();
        assertThat(invalid.body()).doesNotContain("bad slug");
    }

    @Test
    void unsupportedMethodMediaAndAcceptPreserveHttpSemantics() throws Exception {
        var a = actor();
        var method = raw(a, "DELETE", "/api/v1/organizations", null, null, null, true);
        safeProblem(method, 405, "HTTP_ERROR");
        assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("GET", "POST");
        safeProblem(
                raw(a, "POST", "/api/v1/organizations", "x", "text/plain", null, true),
                415,
                "HTTP_ERROR");
        safeProblem(
                raw(a, "GET", "/api/v1/auth/me", null, null, "image/png", false),
                406,
                "HTTP_ERROR");
    }

    @Test
    void oversizedBodyFailsBeforeJsonParsing() throws Exception {
        var a = actor();
        safeProblem(
                raw(
                        a,
                        "POST",
                        "/api/v1/organizations",
                        "x".repeat(262145),
                        "application/json",
                        null,
                        true),
                413,
                "PAYLOAD_TOO_LARGE");
    }

    @Test
    void createdResourcesExposeRelativeGetLocationsAndReplayDoesNotCreateAgain() throws Exception {
        var f = fixture(false);
        var k = key();
        var response = f.requester().write("POST", base(f) + "/requests", payload(f), k);
        var created = ok(response, 201);
        var location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo(base(f) + "/requests/" + created.get("id").asText());
        assertThat(response.headers().firstValue("Idempotent-Replay").orElseThrow())
                .isEqualTo("false");
        ok(f.requester().get(location), 200);
        var replay = f.requester().write("POST", base(f) + "/requests", payload(f), k);
        ok(replay, 200);
        assertThat(replay.headers().firstValue("Idempotent-Replay").orElseThrow())
                .isEqualTo("true");
        assertThat(replay.headers().firstValue("Location")).isEmpty();
        assertThat(count("requests", f.org())).isEqualTo(1);
    }

    @Test
    void conflictNotFoundAndPaginationKeepExistingContracts() throws Exception {
        var f = fixture(false);
        safeProblem(f.admin().get(base(f) + "/roles?limit=101"), 400, "INVALID_PARAMETER");
        safeProblem(
                f.admin().get(base(f) + "/requests?unexpected=true"), 400, "INVALID_SEARCH_QUERY");
        safeProblem(f.admin().get(base(f) + "/requests/" + key()), 404, "REQUEST_NOT_FOUND");
        var r = submit(f);
        var done = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        assertThat(done.get("state").asText()).isEqualTo("APPROVED");
        safeProblem(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 409, "REQUEST_NOT_IN_REVIEW");
        var page = ok(f.admin().get(base(f) + "/audit-logs?limit=1"), 200);
        assertThat(page.has("nextOffset")).isFalse();
        assertThat(page.has("hasMore")).isTrue();
    }
}
