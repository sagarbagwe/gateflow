package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;

import java.util.*;

public final class WorkflowPolicy {
    private WorkflowPolicy() {}

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BUSINESS_RULE", message);
    }

    public static void validate(Condition c) {
        if (c == null || c.type() == null) throw invalid("Condition type is required");
        if (c.type() == ConditionType.ALWAYS && (c.amount() != null || c.currency() != null))
            throw invalid("ALWAYS cannot include amount or currency");
        if (c.type() == ConditionType.PURCHASE_AMOUNT_AT_LEAST
                && (c.amount() == null || c.currency() == null))
            throw invalid("Purchase thresholds require amount and currency");
    }

    public static boolean applies(Condition c, SubmitRequest request) {
        validate(c);
        if (c.type() == ConditionType.ALWAYS) return true;
        if (request.requestType() != RequestType.PURCHASE) return false;
        // Never silently skip mandatory financial review because the currency differs.
        if (!Objects.equals(c.currency(), request.currency()))
            throw invalid("Request currency does not match workflow threshold currency");
        return request.purchaseAmount().compareTo(c.amount()) >= 0;
    }

    public static void validate(SubmitRequest r) {
        if (r.requestType() == RequestType.PURCHASE) {
            if (r.purchaseAmount() == null || r.currency() == null)
                throw invalid("Purchase requests require amount and currency");
        } else if (r.purchaseAmount() != null || r.currency() != null)
            throw invalid("Non-purchase requests cannot include money");
        String field =
                switch (r.requestType()) {
                    case SOFTWARE_ACCESS -> "softwareName";
                    case POLICY_EXCEPTION -> "policyCode";
                    default -> null;
                };
        if (field != null && (r.details().get(field) == null || r.details().get(field).isBlank()))
            throw invalid("Request details require " + field);
    }

    public static void requireReview(RequestView r, long expected) {
        if (r.state() != RequestState.IN_REVIEW)
            throw conflict("REQUEST_NOT_IN_REVIEW", "Request is already terminal");
        if (r.version() != expected)
            throw conflict("VERSION_CONFLICT", "Resource changed; reload and retry");
    }

    public static void requireDraft(VersionView v, long expected) {
        if (v.status() != VersionStatus.DRAFT)
            throw conflict("PUBLISHED_VERSION_IMMUTABLE", "Create a new workflow version instead");
        if (v.version() != expected)
            throw conflict("VERSION_CONFLICT", "Resource changed; reload and retry");
    }

    public static void validateSteps(List<StepInput> steps) {
        var currencies = new HashSet<String>();
        for (var step : steps) {
            validate(step.condition());
            if (step.condition().type() == ConditionType.PURCHASE_AMOUNT_AT_LEAST)
                currencies.add(step.condition().currency());
        }
        if (currencies.size() > 1)
            throw invalid("Workflow purchase thresholds must use one currency");
    }
}
