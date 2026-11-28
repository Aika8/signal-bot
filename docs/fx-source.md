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
- With a missing or stale rate: **no BUY is sized.** `LiveDecisionHandler` rejects the BUY with
  "FX rate unavailable (<reason>)", the same way it rejects "no ATR" and "earnings calendar unavailable" (SB-020),
  and the operator gets the usual Telegram rejection. HOLD and SELL verdicts and exits still flow: closing a
  position must never depend on a tenge rate.
- Paper fills already confirmed by the operator are booked at the last good rate and the booking says which rate
  date it used, so nothing confirmed is lost; a fill with no rate at all at hand fails loudly (alert), not silently.
- At startup with no rate at all: the application starts (exits must stay armed), BUY sizing stays closed until the
  first good read, and an alert says so.
- The environment constants `FX_USD_KZT` / `FX_EUR_KZT` stay only for tests (`@SpringBootTest(properties =
  "signalbot.fx.usd-kzt=500")` already does this) and behind an explicit `signalbot.fx.source=fixed` switch; the
  default becomes `nbk`. A fixed rate is never a silent fallback for a failed NBK read.

## Where it goes in the code

`FxConverter` stays the one FX rule; only where its numbers come from changes (an `FxRateSource` behind it, NBK in
`adapters`, a fixed source for tests). The implementation is proposed as its own task:
`docs/backlog/tech-debt/2026-11-28-SB-042-1.md`. The choice is recorded in `DECISIONS.md` under
"Backlog run (December 2026)".
