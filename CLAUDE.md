# signal-bot — rules for any agent working here

## What this is
A personal signal service: feeds trigger it, Claude gives a verdict, risk rules in code size and filter it, the
operator confirms in Telegram, a **paper** wallet books it. Start with `README.md` and `DECISIONS.md`.

## Never
- Never read, print or commit `.env` or any `.env.*` except `.env.example`. Run `git status` before every commit.
- `adapter-broker-private/` is read-only and never enters this repository (git-ignored). The build must work
  without it; `portfolio.source=private` without the module fails fast at startup (`PortfolioSourceConfig`).
- No real orders, ever: no "place order" port, method or adapter, not even a stub.
- No live calls to Alpaca, Finnhub, Telegram or Claude from an agent session or a test: fixtures and fakes only.
  No API keys exist in the cloud environment; do not ask for any.
- Strategy parameters (the **starting values** in `DECISIONS.md`) change only through a backtest (D-005), never by
  hand, never "to make a test pass".
- No investment advice in assistant output. No AI attribution in commits or PR descriptions.

## Workflow
- Agents here commit, push to `claude/*` branches and open PRs without asking. Only
  `.github/workflows/automerge.yml` merges, after green CI and an independent review. The cloud backlog run:
  `docs/backlog/README.md`.
- A decision that changes how a verdict becomes a trade gets a `D-NNN` entry in `DECISIONS.md` before the code.
- One business rule lives in one place (fees: `FeeModel`; ATR: `Atr`; FX: `FxConverter`). If an implementation
  exists, use it; if it looks wrong, do not write a second one — say so.
- An applied Flyway migration (`adapters/src/main/resources/db/migration`) is never edited; add `V<n+1>__….sql`.
- Existing tests are not edited to get green; new behaviour gets new test files.

## Running
- `./gradlew build` (JDK 21). Without Docker the two Testcontainers tests skip themselves; CI has Docker and fails
  on any skipped test.
- Backlog rules: `python3 -m unittest discover -s scripts/backlog -p 'test_*.py'`.
