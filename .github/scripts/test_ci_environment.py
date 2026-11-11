"""Exercise environment loss, exact process handoff and the real workflow command."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


HELPER = Path(__file__).with_name("run_with_ci_environment.py").resolve()
WORKFLOW = HELPER.parents[1] / "workflows" / "ci.yml"
CHILD = """
import json, os, sys
keys = ('JAVA_HOME', 'PATH', 'FIXTURE_SECRET', 'JAVA_TOOL_OPTIONS', 'SUDO_ONLY')
print(json.dumps({'environment': {key: os.environ.get(key) for key in keys}, 'arguments': sys.argv[1:]}))
sys.exit(int(os.environ.get('FIXTURE_EXIT_CODE', '0')))
"""


def payload(environment):
    return b"".join(os.fsencode(key) + b"=" + os.fsencode(value) + b"\0" for key, value in environment.items())


def verification_script():
    section = WORKFLOW.read_text().split("      - name: Verify backend offline release gate\n", 1)[1]
    body = section.split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines))


class CiEnvironmentTest(unittest.TestCase):
    def environment(self, exit_code=0):
        return {"JAVA_HOME": "/fixture/JDK 17", "PATH": "/fixture/JDK 17/bin" + os.pathsep + os.defpath,
                "FIXTURE_SECRET": "private-fixture=\n'\"$(not-a-command)\u2615",
                "JAVA_TOOL_OPTIONS": "fixture options", "FIXTURE_EXIT_CODE": str(exit_code)}

    def invoke(self, captured, arguments=None):
        return subprocess.run(
            [sys.executable, str(HELPER)] + (arguments if arguments is not None else [sys.executable, "-c", CHILD, "literal argument"]),
            input=captured, env={"PATH": os.defpath, "JAVA_HOME": "/wrong/inherited/jdk", "SUDO_ONLY": "must disappear"},
            capture_output=True,
        )

    def test_restores_exact_values_and_arguments_without_inheriting_privileged_environment(self):
        result = self.invoke(payload(self.environment()))
        self.assertEqual(0, result.returncode, result.stderr)
        actual = json.loads(result.stdout)
        self.assertEqual({**{key: self.environment()[key] for key in ('JAVA_HOME', 'PATH', 'FIXTURE_SECRET', 'JAVA_TOOL_OPTIONS')},
                          'SUDO_ONLY': None}, actual['environment'])
        self.assertEqual(['literal argument'], actual['arguments'])

    def test_child_failure_is_the_helper_exit_status(self):
        result = self.invoke(payload(self.environment(exit_code=23)))
        self.assertEqual(23, result.returncode, result.stderr)

    def test_invalid_environment_is_rejected_without_echoing_values(self):
        marker = b"private-input-marker"
        for invalid in (b"", marker, b"=invalid\0", b"missing-separator\0",
                        b"JAVA_HOME=/first\0JAVA_HOME=" + marker + b"\0", b"FIXTURE_SECRET=" + marker + b"\0"):
            with self.subTest(payload_size=len(invalid)):
                result = self.invoke(invalid)
                self.assertEqual(2, result.returncode)
                self.assertEqual(b"", result.stdout)
                self.assertNotIn(marker, result.stderr)

    def test_missing_command_is_reported_without_printing_arguments_or_environment(self):
        result = self.invoke(payload(self.environment()), [])
        self.assertEqual(2, result.returncode)
        self.assertNotIn(b"private-fixture", result.stderr)
        result = self.invoke(payload(self.environment()), ["/nonexistent/private-command-marker"])
        self.assertEqual(2, result.returncode)
        self.assertNotIn(b"private-command-marker", result.stderr)

    def workflow_command(self, exit_code):
        with tempfile.TemporaryDirectory(prefix="quizmaker-ci-environment-") as temporary:
            root = Path(temporary)
            binary = root / "bin"
            binary.mkdir()
            (binary / "python3").symlink_to(sys.executable)
            helper = root / ".github" / "scripts" / HELPER.name
            helper.parent.mkdir(parents=True)
            shutil.copyfile(HELPER, helper)
            sudo = binary / "sudo"
            sudo.write_text(f"#!{sys.executable}\n" + textwrap.dedent("""
                import os, sys
                args = sys.argv[1:]
                while args[0].startswith('-'):
                    args.pop(0)
                # Model the observed runner: sudo ignores -E and removes the job environment.
                search_path = os.environ['PATH'].split(os.pathsep)[0] + os.pathsep + os.defpath
                os.execvpe(args[0], args, {'PATH': search_path, 'SUDO_ONLY': 'must disappear'})
            """))
            ip = binary / "ip"
            ip.write_text(f"#!{sys.executable}\nimport os,sys\nassert sys.argv[1:3] == ['netns','exec']\nos.execvp(sys.argv[4], sys.argv[4:])\n")
            runuser = binary / "runuser"
            runuser.write_text(f"#!{sys.executable}\nimport os,sys\nargs=sys.argv[sys.argv.index('--')+1:]\nos.execvp(args[0],args)\n")
            maven = root / "mvnw"
            # Check JAVA_HOME before running the stub, as the real Maven enforcer does.
            maven.write_text(f"#!{sys.executable}\nimport os,sys\nif not os.environ.get('JAVA_HOME'): sys.exit(41)\n"
                             + textwrap.dedent(CHILD))
            for executable in (sudo, ip, runuser, maven):
                executable.chmod(0o700)
            environment = self.environment(exit_code)
            environment.update({"PATH": str(binary) + os.pathsep + environment['PATH'],
                                "USER": "fixture", "HOME": str(root), "NETNS": "fixture",
                                "VERIFICATION_LOG": str(root / "verification.log")})
            lines = verification_script().splitlines()
            start = next(index for index, line in enumerate(lines) if 'runuser --user "$USER"' in line) - 1
            end = next(index for index in range(start, len(lines)) if lines[index] == "set -e")
            command = "set -Eeuo pipefail\nMAVEN_CONTEXT_ARGS=()\nset +e\n" + "\n".join(lines[start:end+1]) + '\nexit "$TEST_EXIT_CODE"\n'
            result = subprocess.run(["bash", "-c", command], cwd=root, env=environment, capture_output=True, text=True)
            return result, environment

    def test_workflow_handoff_restores_jdk_and_job_settings_after_sudo_discards_them(self):
        result, environment = self.workflow_command(0)
        self.assertEqual(0, result.returncode, result.stderr)
        actual = json.loads(result.stdout)
        self.assertEqual(environment['JAVA_HOME'], actual['environment']['JAVA_HOME'])
        self.assertEqual(environment['PATH'], actual['environment']['PATH'])
        self.assertEqual(environment['FIXTURE_SECRET'], actual['environment']['FIXTURE_SECRET'])
        self.assertEqual(environment['JAVA_TOOL_OPTIONS'], actual['environment']['JAVA_TOOL_OPTIONS'])
        self.assertIsNone(actual['environment']['SUDO_ONLY'])
        self.assertIn('clean', actual['arguments'])
        self.assertIn('verify', actual['arguments'])
        self.assertIn('-o', actual['arguments'])

    def test_workflow_handoff_preserves_maven_failure_status_through_tee(self):
        result, _ = self.workflow_command(23)
        self.assertEqual(23, result.returncode, result.stderr)

    def test_workflow_verification_script_is_valid_bash(self):
        result = subprocess.run(['bash', '-n'], input=verification_script(), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
