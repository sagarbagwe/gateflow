package com.gateflow.rbac;

import static com.gateflow.rbac.Permission.*;
import static com.gateflow.rbac.RbacDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;

import java.util.*;

public final class RolePolicy {
    private RolePolicy() {}

    public static final Map<String, Set<Permission>> DEFAULTS =
            Map.of(
                    "ADMIN", Set.copyOf(EnumSet.allOf(Permission.class)),
                    "MANAGER",
                            Set.of(
                                    WORKFLOW_VIEW,
                                    REQUEST_SUBMIT,
                                    REQUEST_VIEW_OWN,
                                    REQUEST_VIEW_ALL,
                                    REQUEST_APPROVE,
                                    REQUEST_WITHDRAW_OWN,
                                    AUDIT_VIEW),
                    "MEMBER",
                            Set.of(
                                    WORKFLOW_VIEW,
                                    REQUEST_SUBMIT,
                                    REQUEST_VIEW_OWN,
                                    REQUEST_WITHDRAW_OWN),
                    "VIEWER", Set.of(WORKFLOW_VIEW, REQUEST_VIEW_OWN));

    public static Set<Permission> union(Collection<RoleView> roles) {
        var result = EnumSet.noneOf(Permission.class);
        for (var role : roles) result.addAll(role.permissions());
        return Set.copyOf(result);
    }

    public static boolean hasAdmin(Collection<RoleView> roles) {
        return roles.stream().anyMatch(r -> r.system() && "ADMIN".equals(r.code()));
    }

    public static void requireCeiling(Set<Permission> caller, Set<Permission> managed) {
        if (!caller.containsAll(managed))
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "DELEGATION_DENIED",
                    "Cannot manage privileges beyond your permission scope");
    }

    public static void requireAdminForProtectedMember(
            boolean actorAdmin, boolean currentAdmin, boolean nextAdmin) {
        if ((currentAdmin || nextAdmin) && !actorAdmin)
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "ADMIN_REQUIRED",
                    "Only organization admins may manage admin membership");
    }

    public static void protectLastAdmin(boolean removesActiveAdmin, int activeAdmins) {
        if (removesActiveAdmin && activeAdmins <= 1)
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "LAST_ADMIN",
                    "Organization must retain an active administrator");
    }

    public static void requireVersion(long current, long expected) {
        if (current != expected)
            throw new ApiException(
                    HttpStatus.CONFLICT, "VERSION_CONFLICT", "Resource changed; reload and retry");
    }
}
