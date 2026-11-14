# signal-bot

A personal **signal service**. External feeds trigger it; it builds context from a
portfolio, asks Claude for a verdict, pushes the verdict to Telegram, and executes
it in a **paper wallet**.

> **No real orders, ever.** This service has no "place order" capability and no
> port for one. Execution is simulated end to end. The only writes to a real
> brokerage would live in a separate private module (see below), and even that is
> read-only in this project's design.

## Architecture

Hexagonal, three Gradle modules:

| Module        | Responsibility                                                        |
|---------------|-----------------------------------------------------------------------|
| `domain`      | Pure records and **port interfaces**. No framework, no I/O.           |
| `application` | Use cases and policies: risk limits, trigger engine, orchestration.   |
| `adapters`    | Inbound/outbound adapters + Spring Boot bootstrap + Flyway migrations. |

Dependencies point inward: `adapters → application → domain`. The domain knows
nothing about Spring, HTTP, or SQL.

### Ports (in `domain`)

- `PortfolioReader` → `Portfolio` (positions, total value in KZT, FX rates, asOf)
- `CashReader` → cash balances per currency (balance, KZT value, reserved by open BUYs)
- `MarketDataFeed` → live `PriceTick` stream + snapshot (last price, prior close)
- `NewsFeed` / `EarningsCalendar` → news since a timestamp; upcoming earnings
- `MarketClock` → is-open / next-open / next-close per venue
- `DecisionEngine` → `Verdict` (BUY/SELL/HOLD, sizing, stop, take-profit, confidence)
- `Notifier` → send a verdict + context summary
- `PaperWallet` → apply a verdict as a simulated fill

### Flow

```
feeds (price / news / earnings)
      │  trigger engine (application)
      ▼
assemble DecisionContext (portfolio + cash + news + snapshot)
      │
      ▼
DecisionEngine (Claude)  ──►  Verdict
      │
      ├─► risk limits (hard-coded, in application)  ──►  REJECTED? explain on Telegram
      │
      ├─► Notifier (Telegram)
      └─► PaperWallet (simulated fill, ledger, P&L)
```

## The private-adapter rule

This repository is **public**. No employer/internal service names, endpoint paths,
DTO names, or comments may appear anywhere in it.

The adapter that talks to a real brokerage lives in a **separate, git-ignored**
module, `adapter-broker-private/`. Only a README stub is kept here. That module
implements the public ports `PortfolioReader` and `CashReader` and nothing else;
the public code depends on the interfaces, never on the adapter. The project
builds and runs fully without it, using paper/stub implementations.

## Tech stack

Java 21 · Spring Boot 3 · Gradle (Kotlin DSL) · PostgreSQL · Flyway ·
JUnit 5 + Testcontainers · Resilience4j (retry + circuit breaker on every external call).

## Running

Prerequisites: JDK 21, Docker.

```bash
cp .env.example .env         # fill in secrets; .env is git-ignored
docker compose up -d         # start PostgreSQL
./gradlew build              # compile + run tests
```

All secrets are read from environment variables. Never commit `.env`.

## Risk limits (defaults)

Enforced in code (`application/.../risk`), not in the prompt:

- Max 2 executed trades per day
- Minimum holding period 24h before a sell
- Max 30% of total value in one symbol
- Max 5 open positions
- Daily loss stop at −3% (blocks new BUYs until next day)

A verdict that violates a limit is logged as REJECTED and the Telegram message
says why.

## Increments

All four increments are implemented.

1. **Done** — Multi-module skeleton, domain model + ports, Flyway baseline,
   docker-compose, this README, unit tests for the risk limits.
2. **Done** — Paper wallet + Postgres adapters + Telegram `/portfolio`; Testcontainers integration test.
3. **Done** — Alpaca (IEX) + Finnhub adapters + trigger engine, with a dry-run mode that logs
   triggers without calling Claude.
4. **Done** — Claude decision engine + Telegram verdict push (`/pnl`) + nightly review.

## Going live

Everything is gated by flags in `.env` (copy from `.env.example`). Defaults are
**off** — the service starts in dry-run and never calls Claude or places paper trades.

| Flag | Effect when `true` |
|---|---|
| `DRY_RUN=false` | Triggers run the live path (decide → risk limits → paper fill → push) instead of just logging |
| `CLAUDE_ENABLED=true` + `ANTHROPIC_API_KEY` | Enables the Claude decision engine (`claude-opus-5-5`) and the nightly written review |
| `ALPACA_ENABLED=true` + `ALPACA_API_KEY` / `ALPACA_API_SECRET` | Live US price stream + snapshots + market clock |
| `FINNHUB_ENABLED=true` + `FINNHUB_TOKEN` | Company-news and earnings-calendar polling |
| `TELEGRAM_ENABLED=true` + `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID` | Telegram bot (`/portfolio`, `/pnl`) and verdict/review push |

The API key is read from the environment by the Anthropic SDK (`ANTHROPIC_API_KEY`);
it is never stored in code or config.

**Operational note:** `docker compose` auto-loads `.env`, but the app (plain `java -jar`)
does not. Keep `POSTGRES_PASSWORD` consistent between the two — either export it into
the app's environment to match `.env`, or align both to the same value.

A minimal live run:

```bash
cp .env.example .env        # then edit: set the flags above and the secrets
docker compose up -d        # Postgres
export $(grep -v '^#' .env | xargs)   # load .env into the app's environment
./gradlew :adapters:bootRun
```
