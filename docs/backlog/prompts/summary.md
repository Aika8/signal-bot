# Summary run (once a day)

You are a cloud agent in a fresh clone of `Aika8/signal-bot`. You write the daily summary Aika reads when
she is back — she will read seven of them in one evening, so each must be short, exact and in plain words. You
change no code. Nobody will answer questions, and nothing waits for Aika: a question a run parked is decided here (section 1b). Never read `.env` or anything in `adapter-broker-private/`; no live calls
to the broker, Finnhub, Telegram or Claude.

## 0. Before anything

```bash
git fetch origin && git checkout main && git pull --ff-only
DAY=$(date -u -d "yesterday" +%F)    # the summary covers the previous UTC day
```

The summary is written **even when** `docs/backlog/PAUSE` exists (say so at the top). If
`docs/backlog/summaries/$DAY.md` already exists on `main` or on an open branch `claude/summary-$DAY`, stop.

## 1. Collect (facts only; every number from a command you ran)

```bash
git log --since="$DAY 00:00 UTC" --until="$DAY 23:59 UTC" --format='%h %s' origin/main
gh api "repos/Aika8/signal-bot/pulls?state=all&per_page=100" > /tmp/prs.json      # merged/open/closed, labels
gh api "repos/Aika8/signal-bot/issues?state=open&labels=parked&per_page=100"
gh api "repos/Aika8/signal-bot/issues?state=open&labels=run-stopped&per_page=100"   # runs that had to stop
gh api "repos/Aika8/signal-bot/issues?state=open&labels=hold&per_page=100"          # guard pauses
python3 scripts/backlog/backlog.py lint
```

From these: tasks merged that day (PR title, number); PRs open and why they wait (review, CI, `needs-fix`,
`needs-rebase`); PRs parked or `blocked-paths` with the question each one asks; the intake of the day
(`docs/backlog/intake-log.md`); the bullets added that day to `DECISIONS.md` under `## Backlog run (December 2026)`;
how many tasks are left `todo` and ready; CI failures on `main`, if any
(`gh api "repos/Aika8/signal-bot/actions/runs?branch=main&per_page=20"`).

## 1b. Decide the parked questions (Aika: "nothing waits for me")

For every open PR or issue labelled `parked` whose comment asks a question and that has no `<!-- summary-decided -->`
comment yet:

1. **Never decide** (list it under "For you after the holiday" instead): anything with money, keys, sign-ups or
   external accounts; repository or account settings; protected files (`PROTECTED` in `scripts/backlog/backlog.py`:
   build files, CI, prompts, backlog rules); applied migrations; live calls to the broker, Finnhub, Telegram or
   Claude; real orders or a port that could place one; `.env` or `adapter-broker-private/`; a changed **starting
   value** (D-005: only a backtest changes those).
2. Otherwise choose: the recommendation the question gives; without one, the option easiest to undo.
3. Add one bullet to `DECISIONS.md` under `## Backlog run (December 2026)`: `**<task id> (решено без Aika, summary
   $DAY)** — <question>. Choice: … Not taken: … Undo: …`.
4. Add the task that carries the choice out to `docs/backlog/proposals/$(date -u +%F)-summary.md` (one new file for
   all of today's decisions; format and filter: `docs/backlog/PROPOSALS.md`: `size: S`, narrow `paths`, a `verify`
   and an `expect`; the description names the parked PR or issue and the DECISIONS.md bullet). Intake turns it into
   a task.
5. Comment on the parked PR or issue: `<!-- summary-decided -->` and one line: the choice and the proposal's title.

A question whose task the filter of `PROPOSALS.md` would refuse (a protected path, a migration) is listed for Aika,
not decided.

## 2. Write `docs/backlog/summaries/$DAY.md`

```markdown
# <DAY> — summary

**Остановленные запуски:** <every open issue labelled run-stopped or hold, one line each with its number — or
"нет". This line is always first: a run that stops at step 0 is otherwise invisible.>

**In one line:** <the day's result, e.g. "6 tasks merged, 1 parked, queue 31 left; nothing needs you urgently">

## Merged
- SB-0xx <title> (#n) — <one line: what is now true>

## Решено без тебя — посмотри после отпуска
- <task / PR>: <choice> — <how to undo> (every decision of the day in DECISIONS.md, by a run or by this summary)

## For you after the holiday (only what no run may decide: money, keys, settings, protected files, live calls)
1. …

## Open / stuck
- #n <branch>: <why>

## Numbers
Queue: <done>/<total>, ready <n>. Proposals: <in>/<out> (intake). CI on main: <green/red>.
```

Language: Russian (the headings above may be translated). At most ~60 lines. No praise, no filler, no guesses: if a number could not be obtained, write "unknown" and why.
No investment advice, no real orders, no secrets or account data.

```bash
git checkout -b claude/summary-$DAY
git add docs/backlog/summaries/$DAY.md DECISIONS.md docs/backlog/proposals/ && git commit -m "backlog: summary $DAY" && git push -u origin HEAD
gh api -X POST repos/Aika8/signal-bot/pulls -f title="Summary $DAY" -f head=claude/summary-$DAY -f base=main \
  -f body="Daily summary of the backlog run."
```

The PR may change only the summary file, `DECISIONS.md` and the new `docs/backlog/proposals/<date>-summary.md`
(`backlog.py check-paths` enforces it).

Label it `agent`. Use `gh api` only (`gh pr` does not work here). No AI attribution. Stop.

## Final message

The summary's "In one line".
