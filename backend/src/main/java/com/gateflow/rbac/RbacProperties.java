package com.gateflow.rbac;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "gateflow.rbac")
public record RbacProperties(
        @Min(1) @Max(100) int maxOrganizationsPerUser,
        @Min(4) @Max(1000) int maxRolesPerOrganization) {}
