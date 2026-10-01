package com.gateflow.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.gateflow.async.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
            "gateflow.notifications.enabled=true",
            "gateflow.notifications.consumer-enabled=false",
            "gateflow.notifications.email-enabled=true",
            "gateflow.notifications.email-worker-enabled=false",
            "gateflow.notifications.retry-base=100ms",
            "gateflow.notifications.retry-max=200ms",
            "gateflow.notifications.max-attempts=3",
            "spring.mail.properties.mail.smtp.auth=false",
            "spring.mail.properties.mail.smtp.starttls.enable=false",
            "spring.mail.properties.mail.smtp.starttls.required=false",
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
class NotificationIntegrationTest {
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
                    .withDatabaseName("gateflow_notification_test")
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

    @Container
    static final org.testcontainers.containers.GenericContainer<?> MAIL =
            new org.testcontainers.containers.GenericContainer<>(
                            DockerImageName.parse(
                                    "axllent/mailpit:v1.31.3@sha256:ed9b00c609e77e99c79b93f1178255ebc271868920f2c69a8d166bd5634ed10d"))
                    .withCommand("--disable-version-check", "--smtp-disable-rdns", "--max", "500")
                    .withExposedPorts(1025, 8025);

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", BROKER::getHost);
        r.add("spring.rabbitmq.port", () -> BROKER.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "gf_test");
        r.add("spring.rabbitmq.password", () -> BROKER_PASSWORD);
        r.add("spring.mail.host", MAIL::getHost);
        r.add("spring.mail.port", () -> MAIL.getMappedPort(1025));
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
    @Autowired NotificationConsumer consumer;
    @Autowired NotificationProjector notifications;
    @Autowired EmailDeliveryRepository emails;
    @Autowired EmailDeliveryWorker emailWorker;
    @Autowired EmailDeliveryPolicy emailPolicy;
    @MockitoSpyBean SmtpEmailSender sender;
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
    void settlePreviousFixtures() throws Exception {
        admin.initialize();
        jdbc.update(
                "UPDATE outbox_events SET available_at=clock_timestamp(),lease_until=CASE WHEN"
                        + " lease_token IS NOT NULL THEN clock_timestamp() ELSE NULL END WHERE"
                        + " published_at IS NULL");
        for (int i = 0; i < 100 && relay.drainOnce() > 0; i++) {}
        for (int i = 0; i < 200 && workOne(); i++) {}
        for (int i = 0; i < 4; i++) {
            emailWorker.drainOnce();
            Thread.sleep(250);
        }
        for (String q :
                List.of(
                        AsyncConfiguration.QUEUE,
                        NotificationAsyncConfiguration.QUEUE,
                        NotificationAsyncConfiguration.DEAD,
                        NotificationAsyncConfiguration.QUEUE + ".retry.1",
                        NotificationAsyncConfiguration.QUEUE + ".retry.2",
                        NotificationAsyncConfiguration.QUEUE + ".retry.3"))
            admin.purgeQueue(q, true);
    }

    boolean workOne() {
        return Boolean.TRUE.equals(
                rabbit.execute(
                        channel -> {
                            var d = channel.basicGet(NotificationAsyncConfiguration.QUEUE, false);
                            if (d == null) return false;
                            var p =
                                    new DefaultMessagePropertiesConverter()
                                            .toMessageProperties(
                                                    d.getProps(), d.getEnvelope(), "UTF-8");
                            consumer.receive(new Message(d.getBody(), p), channel);
                            return true;
                        }));
    }

    UUID event(JsonNode req) {
        return jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE request_id=? ORDER BY request_version DESC"
                        + " LIMIT 1",
                UUID.class,
                UUID.fromString(req.get("id").asText()));
    }

    long scalar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    String inbox(Fixture f) {
        return base(f) + "/notifications";
    }

    JsonNode pref(Actor a, Fixture f, boolean inApp, boolean email, long version) throws Exception {
        return ok(
                a.write(
                        "PUT",
                        inbox(f) + "/preferences",
                        Map.of(
                                "inAppEnabled",
                                inApp,
                                "emailEnabled",
                                email,
                                "expectedVersion",
                                version),
                        null),
                200);
    }

    void dispatch() {
        relay.drainOnce();
        while (workOne()) {}
        ;
    }

    void until(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    long ready(String q) {
        var p = admin.getQueueProperties(q);
        return p == null ? 0 : ((Number) p.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue();
    }

    JsonNode messages() throws Exception {
        return mapper.readTree(
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://"
                                                                + MAIL.getHost()
                                                                + ":"
                                                                + MAIL.getMappedPort(8025)
                                                                + "/api/v1/messages?limit=500"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .body());
    }

    List<JsonNode> mailTo(Actor a) throws Exception {
        var found = new ArrayList<JsonNode>();
        for (var m : messages().get("messages"))
            for (var to : m.get("To")) if (to.get("Address").asText().equals(a.email)) found.add(m);
        return found;
    }

    JsonNode delivery(Fixture f) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,status,attempts,last_failure FROM notification_email_deliveries"
                                + " WHERE organization_id=? ORDER BY created_at LIMIT 1",
                        UUID.fromString(f.org()));
        return mapper.valueToTree(rows.getFirst());
    }

    void failureTrigger(String name, String table, String operation, String condition) {
        jdbc.execute(
                "CREATE FUNCTION "
                        + name
                        + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
                        + condition
                        + " THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER "
                        + name
                        + " BEFORE "
                        + operation
                        + " ON "
                        + table
                        + " FOR EACH ROW EXECUTE FUNCTION "
                        + name
                        + "()");
    }

    void removeTrigger(String name, String table) {
        jdbc.execute("DROP TRIGGER " + name + " ON " + table);
        jdbc.execute("DROP FUNCTION " + name + "()");
    }

    @Test
    void defaultInAppTargetsOwnerAndCurrentReviewerWithoutEmail() throws Exception {
        var f = fixture(false);
        var req = submit(f);
        dispatch();
        assertThat(count("notifications", f.org())).isEqualTo(2);
        assertThat(count("notification_email_deliveries", f.org())).isZero();
        var owner = ok(f.requester().get(inbox(f)), 200);
        assertThat(owner.get("items").size()).isEqualTo(1);
        assertThat(owner.get("items").get(0).get("kind").asText()).isEqualTo("STATUS_UPDATE");
        var reviewer = ok(f.first().get(inbox(f)), 200);
        assertThat(reviewer.get("items").get(0).get("actionable").asBoolean()).isTrue();
        assertThat(reviewer.toString())
                .doesNotContain("Engineering laptop", PASSWORD, f.requester().email);
        assertThat(
                        scalar(
                                "SELECT count(*) FROM processed_events WHERE event_id=? AND"
                                        + " consumer_name=?",
                                event(req),
                                NotificationProjector.CONSUMER))
                .isEqualTo(1);
    }

    @Test
    void optedInEmailUsesRealSmtpAndReadAcknowledgmentIsIdempotent() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        var req = submit(f);
        dispatch();
        notifications.process(event(req));
        assertThat(count("notification_email_deliveries", f.org())).isEqualTo(1);
        var item = ok(f.requester().get(inbox(f)), 200).get("items").get(0);
        assertThat(
                        ok(f.requester().get(inbox(f) + "/unread-count"), 200)
                                .get("unreadCount")
                                .asInt())
                .isEqualTo(1);
        var first =
                ok(
                        f.requester()
                                .write(
                                        "PATCH",
                                        inbox(f) + "/" + item.get("id").asText() + "/read",
                                        null,
                                        null),
                        200);
        var replay =
                ok(
                        f.requester()
                                .write(
                                        "PATCH",
                                        inbox(f) + "/" + item.get("id").asText() + "/read",
                                        null,
                                        null),
                        200);
        assertThat(replay.get("readAt")).isEqualTo(first.get("readAt"));
        assertThat(ok(f.requester().get(inbox(f) + "?unreadOnly=true"), 200).get("items").size())
                .isZero();
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(mailTo(f.requester())).hasSize(1);
        assertThat(mailTo(f.requester()).getFirst().get("Subject").asText())
                .isEqualTo(SmtpEmailSender.SUBJECT);
        assertThat(count("notification_email_attempts", f.org())).isEqualTo(1);
    }

    @Test
    void allChannelsDisabledCreatesNoNotificationsOrEmailJobs() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, false, false, 0);
        pref(f.first(), f, false, false, 0);
        submit(f);
        dispatch();
        assertThat(count("notifications", f.org())).isZero();
        assertThat(count("notification_email_deliveries", f.org())).isZero();
    }

    @Test
    void emailOnlyPreferenceDoesNotExposeHiddenInboxRows() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, false, true, 0);
        submit(f);
        dispatch();
        assertThat(ok(f.requester().get(inbox(f)), 200).get("items").size()).isZero();
        emailWorker.drainOnce();
        assertThat(mailTo(f.requester())).hasSize(1);
    }

    @Test
    void preferencesHaveDefaultsVersionConflictsAndStrictValidation() throws Exception {
        var f = fixture(false);
        var defaults = ok(f.requester().get(inbox(f) + "/preferences"), 200);
        assertThat(defaults.get("emailEnabled").asBoolean()).isFalse();
        assertThat(defaults.get("inAppEnabled").asBoolean()).isTrue();
        assertThat(pref(f.requester(), f, true, true, 0).get("version").asLong()).isEqualTo(1);
        problem(
                f.requester()
                        .write(
                                "PUT",
                                inbox(f) + "/preferences",
                                Map.of(
                                        "inAppEnabled",
                                        false,
                                        "emailEnabled",
                                        false,
                                        "expectedVersion",
                                        0),
                                null),
                409,
                "VERSION_CONFLICT");
        problem(
                f.requester()
                        .write(
                                "PUT",
                                inbox(f) + "/preferences",
                                Map.of("emailEnabled", true, "expectedVersion", 1),
                                null),
                400,
                "VALIDATION_FAILED");
        assertThat(
                        ok(f.requester().get(inbox(f) + "/preferences"), 200)
                                .get("emailEnabled")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void inboxAndReadAreRecipientScopedAndTenantConcealing() throws Exception {
        var f = fixture(false);
        submit(f);
        dispatch();
        var item = ok(f.requester().get(inbox(f)), 200).get("items").get(0);
        assertThat(ok(f.admin().get(inbox(f)), 200).get("items").size()).isZero();
        problem(
                f.admin()
                        .write(
                                "PATCH",
                                inbox(f) + "/" + item.get("id").asText() + "/read",
                                null,
                                null),
                404,
                "NOTIFICATION_NOT_FOUND");
        problem(actor().get(inbox(f)), 404, "ORGANIZATION_NOT_FOUND");
        problem(f.requester().get(inbox(f) + "?limit=101"), 400, "INVALID_PARAMETER");
        problem(f.requester().get(inbox(f) + "?offset=10001"), 400, "INVALID_PARAMETER");
    }

    @Test
    void permissionRevocationHidesExistingRowsAndCancelsUnsentMail() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        var item = ok(f.requester().get(inbox(f)), 200).get("items").get(0);
        jdbc.update(
                "DELETE FROM role_permissions WHERE organization_id=? AND role_id=? AND"
                        + " permission_code='REQUEST_VIEW_OWN'",
                UUID.fromString(f.org()),
                UUID.fromString(builtin(f.admin(), f.org(), "MEMBER")));
        assertThat(ok(f.requester().get(inbox(f)), 200).get("items").size()).isZero();
        assertThat(
                        ok(f.requester().get(inbox(f) + "/unread-count"), 200)
                                .get("unreadCount")
                                .asInt())
                .isZero();
        problem(
                f.requester()
                        .write(
                                "PATCH",
                                inbox(f) + "/" + item.get("id").asText() + "/read",
                                null,
                                null),
                404,
                "NOTIFICATION_NOT_FOUND");
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(mailTo(f.requester())).isEmpty();
    }

    @Test
    void suspensionBeforeDeliverySuppressesExternalMail() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        jdbc.update(
                "UPDATE memberships SET status='SUSPENDED' WHERE id=?",
                UUID.fromString(f.requesterMember()));
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(mailTo(f.requester())).isEmpty();
        problem(f.requester().get(inbox(f)), 404, "ORGANIZATION_NOT_FOUND");
    }

    @Test
    void currentPreferencesAreRecheckedBeforeSending() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        pref(f.requester(), f, true, false, 1);
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(mailTo(f.requester())).isEmpty();
    }

    @Test
    void oldEventsNeverCreateStaleActionRequiredNotifications() throws Exception {
        var f = fixture(false);
        var req = submit(f);
        ok(decide(f.first(), f, req, 0, 0, "APPROVE", key()), 200);
        dispatch();
        assertThat(ok(f.first().get(inbox(f)), 200).get("items").size()).isZero();
        assertThat(ok(f.requester().get(inbox(f)), 200).get("items").size()).isEqualTo(2);
    }

    @Test
    void nextStepNotificationsUseCurrentEligibilityAndOldActionsBecomeInactive() throws Exception {
        var f = fixture(true);
        var req = submit(f);
        dispatch();
        ok(decide(f.first(), f, req, 0, 0, "APPROVE", key()), 200);
        dispatch();
        assertThat(
                        ok(f.first().get(inbox(f)), 200)
                                .get("items")
                                .get(0)
                                .get("actionable")
                                .asBoolean())
                .isFalse();
        assertThat(
                        ok(f.second().get(inbox(f)), 200)
                                .get("items")
                                .get(0)
                                .get("actionable")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void reassignmentTargetsNewReviewerAndSuppressesOldActionEmails() throws Exception {
        var f = fixture(false);
        pref(f.first(), f, true, true, 0);
        var req = submit(f);
        dispatch();
        var replacement = actor();
        String member = enroll(f.admin(), f.org(), replacement, f.roleA());
        ok(
                f.admin()
                        .write(
                                "POST",
                                base(f)
                                        + "/requests/"
                                        + req.get("id").asText()
                                        + "/steps/"
                                        + req.get("steps").get(0).get("id").asText()
                                        + "/reassign",
                                Map.of("expectedVersion", 0, "membershipId", member),
                                key()),
                200);
        dispatch();
        assertThat(
                        ok(replacement.get(inbox(f)), 200)
                                .get("items")
                                .get(0)
                                .get("actionable")
                                .asBoolean())
                .isTrue();
        emailWorker.drainOnce();
        assertThat(delivery(f).get("last_failure").asText()).isEqualTo("STALE_ACTION");
        assertThat(mailTo(f.first())).isEmpty();
    }

    @Test
    void duplicateConcurrentEventsCreateOneRecipientSetAndOneJob() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        UUID id = event(submit(f));
        var pool = Executors.newFixedThreadPool(6);
        var latch = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 6; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    latch.await();
                                    return notifications.process(id);
                                }));
            latch.countDown();
            int created = 0;
            for (var x : futures) if (x.get(10, TimeUnit.SECONDS)) created++;
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("notifications", f.org())).isEqualTo(2);
        assertThat(count("notification_email_deliveries", f.org())).isEqualTo(1);
    }

    @Test
    void concurrentEmailClaimsSendOneExternalMessage() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        var pool = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<Optional<EmailDeliveryRepository.Claim>>>();
        try {
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> emails.claim(Duration.ofSeconds(30))));
            var claimed = new ArrayList<EmailDeliveryRepository.Claim>();
            for (var x : futures) x.get(10, TimeUnit.SECONDS).ifPresent(claimed::add);
            assertThat(claimed).hasSize(1);
            emailWorker.process(claimed.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(mailTo(f.requester())).hasSize(1);
    }

    @Test
    void projectionFailureRetriesWithoutPartialReceiptOrRecipientRows() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        var req = submit(f);
        UUID id = event(req);
        String trigger = "notification_failure";
        failureTrigger(
                trigger, "notifications", "INSERT", "NEW.organization_id='" + f.org() + "'::uuid");
        try {
            relay.drainOnce();
            workOne();
            assertThat(count("notifications", f.org())).isZero();
            assertThat(count("notification_email_deliveries", f.org())).isZero();
            assertThat(
                            scalar(
                                    "SELECT count(*) FROM processed_events WHERE event_id=? AND"
                                            + " consumer_name=?",
                                    id,
                                    NotificationProjector.CONSUMER))
                    .isZero();
            assertThat(ready(AsyncConfiguration.QUEUE)).isEqualTo(1);
        } finally {
            removeTrigger(trigger, "notifications");
        }
        until(
                () -> {
                    workOne();
                    return count("notifications", f.org()) == 2;
                });
        assertThat(count("notification_email_deliveries", f.org())).isEqualTo(1);
        assertThat(ready(AsyncConfiguration.QUEUE)).isEqualTo(1);
    }

    @Test
    void emailJobCreationFailureRollsBackInboxAndConsumerReceipt() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        UUID id = event(submit(f));
        String trigger = "email_insert_failure";
        failureTrigger(
                trigger,
                "notification_email_deliveries",
                "INSERT",
                "NEW.organization_id='" + f.org() + "'::uuid");
        try {
            assertThatThrownBy(() -> notifications.process(id))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(count("notifications", f.org())).isZero();
            assertThat(
                            scalar(
                                    "SELECT count(*) FROM processed_events WHERE event_id=? AND"
                                            + " consumer_name=?",
                                    id,
                                    NotificationProjector.CONSUMER))
                    .isZero();
        } finally {
            removeTrigger(trigger, "notification_email_deliveries");
        }
        assertThat(notifications.process(id)).isTrue();
        assertThat(count("notification_email_deliveries", f.org())).isEqualTo(1);
    }

    @Test
    void retryableProviderFailureBacksOffThenSucceedsWithStableDeliveryId() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        doThrow(new EmailSendFailure(EmailSendFailure.Kind.RETRYABLE, "PROVIDER_RETRYABLE"))
                .doCallRealMethod()
                .when(sender)
                .send(any());
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("RETRY");
        Thread.sleep(250);
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(delivery(f).get("attempts").asInt()).isEqualTo(2);
        assertThat(count("notification_email_attempts", f.org())).isEqualTo(2);
        assertThat(mailTo(f.requester())).hasSize(1);
    }

    @Test
    void exhaustedRetryableFailuresAreDurablyDead() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        doThrow(new EmailSendFailure(EmailSendFailure.Kind.RETRYABLE, "PROVIDER_RETRYABLE"))
                .when(sender)
                .send(any());
        for (int i = 0; i < 3; i++) {
            emailWorker.drainOnce();
            Thread.sleep(250);
        }
        assertThat(delivery(f).get("status").asText()).isEqualTo("DEAD");
        assertThat(delivery(f).get("attempts").asInt()).isEqualTo(3);
        assertThat(count("notification_email_attempts", f.org())).isEqualTo(3);
        assertThat(mailTo(f.requester())).isEmpty();
    }

    @Test
    void permanentFailuresDoNotRetry() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        doThrow(new EmailSendFailure(EmailSendFailure.Kind.PERMANENT, "PROVIDER_PERMANENT"))
                .when(sender)
                .send(any());
        emailWorker.drainOnce();
        assertThat(delivery(f).get("status").asText()).isEqualTo("DEAD");
        assertThat(delivery(f).get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void ambiguousProviderOutcomeIsQuarantinedNotAutomaticallyRetried() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        doThrow(new EmailSendFailure(EmailSendFailure.Kind.UNKNOWN, "PROVIDER_UNKNOWN"))
                .when(sender)
                .send(any());
        emailWorker.drainOnce();
        Thread.sleep(250);
        assertThat(emailWorker.drainOnce()).isZero();
        assertThat(delivery(f).get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(delivery(f).get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    void expiredExternalLeaseIsUnknownAndStaleCompletionCannotOverwriteIt() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        var c = emails.claim(Duration.ofMillis(100)).orElseThrow();
        Thread.sleep(150);
        assertThat(emails.claim(Duration.ofSeconds(30))).isEmpty();
        assertThat(emails.finish(c, "ACCEPTED", null, Duration.ZERO)).isFalse();
        assertThat(delivery(f).get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(count("notification_email_attempts", f.org())).isEqualTo(1);
        assertThat(mailTo(f.requester())).isEmpty();
    }

    @Test
    void smtpAcceptanceThenFailedDatabaseMarkerDoesNotAutoResend() throws Exception {
        var f = fixture(false);
        pref(f.requester(), f, true, true, 0);
        submit(f);
        dispatch();
        var c = emails.claim(Duration.ofMillis(100)).orElseThrow();
        String trigger = "email_marker_failure";
        failureTrigger(
                trigger,
                "notification_email_deliveries",
                "UPDATE",
                "NEW.organization_id='" + f.org() + "'::uuid AND NEW.status='ACCEPTED'");
        try {
            assertThatThrownBy(() -> emailWorker.process(c))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            removeTrigger(trigger, "notification_email_deliveries");
        }
        assertThat(mailTo(f.requester())).hasSize(1);
        Thread.sleep(150);
        assertThat(emailWorker.drainOnce()).isZero();
        assertThat(delivery(f).get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(mailTo(f.requester())).hasSize(1);
    }

    @Test
    void realNotificationListenerConsumesIndependentDomainSubscription() throws Exception {
        var f = fixture(false);
        submit(f);
        var listener = listeners.getListenerContainer(NotificationAsyncConfiguration.LISTENER);
        listener.start();
        try {
            relay.drainOnce();
            until(() -> count("notifications", f.org()) == 2);
            assertThat(ready(AsyncConfiguration.QUEUE)).isEqualTo(1);
        } finally {
            listener.stop();
        }
    }

    @Test
    void preferenceAuditFailureRollsBackPreferenceCreation() throws Exception {
        var f = fixture(false);
        String trigger = "preference_audit_failure";
        failureTrigger(
                trigger,
                "audit_logs",
                "INSERT",
                "NEW.organization_id='"
                        + f.org()
                        + "'::uuid AND NEW.action='NOTIFICATION_PREFERENCES_CHANGED'");
        try {
            problem(
                    f.requester()
                            .write(
                                    "PUT",
                                    inbox(f) + "/preferences",
                                    Map.of(
                                            "inAppEnabled",
                                            false,
                                            "emailEnabled",
                                            true,
                                            "expectedVersion",
                                            0),
                                    null),
                    503,
                    "SERVICE_UNAVAILABLE");
            assertThat(count("notification_preferences", f.org())).isZero();
        } finally {
            removeTrigger(trigger, "audit_logs");
        }
        assertThat(pref(f.requester(), f, true, true, 0).get("version").asLong()).isEqualTo(1);
        assertThat(
                        scalar(
                                "SELECT count(*) FROM audit_logs WHERE organization_id=? AND"
                                        + " action='NOTIFICATION_PREFERENCES_CHANGED'",
                                UUID.fromString(f.org())))
                .isEqualTo(1);
    }

    @Test
    void concurrentPreferenceUpdatesHaveOneWinnerAndOneAudit() throws Exception {
        var f = fixture(false);
        var other = fork(f.requester());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var body = Map.of("inAppEnabled", true, "emailEnabled", true, "expectedVersion", 0);
        try {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return f.requester()
                                        .write("PUT", inbox(f) + "/preferences", body, null)
                                        .statusCode();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return other.write("PUT", inbox(f) + "/preferences", body, null)
                                        .statusCode();
                            });
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        scalar(
                                "SELECT count(*) FROM audit_logs WHERE organization_id=? AND"
                                        + " action='NOTIFICATION_PREFERENCES_CHANGED'",
                                UUID.fromString(f.org())))
                .isEqualTo(1);
    }

    @Test
    void notificationWritesRequireCsrfAndAnonymousReadsRequireLogin() throws Exception {
        var f = fixture(false);
        submit(f);
        dispatch();
        var id = ok(f.requester().get(inbox(f)), 200).get("items").get(0).get("id").asText();
        var noCsrf =
                f.requester()
                        .http
                        .send(
                                HttpRequest.newBuilder(uri(inbox(f) + "/" + id + "/read"))
                                        .method("PATCH", HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        var anonymous =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(uri(inbox(f))).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }
}
