# Wallet Service

A concurrency-safe peer-to-peer wallet HTTP API built with Java 21, Spring Boot, explicit JDBC, and PostgreSQL. PostgreSQL transactions, conditional updates, and unique constraints enforce the balance and idempotency invariants; the application uses no in-memory locks or ORM.

## Requirements

- Docker with Compose for the simplest local workflow
- Java 21 and Docker for the Maven/Testcontainers verification suite
- Bash, curl, and Python 3 for the burst script

## Run locally

```bash
docker compose up --build -d
curl http://localhost:8080/healthz
curl http://localhost:8080/readyz
./scripts/burst.sh
```

The local burst command idempotently provisions two demo users, generates one-hour JWTs using the Compose-only secret, creates their wallets through the API, sends concurrent distinct and identical-key requests, retries `503`, and reconciles balances. It exits nonzero for any invariant failure or `500` response.

Stop the services without deleting the persistent database:

```bash
docker compose down
```

Use `docker compose down -v` only when you intentionally want to erase all local wallet data.

## Configuration

| Variable | Required | Purpose |
|---|---:|---|
| `DATABASE_URL` | Yes | JDBC PostgreSQL URL; hosted connections must require TLS |
| `DATABASE_USERNAME` | Yes | Least-privilege database role |
| `DATABASE_PASSWORD` | Yes | Database password |
| `JWT_SECRET` | Yes | At least 32 random bytes for HS256 verification |
| `INITIAL_BALANCE_PAISE` | Yes | Opening balance issued once when a wallet is first inserted; use `0` outside the demo |
| `PORT` | Render-provided | HTTP port; defaults to `8080` locally |

Copy `.env.example` only as a reference. Never commit `.env`, credentials, or generated tokens.

## API

All account and transfer routes require `Authorization: Bearer <JWT>`. The verified UUID `sub` is the caller identity.

```bash
curl -X POST "$BASE_URL/accounts" -H "Authorization: Bearer $TOKEN_A"
curl "$BASE_URL/accounts/me" -H "Authorization: Bearer $TOKEN_A"

curl -X POST "$BASE_URL/transfers" \
  -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d '{"to_user":"00000000-0000-0000-0000-000000000002","amount_paise":250,"idempotency_key":"payment-1"}'

curl "$BASE_URL/transfers/<transfer-id>" -H "Authorization: Bearer $TOKEN_A"
```

Operational routes are public:

```bash
curl "$BASE_URL/healthz"  # process liveness; independent of PostgreSQL
curl "$BASE_URL/readyz"   # application and PostgreSQL readiness
curl "$BASE_URL/metrics"  # Prometheus text format
```

There are deliberately no registration, login, token-issuance, deposit, withdrawal, or UI endpoints.

## Verify

Docker must be running because the integration suite starts PostgreSQL 17.6 with Testcontainers and applies the real Flyway migrations.

```bash
./mvnw verify
docker compose config -q
```

The suite covers concurrent first-use wallet creation, identical-key replay, distinct requests that cannot overspend, conflict handling, participant-only reads, and retryable PostgreSQL lock/deadlock failures.

For a deployed service with two provisioned demo users:

```bash
BASE_URL=https://your-service.onrender.com \
TOKEN_A='<private-token-a>' \
TOKEN_B='<private-token-b>' \
./scripts/burst.sh
```

Optional burst controls are `CONCURRENCY` (default `20`), `AMOUNT_PAISE` (default `1`), and `MAX_RETRIES` (default `5`).

## Deploy: Supabase PostgreSQL

1. Create a Supabase project in a region near the Render service.
2. Create a least-privilege application login with access to the target schema. Keep the bootstrap/migration privileges required by Flyway for this exercise.
3. In **Connect**, select the shared **Session pooler** on port `5432`. Use the exact host and username shown; do not derive them from the region.
4. Convert the connection to JDBC form and require TLS, for example `jdbc:postgresql://<pooler-host>:5432/postgres?sslmode=require`.
5. Set its username and password separately as `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
6. After the first successful application startup applies migrations, run `scripts/seed-demo-users.sql` through the Supabase SQL editor. This creates users only, never wallets.

Transaction-mode pooling on port `6543` is intentionally not used.

## Deploy: Render

`render.yaml` defines a Free Docker web service and `/healthz` health check. Connect the GitHub repository as a Render Blueprint and provide these values when prompted:

- `DATABASE_URL`
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`
- `JWT_SECRET` generated from at least 32 random bytes
- `INITIAL_BALANCE_PAISE=100000` for the assessment demo

Render supplies `PORT`. The container runs as the non-root `wallet` user and Spring Boot applies Flyway migrations before becoming ready. Configure deployment only after the GitHub Actions `CI` workflow passes; no separate container registry or buildpack is used.

Generate private one-hour demo tokens locally with the exact same secret configured on Render:

```bash
export JWT_SECRET='<the-render-secret>'
eval "$(./scripts/generate-demo-tokens.py)"
BASE_URL=https://your-service.onrender.com ./scripts/burst.sh
unset JWT_SECRET TOKEN_A TOKEN_B
```

Do not paste tokens into issues, logs, commits, or public submission material. Grant reviewers access to the Render service log explorer separately.

## Free-tier limitations

Render Free web services can cold-start after idle periods and provide a single small instance with an ephemeral filesystem. All durable state therefore lives in Supabase. Supabase Free capacity and project-pausing behavior are suitable for an assessment, not an SLA-backed production wallet; automatic backups/PITR are outside this exercise. Confirm both projects are active immediately before evaluation.

## Design

See [ARCHITECTURE.md](ARCHITECTURE.md) for the consistency model, transaction ordering, security boundary, fault model, observability, and deployment rationale. [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) tracks the review gates; do not advance a phase before its changes are reviewed.
