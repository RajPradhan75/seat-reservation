# Seat Reservation

Assigned-seat JSON API: Spring Boot 4.1.1, Java 21, PostgreSQL 17.
Design and tradeoffs: [WRITEUP.md](WRITEUP.md).
Test results and outstanding validation: [VERIFICATION.md](docs/VERIFICATION.md).

**Status:** clean-checkout CI passed all 18 tests and a 20,000-concurrent-request burst with zero 5xx or transport errors. Public deployment is healthy; equivalent load validation on Render is still pending. See [verification evidence](docs/VERIFICATION.md).

## Start with Docker

Requires Docker Engine/Compose and Python 3 for credential generation.

```sh
./scripts/setup-env.sh
docker compose up --build -d
python3 scripts/smoke.py http://localhost:8080
```

Credentials are randomly generated in ignored `.env`; existing credentials are preserved.
PostgreSQL uses a persistent volume. Ordinary `docker compose down` retains bookings.

```sh
docker compose logs -f api
```

## Build and test

### Run from IntelliJ

Start Docker PostgreSQL with `docker compose up -d db`, then select the shared **Seat Reservation Local**
Application run configuration. It activates the `local` profile, which reads the existing `.env` from
the project working directory. No password needs to be copied into the run configuration. Set the
project SDK to Java 21. Stop any other application instance using port 8080 before starting it.

If the configuration is not visible, reopen the project or create an Application configuration for
`com.example.seats.SeatReservationApplication`, module `seat-reservation`, working directory the project
root, and program arguments `--spring.profiles.active=local`. Leave environment overrides empty.
Explicit environment variables still override file properties; remove stale DB_PASSWORD/JWT_SECRET
or SPRING_DATASOURCE_PASSWORD overrides when using this local configuration. Do not paste secrets into
the shared `.run` file. The default production profile continues to require environment credentials.

### Maven checks

Requires JDK 21. The committed Maven wrapper downloads the pinned Maven version.

```sh
./mvnw clean verify
```

Integration tests start real PostgreSQL 17.11 and two independent API instances. They require a
non-root environment permitting local sockets and PostgreSQL shared memory; no H2 substitute is used.
Alternatively supply a **disposable** test database. Flyway and the tests write application data to it:

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5432/seats_test \
TEST_DB_USER=postgres TEST_DB_PASSWORD=your-test-password ./mvnw clean verify
```

Database-independent tests:

```sh
./mvnw -Dtest=SecurityConfigTest,ApiContractTest test
```

For host-based Java development with Docker PostgreSQL:

```sh
./scripts/setup-env.sh
docker compose up -d db
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

## Manual API verification

Use [the manual demo guide](docs/MANUAL_DEMO.md), import the
[Bruno/Postman request collection](docs/api/manual-demo.postman_collection.json), and inspect
PostgreSQL with the [read-only SQL checks](docs/demo-checks.sql). No database GUI is required.

## Try the API

An operator mints tokens; callers cannot grant themselves identities or admin scope through a public
endpoint. Keep `JWT_SECRET` private. Mint deployed-service tokens with that deployment's secret.

```sh
ADMIN_TOKEN=$(python3 scripts/mint_token.py operator --admin)
USER_TOKEN=$(python3 scripts/mint_token.py alice)

curl -i http://localhost:8080/shows \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'

# Set SHOW_ID to the id from the response.
curl -i "http://localhost:8080/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-first-booking' -d '{"seats":["A1","A2"]}'

curl "http://localhost:8080/shows/$SHOW_ID"

# Set RESERVATION_ID to reservation_id from the reservation response.
curl -i -X POST "http://localhost:8080/reservations/$RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $USER_TOKEN"
```

| Endpoint | Authorization | Result |
| --- | --- | --- |
| `POST /shows` | JWT scope `admin` | `201`, show and available seats |
| `GET /shows/{id}` | Public | Seat list and reconciled counts |
| `POST /shows/{id}/reserve` | Valid JWT | `201` created, `200` replay, `409` conflict |
| `GET /reservations/{id}` | Owner | Current reservation state |
| `POST /reservations/{id}/cancel` | Owner | `200`, including repeat cancellation |
| `/actuator/health/liveness` | Public | Process liveness |
| `/actuator/health/readiness` | Public | Readiness including DB connectivity |
| `/metrics` | Public | Durable business metrics plus JVM/HTTP metrics |
| `/logs` | Public | Last 1,000 sanitized request events on this instance |
| `/actuator/prometheus` | Public | JVM/HTTP metrics only |

### Request rules

- Integer paise only; floating-point JSON prices are rejected.
- `per_user_limit` defaults to 4; admin can set 1–100 at creation.
- Show name: 1–200 nonblank characters. Show inventory: 1–50,000 seats.
- Seat labels: 1–32 ASCII letters, digits, `_`, or `-`; case-sensitive; no duplicates.
- Reserve requests: 1–100 seats. All-or-nothing, immediately confirmed.
- Use `Idempotency-Key` or body `idempotency_key`; both must agree if supplied together.
- Keys: 1–128 letters, digits, `.`, `_`, `:`, or `-`.
- Identity comes only from verified JWT `sub`. Unknown body fields are rejected.
- Reordered seats mean the same request. Changed show/seats with the same user/key return `409`.
- Keys are per user and operation, retained indefinitely in this version.
- Domain declines are stored; a new attempt after availability changes needs a new key.
- Invalid requests roll back any key claim and can be corrected and resubmitted.
- Reserve replay after cancellation returns the original creation outcome without booking again.
  Use reservation GET for its current state.
- Non-owned reservations return `404`, including attempted cancellation.
- No payment integration or temporary holds. `held` is always zero.

Errors use `{ "code": "SEAT_TAKEN", "message": "...", "request_id": "..." }`.
Conflicts are `409`: `SEAT_TAKEN`, `PER_USER_LIMIT`, `IDEMPOTENCY_KEY_REUSED`.
Validation is `400`; authentication `401`; admin authorization `403`; absent resources `404`.
Dependency failures are `503`, not false seat declines. Retry uncertain outcomes with the same key.
Responses include `X-Request-ID`; reserve responses also include `Idempotency-Replayed`.

## One-command burst

Python 3.10+ with venv support is required. The wrapper installs pinned aiohttp on first use.
Run only against a service you own or have permission to test.

```sh
# 20,000 distinct buyers compete for one seat, then other correctness scenarios run.
./burst.sh http://localhost:8080

# Smaller diagnostic run:
./burst.sh http://localhost:8080 --requests 500 --concurrency 500
```

Output includes outcome distribution, 5xx/transport failures, final counts, and reconciliation reads
during the storm. Any failed check exits nonzero. Test shows remain for inspection. The generator's
connection limits and the host's capacity affect arrival concurrency; request count alone is not
proof that every request arrived simultaneously.

An operator can generate short-lived evaluator credentials without sharing the signing secret:

```sh
python3 scripts/token-bundle.py --users 20000 --output evaluator.tokens.json
./burst.sh https://your-deployed-host --tokens-file evaluator.tokens.json
```

Bundles contain an admin token and distinct users, expire after two hours, and must be shared privately.
Files matching `*.tokens.json` are ignored by Git.
For a scheduled evaluation, the operator can set `--ttl` up to 604800 seconds (seven days).
Never publish token bundles or the signing secret in the repository.

## Deploy and observe

### Render

The committed `render.yaml` provisions the Docker API and PostgreSQL 17 in Singapore, both on
the free plan. Deploy using [this Blueprint](https://dashboard.render.com/blueprint/new?repo=https://github.com/RajPradhan75/seat-reservation).
Render generates the signing secret, injects the database credentials, and checks
`/actuator/health/readiness`. Database access is internal only. `DB_URL` remains supported for
other hosts; the Blueprint supplies `DB_HOST`, `DB_PORT`, and `DB_NAME` instead.

Free resources have capacity and lifecycle limits; acceptance requires measured public burst results.
See `docs/VERIFICATION.md` for the current status, not just the presence of deployment configuration.

### Request logs

`GET /logs` returns a rolling view of the last 1,000 API requests on the current instance, with
timestamp, request ID, normalized route, HTTP status, duration, and outcome. Query strings,
request/response bodies, authentication headers, user identities, and resource IDs are excluded.
Poll this endpoint during a burst for public log access. This bounded diagnostic view resets on
restart; the platform's structured stdout logs remain the primary operational log.

```sh
curl "$BASE_URL/logs"
```

### Runtime configuration

Build with the Dockerfile, provision persistent PostgreSQL, and set platform environment secrets:

| Variable | Purpose |
| --- | --- |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://host:5432/seats?sslmode=require` |
| `DB_USER` / `DB_PASSWORD` | Database credentials |
| `JWT_SECRET` | Random secret of at least 32 bytes; no production default |
| `PORT` | HTTP port, default 8080 |
| `DB_POOL_SIZE` | Connections per instance, default 16 |

Use `/actuator/health/readiness` for the platform readiness check. Flyway applies migrations on startup.
Use liveness for process checks; a database outage should not cause a restart storm. Instances share
the database and JWT secret. HTTP reads use the primary database, not a lagging replica.

Scrape `/metrics`. Business values are shared across replicas: aggregate them with `max` over instance
labels, not `sum`. JVM/HTTP metrics remain per instance. No user IDs or keys are metric labels.
Seat metrics include show IDs, so longer-lived deployments need show-retention and cardinality controls.

Verify fail-closed behavior after startup:

```sh
docker compose stop db
python3 scripts/smoke.py http://localhost:8080 --expect-db-down
docker compose start db
```

Before submitting, verify clean-clone CI, Docker startup, cold starts, DB-outage behavior, and the full
public burst. Add the public repository URL, live URL, metrics URL, and log access or recording.
