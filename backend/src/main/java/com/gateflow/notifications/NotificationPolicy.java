package com.gateflow.notifications;

import com.gateflow.http.ApiException;
import com.gateflow.rbac.AuthorizationService;
import com.gateflow.workflow.RequestAccessPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NotificationPolicy {
    private final AuthorizationService auth;
    private final RequestAccessPolicy access;
    private final JdbcTemplate jdbc;

    public NotificationPolicy(
            AuthorizationService auth, RequestAccessPolicy access, JdbcTemplate jdbc) {
        this.auth = auth;
        this.access = access;
        this.jdbc = jdbc;
    }

    public boolean visible(UUID org, UUID member, UUID request) {
        var users =
                jdbc.query(
                        "SELECT user_id FROM memberships WHERE organization_id=? AND id=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        org,
                        member);
        if (users.isEmpty()) return false;
        try {
            access.requireVisible(auth.requireMember(users.getFirst(), org), request);
            return true;
        } catch (ApiException hidden) {
            return false;
        }
    }

    public boolean actionable(UUID org, UUID member, UUID request, UUID step) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM requests r JOIN request_steps s ON"
                            + " s.organization_id=r.organization_id AND s.request_id=r.id JOIN"
                            + " workflow_steps w ON w.organization_id=s.organization_id AND"
                            + " w.workflow_version_id=s.workflow_version_id AND"
                            + " w.id=s.workflow_step_id WHERE r.organization_id=? AND r.id=? AND"
                            + " r.state='IN_REVIEW' AND s.id=? AND s.state='ACTIVE' AND"
                            + " s.assigned_membership_id=? AND r.requester_membership_id<>? AND"
                            + " EXISTS(SELECT 1 FROM membership_roles mr WHERE"
                            + " mr.organization_id=r.organization_id AND mr.membership_id=? AND"
                            + " mr.role_id=w.approver_role_id) AND EXISTS(SELECT 1 FROM"
                            + " membership_roles mr JOIN role_permissions rp ON"
                            + " rp.organization_id=mr.organization_id AND rp.role_id=mr.role_id"
                            + " WHERE mr.organization_id=r.organization_id AND mr.membership_id=?"
                            + " AND rp.permission_code='REQUEST_APPROVE'))",
                        Boolean.class,
                        org,
                        request,
                        step,
                        member,
                        member,
                        member,
                        member));
    }
}
