# Wallet / P2P Transfer Service

A small wallet service where users hold a balance in integer paise and transfer to each other. The interesting part is what it guarantees under concurrency: money is conserved exactly, retried transfers apply exactly once, wallet get-or-create is race-free, and identity comes only from a verified JWT. The core mechanism was proven against real Postgres under 100-way concurrent bursts before it was implemented, and the same probe ships as a Testcontainers test and a one-command burst script.

## Requirements

- Java 17
- Maven 3.9+
- Docker (for `docker compose` and the Testcontainers integration test)

## Run

Everything in one command (app + Postgres):

```bash
docker compose up --build
```

The API is then on `http://localhost:8080`.

For local development against just the database:

```bash
docker compose up -d db
mvn spring-boot:run
```

## Test

```bash
mvn verify
```

This runs the unit tests plus `WalletConcurrencyGateIT`, which boots the real app against a throwaway Postgres 16 container and reproduces the live probe: 100 concurrent first-transfers between brand-new users, 100 concurrent retries of one idempotency key, exact-count insufficient-funds draining, replay/conflict semantics, and participant-only reads. Docker must be running.

## Burst probe (the correctness gate)

One command against any running instance, local or live:

```bash
./scripts/burst.sh http://localhost:8080
./scripts/burst.sh https://<your-live-url>
```

It creates two brand-new users, fires distinct-key first-transfers and same-key retries in a single simultaneous wave, then reconciles balances to the paisa and prints a PASS/FAIL table (plus p50/p95 latency). Python 3 standard library only. Exits non-zero on any failure.

## API

All amounts are integer paise. Field names are snake_case.

### Get a token (demo identity provider)

```bash
curl -s -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"user_id":"alice"}'
# {"user_id":"alice","token":"eyJ...","expires_in_seconds":86400}
```

The wallet endpoints trust only the signed token, never a header or body field.

### Create or fetch your wallet (idempotent)

```bash
curl -s -X POST http://localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN"
# {"user_id":"alice","balance_paise":100000,"created":true}
```

New wallets are seeded with a configurable demo balance (see Configuration) because the exercise defines no deposit endpoint.

### Read your balance

```bash
curl -s http://localhost:8080/accounts/me \
  -H "Authorization: Bearer $TOKEN"
# {"user_id":"alice","balance_paise":100000}
```

### Transfer

```bash
curl -s -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"to_user":"bob","amount_paise":2500,"idempotency_key":"order-42"}'
# 201 {"transfer_id":"9c9d...","status":"APPLIED","new_balance_paise":97500}
```

If `bob` has no wallet yet, it is created inside the same transaction. A retry with the same key returns the identical original response, status code included. The same key with a different body returns 409.

### Read a transfer (participants only)

```bash
curl -s http://localhost:8080/transfers/<transfer_id> \
  -H "Authorization: Bearer $TOKEN"
# {"transfer_id":"...","from_user":"alice","to_user":"bob","amount_paise":2500,
#  "status":"APPLIED","created_at":"2026-08-12T10:15:03.421Z"}
```

Non-participants get the same 404 as a missing id, so transfer ids leak nothing.

### Probes and metrics

```bash
curl -s http://localhost:8080/healthz   # liveness, never touches the DB
curl -s http://localhost:8080/readyz    # readiness, real DB round-trip
curl -s http://localhost:8080/metrics   # Prometheus text, request p99 + business counters
```

## Error responses

| Status | Body `error`                              | When                                                             |
| ------ | ----------------------------------------- | ---------------------------------------------------------------- |
| 400    | `validation_failed`, `malformed_request_body`, `invalid_parameter` | Bad JSON, failed field validation, non-UUID transfer id |
| 401    | `unauthorized`                            | Missing, invalid, expired or tampered token                      |
| 404    | `not_found`                               | Missing wallet/transfer, or a transfer you are not part of       |
| 409    | `idempotency_key_conflict`                | Same key reused with a different body                            |
| 422    | `insufficient_funds` (with `transfer_id`, `balance_paise`), `self_transfer_not_allowed` | Well-formed request, unprocessable given wallet state |
| 503    | `service_unavailable` (+ `Retry-After`)   | Database slow or unreachable; retry safely with the same key     |

## Configuration

Everything sensitive comes from the environment (see `.env.example`).

| Variable | Default | Purpose |
| -------- | ------- | ------- |
| `SPRING_DATASOURCE_URL` | local compose URL | JDBC URL, e.g. `jdbc:postgresql://host/db?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `wallet` / `wallet` | Database credentials |
| `JWT_SECRET` | dev-only value | HS256 secret, minimum 32 bytes; the app refuses to start otherwise |
| `TOKEN_TTL_SECONDS` | `86400` | Token lifetime |
| `INITIAL_BALANCE_PAISE` | `100000` | Demo seed for brand-new wallets |
| `PORT` | `8080` | Listen port |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `DB_CONNECTION_TIMEOUT_MS` | `10000` | Max wait for a pooled connection before failing closed (503) |
| `DB_SOCKET_TIMEOUT_SECONDS` | `30` | Socket read timeout; must sit above legitimate row-lock waits |
| `SPRING_PROFILES_ACTIVE` | (none) | `json` for JSON logs; add `loki` to also push logs to Grafana Cloud |
| `LOKI_URL` / `LOKI_USERNAME` / `LOKI_PASSWORD` / `LOKI_ENV` | (unset) | Grafana Cloud Loki push endpoint and credentials |

## Design notes

One transaction per transfer, in a deliberately proven order: get-or-create both wallets with `ON CONFLICT DO NOTHING` (sorted), lock both rows with `FOR UPDATE` (sorted), insert the transfer row whose `UNIQUE (from_user, idempotency_key)` arbitrates retries, then check funds and mutate. The order is not cosmetic: inserting the transfer row before locking makes its foreign key take KEY SHARE locks on the wallet rows, turning the later `FOR UPDATE` into a cross-transaction lock upgrade — measured at a 38/40 deadlock rate under a 40-way burst. Full reasoning, rejected alternatives and trade-offs are in [WRITEUP.md](WRITEUP.md). Deployment steps are in [DEPLOY.md](DEPLOY.md).
