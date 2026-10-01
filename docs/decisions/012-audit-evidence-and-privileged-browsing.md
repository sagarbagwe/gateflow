# ADR 012: bounded append-only evidence and privileged tenant browsing

Status: accepted for Milestone 10.

Keep audit evidence in PostgreSQL alongside business transactions. AUDIT_VIEW is a
separate explicit organization-wide investigative privilege. Do not expose every
member's resource history by default, cache grants or copy complete private payloads.

Use metadata-only bounded offset browsing and a separate bounded/redacted detail
read. This is simpler than exports/search infrastructure; offset drift and a 10000
maximum are accepted limitations. A later consistent export/keyset API needs its
own contract. Add actor/action indexes rather than index every filter combination.

Reject malformed new producer evidence; preserve immutable historical rows and
redact unsupported keys on read. New database size constraints are NOT VALID for
legacy rows. Neither migration rewrites evidence nor claims retroactive validation.

Reuse existing atomic writer and immutability triggers. No separate audit broker,
extra datastore, speculative retention job or claim of owner-proof tamper resistance.
