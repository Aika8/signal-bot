# The backlog run: cloud agents working through a queue

For the week Aika is away (2–8 December 2026) and later. Agents in Claude Code cloud sessions, started by
scheduled tasks (routines), take one small checkable task at a time from `QUEUE.md`, open a PR, an independent
run reviews it, and a GitHub Actions workflow — code, not an agent — merges it when every gate holds.
Nothing here costs money beyond the subscription: no API keys, no purchases, no sign-ups; Actions stay within the
free minutes. The same scheme runs in `Aika8/learning-exchange`; `scripts/backlog/backlog.py` is a copy of its
(DECISIONS.md, "Cloud run setup").

## Parts

| Part | File | Who changes it |
|---|---|---|
| The queue | `docs/backlog/QUEUE.md` | Aika by hand; the intake workflow appends |
| The rules as code | `scripts/backlog/backlog.py` (+ tests, run in CI) | Aika |
| CI | `.github/workflows/ci.yml` (`./gradlew build` + backlog tests) | Aika |
| Merge gate | `.github/workflows/automerge.yml` | Aika |
| Proposals → queue, ≤ 5 a day | `.github/workflows/intake.yml`, format in `PROPOSALS.md` | Aika |
| Off switch | `docs/backlog/PAUSE` (create it to stop everything) | Aika, also from the GitHub phone app |
| Guard against pushes that skip the gate | `.github/workflows/guard.yml`, active while `docs/backlog/UNATTENDED` exists | Aika |
| Run prompts | `docs/backlog/prompts/*.md` | Aika |
| Environment check | `scripts/cloud/setup-env.sh`, run by the SessionStart hook in `.claude/settings.json` | Aika |
| Reports of tasks | `docs/backlog/reports/*.md` | tasks that say so |
| Tech debt found outside a task | `docs/backlog/TECH-DEBT.md` (format), `docs/backlog/tech-debt/*.md` (one file each) | runs add, never change |
| Experience log, write-only | `docs/backlog/experience/*.md` — agents never read it | runs add; Aika reads |

The merge gate refuses any agent PR that changes a file in the "Aika" rows, `CLAUDE.md`, `.gitignore`, a Gradle
build file, `docker-compose.yml`, anything under `adapter-broker-private/` or `.env*`, an applied Flyway migration,
or an existing test (unless the task's `rewrites` names it) — see `PROTECTED`, `APPEND_ONLY` in `backlog.py`.

## Environment: nothing to configure on claude.ai

The cloud image has JDK 21. `.claude/settings.json` runs `scripts/cloud/setup-env.sh` as a SessionStart hook in
every cloud session (it does nothing locally): it checks JDK 21, points Gradle's toolchain at it (no foojay) and
fetches the Gradle 8.13 distribution once (services.gradle.org and Maven Central are on the default allowlist of
environment `Default`). Log: `/tmp/setup-env.log`. Docker is not needed: without it the two Testcontainers tests
skip themselves, and CI (which has Docker and fails on any skipped test) runs them.

## Runs

All runs: environment `Default`, model Opus 5.5 (never Sonnet), repository `Aika8/signal-bot`. The prompt of each
scheduled task is one line — the file holds the rest, so changing a prompt is a commit, not a click:

> Read `docs/backlog/prompts/<role>.md` in this repository and follow it exactly. You start from nothing: that
> file and the files it names are all your instructions.

| Run | Prompt file | When (UTC; Almaty = UTC+5) | Writes |
|---|---|---|---|
| Executor | `prompts/executor.md` | hourly at :12 (frequency set after the trial measurement) | branch `claude/<task id>`, a PR |
| Reviewer | `prompts/reviewer.md` | hourly at :42 | a review comment and a label on one PR |
| Intake | `.github/workflows/intake.yml` (code) | daily 21:50 | commit to `main`: QUEUE.md, intake-log.md |
| Automerge | `.github/workflows/automerge.yml` (code) | after every CI run and hourly at :41 | merges |

No analyst, user or summary run is set up for signal-bot: the queue (8 test tasks + 17 ATR-plan tasks) is enough
for the week, and intake has nothing to move until a proposals file appears.

A run that has nothing to do stops in its first minute (`backlog.py plan` / `review-queue` say so).

## Life of a task

1. Executor: `python3 scripts/backlog/backlog.py plan` names the move. Open PRs come first (fix, rebase, park);
   at most 3 agent PRs are open at once; otherwise the next ready task in file order (`NEW SB-0xx`).
2. Executor works on branch `claude/sb-0xx`, only inside the task's `paths` (+ `DECISIONS.md`), runs the task's
   `verify` and `./gradlew build`, checks paths with `backlog.py check-paths`, pushes, opens the PR with `gh api`
   (REST; `gh pr` uses GraphQL, which the cloud proxy refuses) and labels it `agent`.
3. CI runs `./gradlew build` with Docker and the backlog tests.
4. Reviewer (another run, no shared context): when CI is green, answers yes/no questions about the diff against
   the task. Pass → label `review:pass`. Fail → label `needs-fix` and the questions.
5. Executor of a later hour answers `needs-fix` or a red CI: at most 2 fix rounds, then the PR gets `parked` and
   waits for Aika. A pushed fix removes `review:pass` (automerge.yml), so the reviewer looks again.
6. Automerge: PAUSE absent, branch and paths allowed, CI green on the head, branch up to date, and for a task:
   `review:pass` plus a reviewer marker `verdict=pass sha=<that very head>` → squash merge, then CI on `main`.
   A task is *done* when its PR is merged.

Labels: `agent`, `review:pass`, `needs-fix`, `needs-rebase`, `parked` (waits for Aika), `blocked-paths` (touched
a forbidden path; waits for Aika), `hold` (Aika's: do not touch).

## Rule for every run: do not wait for Aika

Aika is away and answers nothing. When a run meets a question the task and the files do not answer:

1. **Look for the answer first**: the task, `DECISIONS.md` (D-001…D-005 decide most ATR questions), `README.md`,
   the code and its tests.
2. **Cheap to undo → decide and go on.** Take the option that is easiest to undo later (smaller change, behind a
   name that is easy to find, no new dependency). Write it in `DECISIONS.md` under the heading
   `## Backlog run (December 2026)` as one bullet: the task id, the question, the choice, the options not taken
   and why — so Aika can reverse it in a minute. Never add or change a `D-NNN` entry: those are Aika's.
3. **Costly to undo → park.** Costly means any of: a new dependency; a database change other than the task's new
   migration; a changed starting value or a new trading rule (D-005); anything touching money flow that
   `DECISIONS.md` does not decide; deleting or renaming public things. Then do not do it: comment the question with
   2–3 options and a recommendation on the PR (or open an issue `[parked] <task id>: <question>` with the label
   `parked` when there is no PR), and stop. The next run takes the next task.

Every run also: works at most 50 minutes (then WIP commit and park); parks instead of retrying when the same error
comes back or a failure is unrelated to the task (infrastructure, network); refuses an incomplete or contradictory
task by parking it ("missing: …" / "contradicts: …"); records problems outside its task in `docs/backlog/tech-debt/`
instead of fixing them; adds a write-only note to `docs/backlog/experience/`.

Never: ask in the chat and wait (nobody answers), weaken or delete a test to get green, touch a protected path,
read `.env`, call a live API, spend money, sign up anywhere, give investment advice.

## Off switch

Create `docs/backlog/PAUSE` on `main` (GitHub app → repository → Add file; any content). Every run stops at its
first step, automerge merges nothing, intake moves nothing. Delete the file to resume. To stop the runs
themselves, pause the scheduled tasks at claude.ai/code/routines.

## Guard (no branch protection on this plan)

`main` cannot be protected on a private repo of the free plan, and the cloud token can push and merge. So:

- While `docs/backlog/UNATTENDED` exists on `main`, `guard.yml` pauses the whole run (creates `PAUSE`, opens an
  issue labelled `hold`) on **any** push to `main` that did not come through automerge or intake (those use
  `GITHUB_TOKEN`, whose pushes start no workflow). Let through: creating `PAUSE`, creating `UNATTENDED`, and
  deleting `PAUSE` while no `[guard]` issue is open.
- Aika creates `UNATTENDED` on 1 December, after the setup is merged. When back she deletes it — that pauses too;
  then she closes the guard issue and deletes `PAUSE`.
- GitHub → Settings → Actions → General → Workflow permissions must be **Read repository contents** (the default
  for new repositories): then a workflow an agent adds on its branch gets no write token.
- What is left: an agent could label its own PR `review:pass` and write a fake reviewer marker. The prompts
  forbid it, the gate still needs green CI and allowed paths — read the reviews of merged PRs first when back.

## Money and limits

- Claude: subscription only, extra usage off. No API keys exist in the environment.
- GitHub Actions: CI ≈ 3–5 min per run (Gradle + Testcontainers), automerge and intake ≈ 1 min each. The Actions
  spending limit must be $0 (GitHub → Settings → Billing).
- Network: environment `Default`, Limited (package registries and GitHub only) — Alpaca, Finnhub, Telegram and
  the Anthropic API are not reachable anyway, which is what we want.

## Trial before leaving

1. Start one cloud session in `Default`: `/tmp/setup-env.log` shows JDK 21 and Gradle; `./gradlew build` is green
   (the two Testcontainers tests skipped).
2. Run the executor once by hand (Run now): it opens a PR with `gh api` without asking anybody.
3. Run the reviewer once by hand on that PR; automerge merges it after the pass.
