package com.gateflow.workflow;

import static com.gateflow.rbac.Permission.*;
import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.http.*;
import com.gateflow.rbac.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class WorkflowService {
    private final WorkflowRepository repository;
    private final AuthorizationService authorization;
    private final RoleService roles;
    private final TenantAuditWriter audit;
    private final PublishedWorkflowReader reader;

    public WorkflowService(
            WorkflowRepository repository,
            AuthorizationService authorization,
            RoleService roles,
            TenantAuditWriter audit,
            PublishedWorkflowReader reader) {
        this.repository = repository;
        this.authorization = authorization;
        this.roles = roles;
        this.audit = audit;
        this.reader = reader;
    }

    @Transactional
    public DefinitionView create(UUID user, UUID org, DefinitionInput b, String requestId) {
        var actor = authorization.shareAndRequire(user, org, WORKFLOW_CREATE);
        DefinitionView result;
        try {
            result = repository.create(org, b);
        } catch (DataIntegrityViolationException conflict) {
            throw WorkflowPolicy.conflict("WORKFLOW_NAME_CONFLICT", "Workflow name already exists");
        }
        audit.record(
                org,
                actor.membershipId(),
                "WORKFLOW_CREATED",
                "WORKFLOW",
                result.id(),
                null,
                Map.of("name", result.name()),
                requestId);
        return result;
    }

    public PageSlice<DefinitionView> list(UUID user, UUID org, int limit, int offset) {
        authorization.require(user, org, WORKFLOW_VIEW);
        return PageSlice.from(repository.page(org, limit + 1, offset), limit, offset);
    }

    public DefinitionView definition(UUID user, UUID org, UUID id) {
        authorization.require(user, org, WORKFLOW_VIEW);
        return repository.definition(org, id, false);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionView version(UUID user, UUID org, UUID id, UUID version) {
        authorization.require(user, org, WORKFLOW_VIEW);
        repository.definition(org, id, false);
        return reader.read(org, id, version);
    }

    @Transactional
    public VersionView createVersion(
            UUID user, UUID org, UUID id, VersionInput b, String requestId) {
        var actor = authorization.shareAndRequire(user, org, WORKFLOW_UPDATE);
        repository.definition(org, id, true);
        validate(org, b.steps());
        UUID version = repository.createVersion(org, id, b.steps());
        var after = repository.version(org, id, version, false);
        audit.record(
                org,
                actor.membershipId(),
                "WORKFLOW_VERSION_CREATED",
                "WORKFLOW_VERSION",
                version,
                null,
                Map.of(
                        "definitionId",
                        id,
                        "stepCount",
                        b.steps().size(),
                        "steps",
                        snapshots(after.steps())),
                requestId);
        return after;
    }

    @Transactional
    public VersionView edit(
            UUID user, UUID org, UUID id, UUID version, EditVersion b, String requestId) {
        var actor = authorization.shareAndRequire(user, org, WORKFLOW_UPDATE);
        repository.definition(org, id, true);
        var before = repository.version(org, id, version, true);
        WorkflowPolicy.requireDraft(before, b.expectedVersion());
        validate(org, b.steps());
        repository.replace(org, version, b.expectedVersion(), b.steps());
        var after = repository.version(org, id, version, false);
        audit.record(
                org,
                actor.membershipId(),
                "WORKFLOW_VERSION_UPDATED",
                "WORKFLOW_VERSION",
                version,
                Map.of(
                        "version",
                        before.version(),
                        "stepCount",
                        before.steps().size(),
                        "steps",
                        snapshots(before.steps())),
                Map.of(
                        "version",
                        before.version() + 1,
                        "stepCount",
                        b.steps().size(),
                        "steps",
                        snapshots(after.steps())),
                requestId);
        return after;
    }

    @Transactional
    public VersionView publish(
            UUID user, UUID org, UUID id, UUID version, ExpectedVersion b, String requestId) {
        var actor = authorization.shareAndRequire(user, org, WORKFLOW_PUBLISH);
        repository.definition(org, id, true);
        var before = repository.version(org, id, version, true);
        WorkflowPolicy.requireDraft(before, b.expectedVersion());
        validate(
                org,
                before.steps().stream()
                        .map(s -> new StepInput(s.name(), s.approverRoleId(), s.condition()))
                        .toList());
        repository.publish(org, version, b.expectedVersion());
        audit.record(
                org,
                actor.membershipId(),
                "WORKFLOW_PUBLISHED",
                "WORKFLOW_VERSION",
                version,
                Map.of("status", "DRAFT", "version", before.version()),
                Map.of("status", "PUBLISHED", "version", before.version() + 1),
                requestId);
        return repository.version(org, id, version, false);
    }

    private void validate(UUID org, List<StepInput> steps) {
        WorkflowPolicy.validateSteps(steps);
        var ids = new HashSet<UUID>();
        for (var s : steps) {
            ids.add(s.approverRoleId());
        }
        for (var role : roles.scoped(org, ids))
            if (!role.permissions().contains(REQUEST_APPROVE))
                throw WorkflowPolicy.invalid("Approver roles must grant REQUEST_APPROVE");
    }

    private static List<Map<String, Object>> snapshots(List<WorkflowStep> steps) {
        return steps.stream()
                .map(
                        s -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", s.id());
                            item.put("position", s.position());
                            item.put("name", s.name());
                            item.put("approverRoleId", s.approverRoleId());
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("type", s.condition().type());
                            c.put("amount", s.condition().amount());
                            c.put("currency", s.condition().currency());
                            item.put("condition", c);
                            return item;
                        })
                .toList();
    }
}
