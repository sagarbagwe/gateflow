package com.gateflow.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReviewerService {
    private final JdbcTemplate jdbc;

    public ReviewerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ELIGIBLE =
            """
SELECT m.id FROM memberships m JOIN users u ON u.id=m.user_id
JOIN membership_roles required ON required.organization_id=m.organization_id AND required.membership_id=m.id AND required.role_id=?
WHERE m.organization_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND m.id<>?
AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_permissions rp ON rp.organization_id=mr.organization_id AND rp.role_id=mr.role_id
  WHERE mr.organization_id=m.organization_id AND mr.membership_id=m.id AND rp.permission_code='REQUEST_APPROVE')
""";

    public UUID select(UUID org, UUID role, UUID requester) {
        return jdbc
                .query(
                        ELIGIBLE + " ORDER BY m.created_at,m.id LIMIT 1",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        role,
                        org,
                        requester)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                WorkflowPolicy.conflict(
                                        "NO_ELIGIBLE_REVIEWER",
                                        "No eligible non-requester reviewer is available"));
    }

    public boolean eligible(UUID org, UUID role, UUID member, UUID requester) {
        if (member == null) return false;
        return !jdbc.query(
                        ELIGIBLE + " AND m.id=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        role,
                        org,
                        requester,
                        member)
                .isEmpty();
    }
}
