# Proposals: format and how they reach the queue

Proposal runs (none is scheduled for signal-bot yet; Aika may add one later) do not change the queue. Each writes its proposals of the day into a **new file**
`docs/backlog/proposals/<YYYY-MM-DD>-<role>.md` (role: `analyst` or `user`; the date is the UTC date of the run),
on a branch `claude/proposals-<role>-<YYYY-MM-DD>`, and opens a PR. Automerge merges such a PR without review when it
adds only that one file. (One file per run instead of one shared file: two runs a day appending to the same file
would conflict.)

Once a day the intake workflow (code, `scripts/backlog/backlog.py intake`) moves **at most 5** proposals of that
day into `QUEUE.md`, as tasks `AUTO-<YYYYMMDD>-<n>`. A proposal is taken only if all of these hold:

- `size: S` — one run can do it (≤ ~60 minutes, one PR, a few files);
- `paths`: narrow globs in backticks, none protected (CI, build files, CLAUDE.md, backlog rules,
  `adapter-broker-private`) and no Flyway migration or build configuration;
- `verify`: a command in backticks that proves it without a screen; `expect`: what it prints or what is true;
- a description of at most ~1500 tokens (6000 characters) that a fresh agent can act on alone;
- its title is not already a task title.

The rest waits for Aika; `docs/backlog/intake-log.md` says why for each. Proposals are taken in file order
(analyst first, then user; within a file, top to bottom), so put the most valuable first.

## Format

```markdown
# Proposals — 2026-12-03 — analyst

### Short imperative title
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/risk/Foo.java`, `application/src/test/java/io/github/signalbot/application/risk/FooEdgeTest.java`
- verify: `./gradlew :application:test --tests 'io.github.signalbot.application.risk.FooEdgeTest'`
- expect: green; the new test shows …
- why: the contradiction or gap found, with the file and line that show it

What to do, in plain words, self-contained: what to read first, what to change, what not to touch.

### Next proposal
…
```

Field order does not matter; a blank line ends the fields. Larger ideas (size M or L), questions for Aika and
things that need live data or a key go in the same file under `## For Aika`, as plain bullets with no `###` heading, so the
intake never picks them up.
