# Design and operational notes

## Atomic decision

PostgreSQL READ COMMITTED transactions and explicit row locks arbitrate booking. A primary key gives
each physical seat one row and one current reservation pointer. The transaction locks user/show usage
and requested seats before checking availability and the limit. Assignment also uses
`WHERE reservation_id IS NULL` and checks affected-row counts.

Mutation lock order: idempotency (reserve only), user/show usage, existing reservation (cancel only),
then seats under C-collation label ordering. Multi-seat requests are all-or-nothing. Deterministic seat
ordering prevents cyclic waits between overlapping seat requests. Operations never lock another
user's usage row or call remote services inside the transaction.

Database constraints enforce identities and relationships; the locked service transaction enforces
the cross-row limit. Arbitrary direct SQL by someone with database write credentials is outside the
API's correctness boundary.

## Idempotency

A unique `(user_id, operation, key)` stores canonical request and original response. Canonical identity
includes show ID plus sorted unique validated labels. A conflicting insertion waits for the original
transaction, then reads the committed result. No unfinished key record is deliberately committed.

Same request returns the original result, with `200` for successful replay to distinguish the one `201`.
Changed show or seats return `409`. Business declines persist; a fresh attempt needs a fresh key.
Malformed/invalid requests roll back key claims. Keys have indefinite retention for this submission.

Exactly-once applies to the committed booking effect, not HTTP response delivery. A lost response after
commit is recovered with the same key. No external payments are implemented; amounts are integer paise,
not actual charges. Real payments need durable payment state, an outbox, and provider-side idempotency.

## Cancellation and expiry

Immediate confirmation plus owner-only cancellation is the chosen model. There are no temporary holds
or expiry worker; held is always zero. Cancellation locks usage, reservation, and seats, clears only
its matching ownership, decrements usage once, and marks history cancelled. Old cancellation retries
cannot release a future booking. Reserve retries after cancellation do not reactivate anything.

## Consistency versus availability

The primary database is authoritative. When it is unreachable, readiness fails and booking does not
proceed. No stale local state can approve a booking during a partition. Expected contention is `409`;
database failure is `503`. The service does not claim zero 5xx during infrastructure outages.

Virtual threads do not remove resource limits. Connection pools, deadlines, proxy limits, CPU, and
storage latency must be tuned using measurements from the deployed host. Free-tier capacity is not
assumed sufficient without a real public burst.

## Observability and 2am alerts

`/metrics` combines durable business totals and current inventory gauges with Micrometer metrics.
Business values come from one database snapshot and are shared across replicas, so use `max`, not
`sum`, over replica labels. Separate API requests and scrapes can observe different committed moments.
Cumulative reservations differ from current occupied seats after cancellations. Replay is counted
separately from fresh declines.

Page for readiness failures, unexpected 5xx/transport errors, severe booking latency, and any detected
inventory/usage invariant violation. Investigate pool saturation, lock waits, deadlocks, disk pressure,
and slow transactions. Seat-taken declines are normal on-sale outcomes. PostgreSQL lock diagnostics
can be inspected through its system views; a PostgreSQL exporter is a future operational improvement.

Structured logs include request IDs, outcome, and latency. `/logs` exposes the latest 1,000 sanitized
request events per instance for public observation during a burst. Routes are normalized; identities,
bodies, query strings, and authentication headers are excluded. This bounded view resets on restart;
platform stdout remains the operational log. Public deployment verification is recorded separately.

## AI usage

AI assistance was used for design, implementation, tests, scripts, and documentation.
The developer selected Spring Boot and directed a design-first implementation with local API
verification. The assistant proposed PostgreSQL row locking, the immediate-confirmation/cancellation
model, idempotency conventions, and the test strategy, and generated substantial portions of the
code and supporting files. Automated checks and manual API exercises were used to validate the
implementation; the scope of completed verification is recorded in docs/VERIFICATION.md.

## Next

Complete database/container tests, deploy publicly, verify cold starts and DB outages, and run the
full public burst. Publish repository URL, live URL, metrics, and log evidence. Future engineering:
managed identity and key rotation, event retention/aggregation, dashboards and alerts, backup/restore
checks, payment integration if needed, and a guarded expiry/confirmation lifecycle if holds are added.
