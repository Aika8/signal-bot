"""Tests of what signal-bot's copy of backlog.py adds to learning-exchange's (test_backlog.py is the shared file):
the signal-bot protected paths, Flyway migrations as append-only, and the task field `rewrites`."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backlog as b  # noqa: E402

QUEUE = """# Queue

### SB-001 — Change a pinned behaviour
- status: todo
- size: S
- paths: `application/src/main/java/io/github/signalbot/application/trigger/**`, `application/src/test/java/io/github/signalbot/application/trigger/**`
- rewrites: `application/src/test/java/io/github/signalbot/application/trigger/ExitGuardTest.java`
- verify: `./gradlew build`
- expect: green

Do it.

### SB-002 — No rewrites
- status: todo
- size: S
- paths: `application/src/test/java/io/github/signalbot/application/trigger/**`, `adapters/src/main/resources/db/migration/**`
- verify: `./gradlew build`
- expect: green

Do it.
"""

GUARD_TEST = "application/src/test/java/io/github/signalbot/application/trigger/ExitGuardTest.java"
OTHER_TEST = "application/src/test/java/io/github/signalbot/application/trigger/DebouncerTest.java"


class SignalBotRulesTest(unittest.TestCase):
    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)

    def check(self, branch, *changes):
        return b.check_paths(branch, [b.Change(*c) for c in changes], self.tasks)

    def test_queue_is_valid(self):
        self.assertEqual(b.lint(self.tasks), [])
        self.assertEqual(self.tasks[0].rewrites, [GUARD_TEST])

    def test_signal_bot_paths_are_protected(self):
        for path in ["adapter-broker-private/src/Main.java", "portfolio.yml", ".env", ".env.local", ".gitignore",
                     "settings.gradle.kts", "build.gradle.kts", "adapters/build.gradle.kts", "docker-compose.yml",
                     ".github/workflows/ci.yml", "CLAUDE.md", "scripts/backlog/backlog.py",
                     "scripts/cloud/setup-env.sh", "docs/backlog/QUEUE.md", ".claude/settings.json"]:
            self.assertTrue(b.matches(path, b.PROTECTED), path)

    def test_an_applied_migration_is_never_edited(self):
        p = self.check("claude/sb-002", ("modified", "adapters/src/main/resources/db/migration/V6__x.sql"))
        self.assertTrue(any("existing test or quality gate" in x for x in p), p)
        self.assertEqual(self.check("claude/sb-002", ("added", "adapters/src/main/resources/db/migration/V7__y.sql")), [])

    def test_rewrites_lets_only_the_named_test_be_modified(self):
        self.assertEqual(self.check("claude/sb-001", ("modified", GUARD_TEST)), [])
        self.assertTrue(self.check("claude/sb-001", ("modified", OTHER_TEST)))
        self.assertTrue(self.check("claude/sb-001", ("removed", GUARD_TEST)))
        self.assertTrue(self.check("claude/sb-002", ("modified", GUARD_TEST)))

    def test_lint_rejects_bad_rewrites(self):
        bad = b.parse_tasks(QUEUE.replace(f"- rewrites: `{GUARD_TEST}`",
                                          "- rewrites: `application/src/test/**`, `adapters/src/test/java/X.java`"))
        errs = "\n".join(b.lint(bad))
        self.assertIn("rewrites application/src/test/** must name one existing test file", errs)
        self.assertIn("rewrites adapters/src/test/java/X.java is not inside the task's paths", errs)

    def test_intake_never_copies_rewrites(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "docs/backlog/proposals").mkdir(parents=True)
            (root / b.QUEUE).write_text(QUEUE, encoding="utf-8")
            (root / "docs/backlog/proposals/2026-12-03-analyst.md").write_text(
                "# P\n\n### Pin something\n- size: S\n"
                "- paths: `application/src/test/java/io/github/signalbot/application/x/NewTest.java`\n"
                f"- rewrites: `{GUARD_TEST}`\n- verify: `./gradlew build`\n- expect: green\n\nDo it.\n",
                encoding="utf-8")
            acc, rej = b.intake("2026-12-03", root)
            self.assertEqual(len(acc), 1, rej)
            added = b.parse_tasks((root / b.QUEUE).read_text(encoding="utf-8"))[-1]
            self.assertEqual(added.rewrites, [])

    def test_build_files_never_come_through_intake(self):
        p = b.parse_proposals("### X\n- size: S\n- paths: `adapters/build.gradle.kts`\n- verify: `x`\n- expect: y\n\nd\n")[0]
        self.assertTrue(any("protected" in x for x in b.proposal_problems(p, self.tasks)))


if __name__ == "__main__":
    unittest.main()
