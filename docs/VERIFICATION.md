# Verification status

Updated: 2026-10-06. Functional results below were obtained on 2026-10-04 and 2026-10-05.

## Completed

- Maven wrapper build with `-Dtest=SecurityConfigTest,ApiContractTest clean verify`: executable JAR
  produced; six security tests and four HTTP contract tests passed.
- Local application startup against Docker PostgreSQL 17.11; readiness returned HTTP 200 UP.
- Local-profile startup verified with credentials loaded from `.env`.
- Smoke test passed in a developer-run local execution.
- All 19 manual collection requests returned their expected HTTP statuses against the running API.
  Replay identity, cancellation history, final inventory, and metrics were checked.
- Read-only SQL checks confirmed Alice's cancelled two-seat reservation, Bob's current A1 ownership,
  matching per-user usage, and inventory of four available, zero held, and one confirmed seat.
- Python script compilation, shell syntax, and Docker Compose configuration validation passed.
- Token generation and burst-runner handling of an unreachable endpoint were checked.

## Pending

- Full PostgreSQL concurrency integration suite.
- 20,000-request burst and capacity measurements.
- API Docker image build/startup and clean-clone CI execution.
- Database-outage behavior, recovery, and deployment cold starts.
- Public repository publication, deployed URL, and live log access or recording.

The completed functional checks do not establish the concurrency or deployment acceptance criteria.

## Reproduce

Follow README.md for `./mvnw clean verify`, Docker startup, the smoke test, and `./burst.sh`.
Use a disposable database for integration tests. Run the load and dependency-failure checks against
both the local containers and the eventual deployed service, and record their results here.
