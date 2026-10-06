# Verification status

Updated: 2026-10-07. The public service does not yet meet the full 20,000-request acceptance bar.

## Clean-checkout CI: passed

[Run 37538949046](https://github.com/RajPradhan75/seat-reservation/actions/runs/37538949046), revision `bf067a8`:

- All 19 tests passed, including PostgreSQL concurrency tests across two API instances and health-probe access while booking admission is saturated.
- Docker build, startup and authenticated lifecycle smoke test passed.
- 20,000 concurrent hot-seat requests: one confirmation, 19,999 seat-taken declines, zero 5xx and zero transport errors. Duration: 30.276 seconds.
- All 19 burst assertions passed. All 218 inventory samples reconciled.
- Database outage failed readiness/metrics/writes closed; recovery smoke test passed.
- [Raw CI results](evidence/ci-20000-burst.jsonl); full reports and logs are attached to the run.

## Public Render service: partial validation

API: https://seat-reservation-api-osgz.onrender.com

The deployed code is revision `bf067a8`. The Docker container now uses HAProxy to queue writes separately from reads/probes, a 192 MiB Java heap, and a 128-connection internal Java cap. The API and database remain on free plans. Authenticated lifecycle smoke tests passed after deployment startup. Metrics (`/metrics`) and sanitized recent request logs (`/logs`) are public. Readiness returned UP after the failed public load tests.

A 500-concurrent-user storm on revision `fe06cdc` passed: one winner, 499 seat-taken declines, zero 5xx, zero transport failures, 196 valid reconciliation samples, and all 19 assertions passed. Duration: 69.61 seconds. [Raw results](evidence/render-500-burst.jsonl).

The full public burst failed on both tested configurations. [Latest independent Linux test](https://github.com/RajPradhan75/seat-reservation/actions/runs/37535811659) against revision `bf067a8`: one winner, 11 seat-taken declines, 2,271 HTTP 5xx responses, 17,717 transport failures, 163.006 seconds. [Raw latest results](evidence/render-20000-burst-latest.jsonl). [Earlier measured failure](evidence/render-20000-burst.jsonl).

This fails the live concurrency acceptance bar. The passing CI result must not be represented as passing public capacity. Render reports 0.15 CPU and 512 MiB for this free instance. Pool waits, slow health responses, gateway failures and connection failures were observed under overload. Further free-tier tuning and a successful public retest are required. No paid upgrade is planned or authorized. Inactivity-driven cold-start timing remains unmeasured; cold deployment startup and recovery have been observed.

A separate HTTP/2 run against the restored free configuration passed 500 concurrent logical requests over five TLS connections: one winner, 499 seat-taken declines, zero 5xx/transport errors, all 80 reconciliation samples valid, and all 19 checks passed in 46.991 seconds. [Raw HTTP/2 results](evidence/render-http2-500-burst.jsonl). The full 20,000-logical-request HTTP/2 test failed: one winner, 1,100 seat-taken declines, 15,247 5xx and 3,652 rate-limited responses in 93.312 seconds. All responses negotiated HTTP/2; there were zero transport errors. Inventory reads also failed under load. [Run 37537350248](https://github.com/RajPradhan75/seat-reservation/actions/runs/37537350248), [raw results](evidence/render-http2-20000-burst.jsonl). This does not pass the acceptance bar.

The updated deployment (`bf067a8`) passed its authenticated smoke test and a 500-user HTTP/2 burst: one winner, 499 declines, no 5xx/transport errors, 45 valid inventory samples, and all 19 checks passed in 41.393 seconds. [Raw results](evidence/render-proxy-http2-500-burst.jsonl). The full retest [37539909506](https://github.com/RajPradhan75/seat-reservation/actions/runs/37539909506) failed: one winner, 7,254 seat-taken declines, 10,800 HTTP 5xx, and 1,945 rate limits in 210.28 seconds, with no transport failures. Later retry, cancellation, limit and final-state checks passed, but inventory reads failed during the large burst. [Raw results](evidence/render-proxy-20000-burst.jsonl).

At the user's request, further large public runs were stopped and defaults were reduced to 10 concurrent users. The small public demo passed all 19 checks: one hot-seat winner, nine seat-taken declines, zero errors, three valid inventory samples, and 0.546 seconds for the hot-seat phase. [Small-demo evidence](evidence/render-10-demo.jsonl). This is demo validation, not full public-load validation.

## Capacity configuration

Booking writes queue on virtual threads behind a fair 12-permit semaphore before authentication/DB work. Health and read requests bypass this admission queue; the 16-connection DB pool retains read capacity. This queue only controls resource use; PostgreSQL transactions remain the correctness mechanism. Occupied-seat reads can decline without the hot-seat row lock; available seats must still pass the locked allocation transaction.

Direct Java execution allows up to 8192 TCP connections; the container now queues up to 25,000 connections in HAProxy and admits only 16 concurrent requests into Java. Docker Compose enforces a 512 MiB API container limit, and the latest complete CI run passed within that limit. Docker Compose raises kernel listen/SYN backlogs to 32768, and CI uses kernel port forwarding. Render controls its own network queues. After public overload tests, the free Render service was restored to a 512-connection cap with a 256 MiB Java heap, two reported processors, and bounded direct/code-cache memory. The recovery deployment is live and its authenticated lifecycle smoke test passed. That older configuration was superseded by the queueing Docker runtime described above. Render Events identified five-second HTTP health-check timeouts as the reason for restarts during those failed bursts. The current runtime passed the small demo but failed the full public retest recorded above. The finite 180-second proxy queue can expire under sustained overload; expired requests are infrastructure failures, never fabricated seat-taken declines.

## Reproduce

README.md documents build, Docker, smoke tests and burst commands. `.github/workflows/live.yml` runs the fixed public endpoint from an independent Linux runner using a protected signing-secret setting. Evaluators receive short-lived token bundles privately. Use disposable databases for integration tests. CI preserves evidence even on failure.
