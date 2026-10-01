package com.gateflow.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class WorkflowDtos {
    private WorkflowDtos() {}

    public enum VersionStatus {
        DRAFT,
        PUBLISHED
    }

    public enum ConditionType {
        ALWAYS,
        PURCHASE_AMOUNT_AT_LEAST
    }

    public enum RequestType {
        PURCHASE,
        SOFTWARE_ACCESS,
        POLICY_EXCEPTION
    }

    public enum RequestState {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        REJECTED,
        WITHDRAWN
    }

    public enum StepState {
        WAITING,
        ACTIVE,
        APPROVED,
        REJECTED,
        SKIPPED,
        CANCELLED
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public record Condition(
            @NotNull ConditionType type,
            @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record DefinitionInput(
            @NotBlank
                    @Size(max = 160)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String name,
            @Size(max = 4000)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String description) {}

    public record StepInput(
            @NotBlank
                    @Size(max = 160)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String name,
            @NotNull UUID approverRoleId,
            @NotNull @Valid Condition condition) {}

    public record VersionInput(
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid StepInput> steps) {}

    public record EditVersion(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid StepInput> steps) {}

    public record ExpectedVersion(@NotNull @PositiveOrZero Long expectedVersion) {}

    public record SubmitRequest(
            @NotNull UUID workflowVersionId,
            @NotBlank
                    @Size(max = 200)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String title,
            @NotBlank
                    @Size(max = 20000)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String description,
            @NotNull RequestType requestType,
            @Positive @Digits(integer = 12, fraction = 2) BigDecimal purchaseAmount,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull @Size(max = 20)
                    Map<
                                    @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]{0,59}") String,
                                    @NotNull @Size(max = 1000)
                                    @Pattern(
                                            regexp = "[^\\x00]*",
                                            message = "must not contain NUL characters")
                                    String>
                            details) {}

    public record DecideRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull Decision decision,
            @Size(max = 2000)
                    @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL characters")
                    String comment) {}

    public record ReassignRequest(
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull UUID membershipId) {}

    public record DefinitionView(UUID id, String name, String description) {}

    public record WorkflowStep(
            UUID id, int position, String name, UUID approverRoleId, Condition condition) {}

    public record VersionView(
            UUID id,
            UUID workflowDefinitionId,
            int versionNumber,
            VersionStatus status,
            long version,
            Instant publishedAt,
            List<WorkflowStep> steps) {
        public VersionView {
            steps = List.copyOf(steps);
        }
    }

    public record DecisionView(
            UUID reviewerMembershipId, Decision decision, String comment, Instant decidedAt) {}

    public record ExecutionStep(
            UUID id,
            UUID workflowStepId,
            int position,
            String name,
            UUID approverRoleId,
            UUID assignedMembershipId,
            StepState state,
            long version,
            Instant activatedAt,
            Instant completedAt,
            DecisionView decision) {}

    public record RequestView(
            UUID id,
            UUID workflowDefinitionId,
            UUID workflowVersionId,
            UUID requesterMembershipId,
            String title,
            String description,
            RequestType requestType,
            Map<String, String> details,
            BigDecimal purchaseAmount,
            String currency,
            RequestState state,
            long version,
            Instant submittedAt,
            Instant completedAt,
            List<ExecutionStep> steps) {
        public RequestView {
            details = Map.copyOf(details);
            steps = List.copyOf(steps);
        }
    }

    public record CommandResult(RequestView request, boolean replayed) {}
}
