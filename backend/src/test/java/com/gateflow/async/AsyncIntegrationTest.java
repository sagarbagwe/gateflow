package com.gateflow.async;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.gateflow.workflow.*;

import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "gateflow.auth.cookie-secure=false",
            "gateflow.async.enabled=true",
            "gateflow.async.relay-enabled=false",
            "gateflow.async.consumer-enabled=false",
            "gateflow.async.retry-delays[0]=150ms",
            "gateflow.async.retry-delays[1]=200ms",
            "gateflow.async.retry-delays[2]=250ms",
            "gateflow.async.retry-base=100ms",
            "gateflow.async.retry-max=200ms",
            "gateflow.async.confirm-timeout=PT1S",
            "gateflow.async.lease-duration=PT3S",
            "gateflow.auth.signup-limit=1000",
            "gateflow.auth.login-limit=1000"
        })
class AsyncIntegrationTest {
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
                    .withDatabaseName("gateflow_async_test")
                    .withUsername("gateflow_test")
                    .withPassword(UUID.randomUUID().toString());

    static final String BROKER_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final org.testcontainers.containers.GenericContainer<?> BROKER =
            new org.testcontainers.containers.GenericContainer<>(
                            DockerImageName.parse(
                                    "rabbitmq:4.2@sha256:3e96f87bcaa24ee025edd961e449b97036a323ff67f884099858fa6305f43c28"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "gf_test")
                    .withEnv("RABBITMQ_DEFAULT_PASS", BROKER_PASSWORD)
                    .withEnv(
                            "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS",
                            "+S 2:2 +A 2 -rabbit max_message_size 65536")
                    .withEnv("RABBITMQ_CTL_ERL_ARGS", "+S 1:1 +A 1")
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", BROKER::getHost);
        r.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "gf_test");
        r.add("spring.rabbitmq.password", () -> BROKER_PASSWORD);
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource datasource;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired EventReferenceCodec codec;
    @Autowired ActivityProjector projector;
    @Autowired ActivityConsumer consumer;
    @Autowired ConfirmedEventPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
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

    @BeforeEach
    void settlePreviousFixtures() {
        admin.initialize();
        jdbc.update(
                "UPDATE outbox_events SET available_at=clock_timestamp(),lease_until=CASE WHEN"
                        + " lease_token IS NOT NULL THEN clock_timestamp() ELSE NULL END WHERE"
                        + " published_at IS NULL");
        for (int i = 0; i < 100 && relay.drainOnce() > 0; i++) {}
        for (int i = 0; i < 200 && workOne(); i++) {}
        for (String q :
                List.of(
                        AsyncConfiguration.QUEUE,
                        AsyncConfiguration.DEAD,
                        AsyncConfiguration.QUEUE + ".retry.1",
                        AsyncConfiguration.QUEUE + ".retry.2",
                        AsyncConfiguration.QUEUE + ".retry.3")) admin.purgeQueue(q, true);
    }

    UUID event(JsonNode request) {
        return jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE request_id=? ORDER BY request_version DESC"
                        + " LIMIT 1",
                UUID.class,
                UUID.fromString(request.get("id").asText()));
    }

    long scalar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    boolean workOne() {
        return Boolean.TRUE.equals(
                rabbit.execute(
                        channel -> {
                            var delivery = channel.basicGet(AsyncConfiguration.QUEUE, false);
                            if (delivery == null) return false;
                            var props =
                                    new DefaultMessagePropertiesConverter()
                                            .toMessageProperties(
                                                    delivery.getProps(),
                                                    delivery.getEnvelope(),
                                                    "UTF-8");
                            consumer.receive(new Message(delivery.getBody(), props), channel);
                            return true;
                        }));
    }

    void until(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    long ready(String queue) {
        var p = admin.getQueueProperties(queue);
        return p == null ? 0 : ((Number) p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    void publish(UUID id, int attempt) {
        publisher.send(
                AsyncConfiguration.EVENTS,
                AsyncConfiguration.EVENT_ROUTE,
                ConfirmedEventPublisher.message(codec.encode(id), id.toString(), attempt, null));
    }

    String activity(Fixture f, JsonNode request) {
        return path(f, request) + "/activity";
    }

    @Test
    void commandReplayCreatesOneDurableEventAndRelayProjectsIt() throws Exception {
        var f = fixture(false);
        String k = key();
        var request = ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 201);
        ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 200);
        assertThat(count("outbox_events", f.org())).isEqualTo(1);
        assertThat(count("request_activity", f.org())).isZero();
        assertThat(
                        ok(f.requester().get(activity(f, request)), 200)
                                .get("eventuallyConsistent")
                                .asBoolean())
                .isTrue();
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(workOne()).isTrue();
        assertThat(count("processed_events", f.org())).isEqualTo(1);
        assertThat(count("request_activity", f.org())).isEqualTo(1);
        var item =
                ok(f.requester().get(activity(f, request)), 200)
                        .get("activity")
                        .get("items")
                        .get(0);
        assertThat(item.get("type").asText()).isEqualTo("REQUEST_SUBMITTED");
        assertThat(item.has("title")).isFalse();
    }

    @Test
    void everyBusinessTransitionEmitsOneVersionedEvent() throws Exception {
        var f = fixture(true);
        var r = submit(f);
        var next = ok(decide(f.first(), f, r, 0, 0, "APPROVE", key()), 200);
        ok(decide(f.second(), f, next, 1, 1, "REJECT", key()), 200);
        var withdrawn = submit(f);
        ok(withdraw(f, withdrawn, 0, key()), 200);
        var reassigned = submit(f);
        var backup = actor();
        String member = enroll(f.admin(), f.org(), backup, f.roleA());
        ok(
                f.admin()
                        .write(
                                "POST",
                                path(f, reassigned)
                                        + "/steps/"
                                        + reassigned.get("steps").get(0).get("id").asText()
                                        + "/reassign",
                                Map.of("expectedVersion", 0, "membershipId", member),
                                key()),
                200);
        assertThat(count("outbox_events", f.org())).isEqualTo(7);
        relay.drainOnce();
        while (workOne()) {}
        assertThat(count("request_activity", f.org())).isEqualTo(7);
        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM outbox_events WHERE organization_id=?",
                                String.class,
                                UUID.fromString(f.org())))
                .contains(
                        "STEP_APPROVED",
                        "REQUEST_REJECTED",
                        "REQUEST_WITHDRAWN",
                        "REVIEWER_REASSIGNED");
    }

    @Test
    void outboxInsertFailureRollsBackRequestAuditAndReceipt() throws Exception {
        var f = fixture(false);
        long before =
                scalar(
                        "SELECT count(*) FROM audit_logs WHERE organization_id=?",
                        UUID.fromString(f.org()));
        jdbc.execute(
                "CREATE FUNCTION fail_outbox_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                    + " RAISE EXCEPTION 'fixture outbox failure' USING ERRCODE='23514'; END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_outbox_test BEFORE INSERT ON outbox_events FOR EACH ROW"
                        + " EXECUTE FUNCTION fail_outbox_test()");
        String k = key();
        try {
            problem(
                    f.requester().write("POST", base(f) + "/requests", payload(f), k),
                    503,
                    "SERVICE_UNAVAILABLE");
        } finally {
            jdbc.execute("DROP TRIGGER fail_outbox_test ON outbox_events");
            jdbc.execute("DROP FUNCTION fail_outbox_test()");
        }
        assertThat(count("requests", f.org())).isZero();
        assertThat(count("command_receipts", f.org())).isZero();
        assertThat(count("outbox_events", f.org())).isZero();
        assertThat(
                        scalar(
                                "SELECT count(*) FROM audit_logs WHERE organization_id=?",
                                UUID.fromString(f.org())))
                .isEqualTo(before);
        ok(f.requester().write("POST", base(f) + "/requests", payload(f), k), 201);
    }

    @Test
    void leasedButAbandonedEventIsReclaimedAndStaleTokenCannotMarkIt() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        var first = outbox.claim(Duration.ofMillis(100)).orElseThrow();
        assertThat(outbox.claim(Duration.ofSeconds(3))).isEmpty();
        Thread.sleep(120);
        var second = outbox.claim(Duration.ofSeconds(3)).orElseThrow();
        assertThat(second.eventId()).isEqualTo(first.eventId());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(outbox.published(first)).isFalse();
        publish(second.eventId(), 0);
        assertThat(outbox.published(second)).isTrue();
        workOne();
        assertThat(count("request_activity", f.org())).isEqualTo(1);
    }

    @Test
    void brokerAcceptedButRelayMarkerLostProducesSafeDuplicate() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        var claim = outbox.claim(Duration.ofMillis(100)).orElseThrow();
        publish(claim.eventId(), 0);
        workOne();
        Thread.sleep(120);
        assertThat(relay.drainOnce()).isEqualTo(1);
        workOne();
        assertThat(count("processed_events", f.org())).isEqualTo(1);
        assertThat(count("request_activity", f.org())).isEqualTo(1);
    }

    @Test
    void concurrentWorkerDuplicatesCommitOneProjection() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return projector.process(id);
                                }));
            start.countDown();
            int inserted = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) inserted++;
            assertThat(inserted).isEqualTo(1);
        }
        assertThat(count("processed_events", f.org())).isEqualTo(1);
        assertThat(count("request_activity", f.org())).isEqualTo(1);
    }

    @Test
    void workerRollbackRetriesThroughDelayThenSucceeds() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        relay.drainOnce();
        jdbc.execute(
                "CREATE FUNCTION fail_projection_test() RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN RAISE EXCEPTION 'fixture projection failure' USING ERRCODE='23514';"
                    + " END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_projection_test BEFORE INSERT ON request_activity FOR EACH ROW"
                        + " EXECUTE FUNCTION fail_projection_test()");
        try {
            assertThat(workOne()).isTrue();
            assertThat(count("processed_events", f.org())).isZero();
            assertThat(count("request_activity", f.org())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_projection_test ON request_activity");
            jdbc.execute("DROP FUNCTION fail_projection_test()");
        }
        until(
                () -> {
                    workOne();
                    return count("request_activity", f.org()) == 1;
                });
        assertThat(count("processed_events", f.org())).isEqualTo(1);
    }

    @Test
    void persistentFailureReachesDlqAfterBoundedRetriesAndCanBeRedriven() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        relay.drainOnce();
        jdbc.execute(
                "CREATE FUNCTION fail_projection_test() RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN RAISE EXCEPTION 'fixture projection failure' USING ERRCODE='23514';"
                    + " END $$");
        jdbc.execute(
                "CREATE TRIGGER fail_projection_test BEFORE INSERT ON request_activity FOR EACH ROW"
                        + " EXECUTE FUNCTION fail_projection_test()");
        try {
            until(
                    () -> {
                        workOne();
                        return ready(AsyncConfiguration.DEAD) == 1;
                    });
            assertThat(count("processed_events", f.org())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_projection_test ON request_activity");
            jdbc.execute("DROP FUNCTION fail_projection_test()");
        }
        var dead = rabbit.receive(AsyncConfiguration.DEAD);
        assertThat((Integer) dead.getMessageProperties().getHeader("x-gf-attempt")).isEqualTo(3);
        publish(id, 0);
        workOne();
        assertThat(count("request_activity", f.org())).isEqualTo(1);
    }

    @Test
    void malformedAndUnknownReferencesAreDeadLetteredWithoutEffects() throws Exception {
        publisher.send(
                AsyncConfiguration.EVENTS,
                AsyncConfiguration.EVENT_ROUTE,
                ConfirmedEventPublisher.message(
                        "{bad".getBytes(), UUID.randomUUID().toString(), 0, null));
        workOne();
        UUID unknown = UUID.randomUUID();
        publish(unknown, 0);
        workOne();
        assertThat(ready(AsyncConfiguration.DEAD)).isEqualTo(2);
    }

    @Test
    void missingBindingDoesNotMarkOutboxPublished() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        admin.removeBinding(
                new org.springframework.amqp.core.Binding(
                        AsyncConfiguration.QUEUE,
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        AsyncConfiguration.EVENTS,
                        AsyncConfiguration.EVENT_ROUTE,
                        null));
        try {
            assertThat(relay.drainOnce()).isZero();
            assertThat(
                            scalar(
                                    "SELECT count(*) FROM outbox_events WHERE id=? AND published_at"
                                            + " IS NULL AND last_failure='UNROUTABLE'",
                                    id))
                    .isEqualTo(1);
        } finally {
            admin.initialize();
        }
        Thread.sleep(250);
        assertThat(relay.drainOnce()).isEqualTo(1);
        workOne();
    }

    @Test
    void brokerOutageDoesNotBlockBusinessCommitAndRelayRecovers() throws Exception {
        var f = fixture(false);
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(BROKER.getContainerId()).exec();
        JsonNode request;
        try {
            request = submit(f);
            assertThat(count("outbox_events", f.org())).isEqualTo(1);
            assertThat(relay.drainOnce()).isZero();
            assertThat(
                            scalar(
                                    "SELECT count(*) FROM outbox_events WHERE organization_id=? AND"
                                            + " published_at IS NULL",
                                    UUID.fromString(f.org())))
                    .isEqualTo(1);
        } finally {
            docker.unpauseContainerCmd(BROKER.getContainerId()).exec();
        }
        until(
                () -> {
                    relay.drainOnce();
                    workOne();
                    return count("request_activity", f.org()) == 1;
                });
    }

    @Test
    void sourceEventIsNotVisibleUntilTransactionCommit() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        var claim =
                outbox.claim(Duration.ofSeconds(3))
                        .orElseThrow(); // Existing committed event is leased, none else visible.
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try (var stmt =
                    connection.prepareStatement(
                            "INSERT INTO"
                                + " outbox_events(organization_id,request_id,request_version,actor_membership_id,event_type,request_state,correlation_id)"
                                + " VALUES(?,?,?,?, 'REQUEST_WITHDRAWN','WITHDRAWN','fixture')")) {
                stmt.setObject(1, UUID.fromString(f.org()));
                stmt.setObject(2, UUID.fromString(request.get("id").asText()));
                stmt.setLong(3, 1);
                stmt.setObject(4, UUID.fromString(f.requesterMember()));
                stmt.executeUpdate();
            }
            assertThat(outbox.claim(Duration.ofSeconds(3))).isEmpty();
            connection.rollback();
        }
        publish(claim.eventId(), 0);
        outbox.published(claim);
        workOne();
        assertThat(count("outbox_events", f.org())).isEqualTo(1);
    }

    @Test
    void activityVisibilityUsesFreshRequestAuthorizationAndBoundedPagination() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        relay.drainOnce();
        workOne();
        var outsider = actor();
        problem(outsider.get(activity(f, request)), 404, "ORGANIZATION_NOT_FOUND");
        var other = actor();
        enroll(f.admin(), f.org(), other, builtin(f.admin(), f.org(), "MEMBER"));
        problem(other.get(activity(f, request)), 404, "REQUEST_NOT_FOUND");
        problem(f.requester().get(activity(f, request) + "?limit=101"), 400, "INVALID_PARAMETER");
        problem(
                f.requester().get(activity(f, request) + "?offset=10001"),
                400,
                "INVALID_PARAMETER");
        jdbc.update(
                "UPDATE memberships SET status='SUSPENDED' WHERE id=?",
                UUID.fromString(f.requesterMember()));
        problem(f.requester().get(activity(f, request)), 404, "ORGANIZATION_NOT_FOUND");
    }

    @Test
    void outOfOrderDeliveryDoesNotRegressRequestStateAndHistorySortsByVersion() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        ok(decide(f.first(), f, request, 0, 0, "APPROVE", key()), 200);
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM outbox_events WHERE request_id=? ORDER BY request_version"
                                + " DESC",
                        UUID.class,
                        UUID.fromString(request.get("id").asText()));
        for (UUID id : ids) publish(id, 0);
        while (workOne()) {}
        var page = ok(f.requester().get(activity(f, request)), 200);
        assertThat(page.get("activity").get("items").get(0).get("requestVersion").asLong())
                .isEqualTo(1);
        assertThat(ok(f.requester().get(path(f, request)), 200).get("state").asText())
                .isEqualTo("APPROVED");
    }

    @Test
    void realListenerConsumesAndAckCommitGapDuplicateRemainsIdempotent() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        projector.process(id); // Simulated crash after DB commit, before broker ack.
        var listener = listeners.getListenerContainer(AsyncConfiguration.LISTENER);
        listener.start();
        try {
            relay.drainOnce();
            until(() -> ready(AsyncConfiguration.QUEUE) == 0);
            Thread.sleep(100);
            assertThat(count("request_activity", f.org())).isEqualTo(1);
            assertThat(count("processed_events", f.org())).isEqualTo(1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void concurrentRelayClaimsUseSkipLockedAndDistinctTokens() throws Exception {
        var f = fixture(false);
        submit(f);
        submit(f);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outbox.claim(Duration.ofSeconds(3)).orElseThrow());
            var b = pool.submit(() -> outbox.claim(Duration.ofSeconds(3)).orElseThrow());
            var ca = a.get(10, TimeUnit.SECONDS);
            var cb = b.get(10, TimeUnit.SECONDS);
            assertThat(ca.eventId()).isNotEqualTo(cb.eventId());
            assertThat(ca.token()).isNotEqualTo(cb.token());
            publish(ca.eventId(), 0);
            publish(cb.eventId(), 0);
            outbox.published(ca);
            outbox.published(cb);
            while (workOne()) {}
            assertThat(count("request_activity", f.org())).isEqualTo(2);
        }
    }

    @Test
    void failedDeadLetterHandoffRetainsOriginalBrokerDelivery() throws Exception {
        admin.removeBinding(
                new org.springframework.amqp.core.Binding(
                        AsyncConfiguration.DEAD,
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        AsyncConfiguration.DEAD,
                        "dead",
                        null));
        try {
            publisher.send(
                    AsyncConfiguration.EVENTS,
                    AsyncConfiguration.EVENT_ROUTE,
                    ConfirmedEventPublisher.message(
                            "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            "bad",
                            0,
                            null));
            assertThat(workOne()).isTrue();
            until(() -> ready(AsyncConfiguration.QUEUE) == 1);
            assertThat(ready(AsyncConfiguration.DEAD)).isZero();
        } finally {
            admin.initialize();
        }
        assertThat(workOne()).isTrue();
        until(() -> ready(AsyncConfiguration.DEAD) == 1);
        assertThat(ready(AsyncConfiguration.QUEUE)).isZero();
    }

    @Test
    void workerCommitThenChannelLossRedeliversWithoutDuplicateEffect() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        relay.drainOnce();
        rabbit.execute(
                channel -> {
                    var delivery = channel.basicGet(AsyncConfiguration.QUEUE, false);
                    assertThat(delivery).isNotNull();
                    assertThat(projector.process(id)).isTrue();
                    ((org.springframework.amqp.rabbit.connection.ChannelProxy) channel)
                            .getTargetChannel()
                            .close(); // Physical loss, not return to Spring channel cache.
                    return null;
                });
        until(() -> ready(AsyncConfiguration.QUEUE) == 1);
        assertThat(workOne()).isTrue();
        assertThat(scalar("SELECT count(*) FROM processed_events WHERE event_id=?", id))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM request_activity WHERE event_id=?", id))
                .isEqualTo(1);
    }

    @Test
    void confirmedPendingMessageSurvivesBrokerApplicationRestart() throws Exception {
        var f = fixture(false);
        var request = submit(f);
        UUID id = event(request);
        relay.drainOnce();
        until(() -> ready(AsyncConfiguration.QUEUE) == 1);
        assertThat(BROKER.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        assertThat(BROKER.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        until(() -> ready(AsyncConfiguration.QUEUE) == 1);
        assertThat(workOne()).isTrue();
        assertThat(scalar("SELECT count(*) FROM request_activity WHERE event_id=?", id))
                .isEqualTo(1);
    }
}
