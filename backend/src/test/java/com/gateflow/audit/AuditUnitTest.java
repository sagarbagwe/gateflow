package com.gateflow.audit;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.*;

class AuditUnitTest {
    final AuditData data = new AuditData(new ObjectMapper());

    @Test
    void safeEvidenceRoundTrips() {
        var text =
                data.encode(
                        Map.of(
                                "state",
                                "APPROVED",
                                "version",
                                2,
                                "roleIds",
                                List.of(UUID.randomUUID())));
        assertThat(data.read(text).redacted()).isFalse();
    }

    @Test
    void createOldValueCanBeNull() {
        assertThat(data.encode(null)).isNull();
        assertThat(data.read(null).value()).isNull();
    }

    @Test
    void unknownSecretsNeverPassWriter() {
        assertThatThrownBy(() -> data.encode(Map.of("password", "fixture-secret")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedSecretsAreRemovedFromRead() {
        var s = data.read("{\"condition\":{\"token\":\"fixture\",\"type\":\"ALWAYS\"}}");
        assertThat(s.redacted()).isTrue();
        assertThat(s.value().toString()).doesNotContain("token", "fixture");
    }

    @Test
    void corruptAndNonObjectLegacySnapshotsAreOmitted() {
        for (String value : List.of("invalid", "[]", "null"))
            assertThat(data.read(value).redacted()).isTrue();
    }

    @Test
    void depthAndArrayBoundsAreEnforced() {
        var list = new ArrayList<Integer>();
        for (int i = 0; i < 101; i++) list.add(i);
        assertThatThrownBy(() -> data.encode(Map.of("steps", list)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedLegacyTextIsOmittedBeforeParse() {
        assertThat(data.read("x".repeat(65537)).value()).isNull();
        assertThat(data.read("x".repeat(65537)).redacted()).isTrue();
    }

    @Test
    void invalidAuditFiltersFail() {
        assertThatThrownBy(() -> new AuditQuery("bad", null, null, null, null, null, null, 20, 0))
                .isInstanceOf(com.gateflow.http.ApiException.class);
        assertThatThrownBy(() -> new AuditQuery(null, null, null, null, null, null, null, 101, 0))
                .isInstanceOf(com.gateflow.http.ApiException.class);
    }
}
