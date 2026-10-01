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
public class MembershipService {
    private final MembershipRepository memberships;
    private final RoleService roleService;
    private final AuthorizationService authorization;
    private final TenantAuditWriter audit;

    public MembershipService(
            MembershipRepository memberships,
            RoleService roleService,
            AuthorizationService authorization,
            TenantAuditWriter audit) {
        this.memberships = memberships;
        this.roleService = roleService;
        this.authorization = authorization;
        this.audit = audit;
    }

    public PageSlice<MemberView> list(UUID user, UUID org, int limit, int offset) {
        authorization.require(user, org, MEMBERSHIP_MANAGE);
        return PageSlice.from(memberships.page(org, limit + 1, offset), limit, offset);
    }

    @Transactional
    public MemberView enroll(UUID user, UUID org, EnrollMember body, String requestId) {
        var actor = authorization.lockAndRequire(user, org, MEMBERSHIP_MANAGE, ROLE_MANAGE);
        var assigned = roleService.scoped(org, body.roleIds());
        RolePolicy.requireAdminForProtectedMember(
                RolePolicy.hasAdmin(actor.roles()), false, RolePolicy.hasAdmin(assigned));
        RolePolicy.requireCeiling(actor.permissions(), RolePolicy.union(assigned));
        memberships.requireActiveTargetUser(body.userId());
        UUID member;
        try {
            member = memberships.create(org, body.userId(), body.roleIds());
        } catch (DataIntegrityViolationException duplicate) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "MEMBERSHIP_CONFLICT", "Membership already exists");
        }
        audit.record(
                org,
                actor.membershipId(),
                "MEMBERSHIP_CREATED",
                "MEMBERSHIP",
                member,
                null,
                Map.of("roleIds", body.roleIds(), "status", "ACTIVE", "version", 0),
                requestId);
        return memberships.find(org, member);
    }

    @Transactional
    public MemberView replaceRoles(
            UUID user, UUID org, UUID id, ReplaceRoles body, String requestId) {
        var actor = authorization.lockAndRequire(user, org, ROLE_MANAGE);
        var target = memberships.find(org, id);
        RolePolicy.requireVersion(target.version(), body.expectedVersion());
        var before = roleService.scoped(org, target.roleIds());
        var after = roleService.scoped(org, body.roleIds());
        boolean oldAdmin = RolePolicy.hasAdmin(before), newAdmin = RolePolicy.hasAdmin(after);
        RolePolicy.requireAdminForProtectedMember(
                RolePolicy.hasAdmin(actor.roles()), oldAdmin, newAdmin);
        RolePolicy.requireCeiling(actor.permissions(), RolePolicy.union(before));
        RolePolicy.requireCeiling(actor.permissions(), RolePolicy.union(after));
        RolePolicy.protectLastAdmin(
                oldAdmin
                        && !newAdmin
                        && target.status() == MembershipStatus.ACTIVE
                        && target.userActive(),
                memberships.activeAdmins(org));
        bump(org, id, body.expectedVersion());
        memberships.replaceRoles(org, id, body.roleIds());
        audit.record(
                org,
                actor.membershipId(),
                "MEMBERSHIP_ROLES_CHANGED",
                "MEMBERSHIP",
                id,
                Map.of("roleIds", target.roleIds(), "version", target.version()),
                Map.of("roleIds", body.roleIds(), "version", target.version() + 1),
                requestId);
        return memberships.find(org, id);
    }

    @Transactional
    public MemberView status(UUID user, UUID org, UUID id, ChangeStatus body, String requestId) {
        var actor = authorization.lockAndRequire(user, org, MEMBERSHIP_MANAGE);
        var target = memberships.find(org, id);
        RolePolicy.requireVersion(target.version(), body.expectedVersion());
        var assigned = roleService.scoped(org, target.roleIds());
        boolean admin = RolePolicy.hasAdmin(assigned);
        RolePolicy.requireAdminForProtectedMember(RolePolicy.hasAdmin(actor.roles()), admin, admin);
        RolePolicy.requireCeiling(actor.permissions(), RolePolicy.union(assigned));
        if (body.status() == MembershipStatus.ACTIVE)
            memberships.requireActiveTargetUser(target.userId());
        if (body.status() == target.status()) return target;
        RolePolicy.protectLastAdmin(
                admin
                        && target.status() == MembershipStatus.ACTIVE
                        && target.userActive()
                        && body.status() == MembershipStatus.SUSPENDED,
                memberships.activeAdmins(org));
        bump(org, id, body.expectedVersion());
        memberships.setStatus(org, id, body.status());
        audit.record(
                org,
                actor.membershipId(),
                "MEMBERSHIP_STATUS_CHANGED",
                "MEMBERSHIP",
                id,
                Map.of("status", target.status(), "version", target.version()),
                Map.of("status", body.status(), "version", target.version() + 1),
                requestId);
        return memberships.find(org, id);
    }

    private void bump(UUID org, UUID member, long version) {
        if (memberships.bumpVersion(org, member, version) != 1)
            throw new ApiException(
                    HttpStatus.CONFLICT, "VERSION_CONFLICT", "Resource changed; reload and retry");
    }
}
