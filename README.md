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
- `PriceHistory` → completed daily bars (input for ATR)
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
      ├─► stop + size from the stop (code, in application)  ──►  no ATR / size < 1? REJECTED
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

- Risk per trade 1% of total value (1R), hard cap 2% ([D-003](DECISIONS.md#d-003--position-size-is-computed-by-code-from-the-stop-2026-11-14))
- Max 2 executed trades per day
- Minimum holding period 24h before a sell
- Max 30% of total value in one symbol — this now **caps the size** instead of rejecting the verdict
- Max 5 open positions
- Daily loss stop at −3% (blocks new BUYs until next day)

A verdict that violates a limit is logged as REJECTED and the Telegram message
says why.

### Stop and position size (risk in R)

Claude does not choose the size; code does, from the stop
([D-002](DECISIONS.md#d-002--buy-without-a-usable-stop-gets-an-atr-stop-shown-to-the-operator-2026-11-14),
[D-003](DECISIONS.md#d-003--position-size-is-computed-by-code-from-the-stop-2026-11-14)):

- **Stop.** `ATR(14)` with Wilder's smoothing on daily bars (`Atr`). Claude's stop is kept if it lies
  1.5–3 ATR below the entry; otherwise (missing, tighter or wider) it is replaced by `entry − 2 × ATR`. No ATR
  → the BUY is REJECTED. The stop is never moved down after entry.
- **Size from the stop.** `1R = total value × 1%`; `qty = floor((1R − estimated round-trip fees) / (entry − stop))`
  in KZT (fees from `FeeModel`, FX from `FxConverter`), then capped by Claude's quantity (Claude may only lower
  the size), the 30% concentration cap and available cash. Whole shares only; a size below one share is
  REJECTED.
- **Shown to the operator.** The Telegram prompt shows the stop, its source (`CLAUDE` / `ATR`), Claude's
  original stop when replaced, and 1R. Pressing **Skipped** is the way to refuse the stop: a BUY never runs
  without one.
- **Results in R.** 1R and the risk per share are stored on the execution; each closed trade stores its
  realised result in R, and the nightly review shows the trades in R and the expectancy
  `E = P(win)·avgWin − P(loss)·avgLoss`.

A stop is not a guaranteed exit: paper exits fill at the operator's (or last) price, so a gap can make the
realised loss larger than 1R. That is recorded, not clipped.

## Exits: the exit guard

Every filled BUY arms a guard (`ExitGuard`, persisted in `position_guards`) that watches the last trade price:

- **Stop** (`EXIT_STOP`) — the ATR or accepted Claude stop above.
- **Trailing take-profit** (`EXIT_TRAIL`,
  [D-004](DECISIONS.md#d-004--trailing-take-profit-2026-11-14)) — activates at `entry + 2R`; after that the best
  price is tracked and the exit fires when the price falls more than `1 × ATR(14)` below it. The exit becomes a
  SELL with a suggested limit `breakout price − 0.1 × ATR(14)` shown to the operator. The stop stays armed after
  activation. Best price and activation survive a restart.

Claude's `takeProfit` is logged for review but not used as an exit. All values above are **starting values**
(D-005): they change only through a backtest.

## Screener (`screen` CLI)

A one-off candidate screener, separate from the worker (its own entry point, not part of the worker's component
scan):

```bash
./gradlew screen
```

For each symbol in `screen.candidates` (`application.yml`) it reads the last price and daily bars from Alpaca and
the next earnings date from Finnhub, then prints a table — price, average daily volume, ATR% (Wilder's ATR(14),
the same `Atr` as the live path), next earnings, PASS/FAIL — sorted by ATR%. The PASS rule is in `ScreenMetrics`;
the price cap is `screen.max-price`. It makes live calls, so it needs the Alpaca and Finnhub keys.

## Database migrations

Flyway, in `adapters/src/main/resources/db/migration`. An applied migration is never edited; a change is a new
`V<n+1>__….sql`.

| Version | What it adds |
|---|---|
| V1 | Baseline: cash balances, paper positions and trades, tax ledger, daily P&L, decisions, daily reviews |
| V2 | Seed: the paper wallet's starting KZT cash |
| V3 | `position_guards`: stop / take-profit per held position |
| V4 | `executions`: the manual confirmation flow (PENDING → FILLED / SKIPPED / EXPIRED) |
| V5 | Fee breakdown on each execution |
| V6 | `paper_trade.execution_id` with a unique index (idempotent fills) |
| V7 | ATR sizing (stop source, Claude's stop, ATR, risk per share, 1R, Claude's quantity, suggested limit), trailing state on `position_guards`, `paper_trade.result_r` (result in R) |

## Decisions

How a verdict becomes a trade is decided in [`DECISIONS.md`](DECISIONS.md):

- [D-001](DECISIONS.md#d-001--errors-are-not-data-2026-11-14-commit-c812e4b) — errors are not data
- [D-002](DECISIONS.md#d-002--buy-without-a-usable-stop-gets-an-atr-stop-shown-to-the-operator-2026-11-14) — ATR stop for a BUY
- [D-003](DECISIONS.md#d-003--position-size-is-computed-by-code-from-the-stop-2026-11-14) — position size from the stop
- [D-004](DECISIONS.md#d-004--trailing-take-profit-2026-11-14) — trailing take-profit
- [D-005](DECISIONS.md#d-005--parameters-are-not-tuned-by-hand-pending) — starting values change only through a backtest
- [D-006](DECISIONS.md#d-006--no-new-buy-just-before-the-companys-earnings-2026-11-26-decided-without-aika) — no new
  BUY within 2 trading days of the company's earnings; **active only once task SB-020 is merged**

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
