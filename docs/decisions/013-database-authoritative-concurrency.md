# ADR 013: database-authoritative concurrency without redundant distributed locks

Status: accepted for Milestone 11.

Retain existing aggregate row locking plus explicit versions, transactional advisory
serialization for scoped idempotency receipts, CAS preferences and unique/fenced
worker effects. They share PostgreSQL as authority and work across API replicas.

Add deterministic server-lock-barrier tests, rather than claim simultaneous client
starts prove a race. Check all durable side effects and correlate the winning audit.
Seven scenarios cover decisions, withdrawal, reassignment, duplicate/conflicting
submissions, publication and preference updates. Observe both blocked transactions
before releasing a test-only barrier; either valid winner is acceptable.

Do not add Redis distributed locks: lease expiry would not replace transactional row
integrity, and every command already commits to PostgreSQL. Do not rewrite working
production locks merely to make M11 look like a new feature. No new migration needed.

Trade-offs: hot aggregate serialization, hash collision contention, bounded DB pool
and worker isolation concerns, and clients must explicitly resolve 409 stale intent.
No automatic business retry, performance guarantee or exhaustive race proof.
See [consistency contract](../concurrency/consistency.md).
