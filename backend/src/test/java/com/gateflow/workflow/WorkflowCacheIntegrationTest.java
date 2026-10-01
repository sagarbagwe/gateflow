package com.gateflow.workflow;

import static com.gateflow.cache.RedisPolicyCache.key;
import static com.gateflow.workflow.WorkflowDtos.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
            "gateflow.cache.workflow.enabled=true",
            "gateflow.cache.workflow.ttl=PT2S",
            "gateflow.cache.workflow.failure-cooldown=PT1S",
            "gateflow.auth.signup-limit=1000",
            "gateflow.auth.login-limit=1000"
        })
class WorkflowCacheIntegrationTest {
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
                    .withDatabaseName("gateflow_cache_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final org.testcontainers.containers.GenericContainer<?> REDIS =
            new org.testcontainers.containers.GenericContainer<>(
                            DockerImageName.parse(
                                    "redis:7.4-bookworm@sha256:c6eabf748fc7a61dbb5a705c78bcf3d6377b1127a97d0ce965c11c44ba46896f"))
                    .withExposedPorts(6379)
                    .withCommand(
                            "redis-server",
                            "--requirepass",
                            REDIS_PASSWORD,
                            "--save",
                            "",
                            "--appendonly",
                            "no",
                            "--maxmemory",
                            "32mb",
                            "--maxmemory-policy",
                            "allkeys-lru");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource datasource;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired com.gateflow.cache.WorkflowCacheCodec codec;
    @Autowired com.gateflow.cache.WorkflowCacheProperties cacheProperties;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    WorkflowRepository repository;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.gateflow.cache.RedisPolicyCache cache;

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

    String commandKey() {
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
                                        Map.of(
                                                "name",
                                                "Workflow tenant",
                                                "slug",
                                                "wf-" + commandKey()),
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
        return ok(
                f.requester().write("POST", base(f) + "/requests", payload(f), commandKey()), 201);
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

    String policyPath(Fixture f) {
        return base(f)
                + "/workflows/"
                + f.definition()
                + "/versions/"
                + f.version().get("id").asText();
    }

    String cacheKey(Fixture f) {
        return key(
                UUID.fromString(f.org()),
                UUID.fromString(f.definition()),
                UUID.fromString(f.version().get("id").asText()));
    }

    JsonNode read(Fixture f) throws Exception {
        return ok(f.admin().get(policyPath(f)), 200);
    }

    void clearSpies() {
        clearInvocations(repository, cache);
    }

    @Test
    void coldReadFillsAndWarmReadSkipsStepsButRechecksMetadata() throws Exception {
        var f = fixture(true);
        assertThat(redis.hasKey(cacheKey(f))).isFalse();
        clearSpies();
        var first = read(f);
        verify(repository, times(1)).withSteps(any(), any());
        verify(cache, times(1)).put(eq(cacheKey(f)), anyString());
        clearSpies();
        assertThat(read(f)).isEqualTo(first);
        verify(repository, times(1))
                .versionHeader(
                        UUID.fromString(f.org()),
                        UUID.fromString(f.definition()),
                        UUID.fromString(f.version().get("id").asText()),
                        false);
        verify(repository, never()).withSteps(any(), any());
        verify(cache, never()).put(anyString(), anyString());
        assertThat(redis.getExpire(cacheKey(f), TimeUnit.MILLISECONDS)).isBetween(1L, 2200L);
    }

    @Test
    void warmReadsDoNotRefreshTtlAndExpiredValuesRebuild() throws Exception {
        var f = fixture(false);
        var first = read(f);
        Long before = redis.getExpire(cacheKey(f), TimeUnit.MILLISECONDS);
        Thread.sleep(60);
        assertThat(read(f)).isEqualTo(first);
        assertThat(redis.getExpire(cacheKey(f), TimeUnit.MILLISECONDS)).isLessThan(before);
        redis.expire(cacheKey(f), java.time.Duration.ofMillis(30));
        for (int i = 0; i < 100 && Boolean.TRUE.equals(redis.hasKey(cacheKey(f))); i++)
            Thread.sleep(10);
        assertThat(redis.hasKey(cacheKey(f))).isFalse();
        clearSpies();
        assertThat(read(f)).isEqualTo(first);
        verify(repository).withSteps(any(), any());
        assertThat(redis.getExpire(cacheKey(f), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void draftsNeverUseRedisAndEditsAreImmediatelyVisible() throws Exception {
        var f = fixture(false);
        var draft =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("Draft one", f.roleA(), always()))),
                                        null),
                        201);
        String path = vpath(f, draft.get("id").asText());
        String key =
                key(
                        UUID.fromString(f.org()),
                        UUID.fromString(f.definition()),
                        UUID.fromString(draft.get("id").asText()));
        redis.opsForValue().set(key, "untrusted draft value");
        clearSpies();
        assertThat(ok(f.admin().get(path), 200).get("steps").get(0).get("name").asText())
                .isEqualTo("Draft one");
        verify(cache, never()).get(anyString());
        ok(
                f.admin()
                        .write(
                                "PUT",
                                path,
                                Map.of(
                                        "expectedVersion",
                                        0,
                                        "steps",
                                        List.of(step("Draft two", f.roleA(), always()))),
                                null),
                200);
        assertThat(ok(f.admin().get(path), 200).get("steps").get(0).get("name").asText())
                .isEqualTo("Draft two");
        assertThat(redis.opsForValue().get(key)).isEqualTo("untrusted draft value");
    }

    @Test
    void publicationUsesNewUuidAndDoesNotPopulateRedisInWriteTransaction() throws Exception {
        var f = fixture(false);
        var original = read(f);
        var v =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(step("New policy", f.roleA(), always()))),
                                        null),
                        201);
        var published =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        vpath(f, v.get("id").asText()) + "/publish",
                                        Map.of("expectedVersion", 0),
                                        null),
                        200);
        String newKey =
                key(
                        UUID.fromString(f.org()),
                        UUID.fromString(f.definition()),
                        UUID.fromString(v.get("id").asText()));
        assertThat(redis.hasKey(newKey)).isFalse();
        var latest = ok(f.admin().get(vpath(f, v.get("id").asText())), 200);
        assertThat(latest.get("steps").get(0).get("name").asText()).isEqualTo("New policy");
        assertThat(read(f)).isEqualTo(original);
        assertThat(newKey).isNotEqualTo(cacheKey(f));
    }

    @Test
    void rolledBackPublicationCannotPolluteCache() throws Exception {
        var f = fixture(false);
        var draft =
                ok(
                        f.admin()
                                .write(
                                        "POST",
                                        base(f) + "/workflows/" + f.definition() + "/versions",
                                        Map.of(
                                                "steps",
                                                List.of(
                                                        step(
                                                                "Rollback policy",
                                                                f.roleA(),
                                                                always()))),
                                        null),
                        201);
        String path = vpath(f, draft.get("id").asText());
        String key =
                key(
                        UUID.fromString(f.org()),
                        UUID.fromString(f.definition()),
                        UUID.fromString(draft.get("id").asText()));
        jdbc.execute(
                "CREATE FUNCTION fail_cache_publish_audit() RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN IF NEW.action='WORKFLOW_PUBLISHED' THEN RAISE EXCEPTION 'fixture"
                    + " audit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_cache_publish_audit BEFORE INSERT ON audit_logs FOR EACH ROW"
                    + " EXECUTE FUNCTION fail_cache_publish_audit()");
        try {
            problem(
                    f.admin().write("POST", path + "/publish", Map.of("expectedVersion", 0), null),
                    503,
                    "SERVICE_UNAVAILABLE");
        } finally {
            jdbc.execute("DROP TRIGGER fail_cache_publish_audit ON audit_logs");
            jdbc.execute("DROP FUNCTION fail_cache_publish_audit()");
        }
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(ok(f.admin().get(path), 200).get("status").asText()).isEqualTo("DRAFT");
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void archivedDefinitionCannotBeReadFromWarmCache() throws Exception {
        var f = fixture(false);
        read(f);
        jdbc.update(
                "UPDATE workflow_definitions SET archived_at=now() WHERE id=?",
                UUID.fromString(f.definition()));
        clearSpies();
        problem(f.admin().get(policyPath(f)), 404, "WORKFLOW_NOT_FOUND");
        verifyNoInteractions(cache);
    }

    @Test
    void revokedViewPermissionIsCheckedBeforeRedis() throws Exception {
        var f = fixture(false);
        var viewer = actor();
        String role = custom(f.admin(), f.org(), "POLICY_VIEWER", List.of("WORKFLOW_VIEW"));
        enroll(f.admin(), f.org(), viewer, role);
        ok(viewer.get(policyPath(f)), 200);
        ok(
                f.admin()
                        .write(
                                "PUT",
                                base(f) + "/roles/" + role + "/permissions",
                                Map.of("expectedVersion", 0, "permissions", List.of()),
                                null),
                200);
        clearSpies();
        problem(viewer.get(policyPath(f)), 403, "PERMISSION_DENIED");
        verifyNoInteractions(cache);
    }

    @Test
    void inactiveAndForeignMembershipsNeverReachCache() throws Exception {
        var f = fixture(false);
        read(f);
        jdbc.update(
                "UPDATE memberships SET status='SUSPENDED' WHERE id=?",
                UUID.fromString(f.requesterMember()));
        clearSpies();
        problem(f.requester().get(policyPath(f)), 404, "ORGANIZATION_NOT_FOUND");
        var outsider = actor();
        problem(outsider.get(policyPath(f)), 404, "ORGANIZATION_NOT_FOUND");
        verifyNoInteractions(cache);
    }

    @Test
    void foreignDefinitionAndVersionIdsFailDbScopeBeforeCache() throws Exception {
        var f = fixture(false);
        read(f);
        var other = fixture(false);
        clearSpies();
        String path =
                base(other)
                        + "/workflows/"
                        + f.definition()
                        + "/versions/"
                        + f.version().get("id").asText();
        problem(other.admin().get(path), 404, "WORKFLOW_NOT_FOUND");
        path =
                base(other)
                        + "/workflows/"
                        + other.definition()
                        + "/versions/"
                        + f.version().get("id").asText();
        problem(other.admin().get(path), 404, "WORKFLOW_VERSION_NOT_FOUND");
        verifyNoInteractions(cache);
    }

    @Test
    void malformedForeignEnvelopeOversizedAndWrongTypeValuesRebuild() throws Exception {
        var f = fixture(false);
        var expected = read(f);
        var view = mapper.treeToValue(expected, VersionView.class);
        String key = cacheKey(f);
        for (String bad :
                List.of(
                        "bad-json",
                        codec.encode(UUID.randomUUID(), view).orElseThrow(),
                        "x".repeat(66000))) {
            redis.opsForValue().set(key, bad);
            clearSpies();
            assertThat(read(f)).isEqualTo(expected);
            verify(repository).withSteps(any(), any());
            assertThat(codec.decode(redis.opsForValue().get(key), UUID.fromString(f.org()), view))
                    .contains(view);
        }
        redis.delete(key);
        redis.opsForList().leftPush(key, "wrong type");
        clearSpies();
        assertThat(read(f)).isEqualTo(expected);
        verify(repository).withSteps(any(), any());
        assertThat(redis.type(key))
                .isEqualTo(org.springframework.data.redis.connection.DataType.STRING);
    }

    @Test
    void commandsIgnoreStructurallyValidPoisonedDisplayCache() throws Exception {
        var f = fixture(false);
        var expected = read(f);
        var view = mapper.treeToValue(expected, VersionView.class);
        var original = view.steps().get(0);
        var poisoned =
                new VersionView(
                        view.id(),
                        view.workflowDefinitionId(),
                        view.versionNumber(),
                        view.status(),
                        view.version(),
                        view.publishedAt(),
                        List.of(
                                new WorkflowStep(
                                        original.id(),
                                        1,
                                        "Poisoned display name",
                                        UUID.randomUUID(),
                                        original.condition())));
        redis.opsForValue()
                .set(cacheKey(f), codec.encode(UUID.fromString(f.org()), poisoned).orElseThrow());
        clearSpies();
        var request = submit(f);
        assertThat(request.get("steps").get(0).get("name").asText()).isEqualTo("Manager review");
        assertThat(request.get("steps").get(0).get("assignedMembershipId").asText())
                .isEqualTo(f.firstMember());
        verifyNoInteractions(cache);
    }

    @Test
    void databaseFailureStillReturns503DespiteWarmCache() throws Exception {
        var f = fixture(false);
        read(f);
        clearSpies();
        doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "fixture source failure"))
                .when(repository)
                .versionHeader(
                        UUID.fromString(f.org()),
                        UUID.fromString(f.definition()),
                        UUID.fromString(f.version().get("id").asText()),
                        false);
        problem(f.admin().get(policyPath(f)), 503, "SERVICE_UNAVAILABLE");
        verifyNoInteractions(cache);
    }

    @Test
    void pausedRedisFallsBackThenRecoversWithoutRestartingApp() throws Exception {
        var f = fixture(false);
        var expected = read(f);
        redis.delete(cacheKey(f));
        clearSpies();
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(read(f)).isEqualTo(expected);
            assertThat(read(f)).isEqualTo(expected);
            verify(repository, times(2)).withSteps(any(), any());
        } finally {
            docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        Thread.sleep(1100);
        for (int i = 0; i < 50; i++) {
            read(f);
            if (Boolean.TRUE.equals(redis.hasKey(cacheKey(f)))) break;
            Thread.sleep(100);
        }
        assertThat(redis.hasKey(cacheKey(f))).isTrue();
        clearSpies();
        assertThat(read(f)).isEqualTo(expected);
        verify(repository, never()).withSteps(any(), any());
    }

    @Test
    void invalidRedisCredentialsAreCacheFailureNotBusinessFailure() throws Exception {
        var f = fixture(false);
        var config =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                        REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setPassword("wrong-test-only");
        var client =
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
                        .builder()
                        .commandTimeout(java.time.Duration.ofMillis(250))
                        .build();
        var factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                        config, client);
        factory.afterPropertiesSet();
        factory.start();
        try {
            var template = new org.springframework.data.redis.core.StringRedisTemplate(factory);
            var bad = new com.gateflow.cache.RedisPolicyCache(template, cacheProperties);
            var reader = new PublishedWorkflowReader(repository, bad, codec, cacheProperties);
            assertThat(
                            reader.read(
                                            UUID.fromString(f.org()),
                                            UUID.fromString(f.definition()),
                                            UUID.fromString(f.version().get("id").asText()))
                                    .status())
                    .isEqualTo(VersionStatus.PUBLISHED);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void concurrentColdFillsAreSafeWithoutDistributedLocks() throws Exception {
        var f = fixture(true);
        redis.delete(cacheKey(f));
        var bodies = new ArrayList<JsonNode>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            var pending = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 6; i++)
                pending.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return f.admin().get(policyPath(f));
                                }));
            start.countDown();
            for (var task : pending) bodies.add(ok(task.get(10, TimeUnit.SECONDS), 200));
        }
        assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(f.version()));
        assertThat(
                        codec.decode(
                                redis.opsForValue().get(cacheKey(f)),
                                UUID.fromString(f.org()),
                                mapper.treeToValue(f.version(), VersionView.class)))
                .isPresent();
        assertThat(redis.getExpire(cacheKey(f), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void boundedMemoryEvictsDisposableKeysAndReadsRebuild() throws Exception {
        var f = fixture(false);
        var expected = read(f);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            var server = connection.serverCommands();
            long used = Long.parseLong(server.info("memory").getProperty("used_memory"));
            long before = Long.parseLong(server.info("stats").getProperty("evicted_keys"));
            server.setConfig("maxmemory", Long.toString(used + 300000));
            try {
                for (int i = 0; i < 300; i++)
                    redis.opsForValue()
                            .set(
                                    "gateflow:eviction-fixture:" + i,
                                    "x".repeat(8000),
                                    java.time.Duration.ofSeconds(5));
                assertThat(Long.parseLong(server.info("stats").getProperty("evicted_keys")))
                        .isGreaterThan(before);
            } finally {
                server.setConfig("maxmemory", "32mb");
            }
        }
        redis.delete(cacheKey(f));
        assertThat(read(f)).isEqualTo(expected);
        assertThat(redis.hasKey(cacheKey(f))).isTrue();
    }

    @Test
    void memoryWriteRejectionStillReturnsDatabasePolicy() throws Exception {
        var f = fixture(false);
        var expected = read(f);
        redis.delete(cacheKey(f));
        try (var connection = redis.getConnectionFactory().getConnection()) {
            var server = connection.serverCommands();
            server.setConfig("maxmemory-policy", "noeviction");
            server.setConfig("maxmemory", "1");
            try {
                assertThat(read(f)).isEqualTo(expected);
            } finally {
                server.setConfig("maxmemory", "32mb");
                server.setConfig("maxmemory-policy", "allkeys-lru");
            }
        }
        Thread.sleep(1100);
        assertThat(read(f)).isEqualTo(expected);
        assertThat(redis.hasKey(cacheKey(f))).isTrue();
    }
}
