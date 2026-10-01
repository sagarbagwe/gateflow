package com.gateflow.audit;

import com.gateflow.auth.UserPrincipal;
import com.gateflow.http.PageSlice;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{org}/audit-logs")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public PageSlice<AuditService.Entry> list(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID actorMembershipId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant before,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.list(
                user.id(),
                org,
                new AuditQuery(
                        action,
                        resourceType,
                        resourceId,
                        actorMembershipId,
                        requestId,
                        from,
                        before,
                        limit,
                        offset));
    }

    @GetMapping("/{id}")
    public AuditService.Detail detail(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @PathVariable UUID id) {
        return service.detail(user.id(), org, id);
    }
}
