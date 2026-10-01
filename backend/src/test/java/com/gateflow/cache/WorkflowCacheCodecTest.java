package com.gateflow.cache;

import static com.gateflow.workflow.WorkflowDtos.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.validation.Validation;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class WorkflowCacheCodecTest {
    static final jakarta.validation.ValidatorFactory FACTORY =
            Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    final ObjectMapper mapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final WorkflowCacheProperties props =
            new WorkflowCacheProperties(true, Duration.ofMinutes(10), Duration.ofSeconds(5), 65536);
    final WorkflowCacheCodec codec = new WorkflowCacheCodec(mapper, props, FACTORY.getValidator());
    final UUID org = UUID.randomUUID(),
            definition = UUID.randomUUID(),
            version = UUID.randomUUID(),
            role = UUID.randomUUID();
    final VersionView header =
            new VersionView(
                    version,
                    definition,
                    1,
                    VersionStatus.PUBLISHED,
                    1,
                    Instant.parse("2020-01-01T00:00:00Z"),
                    List.of());

    VersionView value(List<WorkflowStep> steps) {
        return new VersionView(
                version, definition, 1, VersionStatus.PUBLISHED, 1, header.publishedAt(), steps);
    }

    WorkflowStep step(int position) {
        return new WorkflowStep(
                UUID.randomUUID(),
                position,
                "Review",
                role,
                new Condition(ConditionType.ALWAYS, null, null));
    }

    String text(VersionView v) {
        try {
            return mapper.writeValueAsString(new WorkflowCacheCodec.Envelope(1, org, v));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void plainJsonRoundTripWithTypedImmutableDto() {
        var v = value(List.of(step(1)));
        String encoded = codec.encode(org, v).orElseThrow();
        assertThat(encoded)
                .contains("organizationId", "PUBLISHED")
                .doesNotContain("@class", "java.util");
        assertThat(codec.decode(encoded, org, header)).contains(v);
        assertThatThrownBy(() -> codec.decode(encoded, org, header).orElseThrow().steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void otherTenantRejected() {
        assertThat(codec.decode(text(value(List.of(step(1)))), UUID.randomUUID(), header))
                .isEmpty();
    }

    @Test
    void headerVersionIdentityAndStatusMustMatch() {
        var v = value(List.of(step(1)));
        for (var other :
                List.of(
                        new VersionView(
                                UUID.randomUUID(),
                                definition,
                                1,
                                VersionStatus.PUBLISHED,
                                1,
                                header.publishedAt(),
                                List.of()),
                        new VersionView(
                                version,
                                UUID.randomUUID(),
                                1,
                                VersionStatus.PUBLISHED,
                                1,
                                header.publishedAt(),
                                List.of()),
                        new VersionView(
                                version,
                                definition,
                                1,
                                VersionStatus.PUBLISHED,
                                2,
                                header.publishedAt(),
                                List.of()),
                        new VersionView(
                                version,
                                definition,
                                2,
                                VersionStatus.PUBLISHED,
                                1,
                                header.publishedAt(),
                                List.of()),
                        new VersionView(
                                version, definition, 1, VersionStatus.DRAFT, 1, null, List.of()),
                        new VersionView(
                                version,
                                definition,
                                1,
                                VersionStatus.PUBLISHED,
                                1,
                                header.publishedAt().plusSeconds(1),
                                List.of())))
            assertThat(codec.decode(text(v), org, other)).isEmpty();
    }

    @Test
    void malformedUnknownDuplicateTrailingAndFractionalSchemaRejected() {
        String text = text(value(List.of(step(1))));
        for (String bad :
                List.of(
                        "not-json",
                        "null",
                        text + " {}",
                        text.replace("\"schema\":1", "\"schema\":2"),
                        text.replace("\"schema\":1", "\"schema\":1.5"),
                        text.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                        text.substring(0, text.length() - 1) + ",\"unknown\":true}"))
            assertThat(codec.decode(bad, org, header)).isEmpty();
    }

    @Test
    void emptyTooManyAndOutOfOrderStepsRejected() {
        assertThat(codec.decode(text(value(List.of())), org, header)).isEmpty();
        assertThat(codec.decode(text(value(List.of(step(2)))), org, header)).isEmpty();
        var steps = new ArrayList<WorkflowStep>();
        for (int i = 1; i <= 51; i++) steps.add(step(i));
        assertThat(codec.decode(text(value(steps)), org, header)).isEmpty();
    }

    @Test
    void duplicateStepIdsRejected() {
        var a = step(1);
        assertThat(
                        codec.decode(
                                text(
                                        value(
                                                List.of(
                                                        a,
                                                        new WorkflowStep(
                                                                a.id(),
                                                                2,
                                                                "Again",
                                                                role,
                                                                a.condition())))),
                                org,
                                header))
                .isEmpty();
    }

    @Test
    void invalidStepFieldsAndBusinessConditionsRejected() {
        for (var bad :
                List.of(
                        new WorkflowStep(
                                null,
                                1,
                                "Review",
                                role,
                                new Condition(ConditionType.ALWAYS, null, null)),
                        new WorkflowStep(
                                UUID.randomUUID(),
                                1,
                                "",
                                role,
                                new Condition(ConditionType.ALWAYS, null, null)),
                        new WorkflowStep(
                                UUID.randomUUID(),
                                1,
                                "Review",
                                null,
                                new Condition(ConditionType.ALWAYS, null, null)),
                        new WorkflowStep(
                                UUID.randomUUID(),
                                1,
                                "Review",
                                role,
                                new Condition(
                                        ConditionType.ALWAYS, java.math.BigDecimal.ONE, "USD")),
                        new WorkflowStep(
                                UUID.randomUUID(),
                                1,
                                "Review",
                                role,
                                new Condition(
                                        ConditionType.PURCHASE_AMOUNT_AT_LEAST,
                                        java.math.BigDecimal.ZERO,
                                        "USD"))))
            assertThat(codec.decode(text(value(List.of(bad))), org, header)).isEmpty();
    }

    @Test
    void oversizedAndDeeplyNestedInputRejected() {
        assertThat(codec.decode("東".repeat(22000), org, header)).isEmpty();
        assertThat(codec.decode("[".repeat(20) + "0" + "]".repeat(20), org, header)).isEmpty();
    }

    @Test
    void oversizedEncodingSkipsCacheNotBusinessRead() {
        var small =
                new WorkflowCacheCodec(
                        mapper,
                        new WorkflowCacheProperties(
                                true, Duration.ofMinutes(10), Duration.ofSeconds(1), 1024),
                        FACTORY.getValidator());
        var steps = new ArrayList<WorkflowStep>();
        for (int i = 1; i <= 50; i++) steps.add(step(i));
        assertThat(small.encode(org, value(steps))).isEmpty();
    }
}
