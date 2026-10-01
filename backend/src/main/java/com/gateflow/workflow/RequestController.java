package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.auth.UserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/requests")
public class RequestController {
    private final RequestService service;
    private static final String KEY_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public RequestController(RequestService service) {
        this.service = service;
    }

    private ResponseEntity<RequestView> result(
            CommandResult r, boolean created, HttpServletRequest request) {
        boolean isNew = created && !r.replayed();
        var response =
                ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK)
                        .header("Idempotent-Replay", Boolean.toString(r.replayed()));
        if (isNew)
            response.location(
                    java.net.URI.create(request.getRequestURI() + "/" + r.request().id()));
        return response.body(r.request());
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<RequestView> submit(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody SubmitRequest body,
            HttpServletRequest request) {
        return result(
                service.submit(
                        user.id(),
                        orgId,
                        UUID.fromString(key),
                        body,
                        WorkflowController.trace(request)),
                true,
                request);
    }

    @GetMapping("/{id}")
    public RequestView get(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id) {
        return service.get(user.id(), orgId, id);
    }

    @PostMapping(value = "/{id}/steps/{stepId}/decisions", consumes = "application/json")
    public ResponseEntity<RequestView> decide(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID stepId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody DecideRequest body,
            HttpServletRequest request) {
        return result(
                service.decide(
                        user.id(),
                        orgId,
                        id,
                        stepId,
                        UUID.fromString(key),
                        body,
                        WorkflowController.trace(request)),
                false,
                request);
    }

    @PostMapping(value = "/{id}/withdraw", consumes = "application/json")
    public ResponseEntity<RequestView> withdraw(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody ExpectedVersion body,
            HttpServletRequest request) {
        return result(
                service.withdraw(
                        user.id(),
                        orgId,
                        id,
                        UUID.fromString(key),
                        body,
                        WorkflowController.trace(request)),
                false,
                request);
    }

    @PostMapping(value = "/{id}/steps/{stepId}/reassign", consumes = "application/json")
    public ResponseEntity<RequestView> reassign(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID orgId,
            @PathVariable UUID id,
            @PathVariable UUID stepId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = KEY_PATTERN) String key,
            @Valid @RequestBody ReassignRequest body,
            HttpServletRequest request) {
        return result(
                service.reassign(
                        user.id(),
                        orgId,
                        id,
                        stepId,
                        UUID.fromString(key),
                        body,
                        WorkflowController.trace(request)),
                false,
                request);
    }
}
