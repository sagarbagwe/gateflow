package com.gateflow.workflow;

import com.fasterxml.jackson.databind.*;
import com.gateflow.http.ApiException;
import com.gateflow.rbac.RbacDtos.AccessView;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class SearchCursor {
    private final ObjectMapper mapper;
    private final WorkflowJson json;
    private static final Set<String> FIELDS =
            Set.of("version", "asOf", "afterCreatedAt", "afterId", "context");

    public record Position(
            int version, Instant asOf, Instant afterCreatedAt, UUID afterId, String context) {}

    public SearchCursor(ObjectMapper mapper, WorkflowJson json) {
        this.mapper =
                mapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(
                                com.fasterxml.jackson.core.JsonParser.Feature
                                        .STRICT_DUPLICATE_DETECTION);
        this.json = json;
    }

    public String context(AccessView actor, RequestSearchQuery q) {
        Map<String, Object> value = new TreeMap<>();
        value.put("org", actor.organizationId());
        value.put("actor", actor.membershipId());
        value.put("scope", q.scope());
        value.put("sort", q.sort());
        value.put("q", q.q());
        value.put("statuses", q.statuses().stream().map(Enum::name).sorted().toList());
        value.put("type", q.type());
        value.put("workflow", q.workflowId());
        value.put("from", q.createdFrom());
        value.put("before", q.createdBefore());
        // Permissions are deliberately not in the cursor; current grants are resolved on every
        // page.
        return json.hash(value);
    }

    public Position decode(String token, String context) {
        if (token == null) return null;
        try {
            if (token.length() > 768 || !token.matches("[A-Za-z0-9_-]+")) throw invalid();
            var node = mapper.readTree(Base64.getUrlDecoder().decode(token));
            var names = new HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            if (!node.isObject()
                    || !names.equals(FIELDS)
                    || !node.get("version").isIntegralNumber()) throw invalid();
            for (String field : List.of("asOf", "afterCreatedAt", "afterId", "context"))
                if (!node.get(field).isTextual()) throw invalid();
            var p = mapper.treeToValue(node, Position.class);
            if (p.version() != 1
                    || !context.equals(p.context())
                    || p.asOf() == null
                    || p.afterCreatedAt() == null
                    || p.afterId() == null
                    || p.afterCreatedAt().isAfter(p.asOf())) throw invalid();
            for (Instant t : List.of(p.asOf(), p.afterCreatedAt())) {
                int year = t.atOffset(ZoneOffset.UTC).getYear();
                if (year < 1 || year > 9999 || t.getNano() % 1000 != 0) throw invalid();
            }
            return p;
        } catch (ApiException bad) {
            throw bad;
        } catch (Exception bad) {
            throw invalid();
        }
    }

    public String encode(Instant asOf, Instant created, UUID id, String context) {
        var p = new Position(1, asOf, created, id, context);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.encode(p).getBytes(StandardCharsets.UTF_8));
    }

    private ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_CURSOR",
                "Cursor is malformed or belongs to a different search context");
    }
}
