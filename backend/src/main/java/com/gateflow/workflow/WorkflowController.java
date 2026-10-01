package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.auth.UserPrincipal;
import com.gateflow.http.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/workflows")
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public DefinitionView create(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @Valid @RequestBody DefinitionInput body,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        var result = service.create(user.id(), orgId, body, trace(request));
        response.setHeader("Location", request.getRequestURI() + "/" + result.id());
        return result;
    }

    @GetMapping
    public PageSlice<DefinitionView> list(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.list(user.id(), orgId, limit, offset);
    }

    @GetMapping("/{id}")
    public DefinitionView get(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return service.definition(user.id(), orgId, id);
    }

    @PostMapping(value = "/{id}/versions", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionView version(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @Valid @RequestBody VersionInput body,
            HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        var result = service.createVersion(user.id(), orgId, id, body, trace(request));
        response.setHeader("Location", request.getRequestURI() + "/" + result.id());
        return result;
    }

    @GetMapping("/{id}/versions/{versionId}")
    public VersionView getVersion(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID versionId) {
        return service.version(user.id(), orgId, id, versionId);
    }

    @PutMapping(value = "/{id}/versions/{versionId}", consumes = "application/json")
    public VersionView edit(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @Valid @RequestBody EditVersion body,
            HttpServletRequest request) {
        return service.edit(user.id(), orgId, id, versionId, body, trace(request));
    }

    @PostMapping(value = "/{id}/versions/{versionId}/publish", consumes = "application/json")
    public VersionView publish(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID versionId,
            @Valid @RequestBody ExpectedVersion body,
            HttpServletRequest request) {
        return service.publish(user.id(), orgId, id, versionId, body, trace(request));
    }

    static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }
}
