package com.gateflow.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.auth.AuthProperties;
import com.gateflow.http.*;

import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

class ApiContractUnitTest {
    @RestController
    static class Probe {
        @GetMapping("/required")
        String required(@RequestParam String value) {
            return value;
        }

        @GetMapping("/storage")
        String storage() {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                    "secret SQL SELECT password_hash FROM users");
        }
    }

    @Test
    void secureDocumentationNamesActualHostCookies() {
        var ui = new SwaggerUiConfigProperties();
        var doc =
                new OpenApiConfiguration()
                        .gateflowApi(
                                new AuthProperties(
                                        Duration.ofHours(1), true, 10, 5, Duration.ofMinutes(1)),
                                ui);
        assertThat(doc.getComponents().getSecuritySchemes().get("SessionCookie").getName())
                .isEqualTo("__Host-GATEFLOW_SESSION");
        assertThat(ui.getCsrf().getCookieName()).isEqualTo("__Host-XSRF-TOKEN");
    }

    @Test
    void missingRequiredParameterIs400NotUnexpected500() throws Exception {
        check("/required", 400, "INVALID_PARAMETER");
    }

    @Test
    void databaseFailureIs503WithoutSqlLeak() throws Exception {
        check("/storage", 503, "SERVICE_UNAVAILABLE");
    }

    void check(String p, int status, String code) throws Exception {
        var mapper = new ObjectMapper();
        var mvc =
                MockMvcBuilders.standaloneSetup(new Probe())
                        .setControllerAdvice(new ApiExceptionHandler(new Problems(mapper)))
                        .build();
        var result =
                mvc.perform(get(p).requestAttr(RequestIdFilter.ATTRIBUTE, "fixture-request-id"))
                        .andReturn()
                        .getResponse();
        assertThat(result.getStatus()).isEqualTo(status);
        var body = mapper.readTree(result.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("requestId").asText()).isEqualTo("fixture-request-id");
        assertThat(result.getContentAsString()).doesNotContain("secret SQL", "password_hash");
    }
}
