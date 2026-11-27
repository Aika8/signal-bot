"""signal-bot: the summary run decides parked questions by proposing the task that carries the choice out
(`replaces: <parked id>`); the summary PR adds only its summary and that proposals file, and a done replacement
unblocks the parked task's dependents."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backlog as b  # noqa: E402

BR = "claude/summary-2026-12-03"
QUEUE = """# Queue

### SB-034 — Parked one
- status: todo
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/trigger/XTest.java`
- verify: `./gradlew build`
- expect: green

Do it.

### SB-035 — Waits for the parked one
- status: todo
- depends: SB-034
- size: S
- paths: `adapters/src/test/java/io/github/signalbot/trigger/YTest.java`
- verify: `./gradlew build`
- expect: green

Do it.
"""


class SummaryPathsTest(unittest.TestCase):
    def check(self, *changes):
        return b.check_paths(BR, [b.Change(*c) for c in changes], [])

    def test_summary_and_its_proposal_are_allowed(self):
        self.assertEqual(self.check(("added", "docs/backlog/summaries/2026-12-03.md"),
                                    ("added", "docs/backlog/proposals/2026-12-04-summary.md")), [])

    def test_anything_else_is_refused(self):
        for c in [("modified", "DECISIONS.md"), ("modified", "docs/backlog/proposals/2026-12-04-summary.md"),
                  ("added", "docs/backlog/proposals/2026-12-04-analyst.md"), ("added", "README.md"),
                  ("modified", "docs/backlog/QUEUE.md")]:
            self.assertTrue(self.check(("added", "docs/backlog/summaries/2026-12-03.md"), c), c)


class ReplacesTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "docs/backlog/proposals").mkdir(parents=True)
        (self.root / b.QUEUE).write_text(QUEUE, encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.root)

    def proposal(self, replaces):
        return (f"### Carry out the decision for SB-034\n- size: S\n- replaces: {replaces}\n"
                "- paths: `adapters/src/test/java/io/github/signalbot/trigger/ZTest.java`\n"
                "- verify: `./gradlew build`\n- expect: green\n\nDecided by the summary.\n")

    def test_intake_copies_replaces_and_a_done_replacement_unblocks(self):
        (self.root / "docs/backlog/proposals/2026-12-04-summary.md").write_text(self.proposal("SB-034"))
        acc, rej = b.intake("2026-12-04", self.root)
        self.assertEqual((len(acc), rej), (1, []))
        tasks = b.parse_tasks((self.root / b.QUEUE).read_text())
        self.assertEqual(b.lint(tasks), [])
        auto = tasks[-1]
        self.assertEqual(auto.replaces, "SB-034")
        state = {"prs": [{"number": 1, "head": "claude/sb-034", "state": "open", "merged": False,
                          "labels": ["parked"], "draft": False}], "branches": {}, "parked_issues": []}
        self.assertEqual(b.task_states(tasks, state)["SB-035"], "blocked")
        state["prs"].append({"number": 2, "head": auto.branch, "state": "closed", "merged": True,
                             "labels": [], "draft": False})
        st = b.task_states(tasks, state)
        self.assertEqual((st["SB-034"], st["SB-035"]), ("done", "ready"))

    def test_unknown_replaces_is_refused(self):
        (self.root / "docs/backlog/proposals/2026-12-04-summary.md").write_text(self.proposal("SB-999"))
        acc, rej = b.intake("2026-12-04", self.root)
        self.assertEqual(acc, [])
        self.assertIn("replaces unknown", rej[0])


if __name__ == "__main__":
    unittest.main()
