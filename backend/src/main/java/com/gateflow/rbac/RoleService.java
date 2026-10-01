package com.gateflow.rbac;

import static com.gateflow.rbac.Permission.*;
import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class RoleService {
    private final RoleRepository roles;
    private final AuthorizationService authorization;
    private final TenantAuditWriter audit;
    private final RbacProperties limits;

    public RoleService(
            RoleRepository roles,
            AuthorizationService authorization,
            TenantAuditWriter audit,
            RbacProperties limits) {
        this.roles = roles;
        this.authorization = authorization;
        this.audit = audit;
        this.limits = limits;
    }

    public PageSlice<RoleView> list(UUID user, UUID org, int limit, int offset) {
        authorization.require(user, org, ROLE_MANAGE);
        return PageSlice.from(roles.page(org, limit + 1, offset), limit, offset);
    }

    public RoleView detail(UUID user, UUID org, UUID role) {
        authorization.require(user, org, ROLE_MANAGE);
        return scoped(org, Set.of(role)).getFirst();
    }

    public List<PermissionView> catalog(UUID user, UUID org) {
        authorization.require(user, org, ROLE_MANAGE);
        return roles.catalog();
    }

    public List<RoleView> scoped(UUID org, Set<UUID> ids) {
        var found = roles.byIds(org, ids);
        if (found.size() != ids.size())
            throw new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "Role not found");
        return found;
    }

    @Transactional
    public RoleView create(UUID user, UUID org, CreateRole body, String requestId) {
        var actor = authorization.lockAndRequire(user, org, ROLE_MANAGE);
        if (RolePolicy.DEFAULTS.containsKey(body.code()))
            throw new ApiException(
                    HttpStatus.CONFLICT, "RESERVED_ROLE_CODE", "Role code is reserved");
        RolePolicy.requireCeiling(actor.permissions(), body.permissions());
        if (roles.count(org) >= limits.maxRolesPerOrganization())
            throw new ApiException(
                    HttpStatus.CONFLICT, "ROLE_LIMIT", "Organization role limit reached");
        UUID id;
        try {
            id = roles.create(org, body.code(), body.name().trim(), false, body.permissions());
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "ROLE_CONFLICT", "Role code already exists");
        }
        audit.record(
                org,
                actor.membershipId(),
                "ROLE_CREATED",
                "ROLE",
                id,
                null,
                Map.of("code", body.code(), "permissions", body.permissions(), "version", 0),
                requestId);
        return scoped(org, Set.of(id)).getFirst();
    }

    @Transactional
    public RoleView replace(
            UUID user, UUID org, UUID id, ReplacePermissions body, String requestId) {
        var actor = authorization.lockAndRequire(user, org, ROLE_MANAGE);
        var target = scoped(org, Set.of(id)).getFirst();
        if (target.system())
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "SYSTEM_ROLE_PROTECTED",
                    "Default role permissions are protected");
        RolePolicy.requireVersion(target.version(), body.expectedVersion());
        RolePolicy.requireCeiling(actor.permissions(), target.permissions());
        RolePolicy.requireCeiling(actor.permissions(), body.permissions());
        if (roles.bumpVersion(org, id, body.expectedVersion()) != 1)
            throw new ApiException(
                    HttpStatus.CONFLICT, "VERSION_CONFLICT", "Resource changed; reload and retry");
        roles.replacePermissions(org, id, body.permissions());
        audit.record(
                org,
                actor.membershipId(),
                "ROLE_PERMISSIONS_CHANGED",
                "ROLE",
                id,
                Map.of("permissions", target.permissions(), "version", target.version()),
                Map.of("permissions", body.permissions(), "version", target.version() + 1),
                requestId);
        return scoped(org, Set.of(id)).getFirst();
    }
}
