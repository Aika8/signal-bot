# Decisions

Design decisions that change how a verdict becomes a trade. Newest first;
pending decisions at the end.
Each entry: context, decision, why, consequences. Parameters marked
**starting value** are assumptions to be tuned by the backtest (see D-005), not
findings.

---

## D-004 — Trailing take-profit (2026-11-14)

**Context.** `Verdict.takeProfit` was a fixed level fired once by `ExitGuard`.
In the terminal used for manual execution (QUIK), "take-profit" is a
*trailing* order: reaching the TP price only starts tracking; the order fires
when price pulls back from the best price by an *offset*, and the limit price is
placed a *protective spread* beyond the breakout so it actually fills. The
owner chose trailing.

**Decision.** A BUY's take-profit is a trailing exit with three parameters,
computed by code from the trade's R (D-003) and ATR (D-002):

| Parameter | Meaning | Starting value |
|---|---|---|
| activation | price at which trailing starts | `entry + 2R` |
| offset | pullback from the best price that fires the exit | `1 × ATR(14)` |
| protective spread | limit price = breakout price − spread (shown to the operator) | `0.1 × ATR(14)` |

- Tracking uses the **last trade price** (the same rule the terminal uses), not
  bid/ask. Before activation the best price is not tracked.
- After activation: `best = max(best, price)`; exit when `price < best − offset`.
  The exit is a SELL execution (`ExecutionSource.EXIT`) with
  `refPrice = breakout price` and suggested limit `breakout − spread`.
- **The stop stays armed after activation.** In the terminal's combined
  "take-profit + stop-limit" order the stop is no longer checked once TP
  activates. We keep it as a floor; with the starting values the trailing exit
  (≥ `entry + 2R − 1 ATR` = `entry + 1.5R`) always sits above the stop, so the
  only difference is a gap through both, where keeping the stop is the safer
  simulation.
- Claude's `takeProfit` is logged for review but not used.
- Best price and activation state are persisted (`position_guards`) so a
  restart does not reset trailing.

**Why.** Matches how the order would really be placed, so paper P&L is not
better than the real one. Activation at 2R keeps winners ≥ 1.5R, which is what
makes expectancy positive at a modest hit rate (`E = P(win)·avgWin − P(loss)·avgLoss`).
No source gives numeric offset/spread values; ATR-based values scale with the
instrument's normal swing, the same reasoning as the ATR stop.

**Consequences.** `ExitGuard` gains per-symbol trailing state; `ExitType`
gains `EXIT_TRAIL`; V7 migration adds trailing columns. Fixed-level TP is
removed.

---

## D-003 — Position size is computed by code from the stop (2026-11-14)

**Context.** Claude returned `quantity` directly; risk limits were only counts
and portfolio percentages. Risk per trade in R was not enforced.

**Decision.** For a BUY:

```
riskBudgetKzt = totalValueKzt × riskPerTradePct          (starting value 1%, hard cap 2%)
riskPerShare  = (entry − stop) in instrument currency → KZT via FX
qty           = floor( (riskBudgetKzt − estRoundTripFeesKzt) / riskPerShareKzt )
qty           = min(qty, claudeQty, concentrationCapQty, cashQty)
qty < 1       → REJECTED ("risk budget too small for this stop distance")
```

- **Claude may only lower the size.** Its `quantity` is an upper bound; code
  never increases it. A higher Claude quantity is logged.
- Concentration (30% of total) and available cash now **cap** the size instead
  of rejecting the verdict outright; rejection remains only when the capped
  size is below one share.
- Whole shares only (conservative; matches exchanges without fractional lots).
- Estimated round-trip fees come from the existing `FeeModel` and are counted
  inside the 1R budget.
- `1R` (KZT) and `riskPerShare` are stored on the execution; realised result
  of each closed trade is stored in R (input for the nightly review's
  expectancy).
- SELL: size = Claude quantity capped at the held quantity (no stop needed).

**Why.** "Size from risk, not from desire": `qty = capital × risk% / (entry − stop)`,
1% (max 2%) of capital per trade. With a volatility stop the risk in R is the
same on calm and nervous instruments; only the share count changes.

**Consequences.** `RiskLimits` gains `riskPerTradePct` (+ hard cap);
a pure `PositionSizer` in `application`; `RiskLimitEvaluator` keeps the
daily-loss, trades-per-day, holding-period and open-positions checks.

---

## D-002 — BUY without a usable stop gets an ATR stop, shown to the operator (2026-11-14)

**Context.** `Verdict.stop` was optional; a BUY without a stop could be
executed with no defined exit, i.e. undefined risk.

**Decision.**

- `ATR(14)`, Wilder's smoothing, on daily bars; `stop = entry − k × ATR`,
  **k = 2** (starting value; practice uses 1.5–3).
- Claude's stop is **accepted** if its distance is within `[1.5, 3] × ATR`.
  If it is missing, or tighter than 1.5 ATR (inside normal noise), or wider
  than 3 ATR (risk too diluted), the ATR stop **replaces** it.
- The Telegram execution prompt always shows the stop, its source
  (`CLAUDE` / `ATR`) and, when replaced, Claude's original value. The operator
  cancels by pressing **Skipped**: a BUY is never executed without a stop, so
  "cancel the stop" means "do not take the trade".
- No ATR available (no bars, feed error) → the BUY is **REJECTED** with that
  reason (no silent fallback, consistent with D-001).
- The stop is never moved down after entry. Moving it widens 1R.

**Why.** The exit is defined before the entry and never moved. ATR puts the stop
outside the instrument's normal daily swing, so ordinary noise does not knock
it out, and makes size (D-003) comparable across instruments. Silent rejection
would hide useful signals; silent substitution would hide a changed trade, so
the substitution is visible and cancellable.

**Known limits** (shown in docs, not hidden):
- A stop is not a guaranteed exit. It fires on the trade price, lives on the
  broker's server, and a gap can skip it. Paper exits are filled at the
  operator's (or last) price, not at the stop, so realised loss can exceed 1R;
  that is recorded, not clipped.

**Consequences.** New port for daily bars (`PriceHistory`); ATR moves from the
`screen` CLI into `application` and is shared (the screener switches from a
simple mean to Wilder's ATR — a small change in its ATR% column).

---

## D-001 — Errors are not data (2026-11-14, commit c812e4b)

A failed or malformed source throws `SourceFailureException`; an unusable
Claude response throws `DecisionFailedException` and is logged with
`action=ERROR`. No verdict is built on top of either; the operator gets a
Telegram alert. An empty result means only "the source answered: nothing".
Fills are idempotent: one transaction, conditional `PENDING` updates, and a
unique `paper_trade.execution_id`.

---

## D-005 — Parameters are not tuned by hand (pending)

Every **starting value** above (k = 2, risk 1%, activation 2R, offset 1 ATR,
spread 0.1 ATR) is an assumption. Changing them is a backtest decision:
walk-forward, delisted tickers included, no look-ahead, and the number of
variants tried recorded. Until then they stay fixed.

---

## Cloud run setup (2026-11-25)

Infrastructure choices made while preparing the cloud week; none changes how a verdict becomes a trade.

- **`backlog.py` is a copy of learning-exchange's**, with its test file unchanged except the queue-size floor
  (40 → 20). signal-bot adds rows to `PROTECTED` / `APPEND_ONLY` / `INTAKE_DENY` (build files,
  `adapter-broker-private/**`, Flyway migrations, `.gitignore`, `docker-compose.yml`, `portfolio.yml`) and the task
  field `rewrites` (tests in `test_backlog_signalbot.py`). A shared library for both repositories comes later;
  until then a fix in one copy is ported to the other by hand. Synced with learning-exchange `main` after its
  PR #55 (tech-debt register, write-only experience log, `prod-changes`) on 2026-11-25; signal-bot additionally
  counts Flyway migrations as production for the reviewer's "tests in a vacuum" check.
- **`rewrites`**: steps of the ATR plan change behaviour that existing tests pin (simple-mean ATR in the screener,
  concentration rejection, fixed take-profit). Such a task names the one test file it may edit; anything else in
  `src/test` stays append-only, and intake never copies the field.
- **Testcontainers 1.21.4** (test scope; Boot 3.4.2 brings 1.20.4, which Docker 29 refuses). The two integration
  tests skip without Docker (`disabledWithoutDocker`), so a cloud session without Docker can run the build; CI
  checks Docker first and fails if any test was skipped.
- **The worker no longer scans `io.github.signalbot.screen`.** The first CI run with Docker showed the integration
  tests could never start: `ScreenApplication` (the `screen` CLI's own `@SpringBootApplication`, excluding the
  datasource and Flyway) was picked up by the worker's component scan, Boot merged its excludes, and the worker
  lost its `JdbcClient`; `ScreenRunner` would also have run the screener at worker startup. The CLI keeps its own
  entry point (`./gradlew screen`).

- **Intake without a daily cap (2026-11-26, Aika: "лучше пусть останется не доделанным чем закончатся").** signal-bot's
  `backlog.py intake` moves every proposal that passes the filter (learning-exchange keeps its cap of 5 — the one
  deliberate difference between the copies besides the rows above), runs every three hours, and logs only lines the
  intake log does not hold yet, so a repeated run commits nothing. `test_backlog.py`'s cap test now expects all seven
  good proposals; `test_backlog_intake_signalbot.py` covers the rest.

## Backlog run (December 2026)

Decisions the cloud agents took without Aika, one bullet each (task id, question, choice, options not taken).
