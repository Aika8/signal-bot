# Questions for Aika (not in the queue)

Written 2026-11-26 while filling the queue for 2–8 December. Each needs a decision that changes how a verdict becomes
a trade, a protected file, or money; no cloud run acts on them. Answer here or in `DECISIONS.md`.

1. **Event filter.** The November requirements ask for no entry (or a smaller size) before FOMC, CPI, NBK decisions
   and the company's earnings. That is a new trading rule (a `D-NNN` first): which events, how many days before,
   reject or reduce, and from which calendar (Finnhub earnings exists; macro dates do not)?
2. **Insider buys in the prompt.** SB-411 builds the line "Insider buys (SEC Form 4, last 30 days): …" but does not
   add it to `ClaudePromptBuilder`. Add it as context, or keep it in the lab only until H-001 is measured?
3. **Live EDGAR fetcher.** SEC asks automated clients for a User-Agent with a contact. Which contact (a generic
   project address, not a personal one?), and should the fetcher run in the worker at all?
4. **Lab label rule.** SB-515 uses: under 30 trades → candidate; |t| below the Bonferroni bar for the variants tried
   → no edge; above it and the holdout agrees → works. Keep these numbers?
5. **Lab as a Gradle module with a command** (`./gradlew lab --hypothesis H-001`) needs `settings.gradle.kts` and a
   build file — yours. Until then the lab is pure code in `application/lab` run from tests.
6. **Mutation testing with PIT** needs a Gradle plugin (build file). The queue does it by hand (SB-701…SB-717);
   add the plugin later?
7. **Debounce key.** `SignalDispatcher` debounces by symbol only, so news right after a price move on the same symbol
   is dropped (SB-229 pins it). By symbol and trigger type instead?
8. **GitHub Actions minutes.** The repository is private (2 000 free minutes a month). Hourly runs mean about one
   merged task per hour at best; each task costs roughly 5–10 minutes of Actions (CI on the PR and its fix rounds,
   automerge, CI on main, guard), plus intake and the hourly automerge schedule. A full week can come close to the
   limit; with the spending limit at $0 the workflows then stop and nothing merges. Check the usage page on
   3–4 December, or make the repository public (its history was checked for secrets on 2026-11-25).
9. **ATR plan depth.** The plan is a chain of up to eight steps (SB-001 → … → SB-017); many later tasks (journal,
   weekly review, mutation tests of the new classes) wait for it. If a step is parked, everything behind it waits:
   look at `parked` issues first when back.
