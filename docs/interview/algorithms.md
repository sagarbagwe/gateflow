# Genuine algorithm/data-structure connections

Costs below describe bounded in-memory work or ideal indexed navigation, not measured
end-to-end API complexity. SQL predicates, locks, plans and IO can dominate.

| Problem                                   | Approach / data structure                                                                  | Time                                                         | Extra space                             | Why / limitation                                                                                                          |
| ----------------------------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------ | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| Evaluate ordered approval conditions      | Scan ordered step list; preserve execution order                                           | O(S) condition evaluations                                   | O(S) execution records                  | Sequential policy semantics, at most 50 configured steps; no invented graph engine                                        |
| Safely retry an unchanged HTTP command    | Serialize path/payload as an intent fingerprint; retain UUID key and pending promise       | O(B) payload serialization/comparison                        | O(B) fingerprint, one pending reference | Same intent reuses a durable server receipt; no automatic retry of a different payload                                    |
| Suppress stale async responses            | Epoch + per-channel sequence Map in AsyncScope                                             | Expected O(1) start/check                                    | O(C) active channels                    | A previous account/workspace result cannot replace a newer result; no invented global cache                               |
| Stable bounded request navigation         | Ordered (created_at, UUID) tuple and index-backed seek; fetch one sentinel                 | Ideal O(log N + K); actual plan/predicate dependent          | O(K) page/cursor state                  | Avoids deep offset scanning; current authorization and shifting eligibility still matter                                  |
| Compute/show permission membership safely | Small bounded permission arrays and membership tests; deterministic role grant replacement | Array lookup/toggle O(P); rendering with caller grants O(PG) | O(P) selected grants                    | Clear ceiling checks for a small catalog; server enforces actual authorization, no need for elaborate algorithm machinery |

S = configured steps, B = serialized body bytes, C = concurrent resource channels,
N = candidate indexed rows, K = page size, P = catalog/selection size, G = caller grants.

The backend also uses receipts/unique constraints for event deduplication and full-text
indexes for search. Do not assign universal O(1) or O(log N) to complete DB operations
without examining the real plan. Priority queues, graph traversal and parallel quorum
are not added just to make this document look impressive.
