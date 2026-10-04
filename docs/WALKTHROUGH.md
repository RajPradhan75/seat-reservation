# Step-by-step implementation walkthrough

Read the files below in this order. The controller accepts requests; the database transaction decides
who wins a seat. Implemented behavior is separate from verified behavior: see VERIFICATION.md.

## 1. Project foundation

Read `pom.xml`, `SeatReservationApplication.java`, `application.yml`, `Dockerfile`, and `compose.yaml`.

The application class starts Spring Boot. Maven resolves dependencies and builds the executable JAR.
The Maven wrapper pins the build tool. Docker builds with a JDK and runs the JAR as a non-root user
with a JRE. Compose gives PostgreSQL persistent storage and starts it before the API.

JDBC makes locking SQL explicit. Flyway versions database changes. Security validates identity.
Actuator exposes health and operational metrics. Virtual threads allow waiting HTTP requests without
one platform thread each, but do not increase database throughput: the connection pool remains bounded.

## 2. Database ownership and history

Read `src/main/resources/db/migration/V1__seat_inventory.sql`.

`shows` stores immutable inventory configuration, price, and user limit. `show_seats` has one row per
physical seat, keyed by `(show_id, seat_label)`. Its nullable `reservation_id` is the current owner.
A12 pointing to a reservation means confirmed; A12 pointing to NULL means available. There is no
second status field that can disagree with ownership.

`reservations` records the buyer and amount. `reservation_seats` preserves history even after release.
Composite foreign keys prevent a seat referencing a reservation for a different show.

`user_show_usage` stores active seat count and provides a stable row to lock for that user's show.
Without this lock, ten parallel requests could each observe a count below four and all proceed.

`idempotency_records` stores canonical request identity and the completed response. `request_outcomes`
stores committed business outcomes for metrics. It is append-only, avoiding a global counter row
that all buyers would have to lock.

## 3. HTTP boundary and identity

Read `config/SecurityConfig.java`, `api/Models.java`, `api/ApiErrors.java`, and `api/BookingController.java`.

JWT validation checks signature, issuer, audience, expiry, and subject. Scope `admin` permits creating
shows. Controllers take identity from the JWT, never a request body field. Validation rejects empty
seat lists, invalid labels, missing prices, floating-point prices, and unknown fields.

The controller chooses the header/body idempotency key and rejects disagreements. Services own the
transaction boundaries. Spring's proxy commits the service transaction before control returns to
the controller to send success.

## 4. Consistent show state

Read `service/ShowService.java`.

Creation inserts the show and all seat rows in one transaction. Reading uses one joined SQL query
for metadata and seats, then calculates counts from that same list. PostgreSQL gives the query a
single MVCC snapshot. A concurrent booking can appear before or after that snapshot, but cannot
produce an old seat list with new counts. This preserves the reconciliation equation during writes.

## 5. Reservation transaction

Read `service/ReservationService.java`, especially `reserve`, `lockUsage`, and `lockSeats`.

1. Reject duplicate labels and sort the list. Order changes do not change request identity.
2. Claim the idempotency key with `INSERT ... ON CONFLICT DO NOTHING`. The unique index arbitrates
   concurrent retries. A conflicting insert waits for the other transaction's result.
3. Existing matching requests replay the stored response; changed requests return `409`.
4. Ensure the user/show usage row exists and lock it with `FOR UPDATE`.
5. Lock all requested seats in ascending label order using `ORDER BY seat_label FOR UPDATE`.
6. Check that every seat exists and is free and that the requested count fits the user limit.
7. Create the reservation, conditionally assign every seat, record history, and increment usage.
8. Store the response and outcome in the same transaction and commit.

For 500 different users requesting A12, user locks are separate, but the seat row lock is shared.
The winner sees NULL and commits ownership. The next lock holder sees the committed owner and declines.
For one user requesting ten different seats, seat locks are separate but the usage lock serializes
limit checks. The fifth attempt sees four active seats and declines.

Multi-seat requests lock in a consistent order so reversed input cannot create a cyclic seat wait.
No seats are assigned until all checks pass. Infrastructure errors roll everything back. Domain
declines commit only their stable idempotency result and outcome; the seats and usage remain unchanged.

An unfinished idempotency row exists only inside the uncommitted transaction. A crash before commit
rolls it back. A lost response after commit is recovered by retrying the same key. HTTP delivery is
not exactly-once; the committed booking effect is.

## 6. Cancellation

Read `ReservationService.cancel`.

Cancellation verifies ownership, locks usage, then the reservation, then sorted seats. It clears only
pointers matching that reservation, decrements usage once, and marks the reservation cancelled.
A repeated cancellation returns without mutation. If another buyer subsequently books the seat,
a stale cancellation cannot match or clear that new reservation.

There is no expiry scheduler. The selected model is immediate confirmation and explicit cancellation.
Original reserve retries return the recorded original result and never resurrect cancelled bookings.
Use reservation GET for current state.

## 7. Observability

Read `api/MetricsController.java`, `config/RequestLogFilter.java`, and Actuator configuration.

Business metrics use one database snapshot. Seat gauges count current ownership; counters count
committed outcome records. Rollbacks do not increment counters, and restarting an API does not reset
them. A database restore can reset historical totals.

Cumulative confirmations differ from occupied seats after cancellation. Replays have a separate
counter because a successful replay is not a decline. Each replica sees the same business values;
monitoring must not sum these across replicas.

Requests receive an ID, also present in structured logs along with status, path, duration, and outcome.
Bearer tokens and request bodies are not logged. Readiness checks the database, while liveness checks
the process. A dependency outage must stop booking decisions without causing pointless restarts.

## 8. Verification

Read `src/test/java`, `scripts/smoke.py`, and `scripts/burst.py`.

JWT and HTTP contract tests cover boundary behavior. The database suite launches two application
instances sharing actual PostgreSQL and exercises contention, retries, limits, multi-seat atomicity,
and cancellation. Post-test SQL compares recorded usage to current seat ownership.

The burst runner reads reconciliation snapshots during traffic and reports failures, including
network failures. Running it against the eventual public URL is essential: passing local tests
would not establish that a free-tier host survives 20,000 requests. Current test evidence is
recorded explicitly in VERIFICATION.md.
