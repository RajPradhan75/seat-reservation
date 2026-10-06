# Verification status

Updated: 2026-10-06.

## Verified in CI

[Successful clean-checkout run](https://github.com/RajPradhan75/seat-reservation/actions/runs/37496687189), revision `3554bae`:

- All 18 tests passed, including seven real PostgreSQL integration tests across two API instances.
- Docker image built and started; authenticated reservation lifecycle smoke test passed.
- 20,000 simultaneous hot-seat requests: one HTTP 201, 19,999 seat-taken HTTP 409 responses, zero 5xx and zero transport errors. Duration: 49.667 seconds.
- All 19 burst assertions passed, including idempotency, limits, cancellation, rebooking, identity, metrics and reconciliation. Two inventory samples were collected during the storm; both reconciled. This sparse sampling does not prove every intermediate state independently of the database invariants.
- Database outage failed readiness closed; database recovery and subsequent smoke test passed.
- Raw burst results: [ci-20000-burst.jsonl](evidence/ci-20000-burst.jsonl). Full logs and test reports are attached to the CI run.

## Capacity configuration

Tomcat accepts at most 512 active connections and explicitly closes each after its response. This bounds application connection resources without racing an aggressive idle timeout. Docker Compose sets the kernel listen/SYN backlogs to 32768. CI disables Docker's user-space port proxy and uses kernel port forwarding. Earlier runs through the proxy became unreachable while the API container remained running. These network settings matter; the result is specific to this tested environment.

## Public deployment

The service is deployed at https://seat-reservation-api-osgz.onrender.com. Readiness returned HTTP 200 with UP on 2026-10-06.

The 20,000-request result above is from CI, not Render. Verification of the updated deployed revision, authenticated live-URL load testing and a deployment cold-start check remain pending. Render controls its own network queue settings; Docker Compose sysctls do not configure Render.

## Reproduce

Follow README.md for `./mvnw clean verify`, Docker startup, smoke tests, and `./burst.sh`. Use a disposable database for integration tests. The CI workflow records kernel/container diagnostics and preserves evidence even on failure.
