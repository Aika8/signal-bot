"""signal-bot: a summary PR may also record decisions it took for parked questions (DECISIONS.md) and propose the
task that carries them out (a new proposals file); nothing else."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backlog as b  # noqa: E402

BR = "claude/summary-2026-12-03"


class SummaryDecidesTest(unittest.TestCase):
    def check(self, *changes):
        return b.check_paths(BR, [b.Change(*c) for c in changes], [])

    def test_summary_decision_and_proposal_are_allowed(self):
        self.assertEqual(self.check(("added", "docs/backlog/summaries/2026-12-03.md"), ("modified", "DECISIONS.md"),
                                    ("added", "docs/backlog/proposals/2026-12-04-summary.md")), [])

    def test_anything_else_is_refused(self):
        for c in [("modified", "docs/backlog/proposals/2026-12-04-summary.md"),
                  ("added", "docs/backlog/proposals/2026-12-04-analyst.md"),
                  ("removed", "DECISIONS.md"), ("added", "README.md"),
                  ("modified", "docs/backlog/QUEUE.md")]:
            self.assertTrue(self.check(("added", "docs/backlog/summaries/2026-12-03.md"), c), c)


if __name__ == "__main__":
    unittest.main()
