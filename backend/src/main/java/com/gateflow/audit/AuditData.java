package com.gateflow.audit;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only controlled business/security evidence, never request bodies or credentials. */
@Component
public class AuditData {
    private static final Set<String> FIELDS =
            Set.of(
                    "state",
                    "version",
                    "workflowVersionId",
                    "stepId",
                    "decision",
                    "membershipId",
                    "roleIds",
                    "status",
                    "creatorMembershipId",
                    "organizationId",
                    "permissions",
                    "code",
                    "name",
                    "definitionId",
                    "stepCount",
                    "inAppEnabled",
                    "emailEnabled",
                    "steps",
                    "position",
                    "approverRoleId",
                    "condition",
                    "type",
                    "amount",
                    "currency",
                    "id");
    private final ObjectMapper mapper;

    public AuditData(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Snapshot(JsonNode value, boolean redacted) {}

    public String encode(Map<String, ?> value) {
        if (value == null) return null;
        var raw = mapper.valueToTree(value);
        var safe = clean(raw, 0);
        if (!raw.equals(safe))
            throw new IllegalArgumentException("Unsupported audit snapshot fields or bounds");
        String text = raw.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > 60000)
            throw new IllegalArgumentException("Audit snapshot exceeds limit");
        return text;
    }

    public Snapshot read(String text) {
        if (text == null) return new Snapshot(null, false);
        try {
            if (text.getBytes(StandardCharsets.UTF_8).length > 65536)
                return new Snapshot(null, true);
            JsonNode raw = mapper.readTree(text);
            if (!raw.isObject()) return new Snapshot(null, true);
            JsonNode safe = clean(raw, 0);
            return new Snapshot(safe, !raw.equals(safe));
        } catch (Exception invalid) {
            return new Snapshot(null, true);
        }
    }

    private JsonNode clean(JsonNode node, int depth) {
        if (depth > 6) return NullNode.instance;
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            node.properties()
                    .forEach(
                            e -> {
                                if (FIELDS.contains(e.getKey()))
                                    result.set(e.getKey(), clean(e.getValue(), depth + 1));
                            });
            return result;
        }
        if (node.isArray()) {
            if (node.size() > 100) return NullNode.instance;
            var a = mapper.createArrayNode();
            node.forEach(n -> a.add(clean(n, depth + 1)));
            return a;
        }
        if (node.isTextual() && node.asText().getBytes(StandardCharsets.UTF_8).length > 2048)
            return NullNode.instance;
        return node;
    }
}
