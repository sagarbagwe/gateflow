package com.gateflow.cache;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.gateflow.workflow.WorkflowPolicy;

import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class WorkflowCacheCodec {
    public record Envelope(int schema, UUID organizationId, VersionView policy) {}

    private final ObjectMapper mapper;
    private final WorkflowCacheProperties properties;
    private final Validator validator;

    public WorkflowCacheCodec(
            ObjectMapper mapper, WorkflowCacheProperties properties, Validator validator) {
        this.mapper =
                mapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(8)
                                .maxStringLength(properties.maxBytes())
                                .maxNumberLength(32)
                                .build());
        this.properties = properties;
        this.validator = validator;
    }

    public Optional<String> encode(UUID org, VersionView value) {
        try {
            var text = mapper.writeValueAsString(new Envelope(1, org, value));
            return text.getBytes(StandardCharsets.UTF_8).length <= properties.maxBytes()
                            && decode(text, org, value).isPresent()
                    ? Optional.of(text)
                    : Optional.empty();
        } catch (JsonProcessingException error) {
            return Optional.empty();
        }
    }

    public Optional<VersionView> decode(String value, UUID org, VersionView header) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > properties.maxBytes())
            return Optional.empty();
        try {
            var e = mapper.readValue(value, Envelope.class);
            var v = e.policy();
            if (e.schema() != 1
                    || !org.equals(e.organizationId())
                    || v == null
                    || v.status() != VersionStatus.PUBLISHED
                    || header.status() != VersionStatus.PUBLISHED
                    || !header.id().equals(v.id())
                    || !header.workflowDefinitionId().equals(v.workflowDefinitionId())
                    || header.version() != v.version()
                    || header.versionNumber() != v.versionNumber()
                    || !Objects.equals(header.publishedAt(), v.publishedAt())
                    || v.steps().isEmpty()
                    || v.steps().size() > 50) return Optional.empty();
            var ids = new HashSet<UUID>();
            var inputs = new ArrayList<StepInput>();
            int position = 0;
            for (var s : v.steps()) {
                if (s.id() == null || !ids.add(s.id()) || s.position() != ++position)
                    return Optional.empty();
                inputs.add(new StepInput(s.name(), s.approverRoleId(), s.condition()));
            }
            if (!validator.validate(new VersionInput(inputs)).isEmpty()) return Optional.empty();
            WorkflowPolicy.validateSteps(inputs);
            return Optional.of(v);
        } catch (Exception malformed) {
            return Optional.empty();
        } // Untrusted cached bytes are disposable.
    }
}
