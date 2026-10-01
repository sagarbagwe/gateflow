package com.gateflow.notifications;

import com.gateflow.http.*;
import com.gateflow.rbac.AuthorizationService;
import com.gateflow.rbac.RbacDtos.AccessView;
import com.gateflow.rbac.TenantAuditWriter;
import com.gateflow.workflow.RequestAccessPolicy;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class NotificationService {
    public record Item(
            UUID id,
            UUID requestId,
            UUID eventId,
            String kind,
            String eventType,
            String eventState,
            Instant createdAt,
            Instant readAt,
            boolean actionable) {}

    private final AuthorizationService auth;
    private final RequestAccessPolicy access;
    private final NamedParameterJdbcTemplate jdbc;
    private final PreferenceRepository preferences;
    private final TenantAuditWriter audit;

    public NotificationService(
            AuthorizationService auth,
            RequestAccessPolicy access,
            NamedParameterJdbcTemplate jdbc,
            PreferenceRepository preferences,
            TenantAuditWriter audit) {
        this.auth = auth;
        this.access = access;
        this.jdbc = jdbc;
        this.preferences = preferences;
        this.audit = audit;
    }

    private MapSqlParameterSource params(AccessView actor) {
        return new MapSqlParameterSource(access.visible(actor).params())
                .addValue(
                        "canApprove",
                        actor.permissions().contains(com.gateflow.rbac.Permission.REQUEST_APPROVE));
    }

    private String where(AccessView actor) {
        return "n.organization_id=:org AND n.recipient_membership_id=:actor AND n.in_app AND "
                + access.visible(actor).sql();
    }

    private String from() {
        return " FROM notifications n JOIN requests r ON r.organization_id=n.organization_id AND"
                + " r.id=n.request_id JOIN outbox_events e ON"
                + " e.organization_id=n.organization_id AND e.id=n.event_id ";
    }

    private String select() {
        return "SELECT"
                   + " n.id,n.request_id,n.event_id,n.kind,e.event_type,e.request_state,n.created_at,n.read_at,"
                   + " (:canApprove AND n.kind='ACTION_REQUIRED' AND r.state='IN_REVIEW' AND"
                   + " EXISTS(SELECT 1 FROM request_steps s JOIN workflow_steps w ON"
                   + " w.organization_id=s.organization_id AND"
                   + " w.workflow_version_id=s.workflow_version_id AND w.id=s.workflow_step_id"
                   + " WHERE s.organization_id=r.organization_id AND s.request_id=r.id AND"
                   + " s.id=n.step_id AND "
                + access.pendingAssignee()
                + ")) AS actionable";
    }

    private List<Item> rows(String sql, MapSqlParameterSource p) {
        return jdbc.query(
                sql,
                p,
                (rs, n) ->
                        new Item(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getObject(3, UUID.class),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getTimestamp(7).toInstant(),
                                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                                rs.getBoolean(9)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageSlice<Item> list(UUID user, UUID org, boolean unreadOnly, int limit, int offset) {
        var actor = auth.requireMember(user, org);
        var p = params(actor).addValue("fetch", limit + 1).addValue("offset", offset);
        var found =
                rows(
                        select()
                                + from()
                                + " WHERE "
                                + where(actor)
                                + (unreadOnly ? " AND n.read_at IS NULL" : "")
                                + " ORDER BY n.created_at DESC,n.id DESC LIMIT :fetch OFFSET"
                                + " :offset",
                        p);
        return PageSlice.from(found, limit, offset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public long unread(UUID user, UUID org) {
        var actor = auth.requireMember(user, org);
        return jdbc.queryForObject(
                "SELECT count(*)" + from() + " WHERE " + where(actor) + " AND n.read_at IS NULL",
                params(actor),
                Long.class);
    }

    @Transactional
    public Item read(UUID user, UUID org, UUID id) {
        var actor = auth.shareAndRequire(user, org);
        var p = params(actor).addValue("id", id);
        var found =
                rows(
                        select()
                                + from()
                                + " WHERE "
                                + where(actor)
                                + " AND n.id=:id FOR UPDATE OF n",
                        p);
        if (found.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found");
        jdbc.update(
                "UPDATE notifications SET read_at=clock_timestamp() WHERE organization_id=:org AND"
                        + " id=:id AND read_at IS NULL",
                p);
        return rows(select() + from() + " WHERE " + where(actor) + " AND n.id=:id", p).getFirst();
    }

    @Transactional(readOnly = true)
    public PreferenceRepository.Preferences preferences(UUID user, UUID org) {
        var actor = auth.requireMember(user, org);
        return preferences.find(org, actor.membershipId());
    }

    @Transactional
    public PreferenceRepository.Preferences updatePreferences(
            UUID user, UUID org, boolean inApp, boolean email, long version, String trace) {
        var actor = auth.shareAndRequire(user, org);
        var before = preferences.find(org, actor.membershipId());
        if (!preferences.update(org, actor.membershipId(), inApp, email, version))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "VERSION_CONFLICT",
                    "Preferences changed; reload and retry");
        var after = preferences.find(org, actor.membershipId());
        audit.record(
                org,
                actor.membershipId(),
                "NOTIFICATION_PREFERENCES_CHANGED",
                "NOTIFICATION_PREFERENCES",
                actor.membershipId(),
                Map.of(
                        "inAppEnabled",
                        before.inAppEnabled(),
                        "emailEnabled",
                        before.emailEnabled(),
                        "version",
                        before.version()),
                Map.of(
                        "inAppEnabled",
                        after.inAppEnabled(),
                        "emailEnabled",
                        after.emailEnabled(),
                        "version",
                        after.version()),
                trace);
        return after;
    }
}
