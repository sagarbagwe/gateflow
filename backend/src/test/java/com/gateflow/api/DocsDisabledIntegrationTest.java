package com.gateflow.api;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

import java.net.*;
import java.net.http.*;
import java.util.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateflow.auth.cookie-secure=false")
class DocsDisabledIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>(
                            DockerImageName.parse(
                                            "postgres:17-bookworm@sha256:639ab7ceb90e13123085b741fb31ef493fba25463002f6da665352e7b534b652")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;

    @Test
    void documentationIsNotMountedByDefault() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String base = "http://127.0.0.1:" + port;
        var csrf =
                mapper.readTree(
                        http.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(base + "/api/v1/auth/csrf"))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body());
        var signup =
                http.send(
                        HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/signup"))
                                .header("Content-Type", "application/json")
                                .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"email\":\"docs-off@example.test\",\"displayName\":\"Fixture\",\"password\":\"docs-fixture-password-2026\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(signup.statusCode()).isEqualTo(201);
        for (String p :
                List.of("/v3/api-docs", "/v3/api-docs/gateflow", "/swagger-ui/index.html")) {
            var result =
                    http.send(
                            HttpRequest.newBuilder(URI.create(base + p)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).as(p).isEqualTo(404);
        }
    }
}
