# OnceLedger

OnceLedger is a small Spring Boot application that demonstrates **durable request idempotency** using a transfer ledger. A client can safely retry a request after a timeout without causing a second transfer. An idempotency key is bound to the original request; reusing that key with different transfer details returns HTTP `409 Conflict`.

The application moves **demo credits**, not money. It starts with two seeded accounts, exposes a JSON API, and provides a small browser interface at `http://127.0.0.1:8080/`.

## What it demonstrates

- A PostgreSQL unique constraint arbitrates concurrent requests using the same key.
- Each transfer, its two ledger entries, and the saved HTTP receipt commit in a single transaction.
- Account rows are locked in a consistent order to prevent overdrafts and reduce deadlock risk.
- A completed response, including a business rejection, is replayed with the same status and body.
- Validation failures and changed requests do not silently reuse an existing operation.
- Automated tests exercise concurrent submissions, rollback, conflict handling, and HTTP behavior against PostgreSQL.

## Stack

Java 21, Spring Boot 4.1.1, Spring MVC, Spring JDBC, PostgreSQL 17, Flyway, Maven, JUnit Jupiter, and Testcontainers. The UI uses plain HTML, CSS, and JavaScript. The development database runs with Docker Compose.

## Requirements

- Java 21
- Maven 3.9 or newer
- Docker Desktop or a compatible Docker daemon, running before the database tests

## Run locally

From the repository root:

```sh
docker compose up -d postgres
mvn spring-boot:run
```

Then open `http://127.0.0.1:8080/`. Flyway creates the tables and seeds `operating` with 100,000 credits and `savings` with 25,000 credits (values stored in cents). The service binds to loopback by default.

On Windows, these same commands work from PowerShell when Docker Desktop, Maven, and Java are available on `PATH`.

To stop the application, terminate the Maven process and run `docker compose down`. This stops the development database **without removing its named volume**. Only use `docker compose down -v` when you specifically intend to discard the demonstration data.

The default database username and password in `compose.yaml` are for a **local, disposable demo only**. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and `SERVER_ADDRESS` explicitly for other environments. Do not expose this unauthenticated demonstration application to a network.

## Try the API

First transfer (PowerShell 7+ or a shell with `curl`):

```sh
curl -i -X POST http://127.0.0.1:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-transfer-001' \
  -d '{"fromAccount":"operating","toAccount":"savings","amountCents":1250}'
```

On success the API responds with HTTP `201 Created`, a `transferId`, `outcome: "posted"`, and `Idempotency-Replayed: false`. Repeat **exactly** the same request and key: the status and receipt are preserved, but `Idempotency-Replayed` becomes `true`. The balances change only once.

Changing `amountCents` while keeping `example-transfer-001` produces HTTP `409 Conflict`. Sending more credits than the source holds produces HTTP `422 Unprocessable Entity` and a saved receipt with `outcome: "rejected"` and `reason: "insufficient_funds"`.

Read balances:

```sh
curl http://127.0.0.1:8080/api/accounts
```

Health endpoint:

```sh
curl http://127.0.0.1:8080/actuator/health
```

The interactive UI generates a fresh idempotency key. Leave that key unchanged and submit again to see a replay, or alter an amount to demonstrate a conflict.

## API contract

`POST /api/transfers` requires an `Idempotency-Key` header of 8 to 128 characters. The first character must be alphanumeric; subsequent characters may be alphanumeric or `.`, `_`, `:`, `-`.

Request body:

```json
{
  "fromAccount": "operating",
  "toAccount": "savings",
  "amountCents": 1250
}
```

Account identifiers contain 2 to 32 lowercase letters, numbers, and dashes, beginning with a letter. `amountCents` is an integer from 1 to 10,000,000 inclusive. The two account identifiers must be different.

| Status | Meaning | Saved for key? |
| --- | --- | --- |
| `201` | Transfer posted; repeat requests return the saved response | Yes |
| `422` | Account missing or insufficient balance | Yes |
| `409` | Key already belongs to a different valid request | No new receipt |
| `400` | Missing or invalid header/body | No |
| `5xx` | Unexpected failure; the database transaction rolls back | No committed receipt from the failed attempt |

The fingerprint is a SHA-256 hash of a versioned, canonical representation of the validated transfer fields. Keys remain scoped to this single demonstration service and database, not to users or tenants.

If a client loses a connection after the database commits, retrying with the same key returns the stored receipt. If the database operation fails before commit, both the business changes and the key reservation roll back, so the same key can be retried. No external side effects are performed.

## Local quality gates

**Windows PowerShell 5.1+:**

```powershell
.\scripts\verify.ps1
```

**POSIX shell:**

```sh
sh scripts/verify.sh
```

Both scripts fail if Docker is unavailable or if Maven verification fails. They run the public-text audit, a Java syntax-only preflight, a clean Maven build with Java compiler warnings treated as errors, unit tests, Testcontainers PostgreSQL integration tests, and Git whitespace checks. A locally installed Docker daemon is necessary for the integration tests.

Tests cover same-key retries, different-body conflicts, immutable rejection receipts, restart-equivalent replay, simultaneous duplicate requests, competing balance changes, opposite-direction transfers, injected database failures, and HTTP request validation. A test is only considered passed after `mvn clean verify` completes successfully in the actual environment.

## Design boundaries

This is a **local technical demonstration**, not an account management or payment platform. It deliberately has no login, tenant isolation, real funds, external payment provider, webhook side effects, or production deployment configuration. The two seeded accounts are shared demo fixtures. Protect the service with authentication and authorization, isolate keys per client, add operational limits, and implement appropriate account ownership rules before adapting this design to a real multiuser system.

The database is the single source of truth. There is no in-memory cache for receipts, no separate message broker, and no background worker to reconcile partially applied database effects. See [Architecture](docs/architecture.md) for transaction ordering and failure semantics.

## License

MIT. See [LICENSE](LICENSE).
