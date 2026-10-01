package com.gateflow.api;

import com.gateflow.auth.AuthProperties;
import com.gateflow.workflow.RequestSearchController;
import com.gateflow.workflow.WorkflowDtos;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.method.HandlerMethod;

import java.math.BigDecimal;
import java.util.*;

@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {
    @Bean
    OpenAPI gateflowApi(AuthProperties auth, SwaggerUiConfigProperties ui) {
        ui.getCsrf().setCookieName(auth.cookieSecure() ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN");
        var fields =
                new ObjectSchema()
                        .addProperty("field", new StringSchema())
                        .addProperty("message", new StringSchema());
        fields.setRequired(List.of("field", "message"));
        var problem =
                new ObjectSchema()
                        .addProperty(
                                "type", new StringSchema().format("uri").example("about:blank"))
                        .addProperty("title", new StringSchema())
                        .addProperty(
                                "status",
                                new IntegerSchema()
                                        .minimum(new BigDecimal("400"))
                                        .maximum(new BigDecimal("599")))
                        .addProperty("detail", new StringSchema())
                        .addProperty(
                                "instance",
                                new StringSchema()
                                        .description("Request path, never its query string"))
                        .addProperty(
                                "code",
                                new StringSchema()
                                        .description(
                                                "Stable application code; branch on this, not human"
                                                        + " detail"))
                        .addProperty("requestId", new UUIDSchema())
                        .addProperty(
                                "errors",
                                new ArraySchema()
                                        .items(fields)
                                        .description(
                                                "Optional safe field/messages; never rejected"
                                                        + " values"));
        problem.setRequired(
                List.of("type", "title", "status", "detail", "instance", "code", "requestId"));
        var components =
                new Components()
                        .addSchemas("ApiProblem", problem)
                        .addSecuritySchemes(
                                "SessionCookie",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.COOKIE)
                                        .name(
                                                auth.cookieSecure()
                                                        ? "__Host-GATEFLOW_SESSION"
                                                        : "GATEFLOW_SESSION")
                                        .description(
                                                "Opaque HttpOnly session cookie established by"
                                                    + " login/signup. Browser sends it; Authorize"
                                                    + " cannot set HttpOnly cookies. Not a bearer"
                                                    + " JWT."))
                        .addSecuritySchemes(
                                "CsrfHeader",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-XSRF-TOKEN")
                                        .description(
                                                "GET /api/v1/auth/csrf before writes, and again"
                                                        + " after login/logout. Swagger reads the"
                                                        + " matching same-origin CSRF cookie."));
        return new OpenAPI()
                .openapi("3.0.3")
                .components(components)
                .servers(List.of(new Server().url("/")))
                .info(
                        new Info()
                                .title("GateFlow API")
                                .version("1.0.0")
                                .description(
                                        "Versioned approval APIs. Typed resource/PageSlice success"
                                            + " bodies; RFC 9457 problems. Fresh tenant/RBAC"
                                            + " checks; 404 conceals foreign resources. Writes"
                                            + " require CSRF. Workflow commands require scoped"
                                            + " Idempotency-Key and expectedVersion where"
                                            + " applicable. Documentation is opt-in and"
                                            + " session-protected. No production capacity claim."));
    }

    @Bean
    GroupedOpenApi gateflowGroup(OperationCustomizer gateflowOperations) {
        return GroupedOpenApi.builder()
                .group("gateflow")
                .pathsToMatch("/api/v1/**")
                .addOperationCustomizer(gateflowOperations)
                .build();
    }

    @Bean
    OperationCustomizer gateflowOperations() {
        return this::customize;
    }

    private Operation customize(Operation op, HandlerMethod handler) {
        String controller = handler.getBeanType().getSimpleName().replace("Controller", "");
        String method = handler.getMethod().getName();
        op.setOperationId(controller + "_" + method);
        op.setTags(List.of(controller));
        if (op.getSummary() == null) op.setSummary(controller + ": " + method);
        boolean auth = controller.equals("Auth");
        boolean publicRoute = auth && Set.of("csrf", "signup", "login", "logout").contains(method);
        boolean write =
                handler.hasMethodAnnotation(
                                org.springframework.web.bind.annotation.PostMapping.class)
                        || handler.hasMethodAnnotation(
                                org.springframework.web.bind.annotation.PutMapping.class)
                        || handler.hasMethodAnnotation(
                                org.springframework.web.bind.annotation.PatchMapping.class);
        var security = new SecurityRequirement();
        if (!publicRoute) security.addList("SessionCookie");
        if (write) security.addList("CsrfHeader");
        op.setSecurity(
                security.isEmpty()
                        ? List.of()
                        : List.of(security)); // One object means AND, not OR.
        if (write)
            op.addParametersItem(
                    new Parameter()
                            .name("X-XSRF-TOKEN")
                            .in("header")
                            .required(true)
                            .description(
                                    "Current token from GET /api/v1/auth/csrf; auth transitions"
                                            + " clear it.")
                            .schema(new StringSchema()));
        if (op.getResponses() == null) op.setResponses(new ApiResponses());
        if (!publicRoute) error(op, "401", "Missing, expired or revoked session");
        if (write || !auth)
            error(
                    op,
                    "403",
                    "CSRF or permission denied; security checks precede business validation");
        error(op, "400", "Invalid JSON, fields, UUIDs, filters or pagination");
        error(op, "406", "Unsupported response Accept media type");
        if (!auth)
            error(op, "404", "Absent/invisible resource or organization; tenant concealment");
        error(op, "500", "Sanitized unexpected failure");
        error(op, "503", "Storage unavailable; no internal details exposed");
        if (write) {
            error(op, "413", "Body exceeds 256 KiB");
            if (!auth || !method.equals("logout"))
                error(op, "415", "Unsupported request Content-Type");
            if (!auth || method.equals("signup"))
                error(
                        op,
                        "409",
                        "Conflict: stale version, invalid transition, or conflicting idempotency"
                                + " key");
        }
        if (auth && Set.of("signup", "login").contains(method)) {
            if (method.equals("login")) error(op, "401", "Invalid credentials or disabled account");
            error(op, "429", "Shared authentication rate limit; honor Retry-After");
            op.getResponses()
                    .get("429")
                    .addHeaderObject(
                            "Retry-After",
                            new Header()
                                    .description("Seconds before retry")
                                    .schema(new IntegerSchema().minimum(BigDecimal.ONE)));
        }
        boolean command = controller.equals("Request") && write;
        if (command) {
            var success = op.getResponses().get("200");
            if (method.equals("submit")) {
                op.getResponses()
                        .addApiResponse(
                                "201",
                                new ApiResponse()
                                        .description("New request committed atomically")
                                        .content(success == null ? null : success.getContent()));
                if (success != null)
                    success.setDescription(
                            "Same-key/body replay; current request view, no repeated effects");
            }
            for (String code : List.of("200", "201"))
                if (op.getResponses().containsKey(code))
                    op.getResponses()
                            .get(code)
                            .addHeaderObject(
                                    "Idempotent-Replay",
                                    new Header()
                                            .description(
                                                    "true only for a successful receipt replay")
                                            .schema(new BooleanSchema()));
            if (op.getParameters() != null)
                op.getParameters().stream()
                        .filter(x -> x.getName().equals("Idempotency-Key"))
                        .forEach(
                                x -> {
                                    x.setDescription(
                                            "Required canonical UUID scoped to organization/member."
                                                + " Retry same key/body after transport failure;"
                                                + " different operation/body returns 409.");
                                    x.getSchema().setFormat("uuid");
                                });
        }
        boolean location =
                (controller.equals("Rbac") && Set.of("create", "createRole").contains(method))
                        || (controller.equals("Workflow")
                                && Set.of("create", "version").contains(method))
                        || (controller.equals("Request") && method.equals("submit"));
        if (location && op.getResponses().containsKey("201"))
            op.getResponses()
                    .get("201")
                    .addHeaderObject(
                            "Location",
                            new Header()
                                    .description(
                                            "Relative URI of the implemented GET detail route; only"
                                                    + " on newly created resource")
                                    .schema(new StringSchema()));
        op.getResponses()
                .values()
                .forEach(
                        x ->
                                x.addHeaderObject(
                                        "X-Request-ID",
                                        new Header()
                                                .description(
                                                        "Server-generated correlation UUID, also in"
                                                            + " problem bodies and successful audit"
                                                            + " evidence")
                                                .schema(new UUIDSchema())));
        if (handler.getBeanType() == RequestSearchController.class)
            search(op, method.equals("inbox"));
        if (op.getParameters() != null)
            for (var parameter : op.getParameters()) {
                if (parameter.getIn().equals("query")
                        && Set.of("limit", "offset").contains(parameter.getName())) {
                    parameter
                            .getSchema()
                            .setMinimum(
                                    parameter.getName().equals("limit")
                                            ? BigDecimal.ONE
                                            : BigDecimal.ZERO);
                    parameter
                            .getSchema()
                            .setMaximum(
                                    new BigDecimal(
                                            parameter.getName().equals("limit") ? "100" : "10000"));
                }
            }
        return op;
    }

    private void error(Operation op, String code, String text) {
        op.getResponses()
                .addApiResponse(
                        code,
                        new ApiResponse()
                                .description(text)
                                .content(
                                        new Content()
                                                .addMediaType(
                                                        "application/problem+json",
                                                        new MediaType()
                                                                .schema(
                                                                        new Schema<>()
                                                                                .$ref(
                                                                                        "#/components/schemas/ApiProblem")))));
    }

    private void query(Operation op, String name, Schema<?> schema, String description) {
        op.addParametersItem(
                new Parameter()
                        .name(name)
                        .in("query")
                        .required(false)
                        .schema(schema)
                        .description(description));
    }

    private void search(Operation op, boolean inbox) {
        // MultiValueMap's real public keys must be explicit; never expose a fictitious 'query'
        // parameter.
        if (op.getParameters() != null)
            op.setParameters(
                    new ArrayList<>(
                            op.getParameters().stream()
                                    .filter(x -> !"query".equals(x.getIn()))
                                    .toList()));
        query(
                op,
                "scope",
                new StringSchema()
                        ._enum(inbox ? List.of("INBOX") : List.of("VISIBLE", "OWN", "INBOX"))
                        ._default(inbox ? "INBOX" : "VISIBLE"),
                inbox ? "Forced INBOX; another scope is invalid" : "Current access scope");
        query(
                op,
                "sort",
                new StringSchema()
                        ._enum(List.of("CREATED_DESC", "CREATED_ASC"))
                        ._default("CREATED_DESC"),
                "Creation order with UUID tie-breaker");
        query(
                op,
                "pagination",
                new StringSchema()._enum(List.of("CURSOR", "OFFSET"))._default("CURSOR"),
                "Cursor by default; OFFSET cannot include cursor");
        query(op, "limit", new IntegerSchema()._default(20), "Page size 1..100");
        query(
                op,
                "offset",
                new IntegerSchema(),
                "OFFSET only, 0..10000; never send with CURSOR, even zero");
        query(
                op,
                "q",
                new StringSchema().maxLength(200),
                "PostgreSQL full-text search; blank becomes absent");
        query(
                op,
                "status",
                new ArraySchema()
                        .items(
                                new StringSchema()
                                        ._enum(
                                                Arrays.stream(WorkflowDtos.RequestState.values())
                                                        .map(Enum::name)
                                                        .toList()))
                        .maxItems(5),
                "Repeat status query keys, not comma-separated tokens");
        op.getParameters().get(op.getParameters().size() - 1).setStyle(Parameter.StyleEnum.FORM);
        op.getParameters().get(op.getParameters().size() - 1).setExplode(true);
        query(
                op,
                "type",
                new StringSchema()
                        ._enum(
                                Arrays.stream(WorkflowDtos.RequestType.values())
                                        .map(Enum::name)
                                        .toList()),
                "Exact request type");
        query(op, "workflowId", new UUIDSchema(), "Definition UUID (not workflow version UUID)");
        query(op, "createdFrom", new DateTimeSchema(), "Inclusive lower creation-time bound");
        query(
                op,
                "createdBefore",
                new DateTimeSchema(),
                "Exclusive upper bound; must be after createdFrom");
        query(
                op,
                "cursor",
                new StringSchema().maxLength(768).pattern("[A-Za-z0-9_-]+"),
                "Opaque continuation bound to org/actor/filter/sort; not an authorization token");
    }
}
