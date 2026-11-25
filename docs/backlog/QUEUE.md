# Backlog queue

The work of the cloud agents, one task per run, in file order (`scripts/backlog/backlog.py plan` picks the first
ready one). How the run works: `docs/backlog/README.md`. Only Aika edits this file by hand; the intake script
appends to the end.

Format of a task (the script parses it; `backlog.py lint` checks it):

- `### ID — title`, then fields, a blank line, the description (at most ~1500 tokens).
- `status`: `todo` | `waiting` (needs Aika; `waits` says what) | `done` | `dropped`. A task is *done* when the
  PR from its branch `claude/<id in lower case>` is merged; nobody edits the status for that.
- `depends`: ids that must be done first. `needs`: `docker` when the cloud image may lack it.
- `paths`: the only files the PR may change (plus `DECISIONS.md`). Globs: `*` within a folder, `**` across folders.
- `rewrites`: existing test files this task may edit, because it changes the behaviour they pin by Aika's decision
  in `DECISIONS.md`. Only the assertions about that behaviour change; nothing else in the file.
- `verify`: the command that proves the task; `expect`: what it prints or what must be true.

Rules for every task (the executor prompt repeats them): existing tests are never changed or deleted unless the
task's `rewrites` names the file; new tests are new files; an applied Flyway migration is never edited (a change
is a new `V<n>__….sql`); no real orders and no port that could place one; no live call to Alpaca, Finnhub,
Telegram or Claude (tests use fixtures and fakes); the **starting values** of `DECISIONS.md` (k = 2, risk 1 %,
cap 2 %, band 1.5–3 ATR, activation 2R, offset 1 ATR, spread 0.1 ATR) are used as written and never tuned (D-005);
`./gradlew build` stays green.

Mockito trap: `ExecutionServiceTest` stubs the existing `insertPending(…)` and verifies the existing 5-argument
`promptExecution(…)` and `guardStore.upsert(…)` on mocks. A mock does not run a delegating or default method, so
when a new overload is added (sizing, limit), the path without the new data must still call the **old** method.
Run the whole `./gradlew build`, not only the task's verify.

Code locations used below: `D` = `domain/src/main/java/io/github/signalbot/domain`,
`A` = `application/src/main/java/io/github/signalbot/application`, `AD` = `adapters/src/main/java/io/github/signalbot`;
tests mirror them under `src/test/java`.

## ATR plan (DECISIONS.md D-002, D-003, D-004; Aika approved all seven steps)

### SB-001 — Domain: daily bars and the PriceHistory port
- status: todo
- size: S
- paths: `domain/src/main/java/io/github/signalbot/domain/model/DailyBar.java`, `domain/src/main/java/io/github/signalbot/domain/port/PriceHistory.java`, `domain/src/test/java/io/github/signalbot/domain/**`
- verify: `./gradlew :domain:test --tests 'io.github.signalbot.domain.*'`
- expect: green; the new test shows that a bar with high < low, a negative price or a null field is refused

Step 1 of the ATR plan, domain part (D-002 "Consequences": a new port for daily bars).

- `D/model/DailyBar.java`: `record DailyBar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
  BigDecimal close, long volume)`. The compact constructor refuses (IllegalArgumentException) a null field,
  `high < low`, a negative price or a negative volume. No Spring, no I/O — the domain is framework-free.
- `D/port/PriceHistory.java`: `List<DailyBar> dailyBars(String symbol, int count)` — the last `count` completed
  daily bars, oldest first. Javadoc: a failed or malformed source throws `SourceFailureException` (D-001); an
  empty list means only "the source answered: no bars".
- New test `domain/src/test/java/io/github/signalbot/domain/model/DailyBarTest.java`.

Do not touch `adapters/.../screen/Bar.java` here (SB-004 does). Read `D/port/SourceFailureException.java` and one
existing port (`MarketDataFeed.java`) first and follow their style.

### SB-002 — Adapter: Alpaca daily bars with strict errors
- status: todo
- depends: SB-001
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/market/AlpacaPriceHistory.java`, `adapters/src/main/java/io/github/signalbot/market/AlpacaBarsMapper.java`, `adapters/src/test/java/io/github/signalbot/market/AlpacaBarsMapperTest.java`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.market.AlpacaBarsMapperTest'`
- expect: green; the tests cover a good body, `"bars": []` (empty list), `"bars": null` or missing (empty list), and a bar with a missing or non-numeric `h`/`l`/`c`/`t` (SourceFailureException)

Step 1 of the ATR plan, adapter part. `ScreenRunner.dailyBars` already calls Alpaca
(`/v2/stocks/{s}/bars?timeframe=1Day&limit=…`), but turns a missing field into `0` — that breaks D-001.

- `AlpacaBarsMapper` (pure, like `AlpacaSnapshotMapper`): JSON node → `List<DailyBar>`, oldest first; `t` is an
  RFC-3339 timestamp → `LocalDate` in UTC. Any missing or malformed field → `SourceFailureException("alpaca", …)`.
- `AlpacaPriceHistory implements PriceHistory`: a `@Component` with the same `@ConditionalOnProperty` as
  `AlpacaMarketDataFeed` (`signalbot.alpaca.enabled=true`), using the existing `alpacaDataRestClient` bean and the
  same `@Retry`/`@CircuitBreaker` names as the other Alpaca calls. A transport error → `SourceFailureException`.
- Tests only on JSON fixtures in the test (no live call, no network).

Do not change `ScreenRunner` here. No new dependency.

### SB-003 — Application: Wilder's ATR(14)
- status: todo
- depends: SB-001
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/risk/Atr.java`, `application/src/test/java/io/github/signalbot/application/risk/AtrTest.java`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.risk.AtrTest'`
- expect: green; the reference values are worked out by hand in the test's comments, not copied from the code's output

Step 2 of the ATR plan (D-002: ATR(14), Wilder's smoothing, daily bars).

`public final class Atr` with `static Optional<BigDecimal> wilder(List<DailyBar> bars, int period)` (bars oldest
first):

- True range of bar i (i ≥ 1) = max(high − low, |high − prevClose|, |low − prevClose|). The first bar only gives
  `prevClose` for the second.
- First ATR = simple mean of the first `period` true ranges (bars 1..period); then
  `ATR_t = (ATR_{t−1} × (period − 1) + TR_t) / period` for every later bar. Result = the ATR after the last bar.
- Fewer than `period + 1` bars → `Optional.empty()` (D-002: no ATR → the BUY is rejected later; never a guess).
- BigDecimal throughout, scale 6, `RoundingMode.HALF_UP` at each division. `period < 1` → IllegalArgumentException.

`AtrTest`: a hand-computed example with period 3 and 6 bars (write the TRs and each ATR step in a comment), a
gap-up bar where `|high − prevClose|` is the largest term, a gap-down bar for `|low − prevClose|`, exactly
`period + 1` bars, and `period` bars → empty. Pure Java, no Spring.

### SB-004 — The screener uses Wilder's ATR
- status: todo
- depends: SB-003
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/screen/**`, `adapters/src/test/java/io/github/signalbot/screen/**`
- rewrites: `adapters/src/test/java/io/github/signalbot/screen/ScreenMetricsTest.java`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.screen.*'`
- expect: green; `ScreenMetrics.atrPercent` returns `Atr.wilder(…, 14)` / last × 100, and fewer than 15 bars gives an explicit error row, not 0

D-002 "Consequences": ATR moves from the `screen` CLI into `application` and is shared; the screener's ATR%
column changes from a simple mean to Wilder's (a small, accepted change).

- `ScreenMetrics.atrPercent` takes `List<DailyBar>` and computes with `Atr.wilder(bars, 14)` (one rule in one
  place: no second ATR formula stays in `screen`). Keep `screen/Bar.java` and `advMillions(List<Bar>)` as they are
  (`advMillionsAveragesVolume` builds `Bar` and is not rewritten); `ScreenRunner` parses each bar into a
  `DailyBar` (date from `t`, open from `o`) and derives the `Bar` for ADV from it. It may use `AlpacaBarsMapper`
  if SB-002 is already merged; otherwise it keeps its local parsing but a missing field is an error, not `0`.
- No ATR (too few bars) → that symbol's row prints `ERROR no ATR (n bars)` as other errors already do.
- `rewrites`: in `ScreenMetricsTest`, only `atrPercentIsMeanTrueRangeOverLast` changes (new name and new
  hand-computed expected value with ≥ 15 bars). The other tests in it stay as they are.

### SB-005 — StopPolicy: Claude's stop inside 1.5–3 ATR, else 2 ATR
- status: todo
- depends: SB-003
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/risk/StopPolicy.java`, `application/src/main/java/io/github/signalbot/application/risk/StopDecision.java`, `application/src/main/java/io/github/signalbot/application/risk/StopSource.java`, `application/src/test/java/io/github/signalbot/application/risk/StopPolicyTest.java`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.risk.StopPolicyTest'`
- expect: green; edges 1.5 and 3 ATR are accepted, 1.49 and 3.01 replaced, missing stop replaced, no ATR rejected

Step 3 of the ATR plan, stop part (D-002, starting values — never tune them, D-005).

- `enum StopSource { CLAUDE, ATR }`.
- `record StopDecision(BigDecimal stop, StopSource source, BigDecimal claudeStop, BigDecimal atr)` —
  `claudeStop` is Claude's original value (null when it gave none), kept so Telegram can show it.
- `StopPolicy` (pure): `static final BigDecimal K = 2`, `MIN_ATR = 1.5`, `MAX_ATR = 3` as named constants with a
  comment "starting value, D-002/D-005". Method `Optional<StopDecision> forBuy(BigDecimal entry,
  BigDecimal claudeStop, Optional<BigDecimal> atr)`:
  - `atr` empty → `Optional.empty()` (the caller rejects the BUY with "no ATR").
  - distance = entry − claudeStop; accepted when claudeStop ≠ null and `1.5 × ATR ≤ distance ≤ 3 × ATR`
    (a stop at or above entry is never accepted).
  - otherwise stop = entry − 2 × ATR, source ATR.
- Tests at both edges, below, above, null, stop ≥ entry, empty ATR.

### SB-006 — PositionSizer: size from the stop, 1 % risk, capped
- status: todo
- depends: SB-005
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/risk/PositionSizer.java`, `application/src/main/java/io/github/signalbot/application/risk/SizeDecision.java`, `application/src/main/java/io/github/signalbot/application/risk/RiskLimits.java`, `application/src/test/java/io/github/signalbot/application/risk/PositionSizerTest.java`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.risk.*'`
- expect: green; every existing test of `RiskLimitEvaluatorTest` still passes unchanged

Step 3 of the ATR plan, size part (D-003, read it whole first).

- `RiskLimits` gains `riskPerTradePct` (starting value `0.01`) and `riskPerTradeCapPct` (hard cap `0.02`). Keep
  the existing 5-argument constructor working (a delegating constructor), so no existing code or test changes;
  `defaults()` sets the new ones. A `riskPerTradePct` above the cap is refused in the canonical constructor.
- `record SizeDecision(long quantity, BigDecimal riskKzt, BigDecimal riskPerShareKzt, String limitedBy,
  String rejection)` — `limitedBy` ∈ RISK / CLAUDE / CONCENTRATION / CASH; `rejection` non-null when quantity < 1.
- `PositionSizer` (pure) implements exactly the formula of D-003: `riskBudgetKzt = totalValueKzt ×
  riskPerTradePct`; `riskPerShareKzt = (entry − stop) × fxToKzt`; `qty = floor((riskBudgetKzt −
  estRoundTripFeesKzt) / riskPerShareKzt)`; then `min(qty, claudeQty, concentrationCapQty, cashQty)`; whole
  shares; `qty < 1` → rejection "risk budget too small for this stop distance" (or the cap that made it < 1).
  Inputs are plain values (no FeeModel, no FxConverter: the caller passes `estRoundTripFeesKzt` and the FX rate);
  `concentrationCapQty` and `cashQty` are computed by the caller (SB-009).
- Claude's quantity only ever lowers the size; a higher Claude quantity is reported via `limitedBy` ≠ CLAUDE.
- Tests: the worked example of D-003 by hand, each cap binding once, fees eating the budget, entry ≤ stop refused.

### SB-007 — V7 migration: sizing, trailing and R columns
- status: todo
- depends: SB-006
- size: S
- paths: `adapters/src/main/resources/db/migration/V7__atr_sizing_trailing.sql`, `adapters/src/test/java/io/github/signalbot/persistence/MigrationV7IntegrationTest.java`
- verify: `./gradlew build`
- expect: green, and in CI the new integration test ran (CI fails on a skipped test); locally without Docker it is skipped

Step 5 of the ATR plan (columns are added before the code that writes them). One new Flyway file; V1–V6 are never
edited. All new columns nullable (old rows stay valid) unless stated:

- `executions`: `stop_source VARCHAR(8)`, `claude_stop NUMERIC(20,6)`, `atr NUMERIC(20,6)`,
  `risk_per_share NUMERIC(20,6)`, `risk_kzt NUMERIC(20,2)`, `claude_quantity NUMERIC(20,6)`,
  `limit_price NUMERIC(20,6)` (suggested limit of a trailing exit, D-004).
- `position_guards`: `tp_activation NUMERIC(20,6)`, `trail_offset NUMERIC(20,6)`, `trail_spread NUMERIC(20,6)`,
  `best_price NUMERIC(20,6)`, `activated BOOLEAN NOT NULL DEFAULT false`, `risk_kzt NUMERIC(20,2)`.
- `paper_trade`: `result_r NUMERIC(12,4)` (realised result of a closing trade in R).

Read `V1__baseline.sql`, `V3`, `V4`, `V6` for the exact table names first. `MigrationV7IntegrationTest`: copy the
container setup of `PaperWalletIntegrationTest` (`@Testcontainers(disabledWithoutDocker = true)`) and check via
`information_schema.columns` that every column above exists with the right nullability.

### SB-008 — Executions carry the sizing facts
- status: todo
- depends: SB-007
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/execution/**`, `adapters/src/main/java/io/github/signalbot/persistence/ExecutionStore.java`, `adapters/src/test/java/io/github/signalbot/execution/ExecutionSizingTest.java`, `adapters/src/test/java/io/github/signalbot/persistence/ExecutionSizingIntegrationTest.java`
- verify: `./gradlew build`
- expect: green; an execution submitted with sizing facts reads back with the same values; one submitted the old way reads back with nulls

- New record `execution/SizingInfo(StopSource stopSource, BigDecimal claudeStop, BigDecimal atr,
  BigDecimal riskPerShare, BigDecimal riskKzt, BigDecimal claudeQuantity)`.
- `ExecutionService.submit(verdict, source, refPrice, SizingInfo sizing)`; the existing 3-argument `submit` stays
  and passes `null` (exits and SELLs have no sizing). `ExecutionStore` writes and reads the V7 columns of
  `executions`; the row record gains a nullable `sizing` component (keep old constructors compiling if tests use
  them — existing tests are not edited).
- Unit test with the existing fakes of `ExecutionServiceTest` (read it), integration test like
  `ExecutionIdempotencyIntegrationTest` (Docker; skipped locally, runs in CI).

### SB-009 — Live path: ATR → stop → size → limits → submit
- status: todo
- depends: SB-002, SB-008, SB-010
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/trigger/LiveDecisionHandler.java`, `adapters/src/main/java/io/github/signalbot/config/**`, `application/src/main/java/io/github/signalbot/application/risk/RiskLimitEvaluator.java`, `adapters/src/test/java/io/github/signalbot/trigger/LiveDecisionHandlerAtrTest.java`, `application/src/test/java/io/github/signalbot/application/risk/RiskLimitEvaluatorTest.java`
- rewrites: `application/src/test/java/io/github/signalbot/application/risk/RiskLimitEvaluatorTest.java`
- verify: `./gradlew build`
- expect: green; the new test shows a BUY without a stop submitted with the ATR stop and the sized quantity, a BUY with no bars REJECTED "no ATR", a BUY whose size is < 1 REJECTED with the reason, and a SELL unchanged

Step 4 of the ATR plan (D-002, D-003). For a BUY in `LiveDecisionHandler.handle`, after the verdict and before
the risk limits:

1. bars = `PriceHistory.dailyBars(symbol, 30)` (an `ObjectProvider<PriceHistory>`; absent bean or
   `SourceFailureException` → REJECTED "no ATR: …", notified like other rejections — never a silent fallback);
   `atr = Atr.wilder(bars, 14)`.
2. `StopPolicy.forBuy(entry, verdict.stop(), atr)`; entry = snapshot last (as `refPrice` does today).
3. `PositionSizer` with total value, `RiskLimits` risk %, FX via the existing `FxConverter`, estimated round-trip
   fees via the existing `FeeModel` (one rule in one place — do not re-implement fees), Claude's quantity,
   concentration cap qty = floor((`RiskLimits.maxSingleSymbolPct` × total − held value) / price in KZT) — the
   existing limit, not a new 30 % literal, cash qty from `CashReader`.
4. Build the verdict to submit with the new stop and quantity; then `RiskLimitEvaluator` for the count checks;
   then `executionService.submit(…, SizingInfo)`. Log Claude's quantity when it was higher.

`RiskLimitEvaluator`: concentration no longer rejects a BUY (it caps the size, D-003). `rewrites`: in
`RiskLimitEvaluatorTest` only `concentrationCapBlocksOversizedBuy` changes (it now expects "allowed"; rename it);
every other test stays as it is. SELL: unchanged path (quantity capped at the held quantity, D-003).

Existing tests are not edited: keep the current constructor of `LiveDecisionHandler` as a delegating one (with no
price history, so a BUY is REJECTED "no ATR") and mark the new one `@Autowired`. `LiveDecisionHandlerTest`
checks only the 3-argument `submit`; the new test also checks that the 4-argument `submit` is never called when the
context or the decision fails.

### SB-010 — Telegram shows the stop, its source and 1R
- status: todo
- depends: SB-008
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/telegram/TelegramExecutionGateway.java`, `adapters/src/main/java/io/github/signalbot/telegram/ExecutionPromptFormatter.java`, `adapters/src/main/java/io/github/signalbot/execution/**`, `adapters/src/test/java/io/github/signalbot/telegram/ExecutionPromptFormatterTest.java`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.telegram.*'`
- expect: green; the prompt text of a BUY shows stop, `ATR`/`CLAUDE`, Claude's original stop when replaced, ATR and 1R in ₸; a SELL or exit prompt has no sizing lines

Step 4 of the ATR plan, operator side (D-002: the substitution is visible and cancellable with **Skipped**).
It comes before SB-009 on purpose: once the live path replaces stops, the operator already sees it. The gateway
gets the sizing facts either by reading the execution row by id (`ExecutionStore`) or through a
`promptExecution` overload taking `SizingInfo` (existing calls and tests keep compiling). Move the text building
of the execution prompt out of `TelegramExecutionGateway` into a pure
`ExecutionPromptFormatter` (so it can be tested without Telegram) and add, when `SizingInfo` is present:
`Stop 182.40 (ATR; Claude: 185.00)`, `ATR(14) 3.70`, `1R = 12 500 ₸ (qty 7)`, and the line
"Skipped = do not take the trade". Keep every existing line and button as is. No live Telegram call in tests.

### SB-011 — ATR in the Claude prompt
- status: todo
- depends: SB-002, SB-003
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/decision/ClaudePromptBuilder.java`, `adapters/src/main/java/io/github/signalbot/trigger/ContextAssembler.java`, `domain/src/main/java/io/github/signalbot/domain/model/DecisionContext.java`, `adapters/src/test/java/io/github/signalbot/decision/ClaudePromptAtrTest.java`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.decision.*' --tests 'io.github.signalbot.trigger.*'`
- expect: green; the prompt has a line `ATR(14): 3.70 (2.1% of last)` or `ATR(14): not available`; all existing prompt and context tests pass unchanged

Step 4 of the ATR plan, Claude side. `DecisionContext` gains a nullable `BigDecimal atr` (keep the old
constructor as a delegating one so existing code and tests compile unchanged). The same for `ContextAssembler`:
`ContextAssemblerTest` builds it with today's 4 arguments, so that constructor stays (delegating, no price
history) and the new one is `@Autowired`. `ContextAssembler` fills it from
`PriceHistory` + `Atr.wilder(…, 14)` when the bean exists; a failing history makes the ATR line "not available"
(the ATR is context here, not a gate — the gate is SB-009), and that is logged. The prompt also says in one line
that the stop must be 1.5–3 ATR below entry or code replaces it with 2 ATR (D-002).

### SB-012 — ExitGuard: trailing take-profit (pure)
- status: todo
- depends: SB-007
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/trigger/**`, `application/src/test/java/io/github/signalbot/application/trigger/TrailingExitTest.java`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.trigger.*'`
- expect: green; new `TrailingExitTest` covers: no tracking before activation, activation at the price, best rises, exit at `price < best − offset`, stop still fires after activation, a gap through both gives the stop, state round-trip

Step 6 of the ATR plan (D-004, read it whole first). In `ExitGuard`:

- `ExitType` gains `EXIT_TRAIL`. The existing `arm(symbol, stop, takeProfit)` and `EXIT_TP` stay untouched (the
  callers in `adapters` and `ExitGuardTest` use them; SB-013 stops arming a fixed TP). New methods get new names,
  so `arm(…, null)` calls stay unambiguous:
- `armTrailing(symbol, stop, TrailingLevels trailing)` where `record TrailingLevels(BigDecimal activation,
  BigDecimal offset, BigDecimal spread)`; plus `restore(symbol, stop, trailing, BigDecimal best, boolean activated)`
  for the restart (SB-013). A method `state(symbol)` returns the current `best`/`activated` so the adapter can
  persist them.
- `onPrice`: stop first (it stays armed after activation — D-004 keeps it as a floor); before activation the
  best is not tracked; price ≥ activation → activated, best = price; after: best = max(best, price); exit when
  `price < best − offset`, `ExitSignal(EXIT_TRAIL, price, level = best − offset)`; fires once.
- `ExitSignal` gains a nullable suggested limit: D-004 says limit = **breakout price − spread**, where the
  breakout price is the price that fired the exit (not `best − offset`).
- `ExitGuardTest` is not edited and stays green.

### SB-013 — Trailing state is armed at fill and survives a restart
- status: todo
- depends: SB-008, SB-012
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/persistence/PositionGuardStore.java`, `adapters/src/main/java/io/github/signalbot/trigger/GuardLoader.java`, `adapters/src/main/java/io/github/signalbot/trigger/ExitGuardHandler.java`, `adapters/src/main/java/io/github/signalbot/trigger/PriceStreamRunner.java`, `adapters/src/main/java/io/github/signalbot/trigger/SnapshotGuardPoller.java`, `adapters/src/main/java/io/github/signalbot/trigger/TrailingStateWriter.java`, `adapters/src/main/java/io/github/signalbot/execution/ExecutionService.java`, `adapters/src/test/java/io/github/signalbot/trigger/TrailingRestartTest.java`, `adapters/src/test/java/io/github/signalbot/persistence/PositionGuardTrailingIntegrationTest.java`
- verify: `./gradlew build`
- expect: green; a guard armed at a BUY fill with entry 100, stop 96, ATR 2 has activation 108, offset 2, spread 0.2; after "restart" (new ExitGuard + GuardLoader) an activated guard keeps its best price

D-004 "Consequences". At a BUY fill (`ExecutionService`, where it now calls `exitGuard.arm(row.symbol(),
row.stop(), row.takeProfit())`, switch to `armTrailing`): R = fill price − stop; activation = fill + 2R; offset = 1 × ATR; spread =
0.1 × ATR (ATR from the execution's `SizingInfo`; no ATR → arm the stop only and log it). Persist them and
`risk_kzt` in `position_guards` (V7 columns) through a **new** `PositionGuardStore` method: the existing
`guardStore.upsert(symbol, stop, takeProfit)` call stays exactly as it is (`ExecutionServiceTest` verifies it on a
mock and is not edited); only the in-memory guard is armed with `armTrailing`. `ExitGuard.onPrice` is called by
`PriceStreamRunner` and `SnapshotGuardPoller`, which only see fired signals; after each `onPrice` they hand the
symbol to a small `TrailingStateWriter` that compares `exitGuard.state(symbol)` with the last saved one and
writes `best_price`/`activated` only on activation and on a new best (not on every tick). `GuardLoader` restores with
`ExitGuard.restore` when the trailing columns are set; otherwise it arms the **stop only** and ignores
`take_profit` (no fixed take-profit is re-armed after a restart, D-004). Claude's `takeProfit` is still stored on the execution for review but not armed.

### SB-014 — A trailing exit becomes a SELL with a suggested limit
- status: todo
- depends: SB-013
- size: S
- paths: `adapters/src/main/java/io/github/signalbot/trigger/ExitGuardHandler.java`, `adapters/src/main/java/io/github/signalbot/telegram/ExecutionPromptFormatter.java`, `adapters/src/main/java/io/github/signalbot/telegram/TelegramExecutionGateway.java`, `adapters/src/main/java/io/github/signalbot/execution/**`, `adapters/src/main/java/io/github/signalbot/persistence/ExecutionStore.java`, `adapters/src/test/java/io/github/signalbot/trigger/TrailingExitHandlerTest.java`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.trigger.*' --tests 'io.github.signalbot.telegram.*'`
- expect: green; an `EXIT_TRAIL` signal at 110 with spread 0.2 creates a SELL execution (source EXIT) with ref price 110 and limit 109.80, and the prompt shows "limit 109.80"

D-004: the exit is a SELL execution (`ExecutionSource.EXIT`) with `refPrice` = breakout price and suggested limit
`breakout − spread` (stored in `executions.limit_price`, shown to the operator). Handle `EXIT_TRAIL` the same way
`EXIT_STOP` is handled today (read `ExitGuardHandler`), only the limit is new. The limit must be stored with the
execution before the prompt goes out (`ExecutionService.submit` inserts and prompts at once): pass it into
`submit` (an overload) rather than updating the row afterwards.

### SB-015 — Realised result in R for each closed trade
- status: todo
- depends: SB-008
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/review/**`, `adapters/src/main/java/io/github/signalbot/persistence/PaperWalletImpl.java`, `adapters/src/main/java/io/github/signalbot/persistence/TradeQueryStore.java`, `adapters/src/main/java/io/github/signalbot/persistence/ExecutionStore.java`, `application/src/test/java/io/github/signalbot/application/review/**`, `adapters/src/test/java/io/github/signalbot/persistence/ResultRIntegrationTest.java`
- verify: `./gradlew build`
- expect: green; a closing SELL of a position opened with 1R = 10 000 ₸ and a net result of −13 000 ₸ stores result_r = −1.3000 (a loss beyond 1R is recorded, not clipped — D-002 "Known limits")

Step 7 of the ATR plan, part 1 (D-003: "realised result of each closed trade is stored in R").
Pure `application/review/RMultiple.of(netResultKzt, riskKzt)` (scale 4, HALF_UP; riskKzt null or ≤ 0 →
empty, the trade is left out and the review says how many were left out). The paper wallet sets
`paper_trade.result_r` on the trade that closes (or reduces) a position, using `executions.risk_kzt` of the BUY
execution that opened it (SB-008) — not `position_guards`: `ExitGuardHandler` deletes the guard as soon as an
exit fires, before the SELL fills, and a second BUY overwrites it. With several BUYs, use the sum of their 1R.
Net result (fees are inside 1R, D-003): the wallet's existing `realizedKzt` (`PaperWalletImpl.sell`, before fees —
reuse it, do not compute P&L a second way) − this SELL's `fee.totalKzt()` − the opening BUY executions'
`fee_total` (V5) × sold qty / bought qty. Tax is not part of R. Write this definition in the Javadoc of
`RMultiple`. Set `result_r` with an UPDATE by `execution_id` (`paper_trade.execution_id`, V6) through
`TradeQueryStore`; `LedgerStore` is not in the paths.

### SB-016 — Expectancy in R in the nightly review
- status: todo
- depends: SB-015
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/review/**`, `adapters/src/main/java/io/github/signalbot/review/**`, `adapters/src/main/java/io/github/signalbot/persistence/TradeQueryStore.java`, `application/src/test/java/io/github/signalbot/application/review/ExpectancyTest.java`, `adapters/src/test/java/io/github/signalbot/review/NightlyReviewExpectancyTest.java`
- verify: `./gradlew build`
- expect: green; with closed trades of +2R, +1.5R, −1R, −1R the review shows n=4, win rate 50 %, avg win 1.75R, avg loss 1.00R, E = +0.38R

Step 7 of the ATR plan, part 2. Pure `Expectancy.of(List<BigDecimal> rs)` → n, win rate, avg win, avg loss,
`E = P(win)·avgWin − P(loss)·avgLoss` (a 0R trade counts as neither; say so in the Javadoc). The nightly review
(`NightlyReviewJob`, read it) adds a section "Trades in R" for all closed trades so far, plus "too few trades to
judge" under 20 trades. The numbers are computed by code, never by Claude; Claude's written review may receive
them as input. No live Claude call in tests.

### SB-017 — README: screen, exits, migrations, risk in R
- status: todo
- depends: SB-010, SB-014, SB-016
- size: S
- paths: `README.md`
- verify: `grep -nE 'ATR|EXIT_TRAIL|V7|in R|screen' README.md`
- expect: the README describes the `screen` CLI, the exit guard with the ATR stop and the trailing take-profit, migrations V1–V7, risk per trade in R and position size from the stop, and links DECISIONS.md D-001…D-005

Step 7 of the ATR plan, docs. Update only what changed; keep "No real orders, ever" and the private-adapter rule
word for word. The "Risk limits" section: concentration now caps the size; add risk 1 % (cap 2 %) per trade.
Do not mention values other than those in DECISIONS.md.

## Tests: pin what exists (new test files only, no production code)

These need no ATR work and may run in parallel with it. If a test shows that the code breaks a rule of
`DECISIONS.md` (e.g. D-001: an error turned into "no data"), do not fix the code: keep the test that pins the
right behaviour out, write the finding to `docs/backlog/reports/<id>.md` and open the PR with the passing tests
and the report.

### SB-101 — No port can place an order
- status: todo
- size: S
- paths: `domain/src/test/java/io/github/signalbot/domain/NoOrderPortTest.java`, `docs/backlog/reports/SB-101.md`
- verify: `./gradlew :domain:test --tests 'io.github.signalbot.domain.NoOrderPortTest'`
- expect: green; the test fails if any interface in `io.github.signalbot.domain.port` declares a method whose name contains `order`, `place`, `submit` or `trade` (case-insensitive) — `PaperWallet`'s methods are the listed exception, by exact name

The README's first rule ("no port for one") as a test. Find the port interfaces by walking
`src/main/java/io/github/signalbot/domain/port` (the Gradle test working directory is the module directory) and
loading each class by name; list the found methods in the failure message. Read every port first and write the
`PaperWallet` exceptions by exact method name.

### SB-102 — Domain and application stay framework-free
- status: todo
- size: S
- paths: `application/src/test/java/io/github/signalbot/application/PurityTest.java`, `docs/backlog/reports/SB-102.md`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.PurityTest'`
- expect: green; the test fails on any `import org.springframework`, `java.sql`, `java.net`, `jakarta.`, `com.anthropic` or `org.telegram` in `domain/src/main/java` or `application/src/main/java`

README: "The domain knows nothing about Spring, HTTP, or SQL" and dependencies point inward. A plain JUnit test
that reads the source files (paths relative to the `application` module: `../domain/src/main/java`, `src/main/java`)
and lists every offending file:line. No new dependency (no ArchUnit: build files are Aika's).

### SB-103 — Price-move trigger at the 3 % edge
- status: todo
- size: S
- paths: `application/src/test/java/io/github/signalbot/application/trigger/TriggerEdgeTest.java`, `docs/backlog/reports/SB-103.md`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.trigger.TriggerEdgeTest'`
- expect: green; exactly the threshold, just below, just above, a fall of the same size, and a zero/absent prior close are each pinned

Read `TriggerEvaluator`, `TriggerSettings` and `TriggerEvaluatorTest` first; cover only the edges the existing
test does not. Use the threshold from `TriggerSettings`, not a literal 3 %.

### SB-104 — Fees at their edges
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/persistence/FeeModelEdgeTest.java`, `docs/backlog/reports/SB-104.md`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.persistence.FeeModelEdgeTest'`
- expect: green; one share, a fractional per-share result, the per-order minimum (if the model has one), a non-US venue at 0.1 %, and KZT conversion rounding are each pinned

Read `FeeModel` and `FeeModelTest` first; pin only what the existing test does not, with expected values worked
out by hand in comments.

### SB-105 — The verdict parser never invents a verdict
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/decision/ClaudeVerdictParserRobustnessTest.java`, `docs/backlog/reports/SB-105.md`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.decision.ClaudeVerdictParserRobustnessTest'`
- expect: green; empty text, prose without JSON, truncated JSON, a wrong action, a negative quantity, a confidence outside [0, 1], and 200 random strings (fixed seed) each throw `DecisionFailedException` — none yields a HOLD

D-001: an unusable Claude response is an error, never a default HOLD. Read `ClaudeVerdictParser` and its test
first; add only cases the existing test lacks. Fixed `java.util.Random` seed so a failure can be replayed.

### SB-106 — Finnhub mappers fail loudly on bad bodies
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/news/FinnhubMalformedTest.java`, `docs/backlog/reports/SB-106.md`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.news.FinnhubMalformedTest'`
- expect: green; a JSON object where a list is expected, a news item with no datetime, an earnings entry with a bad date each throw `SourceFailureException`; an empty list returns an empty list

D-001 for Finnhub. Read both mappers and `FinnhubMapperTest` first; add only missing cases.

### SB-107 — Expired executions at the timeout edge
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/execution/ExecutionSweeperTest.java`, `docs/backlog/reports/SB-107.md`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.execution.ExecutionSweeperTest'`
- expect: green; a PENDING execution one millisecond before the timeout stays PENDING, at and after it becomes EXPIRED (or AUTO-filled in AUTO mode, as the code does); a FILLED one is never touched

Read `ExecutionSweeper`, `ExecutionService`, `ExecutionProperties` and `ExecutionServiceTest` (its fakes) first.
Use a fixed `Clock` if the code takes one; if it calls `Instant.now()` directly, test with margins and write that
in the report `docs/backlog/reports/SB-107.md` as a finding (do not change the code).

### SB-108 — Portfolio messages with nothing or losses
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/telegram/PaperStateFormatterEdgeTest.java`, `docs/backlog/reports/SB-108.md`
- verify: `./gradlew :adapters:test --tests 'io.github.signalbot.telegram.PaperStateFormatterEdgeTest'`
- expect: green; no positions, a negative P&L, a zero cash balance and a symbol with a long name each format without an exception and show the sign of a loss

Read `PaperStateFormatter` and its test first; add only the missing cases.
