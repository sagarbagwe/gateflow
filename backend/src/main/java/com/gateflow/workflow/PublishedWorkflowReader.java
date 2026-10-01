package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.cache.*;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Authorized read path only. Approval/submit/edit/publish commands never consume Redis. */
@Component
public class PublishedWorkflowReader {
    private final WorkflowRepository repository;
    private final RedisPolicyCache cache;
    private final WorkflowCacheCodec codec;
    private final WorkflowCacheProperties properties;

    public PublishedWorkflowReader(
            WorkflowRepository repository,
            RedisPolicyCache cache,
            WorkflowCacheCodec codec,
            WorkflowCacheProperties properties) {
        this.repository = repository;
        this.cache = cache;
        this.codec = codec;
        this.properties = properties;
    }

    public VersionView read(UUID org, UUID definition, UUID version) {
        // Fresh DB existence/status/revision gate, even when Redis has a value.
        var header = repository.versionHeader(org, definition, version, false);
        if (!properties.enabled() || header.status() != VersionStatus.PUBLISHED)
            return repository.withSteps(org, header);
        String key = RedisPolicyCache.key(org, definition, version);
        var stored = cache.get(key);
        if (stored.isPresent()) {
            var decoded = codec.decode(stored.get(), org, header);
            if (decoded.isPresent()) return decoded.get();
            cache.evict(key);
        }
        var result = repository.withSteps(org, header);
        codec.encode(org, result).ifPresent(value -> cache.put(key, value));
        return result;
    }
}
