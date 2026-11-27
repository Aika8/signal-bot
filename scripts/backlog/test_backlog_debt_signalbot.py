"""signal-bot's intake with the tech-debt sources: no cap, a rejected item seen on every run is logged once,
`replaces` is copied, and the yesterday pass (`debt=False`) does not read tech debt."""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backlog as b  # noqa: E402

QUEUE = "# Queue\n\n### SB-001 — Existing\n- status: todo\n- size: S\n- paths: `domain/src/main/java/X.java`\n" \
        "- verify: `./gradlew build`\n- expect: green\n\nDo it.\n"


def item(title, kind="debt", paths="`application/src/test/java/io/github/signalbot/x/FooTest.java`", extra=""):
    return (f"### {title}\n- kind: {kind}\n- size: S\n- paths: {paths}\n- verify: `./gradlew build`\n"
            f"- expect: green\n{extra}- why: found by a run\n\nWhat to do.\n\n")


class DebtIntakeSignalBotTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "docs/backlog/proposals").mkdir(parents=True)
        (self.root / "docs/backlog/tech-debt").mkdir(parents=True)
        (self.root / b.QUEUE).write_text(QUEUE, encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.root)

    def log(self):
        return (self.root / b.INTAKE_LOG).read_text(encoding="utf-8")

    def test_rejected_debt_is_logged_once_and_good_debt_has_no_cap(self):
        good = "".join(item(f"Fix thing {i}", paths=f"`application/src/test/java/io/github/signalbot/x/F{i}Test.java`")
                       for i in range(8))
        (self.root / "docs/backlog/tech-debt/2026-12-03-SB-031-1.md").write_text(good, encoding="utf-8")
        (self.root / "docs/backlog/tech-debt/2026-12-03-SB-031-2.md").write_text(
            item("Touch CI", paths="`.github/workflows/ci.yml`"), encoding="utf-8")
        issues = [{"number": 77, "body": item("Bad size", extra="").replace("- size: S", "- size: M"),
                   "author_association": "OWNER"}]
        acc, rej = b.intake("2026-12-03", self.root, issues)
        self.assertEqual((len(acc), len(rej)), (8, 2))
        first = self.log()
        for _ in range(3):  # every later run sees the same rejected items again
            b.intake("2026-12-03", self.root, issues)
        self.assertEqual(self.log(), first)
        self.assertEqual(first.count("Touch CI"), 1)
        self.assertEqual(first.count("Bad size"), 1)

    def test_replaces_is_copied_from_an_issue_and_bug_gets_priority(self):
        issues = [{"number": 78, "body": item("Do the decided thing", kind="bug", extra="- replaces: SB-001\n"),
                   "author_association": "OWNER"}]
        acc, _ = b.intake("2026-12-03", self.root, issues)
        self.assertEqual(len(acc), 1)
        t = b.parse_tasks((self.root / b.QUEUE).read_text(encoding="utf-8"))[-1]
        self.assertEqual((t.replaces, t.fields.get("priority")), ("SB-001", "bug"))
        self.assertEqual(b.issues_to_close(issues, b.parse_tasks((self.root / b.QUEUE).read_text())),
                         [(78, [t.id])])

    def test_the_yesterday_pass_reads_no_tech_debt(self):
        (self.root / "docs/backlog/tech-debt/2026-12-03-SB-031-1.md").write_text(item("Fix it"), encoding="utf-8")
        issues = [{"number": 79, "body": item("Fix that"), "author_association": "OWNER"}]
        self.assertEqual(b.intake("2026-12-02", self.root, issues, debt=False), ([], []))
        acc, _ = b.intake("2026-12-03", self.root, issues)
        self.assertEqual(len(acc), 2)
        self.assertTrue(all("AUTO-20261203-" in a for a in acc))


if __name__ == "__main__":
    unittest.main()
