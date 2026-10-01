package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class RequestSearchDtos {
    private RequestSearchDtos() {}

    public record ActiveStep(
            UUID id, int position, String name, UUID assignedMembershipId, Instant activatedAt) {}

    public record Summary(
            UUID id,
            UUID workflowDefinitionId,
            UUID workflowVersionId,
            UUID requesterMembershipId,
            String title,
            RequestType requestType,
            BigDecimal purchaseAmount,
            String currency,
            RequestState state,
            long version,
            Instant createdAt,
            Instant submittedAt,
            Instant completedAt,
            ActiveStep activeStep) {}

    public record Page(
            List<Summary> items,
            int limit,
            boolean hasMore,
            String nextCursor,
            RequestSearchQuery.Pagination pagination,
            Integer offset) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
