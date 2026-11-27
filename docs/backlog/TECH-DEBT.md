# Tech-debt register

Problems a run found **outside its own task**. The run does not fix them; it records them and goes on. The intake
turns each into a task by itself (behaviour bugs first) — nothing waits for Aika.

Each finding is its own file, so parallel PRs never conflict: `docs/backlog/tech-debt/<YYYY-MM-DD>-<task id>-<n>.md`,
added in the PR of the task during which it was found. It is written **as a proposal** (`docs/backlog/PROPOSALS.md`),
so the intake turns it into a task by itself. `kind` is `bug` (wrong behaviour: the task gets `priority: bug` and is
picked before any other ready task) or `debt` (everything else):

```markdown
### <short imperative title of the fix>
- kind: bug
- size: S
- paths: `the/file/to/Fix.java`, `the/new/test/FixTest.java`
- verify: `<command>`
- expect: <what proves the fix; for a bug: the new test failed before the fix>
- why: `path/to/file:line` — what is wrong and why it matters; found by <task id>, <executor|reviewer>, <date>

<what to do, in plain words, for a fresh agent>
```

A finding that cannot be a small checkable task (a protected file, a migration, a money or trading-rule question)
is still written in this format; the intake logs why it was not taken (`docs/backlog/intake-log.md`). Files and open
`tech-debt` issues written before 2026-11-27 in the old `# …` format are not read by the intake and stay open; their
findings were queued by hand (QUEUE.md, SB-031…SB-040 and SB-951…SB-958).

The reviewer does not commit: it lists such findings in its review comment under "Out of scope (tech debt)"; the
executor of the next fix round of that PR adds them as files, and when the review passes (no fix round) the reviewer
opens one GitHub issue per finding titled `[tech-debt] …` with the label `tech-debt`, its body in the same format.
The intake reads open `tech-debt` issues by the owner or collaborators and closes each once all its proposals are
tasks. The merge gate allows adding these files to any task PR
and refuses changing or deleting existing ones.

How a finding is classified (all runs):

| Finding | What to do |
|---|---|
| A regression inside the task's own change | fix it in this PR |
| The task itself is wrong or contradicts `DECISIONS.md` or the code | park the task with the reason |
| Anything else (outside the task's scope) | a tech-debt file; do not fix |
