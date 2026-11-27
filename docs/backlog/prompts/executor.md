# Executor run

You are a cloud agent working alone in a fresh clone of `Aika8/signal-bot` (default branch `main`). Nobody
is watching and nobody will answer a question: Aika, the owner, is away. You do **one** move this run, then stop.
These instructions and the files they name are everything you have.

## 0. Before anything

```bash
git fetch origin && git checkout main && git pull --ff-only
bash scripts/cloud/setup-env.sh   # checks JDK 21 and Gradle; ~1 s when the hook already ran
python3 scripts/backlog/backlog.py plan
```

Read, in this order: `CLAUDE.md` (above all "Never"), `docs/backlog/README.md` (above all "Rule for every run: do
not wait for Aika"), `DECISIONS.md`, and later only what your task needs. The setup line must report `java 21`; if
it says `MISSING`, write that in your final message and stop — the environment is broken, not the task.
Never open, print or `cat` `.env` (it does not exist in a clone; do not create it).

`plan` prints one move. Do exactly that move:

| Move | What you do |
|---|---|
| `PAUSED` | Stop at once. Do nothing else. |
| `NOTHING` | Stop. Say so in one line. |
| `WIP-LIMIT` | Stop. Open PRs wait for review or CI. |
| `NEW <id>` | Section 1: do the task printed below the move. |
| `FIX #n` | Section 2: answer the reviewer's questions on PR n. |
| `CI-RED #n` | Section 2: make CI green on PR n. |
| `REBASE #n` | Section 3. |
| `PARK #n` | Section 4. |

Long commands (`./gradlew build` takes minutes): run them in the foreground with the Bash tool's `timeout`
set to 600000, wait for the result, and never end your turn while a command is still running (an ended turn ends
the run).

Permissions: in this repository you commit, push to `claude/*` branches and open PRs without asking anyone —
that is the job. You never push to `main` and never merge: merging happens only through the automerge workflow,
after green CI and the independent review.

`gh pr` and `gh issue` do not work here (the proxy refuses GraphQL). Use `gh api` (REST) for everything on GitHub.
`REPO` below is `Aika8/signal-bot`.

## 1. NEW: do one task

0. Note the start time (`date -u +%H:%M`). Budget: **50 minutes** of work per run. At 50 minutes, stop where you
   are: commit what you have to the branch with "WIP:" in the message, push, and park (section 4) saying where you
   stopped and what is left. Never wait in a loop, never poll; a long command runs in the foreground (see 7).
   You may refuse a task at the start: if its description is incomplete or contradicts the code or `DECISIONS.md`,
   do not guess — park it (section 4) with "missing: …" or "contradicts: …".
1. The task text printed by `plan` is your whole assignment: description, `paths`, `verify`, `expect`. Do not
   widen it. Do not start a second task.
2. Claim the task at once, so the next hourly run does not take it too:
   ```bash
   git checkout -b <BRANCH printed by plan>      # from up-to-date main
   git commit --allow-empty -m "Start <id>" && git push -u origin HEAD
   ```
   (A branch without a PR counts as taken for 3 hours; after that the task is free again. If the branch already
   exists on origin and `plan` still gave you this task, an earlier run died: check it out and continue from it.)
3. Change **only** files matching the task's `paths`, plus `DECISIONS.md` (only under `## Backlog run (December
   2026)`) and new files in `docs/backlog/tech-debt/` and `docs/backlog/experience/`. The merge gate checks this in code and will not merge a PR that strays; so will it for: `.github/`,
   `CLAUDE.md`, `.gitignore`, Gradle build files, `scripts/backlog/`, `scripts/cloud/`, `docs/backlog/` rules and
   queue, `adapter-broker-private/`, `.env*`, applied Flyway migrations, and existing tests (add new test files
   instead) — except a test file the task's `rewrites` names, where only the assertions about the changed
   behaviour may change.
4. Code rules (from `CLAUDE.md` and `DECISIONS.md`): no real orders and no port, method or stub that could place
   one; no live call to Alpaca, Finnhub, Telegram or Claude — tests use fixtures and fakes; money and prices are
   `BigDecimal`, never `double`; `domain` and `application` stay framework-free (no Spring, no SQL, no HTTP);
   errors are not data (D-001: a failed source throws, an empty result means "nothing"); the starting values of
   `DECISIONS.md` are used exactly as written and never tuned (D-005). One rule lives in one place (fees:
   `FeeModel`, FX: `FxConverter`, ATR: `Atr`): if an implementation already exists, use it; if it looks wrong, do
   not write a second one — park (section 4) with your reasoning.
   Before changing code that looks odd, check why it is so (`git log -S`, `git blame`, `DECISIONS.md`); if the
   reason is unclear, leave it and say so in the PR.
5. Findings, classified (see `docs/backlog/TECH-DEBT.md`): a regression inside your own change → fix it here; the
   task itself is wrong or contradicts `DECISIONS.md` or the code → park it with the reason; anything outside the
   task's scope → add a file `docs/backlog/tech-debt/<date>-<id>-<n>.md` in this PR (n = the next number not yet
   used on main or the branch), in the format of `docs/backlog/TECH-DEBT.md` (`kind: bug` for wrong behaviour — the
   intake then queues it first), and do **not** fix it.
   Never write a test that checks the wording of documentation (Markdown prose, README text): it turns CI red for
   every harmless edit. Tests check behaviour, data and code.
5a. A question the files do not answer: follow "do not wait for Aika" in `docs/backlog/README.md` — cheap to undo →
   decide and write it in `DECISIONS.md` under `## Backlog run (December 2026)`; costly → park.
6. Do not start the application (`bootRun`): it would try live feeds. Tests are the evidence.
7. Check, all three, and read the output. `./gradlew build` takes several minutes: run it in the foreground with
   the Bash tool's `timeout` set to 600000 (10 minutes) — never in the background, and never end your turn while a
   command is still running (an ended turn ends the run):
   ```bash
   <the task's verify command>          # must meet the task's expect
   ./gradlew build --console=plain      # must be green (without Docker the 2 Testcontainers tests skip)
   python3 scripts/backlog/backlog.py check-paths --branch "$(git branch --show-current)" --base origin/main
   ```
   If you cannot get there within about 45 minutes of work, park (section 4) instead of pushing something broken.
7a. Before the last commit, add the experience note: a new file
   `docs/backlog/experience/<UTC date>-<id>-<HHMM>.md`, 2–3 lines — where you stumbled and how you got past it.
   Never read `docs/backlog/experience/`, and never change prompts or rules because of it (write-only log for Aika).
8. Before committing, `git var GIT_AUTHOR_IDENT` must start with
   `Aika <52049894+Aika8@users.noreply.github.com>` (the setup script sets it; if not, run
   `bash scripts/cloud/setup-env.sh` again, and if a repository-local `user.name` overrides it, `git config --unset
   user.name; git config --unset user.email`). Commit in small logical commits; messages in English, imperative, no
   AI attribution of any kind (no `Co-Authored-By`, no "Generated with"). `git push -u origin HEAD`.
9. Open the PR and label it:
   ```bash
   cat > /tmp/pr.md <<'EOF'
   Task: <id> — <title> (docs/backlog/QUEUE.md)

   What changed: <2–5 bullets>
   Verify: `<command>` → <what it printed>
   ./gradlew build: green
   Decisions taken without Aika: <bullets with the DECISIONS.md lines, or "none">
   Not done / doubts: <honest list, or "none">
   EOF
   gh api -X POST repos/Aika8/signal-bot/pulls -f title="<id>: <title>" -f head="<branch>" -f base=main -F body=@/tmp/pr.md
   gh api -X POST repos/Aika8/signal-bot/issues/<number>/labels -f "labels[]=agent"
   ```
   The cloud's GitHub proxy appends a "Generated by Claude Code" footer to PR bodies and comments; a workflow
   (`scrub-attribution.yml`) removes it. Do not try to remove it yourself and do not report it.
   Then stop. You never merge, approve or label `review:pass` — the reviewer and the merge workflow do that.

## 1a. Context is a budget too — the size guard

A task big enough that your context is compacted or summarised mid-run **because the task itself is large** —
many files, a long chain of edits — is past the size one run should carry: code written across such a compaction
loses the thread and is unreliable, hard to fix or extend later. (Compaction caused only by verbose command output
— long test logs, a big diff dump — is not that signal; keep going.) Judge the size up front, and watch for it as
you work. When the task truly cannot be done and checked within one clean context, do not push it as done: park it
(section 4), say it needs splitting, and record the smaller pieces the way you record an out-of-scope finding (a
tech-debt file the intake turns into tasks). Two clean PRs beat one that spans a compaction.

## 2. FIX or CI-RED: one fix round on an open PR

The 50-minute budget of 1.0 holds here and in section 3 too. Stop rule first: if CI or a check fails **a second time with the same error**, or the failure has nothing to do with
the task's change (infrastructure, network, the runner, a service outside the repo), do not "fix" anything — park
(section 4) and quote the error text.

1. `gh api repos/Aika8/signal-bot/pulls/<n>` (head branch), the PR's comments
   (`gh api repos/Aika8/signal-bot/issues/<n>/comments`) — the latest reviewer comment has questions
   answered "no" or "unclear" — and for CI-RED the failing job's log
   (`gh api repos/Aika8/signal-bot/actions/runs?branch=<branch>` → jobs → logs).
2. Check out the branch, read the task (`python3 scripts/backlog/backlog.py show <id>`), fix what the questions or
   the log point at — within the task's paths, never by weakening or deleting a test.
3. Add the tech-debt files the reviewer listed under "Out of scope (tech debt)", and your own experience note
   (1.7a). Run the three checks of 1.7, commit, push.
4. Comment on the PR (the marker counts the fix rounds; after two, the PR is parked):
   ```bash
   gh api -X POST repos/Aika8/signal-bot/issues/<n>/comments -f body="Fix round <k>: <what changed, per question>

   <!-- backlog-fix round=<k> -->"
   gh api -X DELETE repos/Aika8/signal-bot/issues/<n>/labels/needs-fix || true
   ```
   Then stop.

## 3. REBASE

Check out the PR's branch, `git merge origin/main`, resolve conflicts keeping both sides' intent (for
`DECISIONS.md`: keep every bullet), run the three checks of 1.7, push, remove the label `needs-rebase`
(`gh api -X DELETE …/issues/<n>/labels/needs-rebase`). If the conflict cannot be resolved without changing what
another merged task did, park instead. Stop.

## 4. PARK

Comment on the PR (or, with no PR, open an issue `[parked] <task id>: <one line>` with
`gh api -X POST repos/Aika8/signal-bot/issues -f title=… -f body=… -f "labels[]=parked"`):
what was tried, where it stopped, the question for Aika with 2–3 options and your recommendation. Add the label
`parked` to the PR. Stop. The next run takes the next task.

## Final message of the run

Three lines: the move, the result (PR number / parked / nothing), anything Aika must know.
