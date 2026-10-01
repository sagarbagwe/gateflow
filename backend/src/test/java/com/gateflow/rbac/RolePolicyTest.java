package com.gateflow.rbac;

import static com.gateflow.rbac.Permission.*;

import static org.assertj.core.api.Assertions.*;

import com.gateflow.http.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class RolePolicyTest {
    @Test
    void adminHasEveryPermissionButViewerCannotReadAllRequests() {
        assertThat(RolePolicy.DEFAULTS.get("ADMIN")).containsExactlyInAnyOrder(Permission.values());
        assertThat(RolePolicy.DEFAULTS.get("VIEWER")).doesNotContain(REQUEST_VIEW_ALL, ROLE_MANAGE);
    }

    @Test
    void unionIsDeduplicatedAndImmutable() {
        var role =
                new RbacDtos.RoleView(
                        UUID.randomUUID(), "TEST", "Test", false, 0, Set.of(WORKFLOW_VIEW));
        var result = RolePolicy.union(List.of(role, role));
        assertThat(result).containsExactly(WORKFLOW_VIEW);
        assertThatThrownBy(() -> result.add(ROLE_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void customAdminNameIsNotProtectedAdministrator() {
        assertThat(
                        RolePolicy.hasAdmin(
                                List.of(
                                        new RbacDtos.RoleView(
                                                UUID.randomUUID(),
                                                "ADMIN",
                                                "Admin",
                                                false,
                                                0,
                                                Set.of()))))
                .isFalse();
    }

    @Test
    void delegationCeilingRejectsEscalation() {
        assertThatThrownBy(
                        () -> RolePolicy.requireCeiling(Set.of(WORKFLOW_VIEW), Set.of(ROLE_MANAGE)))
                .isInstanceOf(ApiException.class);
        RolePolicy.requireCeiling(Set.of(WORKFLOW_VIEW), Set.of());
    }

    @Test
    void protectedMembershipRequiresActualAdmin() {
        assertThatThrownBy(() -> RolePolicy.requireAdminForProtectedMember(false, true, false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RolePolicy.requireAdminForProtectedMember(false, false, true))
                .isInstanceOf(ApiException.class);
        RolePolicy.requireAdminForProtectedMember(true, true, false);
    }

    @Test
    void lastAdminGuardOnlyRejectsRemovingFinalActiveAdmin() {
        assertThatThrownBy(() -> RolePolicy.protectLastAdmin(true, 1))
                .isInstanceOf(ApiException.class);
        RolePolicy.protectLastAdmin(true, 2);
        RolePolicy.protectLastAdmin(false, 1);
    }

    @Test
    void expectedVersionMustMatch() {
        assertThatThrownBy(() -> RolePolicy.requireVersion(1, 0)).isInstanceOf(ApiException.class);
        RolePolicy.requireVersion(1, 1);
    }

    @Test
    void boundedSliceDetectsNextPageAndCopiesValues() {
        var input = new ArrayList<>(List.of(1, 2, 3));
        var page = PageSlice.from(input, 2, 0);
        input.clear();
        assertThat(page.items()).containsExactly(1, 2);
        assertThat(page.hasMore()).isTrue();
        assertThatThrownBy(() -> page.items().add(4))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
