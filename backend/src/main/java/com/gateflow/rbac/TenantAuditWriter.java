package com.gateflow.rbac;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Component
public class TenantAuditWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TenantAuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            UUID org,
            UUID actor,
            String action,
            String type,
            UUID resource,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            String requestId) {
        jdbc.update(
                """
INSERT INTO audit_logs(organization_id,actor_kind,actor_membership_id,action,resource_type,resource_id,old_value,new_value,correlation_id)
VALUES(?,'USER',?,?,?,?,?::jsonb,?::jsonb,?)
""",
                org,
                actor,
                action,
                type,
                resource,
                json(oldValue),
                json(newValue),
                requestId);
    }

    private String json(Map<String, ?> value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Unable to serialize safe audit snapshot");
        }
    }
}
