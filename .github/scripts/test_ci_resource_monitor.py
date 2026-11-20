"""Resource evidence, privacy, interruption and workflow cleanup contracts."""

from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import ci_resource_monitor as monitor
from test_ci_environment import verification_script


HELPER = Path(monitor.__file__).resolve()


class ResourceMonitorTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="quizmaker-ci-resources-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.proc, self.cgroup = self.root / "proc", self.root / "cgroup"
        self.proc.mkdir()
        self.cgroup.mkdir()

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def process(self, pid, name, pages=10, threads=2, cpu=(0, 0), start=0):
        fields = ["0"] * 22
        fields[0], fields[17], fields[21] = "S", str(threads), str(pages)
        fields[11], fields[12], fields[19] = str(cpu[0]), str(cpu[1]), str(start)
        self.write(self.proc / str(pid) / "stat", f"{pid} ({name}) " + " ".join(fields))

    def sample(self):
        return monitor.snapshot(self.root, self.root, self.proc, self.cgroup)

    def test_linux_counters_pressure_disk_and_process_memory_are_observable(self):
        self.write(self.proc / "meminfo", "MemTotal: 8000 kB\nMemAvailable: 2000 kB\nSwapTotal: 1024 kB\nSwapFree: 512 kB\n")
        self.write(self.proc / "vmstat", "oom_kill 3\npswpin 7\npswpout 8\nunrelated 900\n")
        self.write(self.proc / "stat", "cpu 100 200 300 400\nctxt 102\nprocs_running 2\nprocs_blocked 1\n")
        self.write(self.proc / "loadavg", "1.00 2.00 3.00 2/80 123\n")
        for name in ("cpu", "memory", "io"):
            self.write(self.proc / "pressure" / name, "some avg10=1.50 avg60=0.50 avg300=0.10 total=120\n")
        self.write(self.proc / "self/cgroup", "0::/worker\n")
        self.write(self.cgroup / "worker/memory.current", "4096\n")
        self.write(self.cgroup / "worker/memory.max", "8192\n")
        self.write(self.cgroup / "memory.events", "oom 2\noom_kill 1\n")
        self.process(12, "java", pages=40, threads=10)
        result = self.sample()
        self.assertEqual([], result["unavailable"])
        self.assertEqual(2000, result["memory_kib"]["MemAvailable"])
        self.assertEqual({"oom_kill": 3, "pswpin": 7, "pswpout": 8}, result["vm_events"])
        self.assertEqual([1, 2, 3], result["load"])
        self.assertEqual(120, result["pressure_memory"]["some"]["total"])
        self.assertEqual({"ctxt": 102, "procs_running": 2, "procs_blocked": 1}, result["scheduler"])
        self.assertEqual(4096, result["cgroup"]["groups"][0]["memory.current"])
        self.assertEqual(1, result["cgroup"]["groups"][1]["memory.events"]["oom_kill"])
        self.assertEqual(40 * os.sysconf("SC_PAGE_SIZE") // 1024, result["processes"]["largest"][0]["rss_kib"])
        self.assertGreater(result["disk"]["workspace"]["total_bytes"], 0)
        self.assertGreaterEqual(result["sample_cpu_ms"], 0)

    def test_missing_or_malformed_metrics_do_not_hide_other_evidence(self):
        self.write(self.proc / "meminfo", "MemAvailable: private-fixture-not-a-number\n")
        self.write(self.proc / "loadavg", "1 2 3")
        result = self.sample()
        self.assertIn("memory_kib", result["unavailable"])
        self.assertEqual([1, 2, 3], result["load"])
        self.assertIn("workspace", result["disk"])
        self.assertNotIn("private-fixture", json.dumps(result))

    def test_process_races_names_and_top_list_are_bounded_and_private(self):
        self.process(1, "private-fixture ) with spaces", pages=20)
        self.process(2, "java", pages=40)
        self.write(self.proc / "3/stat", "process disappeared")
        self.write(self.proc / "2/cmdline", "private-command-argument")
        self.write(self.proc / "2/environ", "SECRET=private-environment-value")
        with patch.object(monitor, "TOP_PROCESSES", 1):
            result = monitor.processes(self.proc)
        self.assertEqual(3, result["scanned"])
        self.assertEqual({"other": 1, "java": 1}, result["kinds"])
        self.assertEqual([2], [item["pid"] for item in result["largest"]])
        self.assertNotIn("private-", json.dumps(result))
        with patch.object(monitor, "PROCESS_LIMIT", 1):
            limited = monitor.processes(self.proc)
        self.assertTrue(limited["truncated"])
        self.assertEqual(1, limited["scanned"])

    def test_cgroup_ancestors_include_root_and_unlimited_values_without_names(self):
        self.write(self.proc / "self/cgroup", "0::/private-group/nested\n")
        self.write(self.cgroup / "private-group/nested/memory.max", "max\n")
        self.write(self.cgroup / "private-group/memory.events", "oom_kill 4\n")
        self.write(self.cgroup / "memory.max", "100000\n")
        self.write(self.cgroup / "pids.events", "max 7\n")
        result = monitor.cgroups(self.proc, self.cgroup)
        self.assertEqual("max", result["groups"][0]["memory.max"])
        self.assertEqual(4, result["groups"][1]["memory.events"]["oom_kill"])
        self.assertTrue(result["groups"][2]["root"])
        self.assertEqual(7, result["groups"][2]["pids.events"]["max"])
        self.assertNotIn("private-group", json.dumps(result))
        with patch.object(monitor, "CGROUP_LEVELS", 2):
            self.assertEqual(2, len(monitor.cgroups(self.proc, self.cgroup)["groups"]))

    def test_all_java_mysql_processes_have_cpu_and_pid_reuse_identity_outside_top_eight(self):
        for pid in range(1, 10):
            self.process(pid, 'node', pages=1000)
        self.process(21, 'java', pages=20, cpu=(100, 3), start=456)
        self.process(22, 'mysqld', pages=10, cpu=(40, 2), start=789)
        result = monitor.processes(self.proc)
        self.assertNotIn(21, [item['pid'] for item in result['largest']])
        self.assertEqual([21, 22], [item['pid'] for item in result['tracked']])
        self.assertEqual(103, result['tracked'][0]['cpu_ticks'])
        self.assertEqual(456, result['tracked'][0]['start_ticks'])
        self.assertEqual(os.sysconf('SC_CLK_TCK'), result['clock_ticks_per_second'])
        self.assertFalse(result['tracked_truncated'])
        with patch.object(monitor, 'TRACKED_PROCESSES', 1):
            bounded = monitor.processes(self.proc)
        self.assertTrue(bounded['tracked_truncated'])
        self.assertEqual(1, len(bounded['tracked']))

    def test_cgroup_v1_namespaced_and_escaping_memberships_are_safe(self):
        self.write(self.cgroup / "memory.current", "200\n")
        for membership in ("0::/not-visible-here", "0::/../../outside"):
            with self.subTest(membership=membership):
                self.write(self.proc / "self/cgroup", membership)
                result = monitor.cgroups(self.proc, self.cgroup)
                self.assertEqual(200, result["groups"][0]["memory.current"])
                self.assertEqual(1, len(result["groups"]))
        self.write(self.proc / "self/cgroup", "1:memory:/legacy\n")
        self.assertEqual({"v2": False}, monitor.cgroups(self.proc, self.cgroup))

    def test_lost_diagnostic_file_keeps_live_output(self):
        live = io.StringIO()
        with redirect_stdout(live):
            monitor.emit({"event": "sample", "sequence": 1}, self.root)
        self.assertEqual({"event": "sample", "sequence": 1}, json.loads(live.getvalue().removeprefix(monitor.PREFIX)))

    def test_collector_exception_remains_safe_and_sampling_continues(self):
        event = unittest.mock.Mock()
        event.wait.side_effect = [False, True]
        with patch.object(monitor.threading, "Event", return_value=event), \
             patch.object(monitor.signal, "signal"), \
             patch.object(monitor, "snapshot", side_effect=RuntimeError("private-exception")), \
             patch.object(monitor, "emit") as emit:
            monitor.monitor(self.root / "out", self.root, 60)
        records = [call.args[0] for call in emit.call_args_list]
        self.assertEqual(["start", "sample", "stop"], [record["event"] for record in records])
        self.assertNotIn("private-exception", json.dumps(records))
        self.assertEqual([unittest.mock.call(60), unittest.mock.call(60)], event.wait.call_args_list)

    def wait_for_file(self, path, minimum_lines=1):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if path.exists() and len(path.read_text().splitlines()) >= minimum_lines:
                return
            time.sleep(0.02)
        self.fail("diagnostic fixture did not become ready")

    def test_real_process_periodic_output_and_signal_stop_leave_final_sample(self):
        for stop_signal in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signal=stop_signal):
                output = self.root / f"signal-{stop_signal}.jsonl"
                process = subprocess.Popen([sys.executable, str(HELPER), "--output", str(output),
                                            "--workspace", str(self.root), "--interval-seconds", "1"],
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                           env=dict(os.environ, FIXTURE_SECRET="private-process-environment"))
                try:
                    self.wait_for_file(output, 2)
                    process.send_signal(stop_signal)
                    live, error = process.communicate(timeout=5)
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=5)
                self.assertEqual(0, process.returncode, error)
                records = [json.loads(line) for line in output.read_text().splitlines()]
                self.assertEqual("start", records[0]["event"])
                self.assertEqual("stop", records[-1]["event"])
                self.assertIn("sample", [record["event"] for record in records])
                self.assertEqual(list(range(len(records))), [record["sequence"] for record in records])
                self.assertNotIn("private-process-environment", live + error)
                self.assertTrue(all(line.startswith(monitor.PREFIX) for line in live.splitlines()))

    def test_actual_workflow_cleanup_preserves_status_and_bounds_broken_monitor(self):
        script = verification_script()
        start = script.index('NETNS="quizmaker-offline"')
        end = script.index('sudo ip netns delete "$NETNS" 2>/dev/null || true',
                           script.index("RESOURCE_MONITOR_PID=$!"))
        lifecycle = script[start:end]
        for mode, exit_code in (("off", 0), ("off", 23), ("real", 0), ("real", 23),
                                ("failed", 0), ("failed", 23), ("hung", 23)):
            with self.subTest(mode=mode, exit_code=exit_code):
                fixture = self.root / f"{mode}-{exit_code}"
                helper = fixture / ".github/scripts/ci_resource_monitor.py"
                if mode == "real":
                    contents = HELPER.read_text()
                else:
                    contents = "import signal, sys, time\n"
                    if mode == "hung":
                        contents += "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                    contents += "open(sys.argv[2], 'w').write('ready')\n"
                    contents += "while True: time.sleep(60)\n" if mode == "hung" else "sys.exit(71)\n"
                self.write(helper, contents)
                sudo = fixture / "bin/sudo"
                self.write(sudo, "#!/bin/sh\nexit 0\n")
                sudo.chmod(0o700)
                environment = dict(os.environ, RUNNER_TEMP=str(fixture), GITHUB_WORKSPACE=str(fixture),
                                   RESOURCE_DIAGNOSTICS="false" if mode == "off" else "true",
                                   PATH=str(sudo.parent) + os.pathsep + os.environ["PATH"])
                command = 'set -Eeuo pipefail\n' + lifecycle + '\n'
                command += 'printf "fixture-monitor-pid=%s\\n" "$RESOURCE_MONITOR_PID"\n'
                if mode != "off":
                    command += 'for ATTEMPT in {1..100}; do\n'
                    command += '  [ -s "$RUNNER_TEMP/quizmaker-resources.jsonl" ] && break\n  sleep 0.02\ndone\n'
                command += f'exit {exit_code}\n'
                process = subprocess.Popen(["bash", "-c", command], cwd=fixture, env=environment,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                           start_new_session=True)
                try:
                    live, error = process.communicate(timeout=8)
                finally:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=5)
                self.assertEqual(exit_code, process.returncode, error)
                recorded_pid = next(line.split("=", 1)[1] for line in live.splitlines()
                                    if line.startswith("fixture-monitor-pid="))
                if mode == "off":
                    self.assertEqual("", recorded_pid)
                    self.assertFalse((fixture / "quizmaker-resources.jsonl").exists())
                    continue
                pid = int(recorded_pid)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
                if mode == "real":
                    records = [json.loads(line) for line in (fixture / "quizmaker-resources.jsonl").read_text().splitlines()]
                    self.assertEqual("stop", records[-1]["event"])


if __name__ == "__main__":
    unittest.main()
