package com.gateflow.rbac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gateflow.http.ApiException;

import org.junit.jupiter.api.Test;

import java.util.*;

class AuthorizationServiceTest {
    @Test
    void privilegesAreReloadedAfterWaitingForMutationLock() {
        var members = mock(MembershipRepository.class);
        var roles = mock(RoleRepository.class);
        var orgs = mock(OrganizationRepository.class);
        var org = UUID.randomUUID();
        var user = UUID.randomUUID();
        var member = UUID.randomUUID();
        when(members.activeActor(org, user)).thenReturn(Optional.of(member));
        when(roles.forMember(org, member))
                .thenReturn(
                        List.of(
                                new RbacDtos.RoleView(
                                        UUID.randomUUID(),
                                        "ADMIN",
                                        "Admin",
                                        true,
                                        0,
                                        Set.of(Permission.ROLE_MANAGE))),
                        List.of());
        assertThatThrownBy(
                        () ->
                                new AuthorizationService(members, roles, orgs)
                                        .lockAndRequire(user, org, Permission.ROLE_MANAGE))
                .isInstanceOf(ApiException.class);
        var order = inOrder(members, roles, orgs);
        order.verify(members).activeActor(org, user);
        order.verify(roles).forMember(org, member);
        order.verify(orgs).lock(org);
        order.verify(members).activeActor(org, user);
        order.verify(roles).forMember(org, member);
    }

    @Test
    void outsiderDoesNotObtainOrganizationMutationLock() {
        var members = mock(MembershipRepository.class);
        var roles = mock(RoleRepository.class);
        var orgs = mock(OrganizationRepository.class);
        var org = UUID.randomUUID();
        var user = UUID.randomUUID();
        when(members.activeActor(org, user)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                new AuthorizationService(members, roles, orgs)
                                        .lockAndRequire(user, org, Permission.ROLE_MANAGE))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(orgs, roles);
    }
}
