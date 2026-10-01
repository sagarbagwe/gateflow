package com.gateflow.workflow;

import static com.gateflow.workflow.WorkflowDtos.*;

import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;
import java.util.function.Supplier;

@Service
public class CommandReceipts {
    private final JdbcTemplate jdbc;
    private final WorkflowJson json;
    private final RequestRepository requests;

    public CommandReceipts(JdbcTemplate jdbc, WorkflowJson json, RequestRepository requests) {
        this.jdbc = jdbc;
        this.json = json;
        this.requests = requests;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CommandResult execute(
            UUID org,
            UUID actor,
            UUID key,
            String operation,
            Object body,
            Supplier<RequestView> command) {
        String digest = json.hash(body);
        // Database-scoped, transaction-lifetime serialization of retries; not a distributed Redis
        // lease.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (RowCallbackHandler) rs -> {},
                org + ":" + actor + ":" + key);
        record Receipt(String operation, String hash, UUID request) {}
        var rows =
                jdbc.query(
                        "SELECT operation,payload_hash,request_id FROM command_receipts WHERE"
                            + " organization_id=? AND actor_membership_id=? AND idempotency_key=?",
                        (rs, n) ->
                                new Receipt(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getObject(3, UUID.class)),
                        org,
                        actor,
                        key);
        if (!rows.isEmpty()) {
            var previous = rows.getFirst();
            if (!previous.operation().equals(operation) || !previous.hash().equals(digest))
                throw WorkflowPolicy.conflict(
                        "IDEMPOTENCY_CONFLICT",
                        "Idempotency key was used with a different command");
            return new CommandResult(requests.find(org, previous.request(), true), true);
        }
        var result = command.get();
        jdbc.update(
                "INSERT INTO"
                    + " command_receipts(organization_id,actor_membership_id,idempotency_key,operation,payload_hash,request_id)"
                    + " VALUES(?,?,?,?,?,?)",
                org,
                actor,
                key,
                operation,
                digest,
                result.id());
        return new CommandResult(result, false);
    }
}
