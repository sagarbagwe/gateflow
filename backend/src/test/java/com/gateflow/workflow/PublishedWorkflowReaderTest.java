package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gateflow.cache.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.*;

class PublishedWorkflowReaderTest {
    final WorkflowRepository repo = mock(WorkflowRepository.class);
    final RedisPolicyCache cache = mock(RedisPolicyCache.class);
    final WorkflowCacheCodec codec = mock(WorkflowCacheCodec.class);
    final WorkflowCacheProperties props =
            new WorkflowCacheProperties(true, Duration.ofMinutes(10), Duration.ofSeconds(5), 65536);
    final UUID org = UUID.randomUUID(), def = UUID.randomUUID(), version = UUID.randomUUID();
    final VersionView header =
            new VersionView(version, def, 1, VersionStatus.PUBLISHED, 1, Instant.now(), List.of());
    final VersionView body =
            new VersionView(
                    version,
                    def,
                    1,
                    VersionStatus.PUBLISHED,
                    1,
                    header.publishedAt(),
                    List.of(
                            new WorkflowStep(
                                    UUID.randomUUID(),
                                    1,
                                    "Review",
                                    UUID.randomUUID(),
                                    new Condition(ConditionType.ALWAYS, null, null))));
    final PublishedWorkflowReader reader = new PublishedWorkflowReader(repo, cache, codec, props);

    void header() {
        when(repo.versionHeader(org, def, version, false)).thenReturn(header);
    }

    @Test
    void hitStillLoadsHeaderButSkipsOrderedStepHydration() {
        header();
        String key = RedisPolicyCache.key(org, def, version);
        when(cache.get(key)).thenReturn(Optional.of("cached"));
        when(codec.decode("cached", org, header)).thenReturn(Optional.of(body));
        assertThat(reader.read(org, def, version)).isEqualTo(body);
        verify(repo).versionHeader(org, def, version, false);
        verify(repo, never()).withSteps(any(), any());
        verify(cache, never()).put(anyString(), anyString());
    }

    @Test
    void missHydratesAndCachesWithoutChangingResult() {
        header();
        String key = RedisPolicyCache.key(org, def, version);
        when(cache.get(key)).thenReturn(Optional.empty());
        when(repo.withSteps(org, header)).thenReturn(body);
        when(codec.encode(org, body)).thenReturn(Optional.of("encoded"));
        assertThat(reader.read(org, def, version)).isEqualTo(body);
        verify(repo).withSteps(org, header);
        verify(cache).put(key, "encoded");
    }

    @Test
    void invalidValueEvictedAndRebuilt() {
        header();
        String key = RedisPolicyCache.key(org, def, version);
        when(cache.get(key)).thenReturn(Optional.of("bad"));
        when(codec.decode("bad", org, header)).thenReturn(Optional.empty());
        when(repo.withSteps(org, header)).thenReturn(body);
        when(codec.encode(org, body)).thenReturn(Optional.of("good"));
        assertThat(reader.read(org, def, version)).isEqualTo(body);
        verify(cache).evict(key);
        verify(cache).put(key, "good");
    }

    @Test
    void draftAlwaysUsesDatabaseWithoutRedis() {
        var draft = new VersionView(version, def, 1, VersionStatus.DRAFT, 0, null, List.of());
        when(repo.versionHeader(org, def, version, false)).thenReturn(draft);
        when(repo.withSteps(org, draft)).thenReturn(draft);
        assertThat(reader.read(org, def, version)).isEqualTo(draft);
        verifyNoInteractions(cache, codec);
    }

    @Test
    void disabledUsesDatabaseWithoutRedis() {
        header();
        when(repo.withSteps(org, header)).thenReturn(body);
        var disabled =
                new PublishedWorkflowReader(
                        repo,
                        cache,
                        codec,
                        new WorkflowCacheProperties(
                                false, props.ttl(), props.failureCooldown(), props.maxBytes()));
        assertThat(disabled.read(org, def, version)).isEqualTo(body);
        verifyNoInteractions(cache, codec);
    }

    @Test
    void sourceDatabaseFailureIsNotMaskedByCache() {
        when(repo.versionHeader(org, def, version, false))
                .thenThrow(new DataAccessResourceFailureException("fixture"));
        assertThatThrownBy(() -> reader.read(org, def, version))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(cache, codec);
    }

    @Test
    void serializationSizeRejectionDoesNotFailTheRead() {
        header();
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.withSteps(org, header)).thenReturn(body);
        when(codec.encode(org, body)).thenReturn(Optional.empty());
        assertThat(reader.read(org, def, version)).isEqualTo(body);
        verify(cache, never()).put(anyString(), anyString());
    }
}
