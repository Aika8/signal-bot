# Where the USD/KZT rate should come from (SB-042)

Research note, no code. Written in a cloud run **without network access**: every fact below about an outside
service comes from the agent's own knowledge and is marked **not checked** unless it is verified from this
repository. Check those facts once, by hand, before the implementation task starts.

## Today

- `adapters/src/main/resources/application.yml`: `signalbot.fx.usd-kzt: ${FX_USD_KZT:525.0}` and
  `signalbot.fx.eur-kzt: ${FX_EUR_KZT:565.0}`, bound to `FxProperties` (checked).
- `FxConverter` is the only FX rule (`toKzt`, `rateOf`) (checked). It is used by `PositionSizer`'s caller
  `LiveDecisionHandler` (risk per share and cash in KZT, D-003), `FeeModel` (USD fees in KZT), `PaperWalletImpl`
  (notional and realised P&L in KZT), `PaperPortfolioReader` and `PaperCashReader` (total value in KZT).
- So the size of every BUY depends on a rate typed in by hand that never ages. A rate that is 5 % off makes every
  1R 5 % off, silently. That is "an error treated as data" in spirit (D-001).

## Candidate sources

| Source | Who publishes it | Licence / terms | Update time | Format | Key / account | KZT pairs |
|---|---|---|---|---|---|---|
| **National Bank of Kazakhstan (NBK) official rates** — `nationalbank.kz`, RSS/XML `rss/get_rates.cfm?fdate=DD.MM.YYYY` and `rss/rates_all.xml` | The central bank of the Republic of Kazakhstan | Official public data; no explicit reuse licence known — **not checked** | Once per business day; the rate for the next day is set from the KASE session and published in the afternoon, Almaty time (UTC+5); the last business day's rate holds over weekends and holidays — **not checked** | XML (RSS items: `title` = currency code, `description` = rate, `quant` = units, `pubDate`) — **not checked** | None | USD/KZT, EUR/KZT and ~40 others, directly quoted in KZT |
| **KASE** (Kazakhstan Stock Exchange) — `kase.kz`, USD/KZT session results | The exchange where the tenge trades | Market data; redistribution terms for exchange data are usually restrictive — **not checked** | Intraday sessions on business days; daily weighted average after the session — **not checked** | HTML pages and files; no documented free API known — **not checked** | Programmatic/real-time data is a paid subscription — **not checked** | USD/KZT, EUR/KZT, RUB/KZT, CNY/KZT |
| **ExchangeRate-API "open access"** — `open.er-api.com/v6/latest/USD` | ExchangeRate-API (private company, aggregated from several sources) | Free, attribution required, rate-limited; terms may change — **not checked** | Once a day — **not checked** | JSON (`rates.KZT`, `time_last_update_unix`) | None | KZT as a cross rate (EUR/KZT = KZT/EUR from the same file) |
| **fawazahmed0 `currency-api`** — via jsDelivr CDN, `…/currency-api@latest/v1/currencies/usd.json` | One open-source maintainer on GitHub | Public domain / CC0 data, sources not documented in detail — **not checked** | Daily — **not checked** | JSON (`{"date": …, "usd": {"kzt": …}}`) | None | KZT as a cross rate |
| ECB euro reference rates / Frankfurter (`frankfurter.app`, built on ECB) | European Central Bank | Free reuse with source cited — **not checked** | ~16:00 CET on TARGET days — **not checked** | XML / JSON | None | **No KZT** in the ECB basket — **not checked**; unusable for this |
| Finnhub forex, Open Exchange Rates, currencylayer, exchangerate.host, Fixer | Commercial data vendors | Free tiers with limits | Hourly or better on paid tiers | JSON | **Key and sign-up required** | KZT on most — **not checked** |

The last row is listed only for completeness: a source that needs a key or an account is not recommended (no keys,
no accounts in this project's runs). Finnhub already has a key in production for other feeds, but adding a
dependency on its forex coverage for KZT is unverified and widens what one key failure takes down.

## Recommendation

**NBK official rates (XML), read for both USD/KZT and EUR/KZT.**

- It is the primary source for the tenge: published by the issuer of the currency, no aggregator in between, no key.
- It is the rate Kazakh brokers, banks and the tax rules refer to as "the official rate" — **not checked** — so
  the paper wallet's KZT numbers would match what the operator sees in statements and tax reports.
- Both pairs come in one response, quoted directly in KZT (no cross-rate arithmetic, no third currency).
- XML parsing stays in `adapters`; `domain`/`application` are unaffected.
- Weakness: one value per business day, not intraday. For sizing a daily-bar swing trade with a 1 % risk budget,
  a day-old rate is acceptable; an intraday tenge move of several percent (a devaluation day) would be seen only the
  next day. The staleness rule below bounds that.
- Second choice: ExchangeRate-API open access (no key, JSON, but an aggregator with changeable terms). It is **not**
  wired as an automatic fallback: two sources silently disagreeing is worse than a clear "no rate" (D-001).

## How often to read it (frequency)

- On startup, and then once per day after publication: a scheduled read at **18:00 Asia/Almaty** on business days,
  with up to three retries 30 minutes apart if the read fails. Reading more often adds nothing — the value changes
  once a day.
- Keep the last good rate in memory with **the date NBK states for it** (not the time it was fetched).
- Log every accepted rate at INFO (`fx USD/KZT 512.34 for 2026-12-01 from NBK`), so the nightly review can quote it.

## When it is unavailable or stale — fail closed (D-001)

- A failed read (HTTP error, timeout, malformed XML, a missing currency, a rate ≤ 0) throws
  `SourceFailureException`, as every other adapter does. It never yields a default, a zero, or the old constant.
- **Stale** = the rate's own date is more than **5 calendar days** before today (Asia/Almaty). Five covers a
  weekend plus the longest usual Kazakh holiday bridge (e.g. Nauryz) — **not checked** against the holiday
  calendar. The number is a property (`signalbot.fx.max-age-days`), not a trading parameter: it decides only whether
  a rate exists at all.
- **What a stale rate blocks: only sizing a new BUY.** `LiveDecisionHandler` rejects the BUY with
  "FX rate unavailable (<reason>)", the same way it rejects "no ATR" and "earnings calendar unavailable" (SB-020),
  and the operator gets the usual Telegram rejection. With a **stale** (but once read) rate, HOLD and SELL verdicts
  and exits still flow: closing a position must never depend on how fresh a tenge rate is.
- **A missing rate (none ever read) blocks every verdict, on purpose.** `LiveDecisionHandler.handle` assembles the
  context first (`ContextAssembler.assemble` → `portfolioReader.read`), and `PaperPortfolioReader` converts through
  `FxConverter`, which throws `SourceFailureException("no FX rate read yet")`. So every trigger stops before Claude
  runs, with `NO VERDICT: context unavailable (no FX rate read yet)` logged at ERROR and sent as a Telegram alert
  (`LiveDecisionHandler.java:159`, `fail`): no BUY, HOLD or SELL verdict comes out until the first good read. This
  is the intended fail-closed behaviour (D-001). No fallback rate, constant or default is ever used to let verdicts
  "flow" in this window; exits in it take the "(position not checked)" path below.
- **Bookings use the last good rate, whatever its age.** Every paper booking (a BUY fill the operator already
  confirmed, a SELL, an exit) converts through `FxConverter` (`PaperWalletImpl.java:90,111,113`, the fees through
  `FeeModel`). Staleness is therefore **not** checked inside `FxConverter.toKzt`/`rateOf`: those keep converting at
  the last good rate, so a stale rate never blocks an exit. The staleness check is a separate question asked only
  by the BUY sizing path. The rate and its NBK date are logged with each booking (INFO); storing the date with the
  booking would need a migration and is left out.
- **No rate ever read** (only possible with `signalbot.fx.source=nbk`: startup before the first good read, or NBK
  down since boot): the application still starts (exits must stay armed), and every trigger is refused with the
  "context unavailable (no FX rate read yet)" alert above. If an exit fires in that window, `ExitGuardHandler` reads the portfolio first, and `PaperPortfolioReader`
  converts through `FxConverter` (`PaperPortfolioReader.java:61,78`), which throws
  `SourceFailureException("no FX rate read yet")`. The handler already treats a failed portfolio read as
  AUTO-20261127-2 made it (`ExitGuardHandler.java:62-70`, `ExitHandlerFailureTest`): it logs at ERROR, sends the
  exit alert marked "(position not checked)" and submits no SELL. The operator closes the position by hand after
  the first good read. Nothing is booked at an invented rate, and the alert is never lost.
- **Default source stays `fixed`.** `signalbot.fx.source` defaults to `fixed` (the current `FX_USD_KZT` /
  `FX_EUR_KZT` constants), so every existing `@SpringBootTest` keeps working without a network read and without
  editing any test. Production switches to NBK by setting `FX_SOURCE=nbk` in its environment once the NBK source is
  merged and its format checked by hand. With `nbk`, a failed NBK read never falls back to the constants.

## Where it goes in the code

`FxConverter` stays the one FX rule; only where its numbers come from changes. Existing callers keep compiling:
`FxProperties(BigDecimal usdKzt, BigDecimal eurKzt)` is **not** changed (the new `source` and `max-age-days`
settings live in a new record, `FxSourceProperties`), and `new FxConverter(FxProperties)` keeps working (it wraps
the fixed source), next to a new Spring constructor marked `@Autowired`. Five existing tests build these classes
directly and must compile untouched: `FeeModelTest`, `LiveDecisionEarningsBlackoutTest`,
`LiveDecisionNonPositiveStopTest`, `LiveDecisionHandlerAtrTest`, `LiveDecisionContextAtrTest`. The staleness
question is a method on `FxConverter` that only the BUY sizing path calls, so `LiveDecisionHandler`'s constructors
(used by six existing tests) do not change either. The implementation is queued as
four size-S proposals in `docs/backlog/tech-debt/2026-11-28-SB-042-1.md`, in this order:

1. an `FxRateSource` port behind `FxConverter` with a fixed source (today's constants), no change in behaviour;
2. an NBK XML parser with fixture tests (no network, no wiring);
3. the BUY rejection on a missing or stale rate, in `LiveDecisionHandler`;
4. the NBK source itself (startup + daily read, last good rate kept), selected only by `signalbot.fx.source=nbk`,
   with the "no rate ever read" exit behaviour above.

Each later piece checks first that the earlier ones are on `main` and parks with "missing: …" if not (intake cannot
yet express `depends` on tasks it has not created). The choice is recorded in `DECISIONS.md` under
"Backlog run (December 2026)".
