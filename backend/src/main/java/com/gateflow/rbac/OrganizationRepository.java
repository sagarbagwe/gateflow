package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class OrganizationRepository {
    private final JdbcTemplate jdbc;

    public OrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockActiveUser(UUID user) {
        if (jdbc.query(
                        "SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        user)
                .isEmpty())
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required");
    }

    public int countCreated(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM organizations WHERE created_by_user_id=?",
                Integer.class,
                user);
    }

    public void lock(UUID org) {
        if (jdbc.query(
                        "SELECT id FROM organizations WHERE id=? FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        org)
                .isEmpty()) throw notFound();
    }

    public void create(UUID id, UUID user, String name, String slug) {
        jdbc.update(
                "INSERT INTO organizations(id,name,slug,created_by_user_id) VALUES(?,?,?,?)",
                id,
                name,
                slug,
                user);
    }

    public OrganizationView find(UUID org) {
        return jdbc
                .query(
                        "SELECT id,name,slug FROM organizations WHERE id=?",
                        (rs, n) ->
                                new OrganizationView(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("slug")),
                        org)
                .stream()
                .findFirst()
                .orElseThrow(OrganizationRepository::notFound);
    }

    public List<OrganizationView> mine(UUID user, int limit, int offset) {
        return jdbc.query(
                """
                SELECT o.id,o.name,o.slug FROM organizations o
                JOIN memberships m ON m.organization_id=o.id JOIN users u ON u.id=m.user_id
                WHERE m.user_id=? AND m.status='ACTIVE' AND o.status='ACTIVE' AND u.status='ACTIVE'
                ORDER BY o.created_at DESC,o.id DESC LIMIT ? OFFSET ?
                """,
                (rs, n) ->
                        new OrganizationView(
                                rs.getObject("id", UUID.class),
                                rs.getString("name"),
                                rs.getString("slug")),
                user,
                limit,
                offset);
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization not found");
    }
}
