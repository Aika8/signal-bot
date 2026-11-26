"""signal-bot's intake: no daily cap, and several runs a day neither re-log nor re-commit what they already saw."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backlog as b  # noqa: E402

QUEUE = "# Queue\n\n### SB-001 — Existing\n- status: todo\n- size: S\n- paths: `domain/src/main/java/X.java`\n" \
        "- verify: `./gradlew build`\n- expect: green\n\nDo it.\n"


def proposal(title, size="S"):
    return (f"### {title}\n- size: {size}\n- paths: `application/src/test/java/io/github/signalbot/x/{title.replace(' ', '')}Test.java`\n"
            f"- verify: `./gradlew :application:test`\n- expect: green\n\nWhat and why.\n\n")


class IntakeNoCapTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "docs/backlog/proposals").mkdir(parents=True)
        (self.root / b.QUEUE).write_text(QUEUE, encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.root)

    def log(self):
        return (self.root / b.INTAKE_LOG).read_text(encoding="utf-8")

    def test_twelve_good_proposals_all_become_tasks(self):
        (self.root / "docs/backlog/proposals/2026-12-03-analyst.md").write_text(
            "".join(proposal(f"Pin thing {i}") for i in range(12)), encoding="utf-8")
        acc, rej = b.intake("2026-12-03", self.root)
        self.assertEqual((len(acc), rej), (12, []))
        tasks = b.parse_tasks((self.root / b.QUEUE).read_text(encoding="utf-8"))
        self.assertEqual(b.lint(tasks), [])
        self.assertEqual(tasks[-1].id, "AUTO-20261203-12")

    def test_a_later_run_logs_only_what_is_new(self):
        f = self.root / "docs/backlog/proposals/2026-12-03-analyst.md"
        f.write_text(proposal("Good one") + proposal("Too big", size="M"), encoding="utf-8")
        b.intake("2026-12-03", self.root)
        first = self.log()
        self.assertEqual(first.count("Too big"), 1)
        # a second run with nothing new changes nothing (so the workflow commits nothing)
        b.intake("2026-12-03", self.root)
        self.assertEqual(self.log(), first)
        # a proposal merged later the same day is appended; numbering continues
        (self.root / "docs/backlog/proposals/2026-12-03-user.md").write_text(proposal("Later one"), encoding="utf-8")
        acc, _ = b.intake("2026-12-03", self.root)
        self.assertEqual(len(acc), 1)
        self.assertIn("AUTO-20261203-2", acc[0])
        log = self.log()
        self.assertEqual(log.count("Too big"), 1)
        self.assertEqual(log.count("## 2026-12-03"), 1)
        self.assertIn("Later one", log)


if __name__ == "__main__":
    unittest.main()
