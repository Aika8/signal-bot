"""Tests of the backlog rules (scripts/backlog/backlog.py). Run: python3 -m unittest discover -s scripts/backlog"""
import shutil
import tempfile
import unittest
from pathlib import Path

import backlog as b

QUEUE = """# Queue

## Engine

### LE-001 — First
- status: todo
- size: S
- paths: `backend/engine/src/test/java/engine/model/**`, `backend/engine/src/main/java/engine/Foo.java`
- verify: `./gradlew :backend:engine:test`
- expect: green

Do the first thing.

### LE-002 — Second
- status: todo
- depends: LE-001
- size: S
- paths: `clients/mobile/src/**`
- verify: `cd clients/mobile && npm test`
- expect: green

Second.

### LE-003 — Waits for Aika
- status: waiting
- waits: the reference book
- size: S
- paths: `backend/engine/src/test/java/engine/ref/**`
- verify: `./gradlew :backend:engine:test`
- expect: green

Third.
"""


def state(*prs, parked=()):
    return {"prs": [dict(number=i + 1, head=h, state=s, merged=m, labels=list(l), draft=False)
                    for i, (h, s, m, l) in enumerate(prs)], "parked_issues": list(parked)}


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)

    def test_parse(self):
        self.assertEqual([t.id for t in self.tasks], ["LE-001", "LE-002", "LE-003"])
        self.assertEqual(self.tasks[1].depends, ["LE-001"])
        self.assertEqual(self.tasks[0].paths[1], "backend/engine/src/main/java/engine/Foo.java")
        self.assertIn("Do the first thing.", self.tasks[0].description)
        self.assertEqual(self.tasks[0].branch, "claude/le-001")

    def test_lint_ok_and_errors(self):
        self.assertEqual(b.lint(self.tasks), [])
        bad = b.parse_tasks(QUEUE.replace("- verify: `./gradlew :backend:engine:test`\n", "", 1)
                            .replace("`clients/mobile/src/**`", "`Makefile`"))
        errs = "\n".join(b.lint(bad))
        self.assertIn("LE-001 (line 5): missing 'verify'", errs)
        self.assertIn("Makefile is protected", errs)

    def test_lint_rejects_long_description(self):
        long = b.parse_tasks(QUEUE.replace("Do the first thing.", "x" * 7000))
        self.assertTrue(any("chars >" in e for e in b.lint(long)))

    def test_next_respects_depends_open_and_merged(self):
        self.assertEqual(b.next_task(self.tasks, state()).id, "LE-001")
        self.assertIsNone(b.next_task(self.tasks, state(("claude/le-001", "open", False, ()))))
        self.assertEqual(b.next_task(self.tasks, state(("claude/le-001", "closed", True, ()))).id, "LE-002")

    def test_parked_tasks_are_skipped_but_do_not_count_as_done(self):
        st = b.task_states(self.tasks, state(("claude/le-001", "open", False, ("parked",))))
        self.assertEqual(st["LE-001"], "parked")
        self.assertEqual(st["LE-002"], "blocked")
        st = b.task_states(self.tasks, state(parked=["[parked] LE-001: needs Aika"]))
        self.assertEqual(st["LE-001"], "parked")

    def test_task_needing_a_missing_tool_is_not_picked(self):
        tasks = b.parse_tasks(QUEUE.replace("- size: S\n- paths: `backend/engine", "- needs: godot\n- size: S\n- paths: `backend/engine", 1))
        self.assertEqual(tasks[0].needs, ["godot"])
        self.assertEqual(b.lint(tasks), [])
        self.assertIsNone(b.next_task(tasks, state(), has_tool=lambda n: False))
        self.assertEqual(b.next_task(tasks, state(), has_tool=lambda n: True).id, "LE-001")

    def test_waiting_is_never_ready(self):
        self.assertEqual(b.task_states(self.tasks, state())["LE-003"], "waiting")

    def test_wip_limit_via_cli(self):
        import json, io, contextlib
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump(state(*[(f"claude/x{i}", "open", False, ()) for i in range(3)]), f)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = b.main(["next", "--state", f.name])
        self.assertEqual(code, 3)
        self.assertIn("WIP-LIMIT", out.getvalue())


def snap(n, head, labels=(), ci="green", fixes=0, reviews=0):
    return {"number": n, "head": head, "sha": "s", "labels": set(labels), "draft": False,
            "fixes": fixes, "reviews": reviews, "ci": ci}


class DecideTest(unittest.TestCase):
    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)
        self.yes = lambda n: True

    def decide(self, *prs):
        st = state(*[(p["head"], "open", False, tuple(p["labels"])) for p in prs])
        return b.decide_executor(list(prs), self.tasks, st, self.yes)

    def test_new_task_when_nothing_open(self):
        self.assertEqual(self.decide(), ("NEW", "LE-001"))

    def test_open_pr_work_comes_first(self):
        self.assertEqual(self.decide(snap(1, "claude/le-001", ["needs-fix"]))[0], "FIX")
        self.assertEqual(self.decide(snap(1, "claude/le-001", ["needs-rebase"]))[0], "REBASE")
        self.assertEqual(self.decide(snap(1, "claude/le-001", ci="red"))[0], "CI-RED")

    def test_park_after_two_fix_rounds(self):
        self.assertEqual(self.decide(snap(1, "claude/le-001", ["needs-fix"], fixes=2))[0], "PARK")
        self.assertEqual(self.decide(snap(1, "claude/le-001", ci="red", fixes=2))[0], "PARK")

    def test_an_open_pr_blocks_its_dependants(self):
        # LE-001 is open (waiting for CI), LE-002 depends on it, LE-003 waits for Aika: nothing to start.
        self.assertEqual(self.decide(snap(1, "claude/le-001", ci="pending"))[0], "NOTHING")

    def test_wip_limit(self):
        prs = [snap(i, h, ci="pending") for i, h in enumerate(
            ["claude/le-001", "claude/proposals-user-2026-12-03", "claude/summary-2026-12-03"])]
        self.assertEqual(self.decide(*prs)[0], "WIP-LIMIT")

    def test_parked_and_foreign_prs_are_ignored(self):
        self.assertEqual(self.decide(snap(1, "claude/le-001", ["parked"]), snap(2, "feature/x", ci="red"))[0], "NOTHING")

    def test_reviewer_takes_the_oldest_green_unreviewed_task_pr(self):
        prs = [snap(3, "claude/le-002"), snap(1, "claude/le-001", ci="pending"),
               snap(2, "claude/summary-2026-12-03"), snap(4, "claude/le-003", ["review:pass"])]
        move, detail = b.decide_reviewer(prs, self.tasks)
        self.assertEqual(move, "REVIEW")
        self.assertTrue(detail.startswith("#3 claude/le-002 LE-002 round=1"))
        self.assertEqual(b.decide_reviewer([snap(1, "claude/le-001", ["needs-fix"])], self.tasks)[0], "NOTHING")


class PathsTest(unittest.TestCase):
    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)
        self.root = Path(tempfile.mkdtemp())
        (self.root / "docs/adr").mkdir(parents=True)
        (self.root / "docs/adr/0002-x.md").write_text("# ADR\n\n- Status: Accepted\n")
        (self.root / "docs/adr/0038-y.md").write_text("# ADR\n\n- Status: Proposed\n")

    def tearDown(self):
        shutil.rmtree(self.root)

    def check(self, branch, *changes):
        return b.check_paths(branch, [b.Change(*c) for c in changes], self.tasks, self.root)

    def test_task_inside_its_paths(self):
        self.assertEqual(self.check("claude/le-001",
                                    ("added", "backend/engine/src/test/java/engine/model/GenTest.java"),
                                    ("modified", "backend/engine/src/main/java/engine/Foo.java"),
                                    ("modified", "DECISIONS.md")), [])

    def test_outside_task_paths(self):
        p = self.check("claude/le-001", ("modified", "backend/app/src/main/java/app/X.java"))
        self.assertTrue(any("outside the paths of LE-001" in x for x in p))

    def test_protected_and_ci(self):
        for path in [".github/workflows/ci.yml", "Makefile", "CLAUDE.md", "game3d/CLAUDE.md",
                     "scripts/backlog/backlog.py", "docs/backlog/QUEUE.md", "docs/backlog/PAUSE"]:
            self.assertTrue(any("protected" in x for x in self.check("claude/le-001", ("modified", path))), path)

    def test_existing_tests_are_append_only(self):
        p = self.check("claude/le-001", ("modified", "backend/engine/src/test/java/engine/model/Old.java"))
        self.assertTrue(any("existing test" in x for x in p))
        p = self.check("claude/le-001", ("removed", "backend/engine/src/test/java/engine/model/Old.java"))
        self.assertTrue(any("existing test" in x for x in p))

    def test_new_test_may_not_skip(self):
        p = self.check("claude/le-001", ("added", "backend/engine/src/test/java/engine/model/T.java", None,
                                         "@Disabled\nvoid t() {}"))
        self.assertTrue(any("skips or disables" in x for x in p))

    def test_accepted_adr_is_frozen_proposed_is_not(self):
        tasks = b.parse_tasks(QUEUE.replace("`clients/mobile/src/**`", "`docs/adr/**`"))
        bad = b.check_paths("claude/le-002", [b.Change("modified", "docs/adr/0002-x.md")], tasks, self.root)
        self.assertTrue(any("accepted ADR" in x for x in bad))
        ok = b.check_paths("claude/le-002", [b.Change("modified", "docs/adr/0038-y.md")], tasks, self.root)
        self.assertEqual(ok, [])

    def test_visual_paths(self):
        tasks = b.parse_tasks(QUEUE.replace("`clients/mobile/src/**`", "`game3d/**`"))
        p = b.check_paths("claude/le-002", [b.Change("modified", "game3d/shaders/ink.gdshader")], tasks, self.root)
        self.assertTrue(any("look" in x for x in p))

    def test_proposals_and_summary_branches(self):
        self.assertEqual(self.check("claude/proposals-analyst-2026-12-03",
                                    ("added", "docs/backlog/proposals/2026-12-03-analyst.md")), [])
        self.assertTrue(self.check("claude/proposals-analyst-2026-12-03",
                                   ("added", "docs/backlog/proposals/2026-12-03-user.md")))
        self.assertEqual(self.check("claude/summary-2026-12-03", ("added", "docs/backlog/summaries/2026-12-03.md")), [])
        self.assertTrue(self.check("claude/summary-2026-12-03", ("modified", "README.md")))

    def test_unknown_branch(self):
        self.assertTrue(self.check("feature/x", ("modified", "README.md")))

    def test_renamed_checks_both_names(self):
        p = self.check("claude/le-001", ("renamed", "backend/engine/src/test/java/engine/model/New.java",
                                         "backend/engine/src/test/java/engine/model/Old.java"))
        self.assertTrue(any("existing test" in x for x in p))

    def test_api_files(self):
        ch = b.changes_from_api([{"filename": "a/T.test.mjs", "status": "added", "patch": "@@\n+test.skip('x')"}])
        self.assertEqual(ch[0].status, "added")
        self.assertIn("test.skip", ch[0].added_text)


class IntakeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "docs/backlog/proposals").mkdir(parents=True)
        (self.root / "docs/backlog/QUEUE.md").write_text(QUEUE)

    def tearDown(self):
        shutil.rmtree(self.root)

    def proposal(self, title, size="S", paths="`clients/web/src/x.ts`", verify="`cd clients/web && npm test`"):
        return f"### {title}\n- size: {size}\n- paths: {paths}\n- verify: {verify}\n- expect: green\n\nWhy and what.\n\n"

    def test_cap_of_five_and_filters(self):
        good = "".join(self.proposal(f"Good {i}") for i in range(7))
        bad = self.proposal("Big", size="M") + self.proposal("CI", paths="`.github/workflows/ci.yml`") + \
            self.proposal("No cmd", verify="run the tests") + self.proposal("First")
        (self.root / "docs/backlog/proposals/2026-12-03-analyst.md").write_text(bad)
        (self.root / "docs/backlog/proposals/2026-12-03-user.md").write_text(good)
        acc, rej = b.intake("2026-12-03", self.root)
        self.assertEqual(len(acc), 5)
        text = "\n".join(rej)
        for why in ["not size S", "protected", "no command", "same title", "daily cap"]:
            self.assertIn(why, text)
        tasks = b.parse_tasks((self.root / "docs/backlog/QUEUE.md").read_text())
        self.assertEqual(b.lint(tasks), [])
        self.assertEqual(tasks[-1].id, "AUTO-20261203-5")
        # idempotent: a second run the same day adds nothing
        acc2, _ = b.intake("2026-12-03", self.root)
        self.assertEqual(acc2, [])
        self.assertEqual(len(b.parse_tasks((self.root / "docs/backlog/QUEUE.md").read_text())), len(tasks))


class RealQueueTest(unittest.TestCase):
    def test_the_real_queue_is_valid(self):
        tasks = b.load_tasks()
        self.assertEqual(b.lint(tasks), [])
        self.assertGreaterEqual(len(tasks), 20)  # signal-bot: 25 tasks at setup (learning-exchange: 40)


class GlobTest(unittest.TestCase):
    def test_globs(self):
        self.assertTrue(b.matches("a/b/c.java", ["a/**"]))
        self.assertTrue(b.matches("x/CLAUDE.md", ["**/CLAUDE.md"]))
        self.assertTrue(b.matches("CLAUDE.md", ["**/CLAUDE.md"]))
        self.assertFalse(b.matches("a/b.java", ["a/*/b.java"]))
        self.assertTrue(b.matches("clients/web/src/x.test.ts", ["clients/**/*.test.ts"]))


if __name__ == "__main__":
    unittest.main()


class ProductionFilesTest(unittest.TestCase):
    def test_tests_and_docs_are_not_production(self):
        for p in ["backend/engine/src/test/java/engine/X.java", "clients/mobile/test/a.test.mjs", "DECISIONS.md",
                  "docs/backlog/tech-debt/x.md", "game3d/tests/unit/test_x.gd", "game3d/tools/test_check_data.py"]:
            self.assertFalse(b.is_production(p), p)
        for p in ["backend/engine/src/main/java/engine/OrderBook.java", "game3d/scripts/core/goals.gd",
                  "clients/mobile/src/statement.ts", "api/openapi.yaml"]:
            self.assertTrue(b.is_production(p), p)


class DebtAndExperienceTest(unittest.TestCase):
    def test_task_may_add_but_not_change_debt_and_experience_files(self):
        tasks = b.parse_tasks(QUEUE)
        ok = b.check_paths("claude/le-001", [b.Change("added", "docs/backlog/tech-debt/2026-12-03-LE-001-1.md"),
                                             b.Change("added", "docs/backlog/experience/2026-12-03-LE-001-0912.md")],
                           tasks)
        self.assertEqual(ok, [])
        bad = b.check_paths("claude/le-001", [b.Change("modified", "docs/backlog/experience/2026-12-02-LE-000-0800.md")],
                            tasks)
        self.assertTrue(any("existing" in x for x in bad))
        self.assertTrue(b.check_paths("claude/le-001", [b.Change("modified", "docs/backlog/TECH-DEBT.md")], tasks))


class HardeningTest(unittest.TestCase):
    """Holes found by the independent review of 2026-11-25."""

    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)

    def test_more_protected_and_visual_paths(self):
        for path in [".claude/settings.json", ".mcp.json", "settings.gradle", "gradle.properties",
                     "game3d/scripts/wayfarer.gd", "game3d/data/talks.json", "docs/adr/0036-game3d-the-ship.md",
                     "docs/design/README.md"]:
            p = b.check_paths("claude/le-002", [b.Change("modified", path)],
                              b.parse_tasks(QUEUE.replace("`clients/mobile/src/**`", "`**`")))
            self.assertTrue(any("protected" in x or "look" in x for x in p), path)
        for path in ["game3d/scripts/core/session_facts.gd", "game3d/tools/check_data.py", "game3d/scripts/sim_clock.gd"]:
            self.assertFalse(b.visual(path), path)

    def test_python_tests_are_append_only(self):
        tasks = b.parse_tasks(QUEUE.replace("`clients/mobile/src/**`", "`scripts/docs/**`"))
        p = b.check_paths("claude/le-002", [b.Change("modified", "scripts/docs/check_adr_index.py")], tasks)
        self.assertTrue(any("existing test" in x for x in p))

    def test_intake_rejects_config_wide_globs_and_keeps_needs(self):
        prop = b.parse_proposals("### A\n- size: S\n- paths: `backend/**`\n- verify: `x`\n- expect: y\n\nd\n\n"
                                 "### B\n- size: S\n- paths: `backend/app/build.gradle`\n- verify: `x`\n- expect: y\n\nd\n\n"
                                 "### C\n- size: S\n- paths: `game3d/tests/unit/test_x.gd`\n- verify: `x`\n- expect: y\n"
                                 "- needs: godot\n- depends: LE-001\n\nd\n")
        self.assertTrue(any("narrowed" in x for x in b.proposal_problems(prop[0], self.tasks)))
        self.assertTrue(any("configuration" in x for x in b.proposal_problems(prop[1], self.tasks)))
        self.assertEqual(b.proposal_problems(prop[2], self.tasks), [])
        root = Path(tempfile.mkdtemp())
        try:
            (root / "docs/backlog/proposals").mkdir(parents=True)
            (root / "docs/backlog/QUEUE.md").write_text(QUEUE)
            (root / "docs/backlog/proposals/2026-12-04-user.md").write_text(
                "### C\n- size: S\n- paths: `game3d/tests/unit/test_x.gd`\n- verify: `x`\n- expect: y\n"
                "- needs: godot\n- depends: LE-001\n\nd\n")
            b.intake("2026-12-04", root)
            t = b.parse_tasks((root / "docs/backlog/QUEUE.md").read_text())[-1]
            self.assertEqual((t.needs, t.depends), (["godot"], ["LE-001"]))
        finally:
            shutil.rmtree(root)

    def test_a_freshly_pushed_branch_without_pr_counts_as_open_for_three_hours(self):
        st = state()
        st["now"] = "2026-12-03T12:00:00Z"
        st["branches"] = {"claude/le-001": "2026-12-03T10:30:00Z"}
        self.assertEqual(b.task_states(self.tasks, st)["LE-001"], "open")
        st["branches"] = {"claude/le-001": "2026-12-03T08:00:00Z"}
        self.assertEqual(b.task_states(self.tasks, st)["LE-001"], "ready")

    def test_ci_state(self):
        run = lambda name, status="completed", concl="success": {"name": name, "status": status,
                                                                 "conclusion": concl, "completed_at": "t"}
        self.assertEqual(b.ci_state([]), "none")
        self.assertEqual(b.ci_state([run("reset-review")]), "none")
        self.assertEqual(b.ci_state([run("test", "in_progress", None)]), "pending")
        self.assertEqual(b.ci_state([run("test")]), "green")
        self.assertEqual(b.ci_state([run("test"), run("other", concl="failure")]), "red")
        self.assertEqual(b.ci_state([run("test"), run("merge", concl="failure")]), "green")

    def test_last_review_marker(self):
        bodies = ["x <!-- backlog-review round=1 verdict=fail sha=aaaaaaa -->",
                  "y <!-- backlog-review round=2 verdict=pass sha=bbbbbbb -->"]
        self.assertEqual(b.last_review(bodies), ("pass", "bbbbbbb"))
        self.assertIsNone(b.last_review(["no marker"]))

    def test_review_survives_github_updates_but_not_own_commits(self):
        def merge(sha, first, by="web-flow", verified=True):
            return {"sha": sha, "parents": [{"sha": first}, {"sha": "m" + sha}], "committer": {"login": by},
                    "commit": {"message": "Merge branch 'main' into claude/le-001", "verification": {"verified": verified}}}
        bodies = ["<!-- backlog-review round=1 verdict=pass sha=aaaaaaa -->"]
        orig = b.gh
        try:
            b.gh = FakeGH({("GET", "repos/o/r/commits/ccc"): merge("ccc", "bbb"),
                           ("GET", "repos/o/r/commits/bbb"): merge("bbb", "aaaaaaa")})
            self.assertTrue(b.review_covers_head("o/r", bodies, "ccc"))
            b.gh = FakeGH({("GET", "repos/o/r/commits/ccc"): merge("ccc", "aaaaaaa", by="Aika8", verified=False)})
            self.assertFalse(b.review_covers_head("o/r", bodies, "ccc"))
        finally:
            b.gh = orig

    def test_godot_uid_next_to_an_allowed_script(self):
        tasks = b.parse_tasks(QUEUE.replace("`clients/mobile/src/**`", "`game3d/tests/unit/test_x.gd`"))
        ok = b.check_paths("claude/le-002", [b.Change("added", "game3d/tests/unit/test_x.gd.uid")], tasks)
        self.assertEqual(ok, [])
        bad = b.check_paths("claude/le-002", [b.Change("added", "game3d/tests/unit/test_y.gd.uid")], tasks)
        self.assertTrue(bad)


class FakeGH:
    def __init__(self, routes):
        self.routes, self.calls = routes, []

    def __call__(self, path, method="GET", fields=None, paginate=False):
        self.calls.append((method, path, fields))
        for (m, prefix), val in self.routes.items():
            if m == method and path.startswith(prefix):
                if isinstance(val, Exception):
                    raise val
                return val
        return {} if not paginate else []


class AutomergeTest(unittest.TestCase):
    def setUp(self):
        self.tasks = b.parse_tasks(QUEUE)
        self.orig = b.gh
        self.pr = {"number": 7, "head": {"ref": "claude/le-001", "sha": "abc1234"}, "labels": [{"name": "review:pass"}],
                   "title": "LE-001: First", "draft": False}
        self.files = [{"filename": "backend/engine/src/test/java/engine/model/G.java", "status": "added", "patch": "+x"}]

    def tearDown(self):
        b.gh = self.orig

    def run_one(self, routes):
        fake = FakeGH(routes); b.gh = fake
        merged = b.automerge_one("o/r", self.pr, self.tasks, False, lambda m: None)
        return merged, fake.calls

    def green(self):
        return {"check_runs": [{"name": "test", "status": "completed", "conclusion": "success", "completed_at": "t"}]}

    def test_merges_when_review_passed_this_commit(self):
        merged, calls = self.run_one({
            ("GET", "repos/o/r/pulls/7/files"): self.files,
            ("GET", "repos/o/r/compare/main...abc1234"): {"behind_by": 0},
            ("GET", "repos/o/r/commits/abc1234/check-runs"): self.green(),
            ("GET", "repos/o/r/issues/7/comments"): [{"body": "<!-- backlog-review round=1 verdict=pass sha=abc1234 -->"}],
        })
        self.assertTrue(merged)
        self.assertIn(("POST", "repos/o/r/actions/workflows/ci.yml/dispatches", {"ref": "main"}), calls)

    def test_label_without_matching_review_does_not_merge(self):
        merged, calls = self.run_one({
            ("GET", "repos/o/r/pulls/7/files"): self.files,
            ("GET", "repos/o/r/compare/main...abc1234"): {"behind_by": 0},
            ("GET", "repos/o/r/commits/abc1234/check-runs"): self.green(),
            ("GET", "repos/o/r/issues/7/comments"): [{"body": "<!-- backlog-review round=1 verdict=pass sha=0000000 -->"}],
            ("GET", "repos/o/r/commits/abc1234"): {"sha": "abc1234", "parents": [{"sha": "0000000"}],
                                                   "committer": {"login": "Aika8"},
                                                   "commit": {"message": "sneaky", "verification": {}}},
        })
        self.assertFalse(merged)
        self.assertIn(("DELETE", "repos/o/r/issues/7/labels/review:pass", None), calls)

    def test_no_ci_and_behind_updates_branch_then_runs_ci_on_the_new_head(self):
        b.POLL_DELAY = 0
        merged, calls = self.run_one({
            ("GET", "repos/o/r/pulls/7/files"): self.files,
            ("GET", "repos/o/r/compare/main...abc1234"): {"behind_by": 2},
            ("GET", "repos/o/r/commits/abc1234/check-runs"): {"check_runs": []},
            ("GET", "repos/o/r/pulls/7"): {"head": {"sha": "def5678"}},
        })
        self.assertFalse(merged)
        self.assertTrue(any(m == "PUT" and p.endswith("update-branch") for m, p, _ in calls))
        self.assertIn(("POST", "repos/o/r/actions/workflows/ci.yml/dispatches", {"ref": "claude/le-001"}), calls)

    def test_no_ci_up_to_date_dispatches_ci(self):
        _, calls = self.run_one({
            ("GET", "repos/o/r/pulls/7/files"): self.files,
            ("GET", "repos/o/r/compare/main...abc1234"): {"behind_by": 0},
            ("GET", "repos/o/r/commits/abc1234/check-runs"): {"check_runs": []},
        })
        self.assertIn(("POST", "repos/o/r/actions/workflows/ci.yml/dispatches", {"ref": "claude/le-001"}), calls)

    def test_conflict_gets_needs_rebase(self):
        _, calls = self.run_one({
            ("GET", "repos/o/r/pulls/7/files"): self.files,
            ("GET", "repos/o/r/compare/main...abc1234"): {"behind_by": 1},
            ("GET", "repos/o/r/commits/abc1234/check-runs"): {"check_runs": []},
            ("PUT", "repos/o/r/pulls/7/update-branch"): RuntimeError("merge conflict"),
        })
        self.assertIn(("POST", "repos/o/r/issues/7/labels", {"labels[]": "needs-rebase"}), calls)

    def test_forbidden_path_is_labelled_not_merged(self):
        self.files = [{"filename": ".github/workflows/ci.yml", "status": "modified", "patch": "+x"}]
        merged, calls = self.run_one({("GET", "repos/o/r/pulls/7/files"): self.files})
        self.assertFalse(merged)
        self.assertIn(("POST", "repos/o/r/issues/7/labels", {"labels[]": "blocked-paths"}), calls)
