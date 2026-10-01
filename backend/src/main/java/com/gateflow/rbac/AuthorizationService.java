package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class AuthorizationService {
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final OrganizationRepository organizations;

    public AuthorizationService(
            MembershipRepository memberships,
            RoleRepository roles,
            OrganizationRepository organizations) {
        this.memberships = memberships;
        this.roles = roles;
        this.organizations = organizations;
    }

    public AccessView requireMember(UUID user, UUID org) {
        UUID member =
                memberships
                        .activeActor(org, user)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "ORGANIZATION_NOT_FOUND",
                                                "Organization not found"));
        var assigned = roles.forMember(org, member);
        return new AccessView(org, member, user, assigned, RolePolicy.union(assigned));
    }

    public AccessView require(UUID user, UUID org, Permission... required) {
        var access = requireMember(user, org);
        for (var permission : required)
            if (!access.permissions().contains(permission))
                throw new ApiException(
                        HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "Permission denied");
        return access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AccessView lockAndRequire(UUID user, UUID org, Permission... required) {
        requireMember(user, org); // Outsiders do not obtain an organization mutation lock.
        organizations.lock(org);
        return require(user, org, required); // Recheck after waiting; no stale-grant TOCTOU.
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AccessView shareAndRequire(UUID user, UUID org, Permission... required) {
        requireMember(user, org);
        organizations.share(org);
        return require(user, org, required);
    }
}
