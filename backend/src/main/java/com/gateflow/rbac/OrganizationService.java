package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class OrganizationService {
    private final OrganizationRepository organizations;
    private final RoleRepository roles;
    private final MembershipRepository memberships;
    private final AuthorizationService authorization;
    private final TenantAuditWriter audit;
    private final RbacProperties limits;

    public OrganizationService(
            OrganizationRepository organizations,
            RoleRepository roles,
            MembershipRepository memberships,
            AuthorizationService authorization,
            TenantAuditWriter audit,
            RbacProperties limits) {
        this.organizations = organizations;
        this.roles = roles;
        this.memberships = memberships;
        this.authorization = authorization;
        this.audit = audit;
        this.limits = limits;
    }

    @Transactional
    public OrganizationView create(UUID user, CreateOrganization body, String requestId) {
        organizations.lockActiveUser(
                user); // Serializes creator quota, separate from per-org mutations.
        if (organizations.countCreated(user) >= limits.maxOrganizationsPerUser())
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_LIMIT",
                    "Organization creation limit reached");
        UUID org = UUID.randomUUID();
        try {
            organizations.create(org, user, body.name().trim(), body.slug());
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "SLUG_CONFLICT", "Organization slug unavailable");
        }
        UUID admin = null;
        for (var entry : RolePolicy.DEFAULTS.entrySet()) {
            UUID role = roles.create(org, entry.getKey(), entry.getKey(), true, entry.getValue());
            if ("ADMIN".equals(entry.getKey())) admin = role;
        }
        UUID member = memberships.create(org, user, Set.of(Objects.requireNonNull(admin)));
        audit.record(
                org,
                member,
                "ORGANIZATION_CREATED",
                "ORGANIZATION",
                org,
                null,
                Map.of("organizationId", org, "creatorMembershipId", member),
                requestId);
        return organizations.find(org);
    }

    public PageSlice<OrganizationView> mine(UUID user, int limit, int offset) {
        return PageSlice.from(organizations.mine(user, limit + 1, offset), limit, offset);
    }

    public OrganizationView detail(UUID user, UUID org) {
        authorization.requireMember(user, org);
        return organizations.find(org);
    }

    public AccessView access(UUID user, UUID org) {
        return authorization.requireMember(user, org);
    }
}
