package com.gateflow.http;

import java.util.List;

public record PageSlice<T>(List<T> items, int limit, int offset, boolean hasMore) {
    public PageSlice {
        items = List.copyOf(items);
    }

    public static <T> PageSlice<T> from(List<T> rows, int limit, int offset) {
        return new PageSlice<>(
                rows.subList(0, Math.min(rows.size(), limit)), limit, offset, rows.size() > limit);
    }
}
