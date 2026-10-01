package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class RoleRepository {
    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    public RoleRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
        this.jdbc = named.getJdbcTemplate();
    }

    private static final String COLUMNS =
            "r.id,r.code,r.name,r.is_system,r.row_version,rp.permission_code";
    private static final String JOIN =
            " LEFT JOIN role_permissions rp ON rp.organization_id=r.organization_id AND"
                + " rp.role_id=r.id ";
    private static final String ORDER = " ORDER BY r.code,r.id,rp.permission_code";

    private List<RoleView> read(String sql, Map<String, ?> params) {
        record Meta(UUID id, String code, String name, boolean system, long version) {}
        Map<UUID, Meta> meta = new LinkedHashMap<>();
        Map<UUID, Set<Permission>> permissions = new HashMap<>();
        named.query(
                sql,
                params,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> {
                            UUID id = rs.getObject("id", UUID.class);
                            meta.putIfAbsent(
                                    id,
                                    new Meta(
                                            id,
                                            rs.getString("code"),
                                            rs.getString("name"),
                                            rs.getBoolean("is_system"),
                                            rs.getLong("row_version")));
                            var set =
                                    permissions.computeIfAbsent(
                                            id, key -> EnumSet.noneOf(Permission.class));
                            String permission = rs.getString("permission_code");
                            if (permission != null) set.add(Permission.valueOf(permission));
                        });
        return meta.values().stream()
                .map(
                        m ->
                                new RoleView(
                                        m.id(),
                                        m.code(),
                                        m.name(),
                                        m.system(),
                                        m.version(),
                                        permissions.get(m.id())))
                .toList();
    }

    public List<RoleView> page(UUID org, int limit, int offset) {
        return read(
                "WITH page AS (SELECT * FROM roles WHERE organization_id=:org ORDER BY code,id"
                    + " LIMIT :limit OFFSET :offset) SELECT "
                        + COLUMNS
                        + " FROM page r "
                        + JOIN
                        + ORDER,
                Map.of("org", org, "limit", limit, "offset", offset));
    }

    public List<RoleView> byIds(UUID org, Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return read(
                "SELECT "
                        + COLUMNS
                        + " FROM roles r "
                        + JOIN
                        + " WHERE r.organization_id=:org AND r.id IN (:ids)"
                        + ORDER,
                Map.of("org", org, "ids", ids));
    }

    public List<RoleView> forMember(UUID org, UUID member) {
        return read(
                "SELECT "
                        + COLUMNS
                        + " FROM roles r JOIN membership_roles mr ON"
                        + " mr.organization_id=r.organization_id AND mr.role_id=r.id "
                        + JOIN
                        + " WHERE r.organization_id=:org AND mr.membership_id=:member"
                        + ORDER,
                Map.of("org", org, "member", member));
    }

    public int count(UUID org) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE organization_id=?", Integer.class, org);
    }

    public UUID create(
            UUID org, String code, String name, boolean system, Set<Permission> permissions) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO roles(id,organization_id,code,name,is_system) VALUES(?,?,?,?,?)",
                id,
                org,
                code,
                name,
                system);
        replacePermissions(org, id, permissions);
        return id;
    }

    public int bumpVersion(UUID org, UUID role, long version) {
        return jdbc.update(
                "UPDATE roles SET row_version=row_version+1 WHERE organization_id=? AND id=? AND"
                    + " row_version=?",
                org,
                role,
                version);
    }

    public void replacePermissions(UUID org, UUID role, Set<Permission> permissions) {
        jdbc.update(
                "DELETE FROM role_permissions WHERE organization_id=? AND role_id=?", org, role);
        SqlParameterSource[] batch =
                permissions.stream()
                        .map(
                                p ->
                                        new MapSqlParameterSource()
                                                .addValue("org", org)
                                                .addValue("role", role)
                                                .addValue("permission", p.name()))
                        .toArray(SqlParameterSource[]::new);
        named.batchUpdate(
                "INSERT INTO role_permissions(organization_id,role_id,permission_code)"
                    + " VALUES(:org,:role,:permission)",
                batch);
    }

    public List<PermissionView> catalog() {
        return jdbc.query(
                "SELECT code,description FROM permissions ORDER BY code",
                (rs, n) ->
                        new PermissionView(Permission.valueOf(rs.getString(1)), rs.getString(2)));
    }
}
