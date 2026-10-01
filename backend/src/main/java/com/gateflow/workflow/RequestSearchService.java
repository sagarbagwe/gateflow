package com.gateflow.workflow;

import static com.gateflow.workflow.RequestSearchDtos.*;

import com.gateflow.rbac.AuthorizationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class RequestSearchService {
    private final AuthorizationService authorization;
    private final RequestAccessPolicy access;
    private final RequestSearchRepository repository;
    private final SearchCursor cursors;

    public RequestSearchService(
            AuthorizationService authorization,
            RequestAccessPolicy access,
            RequestSearchRepository repository,
            SearchCursor cursors) {
        this.authorization = authorization;
        this.access = access;
        this.repository = repository;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page search(UUID user, UUID org, RequestSearchQuery q) {
        var actor = authorization.requireMember(user, org);
        access.requireSearchAccess(actor, q.scope());
        String context = cursors.context(actor, q);
        var cursor = cursors.decode(q.cursor(), context);
        var rows = repository.search(actor, q, cursor);
        boolean more = rows.size() > q.limit();
        var returned = rows.subList(0, Math.min(rows.size(), q.limit()));
        String next = null;
        if (more && q.pagination() == RequestSearchQuery.Pagination.CURSOR) {
            var last = returned.getLast();
            next = cursors.encode(last.asOf(), last.item().createdAt(), last.item().id(), context);
        }
        return new Page(
                returned.stream().map(RequestSearchRepository.Row::item).toList(),
                q.limit(),
                more,
                next,
                q.pagination(),
                q.pagination() == RequestSearchQuery.Pagination.OFFSET ? q.offset() : null);
    }
}
