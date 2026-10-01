package com.gateflow.workflow;

import static com.gateflow.rbac.Permission.*;
import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.http.ApiException;
import com.gateflow.rbac.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class RequestService {
    private final RequestRepository repository;
    private final WorkflowRepository workflows;
    private final ReviewerService reviewers;
    private final AuthorizationService authorization;
    private final CommandReceipts receipts;
    private final TenantAuditWriter audit;
    private final RequestAccessPolicy access;
    private final com.gateflow.async.OutboxRepository outbox;

    public RequestService(
            RequestRepository repository,
            WorkflowRepository workflows,
            ReviewerService reviewers,
            AuthorizationService authorization,
            CommandReceipts receipts,
            TenantAuditWriter audit,
            RequestAccessPolicy access,
            com.gateflow.async.OutboxRepository outbox) {
        this.repository = repository;
        this.workflows = workflows;
        this.reviewers = reviewers;
        this.authorization = authorization;
        this.receipts = receipts;
        this.audit = audit;
        this.access = access;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RequestView get(UUID user, UUID org, UUID id) {
        var actor = authorization.requireMember(user, org);
        access.requireVisible(actor, id);
        return repository.find(org, id, false);
    }

    @Transactional
    public CommandResult submit(UUID user, UUID org, UUID key, SubmitRequest b, String requestId) {
        var actor = authorization.shareAndRequire(user, org, REQUEST_SUBMIT, REQUEST_VIEW_OWN);
        WorkflowPolicy.validate(b);
        return receipts.execute(
                org,
                actor.membershipId(),
                key,
                "SUBMIT",
                b,
                () -> {
                    var version = workflows.published(org, b.workflowVersionId());
                    Map<UUID, UUID> assigned = new HashMap<>();
                    Map<UUID, UUID> selectedByRole = new HashMap<>();
                    for (var step : version.steps())
                        if (WorkflowPolicy.applies(step.condition(), b))
                            assigned.put(
                                    step.id(),
                                    selectedByRole.computeIfAbsent(
                                            step.approverRoleId(),
                                            role ->
                                                    reviewers.select(
                                                            org, role, actor.membershipId())));
                    if (assigned.isEmpty())
                        throw WorkflowPolicy.conflict(
                                "NO_APPLICABLE_STEPS", "Workflow has no applicable approval steps");
                    UUID id = repository.create(org, actor.membershipId(), version, b);
                    boolean active = false;
                    for (var step : version.steps()) {
                        StepState state =
                                assigned.containsKey(step.id())
                                        ? (active ? StepState.WAITING : StepState.ACTIVE)
                                        : StepState.SKIPPED;
                        if (state == StepState.ACTIVE) active = true;
                        repository.createStep(
                                org, id, version.id(), step, assigned.get(step.id()), state);
                    }
                    audit.record(
                            org,
                            actor.membershipId(),
                            "REQUEST_SUBMITTED",
                            "REQUEST",
                            id,
                            null,
                            Map.of(
                                    "state",
                                    "IN_REVIEW",
                                    "workflowVersionId",
                                    version.id(),
                                    "version",
                                    0),
                            requestId);
                    return event(
                            org,
                            actor.membershipId(),
                            id,
                            com.gateflow.async.OutboxRepository.EventType.REQUEST_SUBMITTED,
                            null,
                            requestId);
                });
    }

    @Transactional
    public CommandResult decide(
            UUID user,
            UUID org,
            UUID id,
            UUID stepId,
            UUID key,
            DecideRequest b,
            String requestId) {
        var actor = authorization.shareAndRequire(user, org, REQUEST_APPROVE);
        access.requireVisible(actor, id);
        return receipts.execute(
                org,
                actor.membershipId(),
                key,
                "DECIDE",
                Map.of("requestId", id, "stepId", stepId, "body", b),
                () -> {
                    var before = repository.find(org, id, true);
                    WorkflowPolicy.requireReview(before, b.expectedVersion());
                    var step = step(before, stepId);
                    if (step.state() != StepState.ACTIVE)
                        throw WorkflowPolicy.conflict(
                                "STEP_NOT_ACTIVE", "Only the active approval step may be decided");
                    if (actor.membershipId().equals(before.requesterMembershipId()))
                        throw new ApiException(
                                HttpStatus.FORBIDDEN,
                                "SELF_APPROVAL_FORBIDDEN",
                                "Requesters cannot decide their own request");
                    if (!actor.membershipId().equals(step.assignedMembershipId()))
                        throw new ApiException(
                                HttpStatus.FORBIDDEN,
                                "NOT_ASSIGNED_REVIEWER",
                                "Only the assigned reviewer may decide this step");
                    if (!reviewers.eligible(
                            org,
                            step.approverRoleId(),
                            actor.membershipId(),
                            before.requesterMembershipId()))
                        throw new ApiException(
                                HttpStatus.FORBIDDEN,
                                "REVIEWER_INELIGIBLE",
                                "Reviewer no longer meets this step's eligibility");
                    RequestState state;
                    if (b.decision() == Decision.REJECT) {
                        repository.decide(org, id, step, actor.membershipId(), b);
                        repository.cancelPending(org, id);
                        state = RequestState.REJECTED;
                    } else {
                        var next =
                                before.steps().stream()
                                        .filter(
                                                s ->
                                                        s.position() > step.position()
                                                                && s.state() == StepState.WAITING)
                                        .findFirst();
                        if (next.isPresent()
                                && !reviewers.eligible(
                                        org,
                                        next.get().approverRoleId(),
                                        next.get().assignedMembershipId(),
                                        before.requesterMembershipId()))
                            throw WorkflowPolicy.conflict(
                                    "REVIEWER_UNAVAILABLE",
                                    "Reassign the pending step before continuing");
                        repository.decide(org, id, step, actor.membershipId(), b);
                        if (next.isPresent()) {
                            repository.activate(org, id, next.get().id());
                            state = RequestState.IN_REVIEW;
                        } else state = RequestState.APPROVED;
                    }
                    repository.transition(org, id, b.expectedVersion(), state);
                    audit.record(
                            org,
                            actor.membershipId(),
                            "REQUEST_DECIDED",
                            "REQUEST",
                            id,
                            Map.of("state", before.state(), "version", before.version()),
                            Map.of(
                                    "state",
                                    state,
                                    "version",
                                    before.version() + 1,
                                    "stepId",
                                    step.id(),
                                    "decision",
                                    b.decision()),
                            requestId);
                    return event(
                            org,
                            actor.membershipId(),
                            id,
                            b.decision() == Decision.APPROVE
                                    ? com.gateflow.async.OutboxRepository.EventType.STEP_APPROVED
                                    : com.gateflow.async.OutboxRepository.EventType
                                            .REQUEST_REJECTED,
                            stepId,
                            requestId);
                });
    }

    @Transactional
    public CommandResult withdraw(
            UUID user, UUID org, UUID id, UUID key, ExpectedVersion b, String requestId) {
        var actor =
                authorization.shareAndRequire(user, org, REQUEST_WITHDRAW_OWN, REQUEST_VIEW_OWN);
        var initial = repository.find(org, id, false);
        if (!actor.membershipId().equals(initial.requesterMembershipId()))
            throw WorkflowRepository.missing("REQUEST_NOT_FOUND");
        return receipts.execute(
                org,
                actor.membershipId(),
                key,
                "WITHDRAW",
                Map.of("requestId", id, "body", b),
                () -> {
                    var before = repository.find(org, id, true);
                    WorkflowPolicy.requireReview(before, b.expectedVersion());
                    repository.cancelPending(org, id);
                    repository.transition(org, id, b.expectedVersion(), RequestState.WITHDRAWN);
                    audit.record(
                            org,
                            actor.membershipId(),
                            "REQUEST_WITHDRAWN",
                            "REQUEST",
                            id,
                            Map.of("state", before.state(), "version", before.version()),
                            Map.of("state", "WITHDRAWN", "version", before.version() + 1),
                            requestId);
                    return event(
                            org,
                            actor.membershipId(),
                            id,
                            com.gateflow.async.OutboxRepository.EventType.REQUEST_WITHDRAWN,
                            null,
                            requestId);
                });
    }

    @Transactional
    public CommandResult reassign(
            UUID user,
            UUID org,
            UUID id,
            UUID stepId,
            UUID key,
            ReassignRequest b,
            String requestId) {
        var actor = authorization.shareAndRequire(user, org, REQUEST_REASSIGN, REQUEST_VIEW_ALL);
        repository.find(org, id, false);
        return receipts.execute(
                org,
                actor.membershipId(),
                key,
                "REASSIGN",
                Map.of("requestId", id, "stepId", stepId, "body", b),
                () -> {
                    var before = repository.find(org, id, true);
                    WorkflowPolicy.requireReview(before, b.expectedVersion());
                    var step = step(before, stepId);
                    if (step.state() != StepState.ACTIVE && step.state() != StepState.WAITING)
                        throw WorkflowPolicy.conflict(
                                "STEP_NOT_PENDING", "Only pending steps may be reassigned");
                    if (!reviewers.eligible(
                            org,
                            step.approverRoleId(),
                            b.membershipId(),
                            before.requesterMembershipId()))
                        throw WorkflowPolicy.conflict(
                                "NO_ELIGIBLE_REVIEWER",
                                "Target is not an eligible non-requester reviewer");
                    Map<String, Object> previous = new LinkedHashMap<>();
                    previous.put("stepId", stepId);
                    previous.put("membershipId", step.assignedMembershipId());
                    previous.put("version", before.version());
                    repository.reassign(org, id, stepId, b.membershipId());
                    repository.transition(org, id, b.expectedVersion(), RequestState.IN_REVIEW);
                    audit.record(
                            org,
                            actor.membershipId(),
                            "REQUEST_REVIEWER_REASSIGNED",
                            "REQUEST",
                            id,
                            previous,
                            Map.of(
                                    "stepId",
                                    stepId,
                                    "membershipId",
                                    b.membershipId(),
                                    "version",
                                    before.version() + 1),
                            requestId);
                    return event(
                            org,
                            actor.membershipId(),
                            id,
                            com.gateflow.async.OutboxRepository.EventType.REVIEWER_REASSIGNED,
                            stepId,
                            requestId);
                });
    }

    private RequestView event(
            UUID org,
            UUID actor,
            UUID id,
            com.gateflow.async.OutboxRepository.EventType type,
            UUID step,
            String trace) {
        var result = repository.find(org, id, false);
        outbox.append(org, actor, type, result, step, trace);
        return result;
    }

    private ExecutionStep step(RequestView request, UUID id) {
        return request.steps().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow(() -> WorkflowRepository.missing("REQUEST_STEP_NOT_FOUND"));
    }
}
