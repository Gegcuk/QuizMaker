"""Exercise Maven log formats, partial runs and diagnostic privacy."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from summarize_tests import summarize


def log_line(milliseconds, message, level="INFO"):
    return f"{milliseconds} [{level}] {message}\n"


def plugin(milliseconds, execution):
    return log_line(milliseconds, f"--- surefire:3.5.2:test ({execution}) @ QuizMaker ---")


class TestSummaryTest(unittest.TestCase):
    def test_two_executions_use_wall_intervals_not_sum_of_parallel_suites(self):
        report = summarize([
            plugin(1000, "tests-parallel"),
            log_line(2500, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.000 s -- in example.FirstTest"),
            log_line(2800, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.000 s -- in example.SecondTest"),
            log_line(2900, "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0"),
            plugin(3000, "tests-db-serial"),
            log_line(3900, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"),
            log_line(4000, "--- jacoco:0.8.12:report (report) @ QuizMaker ---"),
            log_line(5000, "BUILD SUCCESS"),
        ])
        self.assertIn("| tests-parallel | 2.000 | 2 | 0 | 0 | 0 | reported |", report)
        self.assertIn("| tests-db-serial | 1.000 | 1 | 0 | 0 | 0 | reported |", report)

    def test_failure_ansi_nested_names_and_skips_are_retained_without_payloads(self):
        report = summarize([
            plugin(0, "tests-parallel"),
            "\x1b[31m" + log_line(900, "Tests run: 3, Failures: 1, Errors: 0, Skipped: 1, Time elapsed: 0.900 s <<< FAILURE! -- in example.OuterTest$Nested", "ERROR") + "\x1b[0m",
            log_line(950, "private-payload-must-not-appear", "ERROR"),
            log_line(990, "Tests run: 3, Failures: 1, Errors: 0, Skipped: 1", "ERROR"),
            log_line(1000, "BUILD FAILURE"),
        ])
        self.assertIn("**FAILURE**", report)
        self.assertIn("| 3 | 1 | 0 | 1 | reported |", report)
        self.assertIn("example.OuterTest$Nested", report)
        self.assertNotIn("private-payload", report)
        self.assertIn("| tests-db-serial | —", report)

    def test_interrupted_run_is_partial_and_not_zero_tests(self):
        report = summarize([plugin(1000, "tests-db-serial"), log_line(2500, "Running example.PendingTest")])
        self.assertIn("| tests-db-serial | >= 1.500 | — | — | — | — | partial |", report)
        self.assertIn("**not recorded**", report)

    def test_empty_or_untimed_log_does_not_invent_timings(self):
        for lines in ([], ["[INFO] BUILD SUCCESS\n"], ["unexpected log format\n"]):
            with self.subTest(lines=lines):
                self.assertIn("| tests-parallel | —", summarize(lines))
                self.assertIn("**not recorded**", summarize(lines))

    def test_slowest_list_is_bounded_sorted_and_accepts_large_durations(self):
        lines = [plugin(0, "tests-parallel")]
        for index in range(25):
            lines.append(log_line(index + 1, f"Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: {index + 1:,}.500 s -- in example.Test{index:02}"))
        lines.append(log_line(100, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1,234.500 s -- in example.SlowestTest"))
        report = summarize(lines)
        self.assertEqual(20, report.count("| example."))
        self.assertLess(report.index("example.SlowestTest"), report.index("example.Test24"))
        self.assertNotIn("example.Test00", report)

    def test_cli_missing_log_is_diagnostic_not_a_test_success(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("summarize_tests.py")), str(Path(directory) / "missing.log")],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(0, result.returncode)
        self.assertIn("no test result inferred", result.stdout)
        self.assertNotIn("SUCCESS", result.stdout)


if __name__ == "__main__":
    unittest.main()
