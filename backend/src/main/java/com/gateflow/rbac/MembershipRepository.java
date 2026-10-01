package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class MembershipRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public MembershipRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
        this.jdbc = named.getJdbcTemplate();
    }

    public Optional<UUID> activeActor(UUID org, UUID user) {
        return jdbc
                .query(
                        """
SELECT m.id FROM memberships m JOIN organizations o ON o.id=m.organization_id JOIN users u ON u.id=m.user_id
WHERE m.organization_id=? AND m.user_id=? AND m.status='ACTIVE' AND o.status='ACTIVE' AND u.status='ACTIVE'
""",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        org,
                        user)
                .stream()
                .findFirst();
    }

    public void requireActiveTargetUser(UUID user) {
        var states =
                jdbc.query(
                        "SELECT status FROM users WHERE id=? FOR SHARE",
                        (rs, n) -> rs.getString(1),
                        user);
        if (states.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        if (!"ACTIVE".equals(states.getFirst()))
            throw new ApiException(
                    HttpStatus.CONFLICT, "ACCOUNT_INACTIVE", "User account is inactive");
    }

    public UUID create(UUID org, UUID user, Set<UUID> roles) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO memberships(id,organization_id,user_id) VALUES(?,?,?)", id, org, user);
        insertRoles(org, id, roles);
        return id;
    }

    public List<MemberView> page(UUID org, int limit, int offset) {
        var rows =
                jdbc.query(
                        """
SELECT m.id,m.user_id,u.email,u.display_name,m.status,u.status AS user_status,m.row_version
FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.organization_id=?
ORDER BY m.created_at DESC,m.id DESC LIMIT ? OFFSET ?
""",
                        (rs, n) ->
                                new MemberView(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("email"),
                                        rs.getString("display_name"),
                                        MembershipStatus.valueOf(rs.getString("status")),
                                        "ACTIVE".equals(rs.getString("user_status")),
                                        rs.getLong("row_version"),
                                        Set.of()),
                        org,
                        limit,
                        offset);
        return attachRoles(org, rows);
    }

    public MemberView find(UUID org, UUID id) {
        var rows =
                jdbc.query(
                        """
SELECT m.id,m.user_id,u.email,u.display_name,m.status,u.status AS user_status,m.row_version
FROM memberships m JOIN users u ON u.id=m.user_id WHERE m.organization_id=? AND m.id=?
""",
                        (rs, n) ->
                                new MemberView(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("email"),
                                        rs.getString("display_name"),
                                        MembershipStatus.valueOf(rs.getString("status")),
                                        "ACTIVE".equals(rs.getString("user_status")),
                                        rs.getLong("row_version"),
                                        Set.of()),
                        org,
                        id);
        if (rows.isEmpty())
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", "Membership not found");
        return attachRoles(org, rows).getFirst();
    }

    private List<MemberView> attachRoles(UUID org, List<MemberView> rows) {
        if (rows.isEmpty()) return List.of();
        Map<UUID, Set<UUID>> roles = new HashMap<>();
        named.query(
                "SELECT membership_id,role_id FROM membership_roles WHERE organization_id=:org AND"
                    + " membership_id IN (:ids)",
                Map.of("org", org, "ids", rows.stream().map(MemberView::id).toList()),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs ->
                                roles.computeIfAbsent(
                                                rs.getObject(1, UUID.class), id -> new HashSet<>())
                                        .add(rs.getObject(2, UUID.class)));
        return rows.stream()
                .map(
                        m ->
                                new MemberView(
                                        m.id(),
                                        m.userId(),
                                        m.email(),
                                        m.displayName(),
                                        m.status(),
                                        m.userActive(),
                                        m.version(),
                                        roles.getOrDefault(m.id(), Set.of())))
                .toList();
    }

    public int bumpVersion(UUID org, UUID id, long version) {
        return jdbc.update(
                "UPDATE memberships SET row_version=row_version+1 WHERE organization_id=? AND id=?"
                    + " AND row_version=?",
                org,
                id,
                version);
    }

    public void replaceRoles(UUID org, UUID id, Set<UUID> roles) {
        jdbc.update(
                "DELETE FROM membership_roles WHERE organization_id=? AND membership_id=?",
                org,
                id);
        insertRoles(org, id, roles);
    }

    private void insertRoles(UUID org, UUID id, Set<UUID> roles) {
        jdbc.batchUpdate(
                "INSERT INTO membership_roles(organization_id,membership_id,role_id) VALUES(?,?,?)",
                roles.stream().map(role -> new Object[] {org, id, role}).toList());
    }

    public void setStatus(UUID org, UUID id, MembershipStatus status) {
        jdbc.update(
                "UPDATE memberships SET status=? WHERE organization_id=? AND id=?",
                status.name(),
                org,
                id);
    }

    public int activeAdmins(UUID org) {
        return jdbc.queryForObject(
                """
SELECT count(DISTINCT m.id) FROM memberships m JOIN users u ON u.id=m.user_id
JOIN membership_roles mr ON mr.organization_id=m.organization_id AND mr.membership_id=m.id
JOIN roles r ON r.organization_id=mr.organization_id AND r.id=mr.role_id
WHERE m.organization_id=? AND m.status='ACTIVE' AND u.status='ACTIVE' AND r.is_system AND r.code='ADMIN'
""",
                Integer.class,
                org);
    }
}
