# Verification status

Last updated: 2026-10-04.

## Verified in this workspace

- Java 21 runtime downloaded locally; distribution SHA-256 matched the vendor metadata.
- The committed Maven wrapper completed `-Dtest=SecurityConfigTest,ApiContractTest clean verify`:
  executable JAR built, 10 selected tests passed. This is not a full database test run.
- Six JWT/security tests passed: valid subject, forged signatures, expiry, issuer/audience,
  missing/invalid claims, and weak startup secrets.
- Four HTTP contract tests passed: token identity and JSON response contract, spoofed body fields,
  mismatched keys, floating-point/missing prices, authentication, and admin access.
- Python script compilation, shell syntax checks, and `docker compose config --quiet` passed.
- Token generation and burst-runner failure reporting against an unreachable local port passed.
  These script checks do not establish concurrency correctness or server performance.

## Implemented but not runtime-verified here

- PostgreSQL transaction/concurrency suite: compiles, but database initialization was blocked by the
  macOS execution sandbox (`shmget: Operation not permitted`). The failure occurred before API startup.
  Embedded PostgreSQL locale was made explicit after an initial locale initialization error.
- Docker startup and image build: Docker CLI is installed, but no daemon is available. Launching
  Docker Desktop from this environment failed. Docker files are provided, not claimed as tested.
- Full HTTP burst, database-backed metrics, DB-outage checks, and clean-clone CI still need execution.
- Public repository publication, public deployment, cold-start verification, and live-log evidence
  are pending. There is no live URL yet.

## Run next

1. On a normal machine with JDK 21, run `./mvnw clean verify`. Or set TEST_DB_URL, TEST_DB_USER,
   and TEST_DB_PASSWORD to a disposable PostgreSQL database as documented in README.md.
2. Start Docker, run `./scripts/setup-env.sh` and `docker compose up --build -d`.
3. Run `python3 scripts/smoke.py http://localhost:8080`.
4. Run `./burst.sh http://localhost:8080 --requests 500 --concurrency 500`, then the full 20,000 burst.
5. Stop DB and run smoke with `--expect-db-down`; restart DB afterward.
6. Repeat startup, smoke, burst, and metrics/log inspection against the actual public deployment.

Do not present generated tests or configured CI as passing concurrency evidence until these runs succeed.
