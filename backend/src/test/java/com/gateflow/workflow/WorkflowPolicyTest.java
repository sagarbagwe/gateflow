package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateflow.http.ApiException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

class WorkflowPolicyTest {
    SubmitRequest purchase(String amount, String currency) {
        return new SubmitRequest(
                UUID.randomUUID(),
                "Laptop",
                "Reason",
                RequestType.PURCHASE,
                new BigDecimal(amount),
                currency,
                Map.of());
    }

    Condition threshold() {
        return new Condition(
                ConditionType.PURCHASE_AMOUNT_AT_LEAST, new BigDecimal("1000.00"), "USD");
    }

    @Test
    void thresholdIsInclusiveWithDecimalArithmetic() {
        assertThat(WorkflowPolicy.applies(threshold(), purchase("999.99", "USD"))).isFalse();
        assertThat(WorkflowPolicy.applies(threshold(), purchase("1000.00", "USD"))).isTrue();
    }

    @Test
    void currencyMismatchIsAnErrorNotAnAutomaticSkip() {
        assertThatThrownBy(() -> WorkflowPolicy.applies(threshold(), purchase("10.00", "EUR")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nonPurchaseDoesNotMeetPurchaseCondition() {
        assertThat(
                        WorkflowPolicy.applies(
                                threshold(),
                                new SubmitRequest(
                                        UUID.randomUUID(),
                                        "Tool",
                                        "Reason",
                                        RequestType.SOFTWARE_ACCESS,
                                        null,
                                        null,
                                        Map.of("softwareName", "Tool"))))
                .isFalse();
    }

    @Test
    void alwaysIsUnconditional() {
        assertThat(
                        WorkflowPolicy.applies(
                                new Condition(ConditionType.ALWAYS, null, null),
                                purchase("1.00", "USD")))
                .isTrue();
    }

    @Test
    void alwaysCannotHideThresholdFields() {
        assertThatThrownBy(
                        () ->
                                WorkflowPolicy.validate(
                                        new Condition(ConditionType.ALWAYS, BigDecimal.ONE, "USD")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void thresholdRequiresCurrencyAndAmount() {
        assertThatThrownBy(
                        () ->
                                WorkflowPolicy.validate(
                                        new Condition(
                                                ConditionType.PURCHASE_AMOUNT_AT_LEAST,
                                                null,
                                                "USD")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                WorkflowPolicy.validate(
                                        new Condition(
                                                ConditionType.PURCHASE_AMOUNT_AT_LEAST,
                                                BigDecimal.ONE,
                                                null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void policyExceptionRequiresTypedPolicyReference() {
        assertThatThrownBy(
                        () ->
                                WorkflowPolicy.validate(
                                        new SubmitRequest(
                                                UUID.randomUUID(),
                                                "Exception",
                                                "Reason",
                                                RequestType.POLICY_EXCEPTION,
                                                null,
                                                null,
                                                Map.of())))
                .isInstanceOf(ApiException.class);
        WorkflowPolicy.validate(
                new SubmitRequest(
                        UUID.randomUUID(),
                        "Exception",
                        "Reason",
                        RequestType.POLICY_EXCEPTION,
                        null,
                        null,
                        Map.of("policyCode", "SEC-1")));
    }

    @Test
    void purchaseCannotOmitMoney() {
        assertThatThrownBy(
                        () ->
                                WorkflowPolicy.validate(
                                        new SubmitRequest(
                                                UUID.randomUUID(),
                                                "Purchase",
                                                "Reason",
                                                RequestType.PURCHASE,
                                                null,
                                                null,
                                                Map.of())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void fingerprintsIgnoreDetailMapInsertionOrderButDetectChangedIntent() {
        var json = new WorkflowJson(new ObjectMapper());
        var a = new LinkedHashMap<String, String>();
        a.put("a", "one");
        a.put("b", "two");
        var b = new LinkedHashMap<String, String>();
        b.put("b", "two");
        b.put("a", "one");
        assertThat(json.hash(a)).isEqualTo(json.hash(b));
        b.put("a", "changed");
        assertThat(json.hash(a)).isNotEqualTo(json.hash(b));
    }

    @Test
    void legacyEmptyConditionsPreserveAlwaysMeaning() {
        assertThat(new WorkflowJson(new ObjectMapper()).condition("{}").type())
                .isEqualTo(ConditionType.ALWAYS);
    }

    @Test
    void publishedVersionCannotBeEditedEvenWithCurrentVersion() {
        var v =
                new VersionView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        VersionStatus.PUBLISHED,
                        1,
                        null,
                        List.of());
        assertThatThrownBy(() -> WorkflowPolicy.requireDraft(v, 1))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void draftVersionRejectsStaleClientIntent() {
        var v =
                new VersionView(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1,
                        VersionStatus.DRAFT,
                        1,
                        null,
                        List.of());
        assertThatThrownBy(() -> WorkflowPolicy.requireDraft(v, 0))
                .isInstanceOf(ApiException.class);
        WorkflowPolicy.requireDraft(v, 1);
    }

    @Test
    void fingerprintsNormalizeEquivalentDecimalRepresentations() {
        var json = new WorkflowJson(new ObjectMapper());
        assertThat(json.hash(Map.of("amount", new BigDecimal("1000.00"))))
                .isEqualTo(json.hash(Map.of("amount", new BigDecimal("1000"))));
    }

    @Test
    void mixedCurrencyWorkflowCannotPublishAnUnsatisfiableReviewPolicy() {
        var usd = new StepInput("USD review", UUID.randomUUID(), threshold());
        var eur =
                new StepInput(
                        "EUR review",
                        UUID.randomUUID(),
                        new Condition(
                                ConditionType.PURCHASE_AMOUNT_AT_LEAST, BigDecimal.ONE, "EUR"));
        assertThatThrownBy(() -> WorkflowPolicy.validateSteps(List.of(usd, eur)))
                .isInstanceOf(ApiException.class);
        WorkflowPolicy.validateSteps(List.of(usd));
    }
}
