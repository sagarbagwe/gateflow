package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import com.gateflow.http.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;

import java.time.*;
import java.util.*;

public record RequestSearchQuery(
        Scope scope,
        Sort sort,
        Pagination pagination,
        int limit,
        int offset,
        String q,
        Set<RequestState> statuses,
        RequestType type,
        UUID workflowId,
        Instant createdFrom,
        Instant createdBefore,
        String cursor) {
    public enum Scope {
        VISIBLE,
        OWN,
        INBOX
    }

    public enum Sort {
        CREATED_DESC,
        CREATED_ASC
    }

    public enum Pagination {
        CURSOR,
        OFFSET
    }

    private static final Set<String> KEYS =
            Set.of(
                    "scope",
                    "sort",
                    "pagination",
                    "limit",
                    "offset",
                    "q",
                    "status",
                    "type",
                    "workflowId",
                    "createdFrom",
                    "createdBefore",
                    "cursor");
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    public RequestSearchQuery {
        statuses = Set.copyOf(statuses);
    }

    public static ApiException invalid() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SEARCH_QUERY",
                "Invalid or incompatible search parameters");
    }

    public static RequestSearchQuery parse(MultiValueMap<String, String> input, Scope forced) {
        if (!KEYS.containsAll(input.keySet())) throw invalid();
        for (var entry : input.entrySet())
            if (!entry.getKey().equals("status") && entry.getValue().size() != 1) throw invalid();
        try {
            Scope scope =
                    Scope.valueOf(
                            value(input, "scope", forced == null ? "VISIBLE" : forced.name()));
            if (forced != null && scope != forced) throw invalid();
            Sort sort = Sort.valueOf(value(input, "sort", "CREATED_DESC"));
            Pagination pagination = Pagination.valueOf(value(input, "pagination", "CURSOR"));
            int limit = Integer.parseInt(value(input, "limit", "20")),
                    offset = Integer.parseInt(value(input, "offset", "0"));
            if (limit < 1 || limit > 100 || offset < 0 || offset > 10000) throw invalid();
            String cursor = optional(input, "cursor");
            if (pagination == Pagination.CURSOR && input.containsKey("offset")) throw invalid();
            if (pagination == Pagination.OFFSET && cursor != null) throw invalid();
            String q = optional(input, "q");
            if (q != null) {
                if (q.length() > 200 || q.indexOf(0) >= 0) throw invalid();
                q = q.strip();
                if (q.isEmpty()) q = null;
            }
            if (cursor != null && (cursor.length() > 768 || !cursor.matches("[A-Za-z0-9_-]+")))
                throw invalid();
            Set<RequestState> statuses = EnumSet.noneOf(RequestState.class);
            var raw = input.getOrDefault("status", List.of());
            if (raw.size() > RequestState.values().length) throw invalid();
            for (String status : raw) statuses.add(RequestState.valueOf(status));
            String type = optional(input, "type"), workflow = optional(input, "workflowId");
            if (workflow != null && !workflow.matches(UUID_PATTERN)) throw invalid();
            Instant from = instant(optional(input, "createdFrom")),
                    before = instant(optional(input, "createdBefore"));
            if (from != null && before != null && !from.isBefore(before)) throw invalid();
            return new RequestSearchQuery(
                    scope,
                    sort,
                    pagination,
                    limit,
                    offset,
                    q,
                    statuses,
                    type == null ? null : RequestType.valueOf(type),
                    workflow == null ? null : UUID.fromString(workflow),
                    from,
                    before,
                    cursor);
        } catch (IllegalArgumentException | DateTimeException bad) {
            throw invalid();
        }
    }

    static Instant instant(String input) {
        if (input == null) return null;
        var instant = OffsetDateTime.parse(input).toInstant();
        int year = instant.atOffset(ZoneOffset.UTC).getYear();
        if (year < 1 || year > 9999) throw invalid();
        return instant;
    }

    private static String optional(MultiValueMap<String, String> p, String key) {
        String s = p.getFirst(key);
        if (s != null && s.isEmpty() && !key.equals("q")) throw invalid();
        return s;
    }

    private static String value(MultiValueMap<String, String> p, String key, String fallback) {
        var s = optional(p, key);
        return s == null ? fallback : s;
    }
}
