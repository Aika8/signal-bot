# Decisions

Design decisions that change how a verdict becomes a trade. Newest first;
pending decisions at the end.
Each entry: context, decision, why, consequences. Parameters marked
**starting value** are assumptions to be tuned by the backtest (see D-005), not
findings.

---

## D-010 — Backtest on history, and a market universe instead of hand-written lists (2026-11-27)

Aika (relayed by the strategy session): «давай сделаем проверку на истории для бота. давай уберем захаркоженный
список», «хочется чтобы смотрел на весь рынок а не на список бумаг которые заранее выбрали». Forks were decided
without her by the option easiest to undo (her rule: nothing waits); each says how to undo it. Tasks BT-001…BT-036;
the lab tasks they replace are marked `dropped` in the queue. No real orders, ever (README) is unchanged; the
backtest never calls a broker.

**Relation to D-005.** This is the first instrument for D-005, not all of it: D-005 also asks for walk-forward and
delisted tickers, which this version lacks (train/test split only; today's companies only). So **no starting value
changes on its results**; it measures entry rules under the fixed starting values. Making k, risk or activation
parameters of a run is a later decision.

**1. Entry is a rule in code; the model is not part of the backtest.** An entry rule is a pure interface: history up
to and including day D → ranked candidates. Claude's verdict cannot be reproduced, so it is no entry rule here; in the
live path it stays a filter that can only lower risk (D-009).

**2. The engine calls the production rules, never copies them.** `Atr` over the same window as live (30 bars, the
constant moves to `application`), `StopPolicy` (D-002, D-007), the whole BUY sizing of the live path (concentration
cap, cash cap, round-trip fee estimate, then `PositionSizer`; moved from `LiveDecisionHandler` to `application`),
`ExitGuard` with `TrailingPolicy` (D-004), `RiskLimitEvaluator` (open positions, trades per day, daily loss stop),
`EarningsBlackout` (D-006, only when earnings history is given; the report says when not), `RMultiple` for R, fees
through `FeeModel` via a fee function. "Top N of a rule on a day" is one class used by the backtest, the screener and
the live watchlist. A rule that lives in an adapter moves to `application` first.

**3. No look-ahead, conservative fills.** Decide on the close of D, enter at the open of D+1; every input uses bars up
to D only. Slippage **10 bps per side** (starting value) on entries and on every exit. Each later bar is fed to
`ExitGuard` as open, low, high, close: what fires at the open fills at the open (a gap below the stop fills at the
open, not the stop); inside the bar the low comes before the high, so when the stop and a trailing exit could both
happen inside one bar the stop (the worse) counts; a stop inside the bar fills at the stop level, a trailing exit at
its level. On the entry bar only the stop is checked (low), never trailing. No time exit (live has none). A test
replaces every bar after D and checks that nothing decided up to D changes.

**4. Prices: split-adjusted for the rules, raw for levels and shares.** ATR, ATR%, returns, averages, stops, the
exit guard and R use split-adjusted bars (D-008). Anything that depends on the *level* of the day — `close ≥ 5 USD`,
the screener's `price ≤ max`, volume in shares (ADV), the number of shares bought and the per-share fee — uses the
raw (unadjusted) series of that day; the store keeps both, and a scale (raw ÷ adjusted close of the day) converts
between them. Otherwise a later split would leak into past filters.

**5. Universe instead of lists.** A port answers "which symbols could be traded on day D". First implementation: the
symbols of Alpaca's active, tradable `us_equity` list (ETFs included, as Alpaca lists them; OTC excluded) that have a
bar on D in the local store, filtered by **raw close ≥ 5 USD** and **average dollar volume of the 20 sessions before
D ≥ 20 million USD** (starting values). Today's list holds today's companies only: **every report says delisted
companies are missing and results are biased upwards.** `screen.candidates` goes; the screener ranks the universe
with R3. The live watchlist becomes **held, then armed, then the top of a rule** (`signalbot.watchlist.rule`, default
R3) up to **5 symbols in total by default — the size of today's list**, because every symbol costs Claude calls
(paid API); held and armed are never cut; the free IEX stream allows up to 30, and raising the total is Aika's
decision. It is computed once a day. `signalbot.watchlist.symbols` stays as an explicit
override (empty by default). A missing or stale store (last day older than 5 trading days) gives held ∪ armed only
(exits keep working, no new entries) and an operator alert (D-001). The stream re-subscribes once a day (close, then subscribe —
the free plan allows one connection; on failure the old list again, else snapshots keep the exits).

**6. History on disk, loaded by Aika.** One CSV per symbol (split-adjusted) and one `.raw.csv` (raw) under
`${SIGNALBOT_DATA_DIR:-~/.signal-bot/history}`, outside the repository; SPY also as a total-return series
(`adjustment=all`) for the benchmark only. History starts 2015-01-01 so that 100-session rules are ready when
training starts. The loader re-fetches a symbol whole when a new split changes the overlapping bar. A bar that `DailyBar` refuses skips that symbol (listed in the load summary), it never enters the
store. The loader needs Alpaca keys, which the cloud never has: agents build and test on fixtures and synthetic
series; Aika runs `./gradlew screen --args='history-load'` and `…='backtest --rule R1'` (the `screen` entry point;
build files are hers; memory via `JAVA_TOOL_OPTIONS=-Xmx8g`).

**7. First rules to measure (hypotheses, not advice).** Parameters are starting values (D-005):
- **R0** random entry among the universe with a fixed seed — the control: what exits and risk alone give.
- **R1** momentum: close above the highest high of the previous 20 sessions, ranked by the 60-session return.
- **R2** pullback in trend: close above its 100-session simple average and three lower closes in a row, ranked by the
  distance below the 5-session high.
- **R3** the screener's PASS rule (raw price ≤ max, ADV ≥ 5 M shares and ATR% in [2, 4] over the screener's 60
  bars), moved to `application` so the screener and the backtest share it.
- **R4** insider purchases: ≥ 3 different owners buying in the open market within 10 days (SB-406), known on the
  **filing date**. The EDGAR client takes its User-Agent only from `SIGNALBOT_EDGAR_USER_AGENT` (no name or address
  in code or the repository).

**8. The report and the status, fixed before the first run.** One command, deterministic. Starting equity = the
paper wallet's seed converted at the configured FX rate (the account the live path sizes against), `--equity`
overrides it. Default split: train 2016-01-01…2021-12-31, test 2022-01-01…2025-12-31. The report shows trades, win
rate, average win and loss in R, E in R with a 95 % bootstrap interval (fixed seed), maximum drawdown in R, fees as a
share of the gross result, and the same for R0 (median of 20 seeds) and for buy-and-hold SPY (total return). Status,
from the **test** period:
- **VERIFIED** — at least 30 trades, the lower bound of E's interval > 0, the train |t| of the mean R at least the
  Bonferroni bar for the variants logged for this rule (SB-506), and E > R0's median E;
- **CANDIDATE** — the train bar is met and test E > 0, but the interval includes 0 or there are fewer than 30 trades;
- **NOISE** — anything else.
A **variant** is a distinct set of rule parameters, period, split, max price and equity (R0's seed is not); the
current run counts. Every run appends a line to `docs/backtest/runs.md` at the repository root; Aika commits it.

**Not taken.** A Gradle module or task (build files); Claude in the backtest; intraday bars; dividend adjustment for
the rules (D-008); walk-forward in the first version (`TimeSplits` allows it later); a 30-symbol live watchlist
(cost); excluding ETFs (Alpaca's list has no reliable flag).

---

## D-009 — The model can only lower risk; text from outside is data (2026-11-27)

Agreed with Aika while reading the code (relayed by the strategy session). **Undo:** revert SB-043.

The model's answer may only make a trade smaller or not happen: it can say HOLD, give a smaller quantity than code
allows, or a stop that code accepts within the D-002 band. Code alone decides the size (D-003), the stop band (D-002),
the limits and the blackout (D-006); nothing the model writes raises them. Text from outside the system — news
headlines and sources, later filings — enters the prompt only as marked, cleaned, length-bounded data, with the
system prompt saying it is never an instruction (SB-043). No real orders, ever (README) is unchanged.

---

## D-008 — Daily bars are split-adjusted (2026-11-27)

Agreed with Aika while reading the code (relayed by the strategy session). **Undo:** drop the parameter in
`AlpacaBarsQuery` (SB-041).

**Context.** Alpaca's historical bars default to `adjustment=raw` (docs.alpaca.markets, "Historical bars", checked
2026-11-27 by the strategy session). On a split day the true range becomes the whole price gap, so ATR(14) is
inflated for about two weeks and the stop and size (D-002, D-003) are wrong.

**Decision.** Daily bars are requested with `adjustment=split`. Not `all`: the dividend adjustment shifts past prices
away from the levels that really traded, and the stop is placed against real prices. `feed=sip` (Alpaca's default)
is passed explicitly, so a change of plan cannot silently switch volume and prices to IEX only. The reference page
(docs.alpaca.markets/reference/stockbars, read 2026-11-27) gives `feed` default `sip` and says the default `end` is
"the current time if the user has a real-time access for the feed, otherwise 15 minutes before the current time" —
so the request sends no `end`, and a plan without real-time SIP still gets completed daily bars. Not checked live (no
keys in the cloud): if the first live run gets 403 on bars, that is the place to look.

---

## D-007 — No BUY with a stop at or below zero (2026-11-27, decided without Aika)

**Decided without Aika** (her instruction: nothing waits for her). **Undo:** remove the two guards in
`StopPolicy.forBuy` (task SB-031).

**Context.** Issue #16: on a cheap or very volatile instrument `entry − 2 × ATR` is ≤ 0, and `StopPolicy` returned it
as a valid stop; sizing and the Telegram prompt would show it as a real stop with a meaningless 1R.

**Decision.** A Claude stop ≤ 0 is unusable and replaced by the ATR stop (as D-002 replaces any unusable stop). If the
ATR stop is ≤ 0 too, there is no usable stop: the BUY is **REJECTED** "no usable stop" (D-002: never a BUY without a
stop).

**Not taken.** Clamping the stop to a tiny positive price (a fake, huge 1R); a wider band (a starting value, D-005).

---

## D-006 — No new BUY just before the company's earnings (2026-11-26, decided without Aika)

**Decided without Aika** (her instruction of 2026-11-26: "nothing should wait for me"; the queue author chose the
option easiest to undo). **Undo:** set `signalbot.risk.earnings-blackout-trading-days: 0` (off), or revert the
tasks SB-018…SB-020.

**Context.** The November requirements ask for no entry, or a smaller size, before FOMC/NBK decisions, CPI and the
company's earnings. Only the company's earnings have a source in the code (`EarningsCalendar`, Finnhub); there is no
macro calendar.

**Decision.** A BUY is **REJECTED** ("earnings on <date>, within N trading days") when the symbol reports earnings
within **N = 2 trading days** (starting value, D-005), counted by the same trading-day rule the earnings trigger uses
(moved to `application`, one rule in one place). Earnings **today** block too. N = 0 switches the blackout off (an
explicit guard, independent of the trading-day rule). "Today" is the date in `America/New_York` (earnings dates are
US exchange dates), read from an injected `Clock`. A configured calendar that fails → REJECTED (D-001, as with no
ATR); no calendar bean → no blackout, logged. SELLs and protective exits are never blocked.

**Not taken.** Reducing the size instead of rejecting (needs a second sizing rule); macro events (no data source —
after the vacation); doing nothing until Aika is back.

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
spread 0.1 ATR, earnings blackout 2 trading days — D-006) is an assumption. Changing them is a backtest decision:
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

## Questions of 2026-11-26, decided without Aika

Aika's instruction (2026-11-26): nothing waits for her. Each question of the queue author got the recommended option,
or else the one easiest to undo. Things only Aika can do (money, keys, accounts, repository settings, build files,
live calls) are listed in `docs/backlog/QUESTIONS-BEFORE-LEAVE.md` under "After the vacation", not queued.

- **Event filter** → D-006 (earnings only). Undo: the property to 0.
- **Insider buys in the Claude prompt** → not added; the line (SB-411) stays in the lab until H-001 is measured. Not
  taken: adding it now (a verdict input nobody measured). Undo: one `append` in `ClaudePromptBuilder`.
- **Lab label rule** (SB-515: < 30 trades → candidate; Bonferroni bar on |t|; holdout must agree) → kept as written,
  constants marked "lab starting rule". Not taken: no multiple-testing correction (overstates edges). Undo: the
  constants in `LabelRule`.
- **Debounce key** → stays the symbol only (pinned by SB-229). Not taken: symbol + trigger type (more Claude calls
  per symbol). Undo: the key in `SignalDispatcher.dispatch`.
- **Lab as a Gradle module, PIT plugin** → not now: both need build files, which agents may not change. The lab stays
  in `application/lab`; mutation testing stays by hand (SB-701…SB-717).

## Tech debt becomes tasks by itself (2026-11-27, ported from learning-exchange PR #112)

- `backlog.py intake` also reads every `docs/backlog/tech-debt/*.md` and every open `tech-debt` issue by the owner or
  collaborators, all in the proposal format (`docs/backlog/TECH-DEBT.md`); `kind: bug` → `priority: bug`, which
  `next_task` picks before any other ready task; an issue is closed once all its proposals are tasks (re-checked on
  every run). Intake runs after every automerge sweep that merged, on `tech-debt` issue events, and every 3 hours.
  signal-bot keeps its differences: no daily cap, log dedupe, `replaces`.
- `gradle.properties`: Gradle retries a Maven Central 429 eight times from 3 s (issue #18), instead of 3 times.

## Tech-debt bugs of 2026-11-27, decided without Aika

Aika's instruction: nothing waits for her. The queue author turned the behaviour bugs of the tech-debt register
into tasks SB-031…SB-040 at the front of the queue and chose where a choice was needed:

- **#16, stop ≤ 0 (SB-031)** → D-007.
- **#28, #30, races (SB-032, SB-033)** → `ExitGuard`'s mutating methods share one monitor; a trailing-state write
  only matches a guard row with the same levels. No behaviour choice; undo = revert. SB-033 may edit
  `TrailingRestartTest` (`rewrites`): only its in-memory store fake gains the override of the new conditional
  `saveTrailingState`, so the fake keeps recording writes; no assertion changes.
- **#31, re-fire after restart (SB-034)** → test first; the code deletes the guard row when an exit fires, so it may
  not reproduce. Found with it: a Skipped or expired exit SELL leaves the position with no stop → **SB-035 lists such
  positions in the nightly review**. Not taken: re-arming the stop (the price is usually already below it, so it
  would fire and prompt again in a loop); re-arming the trailing take-profit (its best price is gone). Undo: the
  line in `NightlyReviewJob`.
- **#24, unused SYMBOL_CONCENTRATION (SB-958)** → deprecated, not deleted. Undo: remove the annotation.
- **#40/#42, 14-day horizon (SB-954)** → values above the horizon are refused at startup. Not taken: widening the
  horizon. Undo: remove the check.
- **#18, first Gradle run fails (Maven 429)** → not queued here; the coordinator's common fix comes separately.

## Backlog run (December 2026)

Decisions the cloud agents took without Aika, one bullet each (task id, question, choice, options not taken).

- **SB-002** — How does the Alpaca adapter leave out the still-open session (the `PriceHistory` contract)? Choice:
  ask for the newest `count + 1` daily bars (`sort=desc`, `start` = today − (2·count + 10) calendar days, because
  without `start` Alpaca returns only the current day) and drop any bar dated today or later in `America/New_York`;
  so today's bar is left out even after the close. Also: `o` and `v` are as strict as `h`/`l`/`c`/`t`, and a bar
  `DailyBar` rejects (e.g. `h < l`) is a `SourceFailureException`. Not taken: an `end` query parameter (behaviour at
  the day boundary harder to test without a live call); keeping today's bar after 16:00 (needs the market clock).
- **SB-005** — What does `StopPolicy` do with an ATR of zero or below (flat bars give ATR 0, and `entry − 2 × 0`
  would be a stop at the entry, i.e. undefined risk)? Choice: treat it like no ATR — `Optional.empty()`, so the
  caller rejects the BUY with "no ATR" (D-002: no silent fallback). Also: `forBuy` is static on a final class, like
  `Atr`, and the ATR stop is not rounded (scale follows the inputs). Not taken: throwing (a data condition, not a
  programming error, would stop the pipeline); accepting a zero-distance stop.
- **SB-006** — Details of `PositionSizer` that D-003 leaves open. Choices: (1) on a tie the earlier bound in the
  order RISK, CLAUDE, CONCENTRATION, CASH is reported, so a Claude quantity equal to the risk size is not `CLAUDE`;
  (2) no Claude quantity (`OptionalLong.empty()`) means no Claude bound; (3) `riskKzt` = final quantity ×
  `riskPerShareKzt` (the 1R actually taken, fees not included), not the budget; (4) `entry ≤ stop` throws
  `IllegalArgumentException` instead of a rejection (`StopPolicy` never yields it, so it is a caller bug);
  (5) `RiskLimits` also refuses a `riskPerTradeCapPct` above 0.02 (D-003's hard cap) or ≤ 0, and a
  `riskPerTradePct` ≤ 0; (6) a rejection names the bound that made the size < 1 (e.g. "available cash buys less
  than one share"). Not taken: storing the budget as `riskKzt` (realised R would then be measured against risk not
  taken); a rejection instead of an exception for `entry ≤ stop` (would hide a bug upstream).
- **SB-010** — How does the Telegram prompt get the sizing facts, and how are they shown? Choice: the gateway reads
  the execution row by id (`ExecutionStore.find`), so `ExecutionGateway` and its callers stay unchanged; if the row
  cannot be read the prompt still goes out without the sizing lines (logged), because a missing prompt would block
  the operator more than a shorter one. Sizing lines only for a BUY with sizing facts. Prices show at least two
  decimals and never lose digits (an unrounded ATR stop shows in full); 1R is rounded HALF_UP to whole tenge with a
  space as thousands separator; an ATR stop without a Claude stop reads `Claude: none`. Not taken: a
  `promptExecution` overload with `SizingInfo` (a default method would break the existing Mockito verifications
  of the five-argument call); rounding stops to two decimals (would change the number the operator places).
- **SB-009** — Details of the live BUY path that D-002/D-003 leave open. Choices: (1) the live path prices and sizes
  in USD with US fees (Alpaca is its only feed and the paper wallet defaults to USD/US), as the existing
  `riskContext` already assumed; (2) round-trip fees = 2 × `FeeModel.compute(US, q, q × entry)` where q is the size
  the other bounds allow before fees, then the size is computed again with those fees (fees only lower the size, so
  this never under-counts them); (3) cash qty = floor(Σ over all cash balances of (KZT value − reserved in KZT) /
  entry in KZT), read from the context's `CashReader` balances, buy fee not deducted; (4) a fractional Claude
  quantity is floored, a missing one is no bound; (5) `SizingInfo.riskPerShare` is `entry − stop` in the instrument
  currency (the column sits beside `atr` and `claude_stop`; KZT is in `risk_kzt`); (6) no portfolio value or no
  entry price → REJECTED with that reason, like "no ATR"; (7) the submitted verdict's `entry` is the price the
  size was computed at, so the evaluator's concentration check sees the notional the cap was sized for;
  (8) `RiskLimits` is now a bean (`TriggerConfig.riskLimits`) shared by the evaluator and the sizing. Not taken:
  fees for Claude's quantity (absent for some verdicts); a second fee or FX rule. Undo: `LiveDecisionHandler.size`.
- **SB-011** — Details of the ATR line in the Claude prompt. Choices: (1) `ContextAssembler` uses the live BUY
  path's `LiveDecisionHandler.ATR_BARS` (30) and `ATR_PERIOD` (14), so prompt and gate see the same ATR; (2) the
  ATR shows rounded HALF_UP to 2 decimals and the percent of the last price to 1 decimal; without a snapshot (no
  market-data feed) the line is `ATR(14): <value>` with no percent; (3) "not available" covers no `PriceHistory`
  bean, a failing history (logged WARN, the context does not fail — ATR is context, not a gate), too few bars, and
  an ATR of zero or below (as `StopPolicy` treats it, SB-005); (4) the stop band (1.5–3 ATR, else 2 ATR) is one
  bullet in the system prompt's rules, written as text because `StopPolicy`'s constants are package-private
  (tech debt SB-011-1). Not taken: failing the context on a broken history (would block HOLD/SELL verdicts on a
  number that is only context). Undo: `ContextAssembler.atr` and `ClaudePromptBuilder.atr`.
- **SB-012** — Details of the trailing take-profit in `ExitGuard` that D-004 leaves open. Choices: (1) `ExitSignal`
  gains a nullable `suggestedLimit` (EXIT_TRAIL: breakout price − spread; stop and fixed TP: `null`) and keeps a
  4-argument constructor, so existing callers compile unchanged; (2) `state(symbol)` returns an
  `Optional<TrailingState(best, activated)>`, empty when the symbol has no trailing armed; (3) `best` is `null`
  exactly when not activated — `restore` with an inconsistent pair throws `IllegalArgumentException`, and
  `TrailingLevels` refuses a negative offset or spread; (4) the stop, fixed TP and trailing exit each fire once,
  independently, as the existing levels did (the handler disarms on any exit anyway); `arm` and `armTrailing`
  replace each other and reset the trailing state; (5) the activation tick sets best = price and cannot exit on the
  same tick. Not taken: silently accepting an inconsistent restored state (would hide a persistence bug); a
  separate per-symbol trailing map (two maps could drift apart). Undo: `ExitGuard.onTrailingPrice`.
- **SB-013** — Details of arming and persisting the trailing take-profit that D-004 leaves open. Choices: (1) a BUY
  fill arms trailing only with an ATR above zero, a stop, and a fill above the stop (R > 0); otherwise the stop
  only, logged (an ATR ≤ 0 is "no ATR", as SB-005); (2) `PositionGuardStore.saveTrailing` writes the levels and
  `risk_kzt` and resets `best_price`/`activated` after the unchanged `upsert`, in the same transaction, and with no
  trailing it clears the columns so a re-bought symbol never inherits the old levels; (3) the D-004 factors (2R,
  1 × ATR, 0.1 × ATR) are private constants of `ExecutionService.trailingFor`, unrounded; (4) `GuardLoader` reads
  the new `findAllGuards` (the old `findAll` is left in place) and, for a stored state `TrailingState` rejects
  (activated without a best price, or the reverse), logs an ERROR and arms the stop only instead of failing
  startup, so the other positions stay guarded; (5) `TrailingStateWriter` keeps the last saved state in memory,
  writes only an activated state that differs from it, and on a failed write logs a WARN and retries on the next
  tick; a restored activated state is remembered so the first tick after a restart does not rewrite it. Not taken:
  writing on every tick (the task forbids it); failing startup on an inconsistent row (all guards would be
  unarmed). Undo: `ExecutionService.trailingFor`, `GuardLoader.load`, `TrailingStateWriter.afterPrice`.
- **SB-014** — How the trailing exit's suggested limit reaches the execution and the prompt. Choices: (1) a
  five-argument `ExecutionService.submit(verdict, source, ref, sizing, limitPrice)` overload (a four-argument one
  taking a `BigDecimal` would make `submit(v, s, p, null)` ambiguous) and a ten-argument
  `ExecutionStore.insertPending` writing `limit_price`; the handler uses them only when the signal carries a
  suggested limit, so a stop exit takes exactly the old path; (2) `ExecutionRow` gains `limitPrice` as its last
  component and keeps its 10- and 11-argument constructors; (3) the prompt adds a second line
  `Suggested limit 109.80` (at least two decimals, as the other prices) and the immediate alert ends with
  `, limit <value>`; (4) the limit is not rounded to a tick (the ATR spread is unrounded, as in SB-013). Not taken:
  updating the row after insert (the task forbids it); putting the limit into the first prompt line (would change
  the line existing tests and the operator already read). Undo: `ExitGuardHandler.handle`.
- **SB-016** — Details of "Trades in R" in the nightly review. Choices: (1) a closed trade is every SELL with a
  `result_r` (a partial SELL counts as its own trade, as SB-015 stores R per SELL); SELLs without R are counted
  and shown as `left out (no 1R): n`, as `RMultiple`'s Javadoc promised; (2) `Expectancy` computes E as
  `(Σ wins − Σ |losses|) / n`, the D-004 formula without rounding intermediate values, scale 4 HALF_UP; the review
  shows R values to 2 decimals HALF_UP, the win rate as a whole percent, E with a sign (`+0.38R`); (3) the section
  goes into the summary Claude receives and is appended verbatim after Claude's written review, so the stored and
  pushed review always carries the code's numbers even when Claude omits or restates them; (4) "too few trades to
  judge" below 20 trades in R (`ExpectancySection.MIN_TRADES_TO_JUDGE`). Not taken: relying on Claude to repeat
  the numbers (the task forbids Claude computing them); counting trades without R as 0R (would distort E). Undo:
  `NightlyReviewJob.run` and `ExpectancySection.format`.
- **SB-020** — Details of the live earnings blackout that D-006 leaves open. Choices: (1) the check runs after the
  "no entry price" check and before the ATR step, so a blacked-out BUY never asks for bars; (2) any
  `RuntimeException` from the calendar (not only `SourceFailureException`, e.g. an open circuit breaker) rejects
  the BUY with "earnings calendar unavailable (<message>)" — fail closed, D-001; (3) the window asked for is today …
  today + 14 calendar days, the horizon `TradingDays` already uses; (4) the property lives in a new
  `RiskProperties` record (`signalbot.risk`), and an absent property binds to 0 (off), while `application.yml`
  sets the starting value 2; (5) `Clock` is a `@ConditionalOnMissingBean` bean in `ClockConfig`. Not taken:
  catching only `SourceFailureException` (a breaker exception would then escape `handle` and drop the verdict
  without a notice). Undo: `LiveDecisionHandler.earningsBlackout`.
- **SB-033** — What does the trailing writer do when the conditional write updates 0 rows, and how are the levels
  compared? Choice: log at INFO and remember the state as handled, so the same stale state is not retried on every
  tick (the next new best or activation tries again); the store compares the levels at the columns' scale
  (`CAST(:x AS NUMERIC(20, 6))`), so in-memory levels with more decimals than V7 stores still match their own row.
  Not taken: retrying on every tick (an INFO line and an UPDATE per tick for a write that cannot succeed); comparing
  the raw values (an ATR with more than 6 decimals would never match and no state would ever be saved).
- **SB-039** — Details of splitting long Telegram messages. Choices: (1) the limit 4 096 is counted in Java `char`s
  (UTF-16 code units), and a hard cut never separates a surrogate pair (it moves one char earlier); (2) a line break
  "within the last 500 characters" means a `\n` at index limit − 500 … limit − 1 of the part; the `\n` stays at the
  end of the part it closes, so the parts joined are exactly the original; (3) the nightly review splits the whole
  pushed text (`Daily review\n` + review), so the header is only on part 1; (4) when a part fails (after the
  sender's own retries) the remaining parts are not sent and a WARN names the part — later parts without the
  earlier ones would read as a whole review. Not taken: a loop inside `TelegramSender` (the task forbids it);
  counting Unicode code points (Telegram's limit is closer to UTF-16 length, and the smaller count is the safe
  side); continuing after a failed part. Undo: `TelegramText.cut` and `NightlyReviewJob.push`.
- **SB-037** — Which context ATR does the BUY gate take as "the ATR Claude saw"? Choice: only a positive
  `ctx.atr()`; a null (not available) or a zero/negative one falls back to fetching the bars as before, so the
  rejection reasons ("no ATR: …", "no usable stop") stay exactly as today. Not taken: trusting any non-null
  `ctx.atr()` (a non-positive one would be rejected with a misleading "not enough daily bars"; `ContextAssembler`
  never produces one anyway).
- **SB-043** — What do "line breaks removed" and "at most 200 characters, `…` when cut" mean exactly in
  `UntrustedText.clean`? Choice: a line break (LF, CR, VT, FF, NEL, U+2028, U+2029) becomes a space so words do not
  run together; every other ISO control character is dropped; the ends are trimmed; the 200 code points include the
  `…` (199 kept + `…`). Not taken: deleting line breaks outright (glues "line one" and "line two" into one word);
  200 code points plus the `…` (the bound would be 201). Unicode format characters (e.g. bidi overrides) are kept:
  removing them also breaks emoji joiners, and they cannot open or close a tag.
