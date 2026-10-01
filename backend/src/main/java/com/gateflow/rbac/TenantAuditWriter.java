package com.gateflow.rbac;

import com.gateflow.audit.AuditData;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Component
public class TenantAuditWriter {
    private final JdbcTemplate jdbc;
    private final AuditData data;

    public TenantAuditWriter(JdbcTemplate jdbc, AuditData data) {
        this.jdbc = jdbc;
        this.data = data;
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
                data.encode(oldValue),
                data.encode(newValue),
                requestId);
    }
}
