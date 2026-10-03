"""Exercise documentation-only PR classification against real, isolated Git histories."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("pr_docs_only.py").resolve()


class DocumentationOnlyPullRequestTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="quizmaker-docs-filter-")
        self.addCleanup(temporary.cleanup)
        self.repository = Path(temporary.name)
        self.environment = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("-c", "init.templateDir=", "init", "--quiet", "--initial-branch=base")
        self.write("README.md", "Initial documentation\n")
        self.write("src/Main.java", "class Main {}\n")
        self.base = self.commit()

    def git(self, *arguments):
        return subprocess.run(
            ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
             "-c", "commit.gpgsign=false", *arguments],
            cwd=self.repository, env=self.environment, check=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        ).stdout.strip()

    def write(self, name, content="Documentation fixture\n"):
        path = self.repository / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "--quiet", "--message=Fixture change")
        return self.git("rev-parse", "HEAD")

    def classify(self, base=None, head=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), base or self.base, head or self.git("rev-parse", "HEAD")],
            cwd=self.repository, env=self.environment,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )

    def assert_docs_only(self, expected, base=None, head=None):
        result = self.classify(base, head)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"docs_only={str(expected).lower()}\n", result.stdout)

    def test_documentation_allowlist_includes_formats_images_and_templates(self):
        names = ["README.md", "CONTRIBUTING.RST", "agents.ADOC",
                 ".github/ISSUE_TEMPLATE/nested/bug.md",
                 ".github/PULL_REQUEST_TEMPLATE/nested/change.md",
                 ".github/PULL_REQUEST_TEMPLATE.md"]
        names += [f"docs/nested/guide.{suffix}" for suffix in
                  ("md", "rst", "adoc", "txt", "png", "jpg", "jpeg", "gif",
                   "svg", "webp", "pdf", "MD", "PNG")]
        for name in names:
            self.write(name)
        self.commit()
        self.assert_docs_only(True)

    def test_non_documentation_paths_run_tests_even_with_readme_changes(self):
        self.write("README.md", "Updated documentation\n")
        for name in ("src/Main.java", "pom.xml", ".github/workflows/ci.yml",
                     "src/main/resources/prompt.md", "docs/example.py",
                     ".github/ISSUE_TEMPLATE/config.yml",
                     ".github/PULL_REQUEST_TEMPLATE/validator.py",
                     "other/guide.md", "notes.txt"):
            with self.subTest(path=name):
                self.write(name, "Changed non-documentation input\n")
                head = self.commit()
                self.assert_docs_only(False, head=head)
                self.git("restore", "--source", self.base, "--staged", "--worktree", "--", name)

    def test_code_renamed_into_documentation_still_runs_tests(self):
        self.git("mv", "src/Main.java", "Main.md")
        self.commit()
        self.assert_docs_only(False)

    def test_documentation_renamed_into_source_still_runs_tests(self):
        self.git("mv", "README.md", "src/Guide.java")
        self.commit()
        self.assert_docs_only(False)

    def test_deleting_only_documentation_skips_tests(self):
        self.git("rm", "README.md")
        self.commit()
        self.assert_docs_only(True)

    def test_deleting_source_runs_tests(self):
        self.git("rm", "src/Main.java")
        self.commit()
        self.assert_docs_only(False)

    def test_source_change_after_more_than_300_documentation_files_is_not_truncated(self):
        for index in range(350):
            self.write(f"docs/guide-{index:04}.md")
        self.write("src/Main.java", "class Main { int changed; }\n")
        self.commit()
        self.assert_docs_only(False)

    def test_newlines_in_documentation_filenames_are_not_separate_paths(self):
        self.write("docs/guide\nwith newline.md")
        self.commit()
        self.assert_docs_only(True)

    def test_newlines_cannot_hide_non_documentation_paths(self):
        self.write("docs/guide.md\nsource.py")
        self.commit()
        self.assert_docs_only(False)

    def test_diverged_base_uses_merge_base_not_two_dot_difference(self):
        self.git("checkout", "--quiet", "-b", "pull-request")
        self.write("README.md", "PR documentation change\n")
        head = self.commit()
        self.git("checkout", "--quiet", "base")
        self.write("src/Main.java", "class Main { int baseOnly; }\n")
        advanced_base = self.commit()
        self.assert_docs_only(True, base=advanced_base, head=head)

    def test_empty_diff_does_not_assert_documentation_only(self):
        self.assert_docs_only(False)

    def test_invalid_revision_fails_without_reporting_a_documentation_skip(self):
        for base, head in (("0" * 40, self.base), (self.base, "f" * 40)):
            with self.subTest(base=base, head=head):
                result = self.classify(base, head)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("docs_only=true", result.stdout)


if __name__ == "__main__":
    unittest.main()
