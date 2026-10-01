package com.gateflow.workflow;

import static com.gateflow.workflow.RequestSearchDtos.*;

import com.gateflow.auth.UserPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/requests")
public class RequestSearchController {
    private final RequestSearchService service;

    public RequestSearchController(RequestSearchService service) {
        this.service = service;
    }

    @GetMapping
    public Page search(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestParam MultiValueMap<String, String> query) {
        return service.search(user.id(), orgId, RequestSearchQuery.parse(query, null));
    }

    @GetMapping("/inbox")
    public Page inbox(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestParam MultiValueMap<String, String> query) {
        return service.search(
                user.id(), orgId, RequestSearchQuery.parse(query, RequestSearchQuery.Scope.INBOX));
    }
}
