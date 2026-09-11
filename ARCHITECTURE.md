# Wallet Service — Architecture Proposal

Status: **architecture approved; ready for implementation**

Approved decisions:

1. Demo-only configurable opening balance, issued exactly once when a wallet row is first created; production default is zero.
2. No registration, login, logout, refresh/session management, or token-issuance HTTP endpoints. The service only verifies externally issued JWTs and applies authorization.
3. Java implementation using a minimal Spring Boot/JDBC stack; PostgreSQL semantics remain the consistency mechanism.
4. Render Free Web Service for the immutable API container and Supabase Free PostgreSQL for persistent data; Supabase's optional Auth/API/Realtime/Storage features are not used.
5. Render's structured-log explorer is the operational log interface; reviewers receive access before submission rather than adding a separate logging platform.

## 1. Scope and success criteria

Build only the requested HTTP API: signed-token authentication, wallet get-or-create, peer-to-peer transfers, participant-only transfer reads, health/readiness, Prometheus metrics, JSON logs, migrations, a concurrency burst script, container packaging, and free-tier deployment.

The primary success criterion is correctness under concurrent requests and retries. UI, registration, password management, deposits/withdrawals, transaction history, reversals, currencies, notifications, and an event bus are explicitly out of scope.

### Required invariants

1. A committed transfer debits and credits exactly the same positive integer number of paise.
2. A sender balance never becomes negative.
3. For a caller and idempotency key, at most one logical transfer outcome exists.
4. Reusing that key with a different recipient or amount returns `409` and never mutates balances.
5. There is exactly one wallet row per user.
6. Only the authenticated subject can spend its wallet; only transfer participants can read it.

## 2. Recommended architecture

Use a stateless HTTP service backed by one PostgreSQL primary. PostgreSQL is both the persistence layer and the concurrency/atomicity boundary. Run any number of identical app replicas; correctness must not depend on in-process locks, sticky sessions, or a cache.

Implementation stack: **Java 21**, Spring Boot, Spring Web, Spring JDBC (`JdbcTemplate`/`NamedParameterJdbcTemplate`), HikariCP, Spring Security Resource Server for JWT verification, Flyway migrations, Micrometer's Prometheus registry, Actuator health endpoints, and structured JSON logging with correlation IDs in MDC. Use explicit SQL for wallet upserts, idempotency claims, conditional balance mutation, and affected-row checks. Do not use JPA/Hibernate for the transfer write path: its implicit flush/order behavior would obscure the exact database operations this exercise is intended to demonstrate.

Java has a larger memory and image footprint than Go, but developer fluency and implementation clarity are more important for a one-day exercise. Constrain the JVM for the free container (`-XX:MaxRAMPercentage` and an appropriate minimum heap), keep dependencies narrow, and verify cold-start/memory behavior on the selected Render plan. PostgreSQL—not language-level synchronization—remains the correctness mechanism.

```text
Client / burst script
        |
   HTTPS host/router
        |
 stateless wallet API  ----> stdout JSON logs ----> host log viewer
        |
        +--------------> /metrics
        |
 managed PostgreSQL (single source of truth)
```

No Redis, queue, distributed lock service, ORM-level `find-or-create`, or microservices are needed.

### 2.1 Concrete hosting decision

Deploy the API as an immutable Docker image on **Render Free Web Service** and store all durable state in a separate **Supabase Free PostgreSQL** project.

```text
Public Internet
      |
      | HTTPS (TLS terminated by Render)
      v
Render Web Service
  image: built by Render from the repository Dockerfile
  filesystem: ephemeral; application writes no state to it
      |
      | PostgreSQL protocol over TLS (`sslmode=require` or stronger)
      v
Supabase PostgreSQL
  durable tables: users, wallets, transfers, flyway_schema_history
```

This split is deliberate. Render's free service filesystem is ephemeral and free services can spin down after inactivity, so neither balances nor idempotency records may be stored in the container. Supabase PostgreSQL has a lifecycle independent of the app container; application restarts, Render deploys, and Render idle shutdowns therefore do not erase wallet state. The API is replaceable compute; PostgreSQL is the system of record.

Why these providers for this exercise:

- Render supports public HTTPS web services and builds the checked-in multi-stage Dockerfile after GitHub CI checks pass. No buildpack or separate container registry is used.
- Supabase provides persistent managed PostgreSQL, TLS, and shared connection pooling independently of the app container lifecycle. Only its PostgreSQL capability is used.
- Do **not** use Render Free Postgres for this submission: its current free database expires after 30 days, which makes the evaluator URL unnecessarily fragile.

Provider limits must be acknowledged rather than disguised as production guarantees. Render Free can cold-start after 15 minutes idle, is restricted to one instance, and has an ephemeral filesystem. Supabase Free currently advertises a 500 MB database limit, no automatic backups/PITR, and possible pausing after approximately one week of insufficient activity. These are sufficient for an interview workload but not an SLA-backed production wallet. Verify limits and ensure the project is active immediately before submission because free-tier terms can change.

### 2.2 Persistent database operation

Use Supabase's shared **session-mode** pooler on port `5432` as secrets:

- `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`: Supabase session-pooler connection with TLS required. Flyway and the application deliberately share this datasource for the exercise. HikariCP uses a small pool of 5 connections to protect the free database during the burst.

Do not use Supabase's transaction-mode pooler on port `6543` for this container: its session-state and prepared-statement restrictions add JDBC caveats without providing value for one long-running application instance.

The production database is never initialized from container-local files at runtime except by applying checked-in migrations. The container does not run PostgreSQL and does not mount a database volume. Local Compose uses a named Postgres volume only for developer persistence; hosted persistence is Supabase-managed.

Because Render's free tier does not provide a pre-deploy command, Spring Boot executes Flyway during startup before the service becomes ready. A migration failure terminates startup, leaving `/readyz` unavailable; the API must never start against a partially compatible schema. Migrations must be backward-compatible with the currently deployed binary where rollout overlap is possible.

Committed transfers rely on PostgreSQL durable storage, never local disk or application memory. The free database is adequate for the assessment but is not presented as an SLA-backed production wallet. Schema is reproducible from migrations in Git; backup and disaster-recovery infrastructure are outside this exercise.

### 2.3 Deployment flow and configuration

1. GitHub Actions runs `./mvnw verify`, including the focused PostgreSQL Testcontainers suite.
2. After CI passes, Render checks out the linked commit, builds the checked-in multi-stage Dockerfile, and runs the resulting image. It binds to `0.0.0.0:$PORT`, runs Flyway during application startup, and runs as a non-root user.
3. Render and the Docker `HEALTHCHECK` target `/healthz`. `/readyz` remains available to reviewers for explicit database-readiness checks without causing restart loops during a database outage.
4. Configure only values that are secret or deployment-specific on Render: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET`, and `INITIAL_BALANCE_PAISE`. Render supplies `PORT`. JWT issuer/audience and conservative pool/timeout/logging defaults live in checked-in `application.yml`; Spring can still override them later without us defining a large custom environment contract. `.env.example` contains variable names and placeholders, never credentials.
5. Deploy, capture the public `https://<service>.onrender.com` URL, and run the live burst script before submission.

Choose the closest compatible Render and Supabase regions to reduce transaction lock duration and p99 latency. Cross-provider database traffic uses the public session-pooler endpoint with TLS; database credentials are least-privilege application credentials, not the default database owner. If network restrictions are available on the selected plans, allow only Render's documented regional outbound ranges; otherwise TLS credentials remain the free-tier boundary and that limitation is stated explicitly.

## 3. HTTP contract

All money values are signed 64-bit integers at the API boundary and `BIGINT` in PostgreSQL. Reject JSON numbers that are fractional, zero, negative, or outside Java `long`. Use `Content-Type: application/json` and return `{ "code": "...", "message": "...", "retryable": false, "correlation_id": "..." }` for errors.

| Endpoint | Result / decision |
|---|---|
| `POST /accounts` | Atomic get-or-create for JWT `sub`; `200 {"balance": n}`. Repeated calls return the same row. |
| `GET /accounts/me` | `200 {"balance": n}`; `404 account_not_found` if never created. |
| `POST /transfers` | `200` for both first success and successful replay; `422` for insufficient funds; `409` for key/body conflict; `400` for invalid amount or self-transfer. Success contains the original `transfer_id` and sender `new_balance`. |
| `GET /transfers/{id}` | `200` only when JWT `sub` is sender or recipient; otherwise return `404` to avoid disclosing existence. |
| `GET /healthz` | Process/event-loop liveness only; never depends on PostgreSQL. |
| `GET /readyz` | Short-timeout PostgreSQL ping plus migration/schema compatibility; `503` when unavailable. |
| `GET /metrics` | Public Prometheus text endpoint. Never include user IDs or idempotency keys as labels. |

JWTs are supplied as `Authorization: Bearer <token>`. Verify the HS256 algorithm, signature, issuer, audience, expiry, and a non-empty UUID `sub`. Never accept a user identity from a header or request body. Registration, login, sessions, and token issuance are not part of the service; pre-generated demo tokens are supplied privately to assessors. Production should use an asymmetric issuer/JWKS, but adding an identity provider is outside scope.

## 4. Data model

```sql
CREATE TABLE users (
  user_id       uuid PRIMARY KEY,
  name          varchar(200) NOT NULL,
  active        boolean NOT NULL DEFAULT true,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
  wallet_id     uuid PRIMARY KEY,
  user_id       uuid NOT NULL UNIQUE REFERENCES users(user_id),
  balance_paise bigint NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
  transfer_id       uuid PRIMARY KEY,
  from_user         uuid NOT NULL REFERENCES users(user_id),
  to_user           uuid NOT NULL REFERENCES users(user_id),
  amount_paise      bigint NOT NULL CHECK (amount_paise > 0),
  idempotency_key   varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
  status            varchar(32) NOT NULL CHECK
                    (status IN ('PROCESSING', 'APPLIED', 'REJECTED_INSUFFICIENT_FUNDS')),
  sender_balance_after bigint,
  created_at        timestamptz NOT NULL DEFAULT now(),
  CHECK (from_user <> to_user),
  CHECK ((status = 'APPLIED' AND sender_balance_after IS NOT NULL)
      OR (status IN ('PROCESSING', 'REJECTED_INSUFFICIENT_FUNDS') AND sender_balance_after IS NULL)),
  UNIQUE (from_user, idempotency_key)
);

CREATE INDEX transfers_to_user_idx ON transfers(to_user);
```

`users` is the minimal local registry of valid identities; it does not implement credentials, registration, login, sessions, or token issuance. User IDs are UUIDs provisioned independently of display names. The verified JWT `sub` and `to_user` request value must parse as UUIDs; names are never used for identity or authorization. Both participants must exist and be active. A valid user may have no wallet yet, preserving the required transfer-time wallet creation behavior.

`wallet_id` gives the wallet an identity independent of the user and leaves room for a future multi-wallet model, while `UNIQUE (user_id)` enforces the exercise's current rule of exactly zero or one wallet per user. We intentionally do not implement multiple wallets, currencies, or wallet lifecycle at this stage. Transfers reference users rather than wallets because a valid participant can exist before their wallet is created.

The idempotency key is scoped to the authenticated sender, which prevents unrelated users from colliding. Compare the stored `to_user` and `amount_paise` directly to detect a same-key/different-body request; a separate request hash adds no value for this small request shape.

Applied and insufficient-funds outcomes are retained indefinitely for this exercise. Expiry introduces the possibility that an old retry moves money again. A production retention policy may archive rows, but the uniqueness tombstone for `(from_user, idempotency_key)` must live at least as long as clients may retry—often indefinitely for financial operations.

### Retry versus new attempt

A client **may and should retry the same logical transfer with the same idempotency key** after a timeout, dropped response, or other uncertain result. The server does not re-execute a committed terminal outcome; it returns the stored result. A caller that wants the balance to be evaluated again after a known business rejection is expressing a **new attempt** and must use a new key.

| First outcome | Persisted against key? | Same-key, same-body request |
|---|---:|---|
| Applied | Yes | Return the original `transfer_id` and `sender_balance_after`; never move money again. |
| Insufficient funds | Yes | Return the original `422`, even if funds arrived later. A new attempt needs a new key. |
| Same-key/different-body conflict | No new record | Return `409`; leave the original record unchanged. |
| Authentication or request-validation failure | No | Authenticate/validate again; no idempotency claim was made. |
| Database timeout/unavailable before a known commit | No terminal record; transaction rolls back | Retry the same key. If the earlier commit actually succeeded but its response was lost, the retry finds and replays it. |
| Process crash before commit | No; PostgreSQL rolls back | Retry the same key and safely become the new claimant. |

An existing terminal transfer row is immutable. Replays produce a structured log event and increment an in-memory metric, but do not update replay counts or timestamps in PostgreSQL. The only status transition is performed by the winning request inside its original transaction: `processing` becomes `applied` or `rejected_insufficient_funds` before commit.

## 5. Exact transfer algorithm

Use one PostgreSQL transaction at `READ COMMITTED`; set a short statement/lock timeout and return a retryable `503` on database timeout/unavailability. Pseudocode:

```text
authenticate and validate request shape; reject self-transfer before opening a transaction
BEGIN

confirm sender and recipient are registered and active

INSERT a provisional transfer claim keyed by (sender, idempotency_key)
  ON CONFLICT DO NOTHING

if this request did not win the claim:
  SELECT the existing transfer (the unique-conflict wait observes its committed row)
  compare explicit recipient and amount
  mismatch -> COMMIT/return 409
  match -> COMMIT/return the stored original applied or rejected outcome

for sender and recipient in ascending user_id order:
  INSERT wallet(wallet_id, user_id, configured_initial_balance)
    ON CONFLICT (user_id) DO NOTHING

conditionally debit sender:
  UPDATE wallets
  SET balance_paise = balance_paise - amount
  WHERE user_id = sender AND balance_paise >= amount

if affected row count is 0:
  finalize claim as rejected_insufficient_funds
  COMMIT; return 422

UPDATE recipient balance = balance + amount
finalize claim as applied and store the sender balance returned by the debit
COMMIT; return 200
```

The claimed row uses the internal `processing` state, which cannot survive as an externally visible result because it is inserted and finalized in the same transaction. If the process dies, PostgreSQL rolls the claim and all balance mutations back together. The idempotency claim is therefore **before** mutation but inside the **same** transaction. A competing identical request waits on the unique index and then replays the committed result. It can never observe a half-finished outcome.

The claim precedes wallet creation so a same-key/different-body request cannot create an unintended recipient wallet. Transfers reference the already-existing user records, so no deferred wallet foreign keys are necessary. Wallet creation uses `INSERT ... ON CONFLICT (user_id) DO NOTHING`, backed by the unique user constraint. This is the simplest correct resolution of concurrent first-use creation: the database arbitrates uniqueness atomically, and losing the insert race is expected—not an exception or `500`. Wallet IDs are generated by the application, and a losing insert simply discards its unused candidate UUID. Wallet inserts use ascending user-ID order to reduce avoidable circular waits during concurrent first use.

The conditional sender update combines the balance check and debit atomically. Its affected-row count distinguishes success from insufficient funds, so concurrent transfers cannot overspend. The recipient credit and terminal transfer outcome remain in the same transaction; any failure rolls back all of them. PostgreSQL still takes row locks for the updates, but the common path avoids a preliminary read and avoids locking the recipient before it is needed.

This ordering can deadlock for a circular workload such as simultaneous `A -> B` and `B -> A`. PostgreSQL detects the wait cycle and aborts one whole transaction with SQLSTATE `40P01`; the application must roll it back and map it to a documented retryable `503`, never an accidental `500`. The service does not retry internally. The caller retries the complete request with the same idempotency key. A transaction-local `lock_timeout` similarly becomes a retryable `503`; it bounds non-circular lock waits, while PostgreSQL's `deadlock_timeout` governs cycle detection. Common fan-out (`A -> B`, `A -> C`), fan-in (`A -> B`, `C -> B`), and chains (`A -> B`, `B -> C`) may wait on a shared row but do not by themselves form a cycle.

`READ COMMITTED` plus conditional updates is sufficient: database row locks serialize mutations of each shared balance, and the unique index serializes identical idempotency keys. Explicitly pre-locking both wallets in sorted order would reduce deadlock probability, while `SERIALIZABLE`, advisory/distributed locks, and a ledger/event-sourcing architecture are heavier alternatives. We reject them here in favor of lower common-path query count and accept documented client retry for rare circular transactions.

### 5.1 Code structure

Keep the implementation as one Spring Boot application with narrow, conventional layers:

```text
controller     AccountController, TransferController
service        AccountService, TransferService, TransferQueryService
repository     UserRepository, WalletRepository, TransferRepository
model          User, Wallet, Transfer, TransferStatus, TransferResult
dto            request/response records and ApiError
mapper         AccountMapper, TransferMapper
security       JWT configuration and authenticated-subject extraction
exception      domain exceptions and RestControllerAdvice
observability  correlation filter, business metrics, structured events
health         liveness and database-readiness contributors
config         minimal typed application configuration
```

Controllers own HTTP concerns, services own use-case and transaction orchestration, and repositories own explicit SQL plus affected-row validation. Models are immutable records/POJOs rather than persistence-aware entities. API DTOs remain separate, and small manual mappers prevent accidental exposure of internal fields. Do not add a parallel DAO layer, generic repository framework, or MapStruct dependency.

## 6. Fault model and consistency/availability choice

Transfers are **consistency-first and fail closed**. If PostgreSQL is unavailable, slow beyond the configured timeout, loses the connection, or commit outcome is uncertain, return `503` with a retryable error. Never acknowledge success from memory or queue a debit for later. The client retries with the same idempotency key; once PostgreSQL recovers, the stored outcome determines whether to replay or execute. An ambiguous client response after commit is safe for the same reason.

Balance reads also use the primary database—no stale cache or replica—because a wallet balance is a decision-relevant financial value. Under overload, bound connection pools and deadlines, then shed load with `503` rather than build an unbounded queue. This reduces availability but preserves understandable read-after-write behavior and prevents stale balances from being presented as current. Liveness remains up while readiness goes down, allowing the platform to stop routing new traffic without restart loops.

NFR priority order: **correctness/data integrity > security > durability/auditability > bounded latency > availability > throughput**. Target values for the exercise: no invariant violation at any concurrency; p99 measured and exposed rather than promised on an unknown free tier; request deadline around 5 seconds, DB statement/lock timeout below it, and a small bounded pool sized for the database free-tier limit.

## 7. Security and edge cases

- Sender is always JWT `sub`; `from_user` is not accepted in transfer JSON.
- Recipient is a registered, active user UUID. A registered recipient without a wallet receives one transactionally; an unknown or inactive recipient returns `404`.
- Self-transfer: `400`, with no wallet or transfer row created.
- Zero, negative, fractional, or non-`long` amount: `400`.
- Insufficient funds: stable, persisted `422`; an identical retry returns the same rejection even if funds arrive later. A new attempt requires a new key.
- Same key/different recipient or amount: `409`, regardless of whether the original outcome was applied or rejected.
- PostgreSQL arithmetic overflow is outside the assessment's operational range; if encountered, PostgreSQL rolls back the complete transaction and the service returns `500`.
- Do not log bearer tokens or full idempotency keys. Hash/redact user identifiers in logs if public logs are truly anonymous-access.
- Database credentials and JWT verification secrets come only from environment/secret settings. Spring Boot runs Flyway before accepting traffic, using the same exercise datasource; Flyway coordinates concurrent migration attempts.

## 8. Observability

Generate a fresh UUID correlation ID for every request, add it to MDC and JSON logs, return it as `X-Correlation-ID`, include it in error bodies, and clear MDC in a `finally` block. Client-supplied tracing IDs are ignored. Emit one JSON object per event with timestamp, level, correlation ID, route, method, status, duration, and safe error code. Required domain events: `transfer_applied`, `insufficient_funds`, `idempotent_replay`, `idempotency_conflict`, `wallet_create_won`, `wallet_create_conflict`, and `auth_failed`. Do not interpret normal upsert conflicts as errors.

Business success/rejection logs and counters are emitted only after the transactional service returns and its commit has succeeded. Database exceptions are logged after rollback by the global exception handler. This avoids reporting rolled-back transfers as applied; a crash after commit but before logging is accepted because PostgreSQL remains the source of truth.

Metrics:

- `http_requests_total{route,method,status_class}`
- `http_request_duration_seconds{route,method}` histogram (dashboard computes p99)
- `transfers_total{outcome="applied|insufficient|replay|conflict"}`
- `wallet_upserts_total{outcome="created|existing"}`
- database pool in-use/wait counters and readiness state

Labels must remain low-cardinality; never label by request, transfer, user, or idempotency key. The submission links to Render's service log explorer and grants the reviewers access before submission. The write-up describes this accurately as assessor-accessible rather than anonymously public. No separate logging platform is added, and secrets/PII are never exposed merely to satisfy the log-link request.

## 9. Delivery and verification

- Multi-stage Dockerfile: pinned builder image, tests/build in builder, minimal runtime image, non-root UID, read-only-compatible filesystem, only the binary and certificates, `HEALTHCHECK` against `/healthz`.
- `docker-compose.yml`: app plus pinned PostgreSQL, DB healthcheck, app waits for readiness, named volume, environment from an uncommitted `.env`; checked-in `.env.example` contains no secrets.
- Migrations are versioned and tested from an empty database.
- CI from clean checkout: unit tests and the focused integration/concurrency tests against PostgreSQL, followed by packaging. Render builds the same checked-in Dockerfile after CI passes.
- Deploy the Render-built image and connect it to Supabase PostgreSQL over TLS. Keep one app instance acceptable for the free demo, but do not rely on that for correctness.
- Before assessment, insert two UUID-identified users with realistic names through the documented administrative SQL and generate matching demo tokens without creating wallets. The burst script accepts those tokens as environment variables, fires concurrent first transfers plus many identical-key retries, retries documented `503` responses with the same keys, and then verifies balances and conserved total. Database-backed integration tests additionally verify wallet-row and logical-transfer uniqueness.

The focused automated gate includes: concurrent first-use wallet creation, identical-key retries applying once, unique concurrent transfers never overspending, and same-key/different-body conflict. The live burst script exercises first transfers, retries retryable `503` responses with the same keys, fails on any `500`, and reconciles final balances.

## 10. Approved decision record

### A. Initial funding — APPROVED

The requested API can create only zero-balance wallets and has no deposit/funding operation, so no successful transfer can ever occur from a clean system. This must be resolved without inventing a public money-creation feature.

**Approved:** every wallet receives the environment-configured opening balance exactly once through the successful wallet insert. Its conceptual production default is zero; the assessment deployment sets a demo value. This makes new-user live tests reproducible without adding a funding endpoint. Transfer conservation is measured from the resulting initial supply. `POST /accounts` and transfer-driven recipient creation use the same rule, so creation order cannot change the result.

### B. User namespace — APPROVED

**Approved:** keep a minimal `users` registry with UUID `user_id`, required `name`, `active`, and creation timestamp. Demo users are inserted manually through documented administrative SQL; provisioning does not create wallets. Both the verified JWT subject and recipient must be registered and active. There remains no public user-registration or user-management API. A wallet has its own UUID primary key plus `UNIQUE (user_id)`, preserving exactly one wallet per user today while leaving a clear schema evolution path without implementing multi-wallet behavior.

### C. Insufficient-funds idempotency — APPROVED

**Approved:** persist the rejection as the original outcome. Thus a retry remains rejected after later funding; the caller uses a new key for a new attempt. This gives literal “original outcome” semantics and deterministic retries. Transient infrastructure failures roll back and remain safely retryable with the same key.

### D. Authentication for assessment — APPROVED

**Approved boundary:** no registration, login, logout, refresh/session management, or token-issuance endpoint. The wallet service accepts externally issued bearer JWTs, verifies signature and claims, and derives caller identity exclusively from UUID `sub`. For this demo it validates HS256 tokens using an environment-only Render secret, and assessment tooling receives pre-generated tokens as inputs. A production deployment would replace this with an asymmetric issuer/JWKS without changing the wallet API.

### K. Schema migrations — APPROVED

**Approved:** use Spring Boot Flyway startup migration with separate, ordered SQL files: `V1__create_users.sql`, `V2__create_wallets.sql`, and `V3__create_transfers.sql`. Each file owns its table, constraints, and directly related indexes. The same migrations initialize Docker Compose, Testcontainers, and Supabase; no independently maintained schema script is allowed. Already-applied migrations are immutable, and any future change receives a new forward migration.

### L. Remaining implementation choices — APPROVED

- Use immutable model POJOs/records, separate API DTOs, and small manual mappers; do not expose persistence models directly.
- Use `NamedParameterJdbcTemplate` repositories and no additional DAO abstraction.
- Use a public Spring-managed `@Transactional(READ_COMMITTED)` transfer service. Expected insufficient-funds rejection returns a result so its terminal record commits; exceptions that require rollback propagate through the transaction boundary.
- Map domain exceptions with `@RestControllerAdvice`. A `OncePerRequestFilter` owns only correlation-ID/MDC lifecycle and generic request timing.
- Use a small domain exception set for invalid transfer (`400`), idempotency conflict (`409`), unknown resources (`404`), unregistered caller (`403`), retryable database failures (`503`), and invariant violations (`500`). Spring Security owns invalid/missing JWT responses (`401`). Insufficient funds returns a rejected result across the commit boundary and is then rendered as `422`, so its terminal record is not rolled back.
- Configure stateless bearer security: account/transfer APIs require JWT, while only `/healthz`, `/readyz`, and `/metrics` are public. Disable sessions, CSRF, and CORS.
- Treat idempotency keys as opaque 1–128-character printable ASCII strings, scoped to the authenticated sender. Do not trim, normalize, return, or log them.
- Enable graceful shutdown with a short timeout. Add no custom deployment coordinator.
- Do not add OpenAPI/Swagger or a UI; README curl examples are sufficient.

## 11. Definition of done

The work is complete only when the clean-checkout command passes migrations/tests and builds the image; Compose starts app+DB; the live URLs respond; the supplied burst script passes repeatedly with no `500`; starting and ending totals reconcile; duplicate wallets and duplicate logical transfers are absent; authorization probes pass; and `/healthz`, `/readyz`, logs, and metrics work as documented. The README contains the required endpoint/run/deployment/burst instructions; the architecture document supplies the design reasoning from which the final one-page submission can be derived.

## 12. Provider references (verified 2026-09-10)

- [Render free-tier behavior and limitations](https://render.com/docs/free)
- [Render Docker deployment](https://render.com/docs/docker)
- [Render health checks](https://render.com/docs/health-checks)
- [Supabase Free plan limits](https://supabase.com/pricing)
- [Supabase database connections and pooler modes](https://supabase.com/docs/guides/database/connecting-to-postgres)
- [Supabase free-project pausing](https://supabase.com/docs/guides/platform/free-project-pausing)
