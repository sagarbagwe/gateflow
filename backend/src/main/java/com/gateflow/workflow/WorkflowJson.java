package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class WorkflowJson {
    private final ObjectMapper mapper;

    public WorkflowJson(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize workflow value");
        }
    }

    public Condition condition(String json) {
        try {
            if (mapper.readTree(json).isObject() && mapper.readTree(json).isEmpty())
                return new Condition(ConditionType.ALWAYS, null, null);
            return mapper.readValue(json, Condition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted workflow condition");
        }
    }

    public Map<String, String> details(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid persisted request details");
        }
    }

    public String hash(Object body) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            encode(canonical(mapper.valueToTree(body)))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isNumber())
            return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
                    node.decimalValue().stripTrailingZeros());
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            var names = new TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) {
            var result = mapper.createArrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }
}
