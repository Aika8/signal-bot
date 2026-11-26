# Questions for Aika: decided, and what waits for the vacation's end

Written 2026-11-26. Aika's instruction the same day: nothing waits for her. So:

## Decided without Aika (each can be undone; details in `DECISIONS.md`)

| Question | Decision | Undo |
|---|---|---|
| Event filter before FOMC/CPI/NBK/earnings | D-006: no new BUY within 2 trading days before the company's earnings (starting value); tasks SB-018…SB-020 | property `signalbot.risk.earnings-blackout-trading-days: 0` |
| Insider buys line in the Claude prompt | not added; stays in the lab until H-001 is measured | one `append` in `ClaudePromptBuilder` |
| Lab label rule (SB-515) | kept: < 30 trades → candidate, Bonferroni bar on \|t\|, holdout must agree | constants in `LabelRule` |
| Debounce key | stays the symbol only | the key in `SignalDispatcher.dispatch` |
| Lab as a Gradle module, PIT plugin | not now (build files are off-limits for agents); lab in `application/lab`, mutation testing by hand | — |

## After the vacation (not in the queue: needs money, keys, accounts, settings, build files or live calls)

1. **Macro events for the filter** (FOMC, CPI, NBK): no data source in the code; needs a calendar source or a
   hand-kept file of dates.
2. **Live EDGAR fetcher**: a live call to sec.gov with a User-Agent contact.
3. **Lab Gradle module with a command** (`./gradlew lab --hypothesis H-001`) and **PIT**: build files.
4. **GitHub Actions minutes**: the repository is private (2 000 free minutes a month). One merged task costs roughly
   5–10 minutes of Actions; hourly runs can approach the limit within the week, and with the spending limit at $0
   the workflows then stop and nothing merges until the month turns. Options: check the usage page on 3–4 December;
   make the repository public (a settings change, Aika's); or accept the stop.
5. **Anything with real orders, `.env`, `adapter-broker-private/`, or live Alpaca/Finnhub/Telegram/Claude calls**:
   never queued.

## Worth knowing when back

- The ATR plan is a chain of up to eight steps (SB-001 → … → SB-017); the journal, the weekly review and some
  mutation tests wait for it. If a step is parked, look at `parked` issues first.
- The run still parks a task whose decision is costly to undo (README, "do not wait for Aika"): that rule is a
  protection and was not loosened.
