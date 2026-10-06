# Verification status

Updated: 2026-10-07. The public service does not yet meet the full 20,000-request acceptance bar.

## Clean-checkout CI: passed

[Run 37535475942](https://github.com/RajPradhan75/seat-reservation/actions/runs/37535475942), revision `7c5e490`:

- All 19 tests passed, including PostgreSQL concurrency tests across two API instances and health-probe access while booking admission is saturated.
- Docker build, startup and authenticated lifecycle smoke test passed.
- 20,000 concurrent hot-seat requests: one confirmation, 19,999 seat-taken declines, zero 5xx and zero transport errors. Duration: 42.757 seconds.
- All 19 burst assertions passed. All 109 inventory samples reconciled.
- Database outage failed readiness/metrics/writes closed; recovery smoke test passed.
- [Raw CI results](evidence/ci-20000-burst.jsonl); full reports and logs are attached to the run.

## Public Render service: partial validation

API: https://seat-reservation-api-osgz.onrender.com

The deployed code is revision `7c5e490`. Authenticated lifecycle smoke tests passed after deployment startup. Metrics (`/metrics`) and sanitized recent request logs (`/logs`) are public. Readiness returned UP after the failed public load tests.

A 500-concurrent-user storm on revision `fe06cdc` passed: one winner, 499 seat-taken declines, zero 5xx, zero transport failures, 196 valid reconciliation samples, and all 19 assertions passed. Duration: 69.61 seconds. [Raw results](evidence/render-500-burst.jsonl).

The full public burst failed on both tested configurations. [Latest independent Linux test](https://github.com/RajPradhan75/seat-reservation/actions/runs/37535811659) against revision `7c5e490`: one winner, 11 seat-taken declines, 2,271 HTTP 5xx responses, 17,717 transport failures, 163.006 seconds. [Raw latest results](evidence/render-20000-burst-latest.jsonl). [Earlier measured failure](evidence/render-20000-burst.jsonl).

This fails the live concurrency acceptance bar. The passing CI result must not be represented as passing public capacity. Render reports 0.15 CPU and 512 MiB for this free instance. Pool waits, slow health responses, gateway failures and connection failures were observed under overload. Further capacity and a successful public retest are required. Inactivity-driven cold-start timing remains unmeasured; cold deployment startup and recovery have been observed.

## Capacity configuration

Booking writes queue on virtual threads behind a fair 12-permit semaphore before authentication/DB work. Health and read requests bypass this admission queue; the 16-connection DB pool retains read capacity. This queue only controls resource use; PostgreSQL transactions remain the correctness mechanism. Occupied-seat reads can decline without the hot-seat row lock; available seats must still pass the locked allocation transaction.

The local/CI configuration allows up to 8192 TCP connections. Docker Compose raises kernel listen/SYN backlogs to 32768, and CI uses kernel port forwarding. Render controls its own network queues. After public overload tests, the free Render service is being restored to a 512-connection cap with a 256 MiB Java heap, two reported processors, and bounded direct/code-cache memory. This recovery configuration still needs its own large public capacity verification.

## Reproduce

README.md documents build, Docker, smoke tests and burst commands. `.github/workflows/live.yml` runs the fixed public endpoint from an independent Linux runner using a protected signing-secret setting. Evaluators receive short-lived token bundles privately. Use disposable databases for integration tests. CI preserves evidence even on failure.
