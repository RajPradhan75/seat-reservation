# Seat Reservation

Spring Boot 4.1.1, Java 21, PostgreSQL. Work in progress; implementation and verification are recorded incrementally in Git.

## Local startup

```sh
./scripts/setup-env.sh
docker compose up --build
```

Docker Desktop must be running. Credentials are generated into an ignored `.env` file.
Readiness: `http://localhost:8080/actuator/health/readiness`.
Liveness: `http://localhost:8080/actuator/health/liveness`.

## Design

Immediate confirmation; explicit owner cancellation; all-or-nothing seat requests.
Database transactions arbitrate ownership, user limits, and idempotency across application instances.
No payment provider is integrated. Amounts are integer paise.
