package com.gateflow.notifications;

import com.gateflow.auth.UserPrincipal;
import com.gateflow.http.PageSlice;
import com.gateflow.http.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/organizations/{org}/notifications")
public class NotificationController {
    public record PreferencesInput(
            @NotNull Boolean inAppEnabled,
            @NotNull Boolean emailEnabled,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Unread(long unreadCount) {}

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageSlice<NotificationService.Item> list(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        return service.list(user.id(), org, unreadOnly, limit, offset);
    }

    @GetMapping("/unread-count")
    public Unread unread(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID org) {
        return new Unread(service.unread(user.id(), org));
    }

    @PatchMapping("/{id}/read")
    public NotificationService.Item read(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @PathVariable UUID id) {
        return service.read(user.id(), org, id);
    }

    @GetMapping("/preferences")
    public PreferenceRepository.Preferences preferences(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID org) {
        return service.preferences(user.id(), org);
    }

    @PutMapping(value = "/preferences", consumes = "application/json")
    public PreferenceRepository.Preferences update(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID org,
            @Valid @RequestBody PreferencesInput input,
            HttpServletRequest request) {
        return service.updatePreferences(
                user.id(),
                org,
                input.inAppEnabled(),
                input.emailEnabled(),
                input.expectedVersion(),
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
