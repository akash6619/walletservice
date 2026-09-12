# Wallet Service — Implementation Plan

This document tracks implementation against the approved [architecture](ARCHITECTURE.md). Each phase ends at a reviewable boundary. A phase is marked complete only after its verification gate passes; feedback is incorporated before starting the next phase.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Project and persistence foundation | Completed |
| 2 | Core domain and transactional persistence | Completed (`e8a9c3f`) |
| 3 | HTTP API, authentication, and error handling | Completed (`7e224bd`) |
| 4 | Observability and operational endpoints | Completed |
| 5 | Concurrency verification and burst tooling | Completed |
| 6 | Delivery automation and deployment readiness | Completed |
| 7 | Live deployment and final acceptance | Not started |

## Phase 1 — Project and persistence foundation

Goal: establish a reproducible build and the infrastructure contracts without implementing business behavior.

Deliverables:

- Java 21 and Spring Boot Maven project with Maven Wrapper.
- Minimal dependencies for Web, JDBC, Security/JWT, Flyway, PostgreSQL, Actuator, Prometheus, validation, and testing.
- Versioned Flyway migrations for `users`, `wallets`, and `transfers`.
- Environment-based datasource, JWT-secret, opening-balance, and port configuration.
- Multi-stage non-root Dockerfile with `/healthz` health check.
- Docker Compose app and PostgreSQL services with a persistent local database volume.
- `.env.example`, `.gitignore`, and `.dockerignore`.

Verification gate:

- `./mvnw verify` succeeds from the generated project.
- `docker compose config -q` succeeds.
- Docker image and Compose runtime test succeeds when a Docker daemon is available.

Current result:

- Maven build: passed.
- Compose configuration validation: passed.
- Container image and Compose runtime: passed with Java 21 app and PostgreSQL 17.6 containers healthy.
- Flyway applied all three migrations; `/healthz`, `/readyz`, and `/metrics` returned `200`.

Review focus:

- Dependency and configuration minimality.
- Schema constraints and indexes.
- Docker and Compose structure.

## Phase 2 — Core domain and transactional persistence

Goal: implement money movement and idempotency correctly at the database boundary, independent of HTTP and JWT handling.

Deliverables:

- Immutable model records/POJOs: `User`, `Wallet`, `Transfer`, `TransferStatus`, and `TransferResult`.
- `UserRepository`, `WalletRepository`, and `TransferRepository` using `NamedParameterJdbcTemplate` and bound parameters.
- Atomic wallet insertion with `ON CONFLICT (user_id) DO NOTHING`.
- Idempotency claim with unique `(from_user, idempotency_key)` and provisional `PROCESSING` state.
- Conditional sender debit and atomic recipient credit.
- Terminal `APPLIED` and `REJECTED_INSUFFICIENT_FUNDS` outcomes.
- Transactional `AccountService`, `TransferService`, and participant-scoped `TransferQueryService`.
- Focused service unit tests for business branching and statement ordering; repository SQL is verified against real PostgreSQL in Phase 5.

Verification gate:

- Project compiles and all Phase 2 unit tests pass.
- Code inspection confirms the idempotency claim occurs before balance mutation in the same transaction.
- Insufficient-funds outcome commits rather than being rolled back by an exception.
- No identity is accepted as a sender argument from an API request model.

Review focus:

- Transaction boundary and statement ordering.
- Affected-row invariants.
- Replay versus conflict semantics.
- Readability of explicit SQL.

Current result:

- Models, repositories, services, and domain exceptions implemented.
- Idempotency claim, ordered wallet upsert, conditional debit, credit, and terminal outcome ordering covered by unit tests.
- Eight focused service tests pass.
- Real PostgreSQL concurrency behavior remains intentionally reserved for Phase 5.

## Phase 3 — HTTP API, authentication, and error handling

Goal: expose only the required authenticated API contract.

Deliverables:

- `POST /accounts` and `GET /accounts/me`.
- `POST /transfers` and participant-only `GET /transfers/{id}`.
- Request/response DTOs and small manual mappers.
- Stateless Spring Security resource-server configuration.
- HS256 JWT verification for signature, issuer, audience, expiry, and UUID `sub`.
- Registered/active-user authorization.
- Bean validation plus explicit self-transfer and idempotency-key validation.
- Domain exceptions and `@RestControllerAdvice` mappings.
- Stable `ApiError(code, message, retryable, correlation_id)` response.
- Controller/security tests for status codes, response shapes, and authorization.

Verification gate:

- Missing, invalid, and expired JWTs return `401`.
- A valid JWT for an unregistered/inactive caller returns `403`.
- A transfer can spend only the JWT subject's wallet.
- Nonparticipants receive `404` for transfer reads.
- Invalid input, insufficient funds, replay, and conflict return the approved status and body.
- No registration, login, session, token-issuance, UI, or extra business endpoint exists.

Review focus:

- API contract and status-code choices.
- Authentication-to-caller mapping.
- Information-leak prevention.
- Separation among controller, DTO, mapper, service, model, and repository layers.

Current result:

- All four required account/transfer endpoints implemented with snake_case DTOs and manual mappers.
- Stateless HS256 JWT validation enforces signature, issuer, audience, expiry, and UUID subject.
- Spring Security derives caller identity exclusively from the authenticated token.
- Domain, validation, authorization, database, and unexpected failures have centralized HTTP mappings.
- Twenty-two unit/web/security tests pass; PostgreSQL-backed behavior remains scheduled for Phase 5.

## Phase 4 — Observability and operational endpoints

Goal: satisfy the health, readiness, logging, and metrics requirements without adding an external observability stack.

Deliverables:

- Server-generated correlation UUID in MDC, `X-Correlation-ID`, and error responses.
- Structured JSON request and domain-event logs.
- Logs for applied/rejected transfers, replay/conflict, wallet creation/race loss, authentication failures, and retryable database failures.
- Post-commit business logging and counters.
- `/healthz` process liveness independent of PostgreSQL.
- `/readyz` readiness including a short-timeout database check.
- Public `/metrics` in Prometheus format.
- Low-cardinality counters and HTTP latency histogram suitable for p99 calculation.
- Security rules that expose only the three operational endpoints without authentication.

Verification gate:

- Every response has a server-generated correlation ID.
- Logs contain correlation IDs but no tokens, idempotency keys, names, balances, or credentials.
- `/healthz` stays live during a database outage while `/readyz` returns `503`.
- `/metrics` contains HTTP latency and transfer/wallet outcome series with no high-cardinality labels.

Review focus:

- Log usefulness and redaction.
- Correct post-commit event timing.
- Operational endpoint exposure.
- Metric names and label cardinality.

Current result:

- Every request receives a new server-generated correlation UUID in MDC and `X-Correlation-ID`.
- Structured request, authentication, database, wallet, and transfer events contain no business identifiers or secrets.
- Low-cardinality HTTP duration/count, transfer outcome, and wallet upsert metrics are emitted through `/metrics`.
- Actuator exposes only public `/healthz`, `/readyz`, and `/metrics`; readiness includes PostgreSQL while liveness does not.
- Twenty-six unit/web/security/observability tests pass; database-outage behavior remains part of PostgreSQL verification.

## Phase 5 — Concurrency verification and burst tooling

Goal: prove the invariants against real PostgreSQL behavior under concurrent requests and retries.

Deliverables:

- Focused Testcontainers PostgreSQL integration suite using the real Flyway migrations.
- Concurrent first-use wallet creation test.
- Many identical-key requests test proving one balance mutation and one transfer row.
- Concurrent distinct-transfer test proving the sender cannot overspend.
- Same-key/different-body `409` test.
- Participant authorization test at the HTTP/database boundary.
- Retryable deadlock/lock-timeout mapping coverage.
- One-command live burst script accepting `BASE_URL`, `TOKEN_A`, and `TOKEN_B`.
- Script retries `503` with the same idempotency key, fails on any `500`, and reconciles balances.

Verification gate:

- Integration suite passes repeatedly against PostgreSQL.
- Exactly one wallet exists per user after concurrent first use.
- Exactly one logical transfer exists per sender/key.
- Final balances equal opening supply plus net transfers; no balance is negative.
- Burst script provides clear pass/fail output and a nonzero exit code on invariant failure.

Review focus:

- Whether tests reproduce the evaluator's stated gate.
- Determinism and failure diagnostics.
- Deadlock retry behavior without server-side retry loops.

Current result:

- Six PostgreSQL 17.6 Testcontainers tests exercise real Flyway migrations, concurrent first-use creation, identical-key replay, overspend prevention, conflict handling, participant authorization, and lock-timeout rollback/mapping.
- Deadlock SQLSTATE mapping is covered separately and returns a retryable `503` without a server-side retry loop.
- The executable burst script supports zero-configuration local Compose runs and explicit Render URL/token inputs, retries `503` with the same key, rejects `500` and inconsistent replay outcomes, and reconciles balances.
- The full Maven verification gate passes with 33 tests; the focused concurrency suite also passed three consecutive runs.

## Phase 6 — Delivery automation and deployment readiness

Goal: make a clean checkout buildable, testable, and deployable with minimal manual configuration.

Deliverables:

- GitHub Actions workflow running `./mvnw verify`.
- Render configuration/instructions for Dockerfile deployment after CI passes.
- Supabase session-pooler JDBC/TLS configuration instructions.
- Demo-user administrative SQL with proper UUIDs and names; it does not create wallets.
- Private demo-token generation/setup instructions without an HTTP issuance endpoint.
- README covering local setup, environment variables, API curl examples, tests, Compose, burst script, deployment links/placeholders, and free-tier limitations.
- Final Docker image and Compose smoke tests.

Verification gate:

- CI passes from a clean checkout.
- `docker compose up --build` starts app and database and applies all migrations.
- Container runs as non-root and its health check passes.
- No secret or generated token is committed.
- Deployment requires only the five approved environment variables plus Render's `PORT`.

Review focus:

- Ease of setup for an evaluator.
- Secret handling.
- CI and Render simplicity.
- Scope control in documentation.

Current result:

- GitHub Actions runs the complete Maven verification suite, including PostgreSQL Testcontainers tests, for pushes to `main` and pull requests.
- A Render Blueprint builds the checked-in Dockerfile on the Free plan, waits for CI checks, uses `/healthz`, and prompts for only the five approved deployment variables.
- The README documents local operation, API calls, verification, Compose, Render/Supabase deployment, demo provisioning, private token generation, burst testing, and free-tier limitations.
- Idempotent demo-user SQL creates no wallets, and a local token utility generates short-lived assessment JWTs without persisting secrets.
- Maven verification, Compose validation, rebuilt container startup, non-root execution, operational endpoints, metrics, and the local burst test all pass.

## Phase 7 — Live deployment and final acceptance

Goal: deploy and demonstrate the complete assessment artifact.

Deliverables:

- Public Render application URL.
- Supabase-backed persistent schema migrated by the application.
- Render logs access/link for reviewers.
- Public health, readiness, and metrics endpoints.
- Seeded demo users and privately supplied JWTs.
- Successful live burst-script output.
- Public GitHub repository and final submission checklist.

Verification gate:

- All required endpoints work against the public URL.
- Live burst passes repeatedly with no `500` and reconciled balances.
- Restart/redeploy preserves wallets, transfers, and idempotency outcomes.
- Database unavailability fails transfers closed with retryable `503` behavior.
- Repository, application, and log links are ready to send.

Review focus:

- Exact match to the assessment mail.
- Live concurrency correctness.
- Operational evidence and free-tier cost statement.

## Change-control rules

- Do not begin the next phase until the current phase has been reviewed or explicitly accepted.
- Update this status table and the completed phase's result after every review.
- Changes to an approved architectural decision must also update `ARCHITECTURE.md`.
- Do not add features merely because a framework makes them easy.
- Correctness changes may cross a phase boundary when necessary, but the reason must be recorded here.
