package com.gateflow.rbac;

import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.auth.UserPrincipal;
import com.gateflow.http.PageSlice;
import com.gateflow.http.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/organizations")
public class RbacController {
    private final OrganizationService organizations;
    private final RoleService roles;
    private final MembershipService members;

    public RbacController(
            OrganizationService organizations, RoleService roles, MembershipService members) {
        this.organizations = organizations;
        this.roles = roles;
        this.members = members;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationView create(
            @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody CreateOrganization body,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        var result = organizations.create(user.id(), body, id(request));
        response.setHeader("Location", request.getRequestURI() + "/" + result.id());
        return result;
    }

    @GetMapping
    public PageSlice<OrganizationView> mine(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return organizations.mine(user.id(), limit, offset);
    }

    @GetMapping("/{orgId}")
    public OrganizationView detail(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID orgId) {
        return organizations.detail(user.id(), orgId);
    }

    @GetMapping("/{orgId}/me")
    public AccessView access(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID orgId) {
        return organizations.access(user.id(), orgId);
    }

    @GetMapping("/{orgId}/permissions")
    public List<PermissionView> permissions(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID orgId) {
        return roles.catalog(user.id(), orgId);
    }

    @GetMapping("/{orgId}/roles")
    public PageSlice<RoleView> roles(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return roles.list(user.id(), orgId, limit, offset);
    }

    @GetMapping("/{orgId}/roles/{roleId}")
    public RoleView role(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID roleId) {
        return roles.detail(user.id(), orgId, roleId);
    }

    @PostMapping(value = "/{orgId}/roles", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView createRole(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateRole body,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        var result = roles.create(user.id(), orgId, body, id(request));
        response.setHeader("Location", request.getRequestURI() + "/" + result.id());
        return result;
    }

    @PutMapping(value = "/{orgId}/roles/{roleId}/permissions", consumes = "application/json")
    public RoleView replacePermissions(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID roleId,
            @Valid @RequestBody ReplacePermissions body,
            HttpServletRequest request) {
        return roles.replace(user.id(), orgId, roleId, body, id(request));
    }

    @GetMapping("/{orgId}/memberships")
    public PageSlice<MemberView> members(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return members.list(user.id(), orgId, limit, offset);
    }

    @PostMapping(value = "/{orgId}/memberships", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView enroll(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @Valid @RequestBody EnrollMember body,
            HttpServletRequest request) {
        return members.enroll(user.id(), orgId, body, id(request));
    }

    @PutMapping(value = "/{orgId}/memberships/{memberId}/roles", consumes = "application/json")
    public MemberView replaceRoles(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ReplaceRoles body,
            HttpServletRequest request) {
        return members.replaceRoles(user.id(), orgId, memberId, body, id(request));
    }

    @PatchMapping(value = "/{orgId}/memberships/{memberId}/status", consumes = "application/json")
    public MemberView status(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID memberId,
            @Valid @RequestBody ChangeStatus body,
            HttpServletRequest request) {
        return members.status(user.id(), orgId, memberId, body, id(request));
    }

    private static String id(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
