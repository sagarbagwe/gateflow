package com.gateflow.rbac;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RbacDtos {
    private RbacDtos() {}

    public record CreateOrganization(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug) {}

    public record CreateRole(
            @NotBlank @Size(max = 60) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull Set<@NotNull Permission> permissions) {}

    public record ReplacePermissions(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Set<@NotNull Permission> permissions) {}

    public record EnrollMember(
            @NotNull UUID userId, @NotNull @Size(min = 1, max = 10) Set<@NotNull UUID> roleIds) {}

    public record ReplaceRoles(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(max = 10) Set<@NotNull UUID> roleIds) {}

    public enum MembershipStatus {
        ACTIVE,
        SUSPENDED
    }

    public record ChangeStatus(
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull MembershipStatus status) {}

    public record OrganizationView(UUID id, String name, String slug) {}

    public record RoleView(
            UUID id,
            String code,
            String name,
            boolean system,
            long version,
            Set<Permission> permissions) {
        public RoleView {
            permissions = Set.copyOf(permissions);
        }
    }

    public record MemberView(
            UUID id,
            UUID userId,
            String email,
            String displayName,
            MembershipStatus status,
            boolean userActive,
            long version,
            Set<UUID> roleIds) {
        public MemberView {
            roleIds = Set.copyOf(roleIds);
        }
    }

    public record AccessView(
            UUID organizationId,
            UUID membershipId,
            UUID userId,
            List<RoleView> roles,
            Set<Permission> permissions) {
        public AccessView {
            roles = List.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    public record PermissionView(Permission code, String description) {}
}
