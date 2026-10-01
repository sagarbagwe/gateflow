package com.gateflow.async;

import com.gateflow.auth.UserPrincipal;

import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{org}/requests/{request}/activity")
public class RequestActivityController {
    private final RequestActivityService service;

    public RequestActivityController(RequestActivityService service) {
        this.service = service;
    }

    @GetMapping
    public RequestActivityService.Page get(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @PathVariable UUID request,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.read(user.id(), org, request, limit, offset);
    }
}
