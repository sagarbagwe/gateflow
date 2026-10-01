package com.gateflow.async;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class EventReferenceCodec {
    public static final int MAX_BYTES = 256;

    public static class InvalidEvent extends RuntimeException {
        public InvalidEvent() {
            super("Invalid event reference");
        }
    }

    private final ObjectMapper mapper;

    public EventReferenceCodec(ObjectMapper mapper) {
        this.mapper =
                mapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(3)
                                .maxStringLength(128)
                                .maxNumberLength(8)
                                .build());
    }

    public byte[] encode(UUID id) {
        return ("{\"schema\":1,\"eventId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    public UUID decode(byte[] body, String messageId) {
        if (body == null || body.length > MAX_BYTES) throw new InvalidEvent();
        try {
            var n = mapper.readTree(body);
            var names = new HashSet<String>();
            n.fieldNames().forEachRemaining(names::add);
            if (!n.isObject()
                    || !names.equals(Set.of("schema", "eventId"))
                    || !n.get("schema").isIntegralNumber()
                    || n.get("schema").intValue() != 1
                    || !n.get("eventId").isTextual()) throw new InvalidEvent();
            String raw = n.get("eventId").asText();
            if (!raw.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || !raw.equals(messageId)) throw new InvalidEvent();
            return UUID.fromString(raw);
        } catch (InvalidEvent e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidEvent();
        }
    }

    public int attempt(Object value) {
        if (!(value instanceof Integer n) || n < 0 || n > 3) throw new InvalidEvent();
        return (Integer) value;
    }
}
