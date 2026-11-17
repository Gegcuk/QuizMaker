"""Best-effort Linux resource samples for interrupted CI jobs (stdlib only)."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import threading
import time


PREFIX = "[ci-resources] "
PROCESS_LIMIT = 4096
TOP_PROCESSES = 8
CGROUP_LEVELS = 8
MEMORY_KEYS = {"MemTotal", "MemAvailable", "SwapTotal", "SwapFree"}
PROCESS_KINDS = {"java", "mysqld", "dockerd", "containerd", "socat", "node", "python3",
                 "Runner.Worker", "Runner.Listener"}


def counters(path, allowed=None):
    result = {}
    for line in path.read_text().splitlines():
        key, value, *_ = line.replace(":", " ").split()
        if allowed is None or key in allowed:
            result[key] = int(value)
    return result


def pressure(path):
    result = {}
    for line in path.read_text().splitlines():
        kind, *fields = line.split()
        if kind in ("some", "full"):
            result[kind] = {key: float(value) for key, value in
                            (field.split("=", 1) for field in fields)
                            if key in ("avg10", "avg60", "avg300", "total")}
    return result


def processes(proc):
    """Read one small stat file per PID, never cmdline, environ, or arbitrary names."""
    total_rss, total_threads, count = 0, 0, 0
    largest, kinds = [], {}
    truncated = False
    page_kib = os.sysconf("SC_PAGE_SIZE") // 1024
    with os.scandir(proc) as entries:
        for entry in entries:
            if not entry.name.isdigit():
                continue
            if count >= PROCESS_LIMIT:
                truncated = True
                break
            count += 1
            try:
                stat = (Path(entry.path) / "stat").read_text()
                name, fields = stat.split("(", 1)[1].rsplit(")", 1)
                fields = fields.split()  # Starts at Linux proc_pid_stat field 3 (state).
                rss = max(0, int(fields[21])) * page_kib
                threads = int(fields[17])
                kind = name if name in PROCESS_KINDS else "other"
                kinds[kind] = kinds.get(kind, 0) + 1
                total_rss += rss
                total_threads += threads
                largest.append({"pid": int(entry.name), "kind": kind,
                                "rss_kib": rss, "threads": threads})
                largest = sorted(largest, key=lambda item: (-item["rss_kib"], item["pid"]))[:TOP_PROCESSES]
            except (OSError, ValueError, IndexError):
                continue  # Processes can exit between directory listing and stat read.
    return {"scanned": count, "truncated": truncated, "rss_kib_sum": total_rss,
            "threads_sum": total_threads, "kinds": kinds, "largest": largest}


def cgroups(proc, root):
    """Record v2 limits/events for our group and ancestors, including the root."""
    membership = next((line[3:] for line in (proc / "self/cgroup").read_text().splitlines()
                       if line.startswith("0::")), None)
    if membership is None:
        return {"v2": False}
    root = root.resolve()
    group = (root / membership.lstrip("/")).resolve()
    if not group.is_relative_to(root) or not group.is_dir():
        group = root  # A cgroup namespace can expose the current group at the mount root.
    paths = []
    while group != root and len(paths) < CGROUP_LEVELS - 1:
        paths.append(group)
        group = group.parent
    paths.append(root)
    groups = []
    for depth, path in enumerate(paths):
        values = {"level": depth, "root": path == root, "unavailable": []}
        for name in ("memory.current", "memory.max", "memory.peak", "memory.swap.current",
                     "memory.swap.max", "pids.current", "pids.max"):
            try:
                value = (path / name).read_text().strip()
                if value.isdigit() or value == "max":
                    values[name] = int(value) if value.isdigit() else value
                else:
                    values["unavailable"].append(name)
            except OSError:
                values["unavailable"].append(name)
        for name, allowed in (("memory.events", {"low", "high", "max", "oom", "oom_kill", "oom_group_kill"}),
                              ("cpu.stat", {"usage_usec", "nr_throttled", "throttled_usec"}),
                              ("pids.events", {"max"})):
            try:
                values[name] = counters(path / name, allowed)
            except (OSError, ValueError):
                values["unavailable"].append(name)
        groups.append(values)
    return {"v2": True, "groups": groups}


def snapshot(workspace, temporary, proc=Path("/proc"), cgroup=Path("/sys/fs/cgroup")):
    wall, cpu = time.monotonic(), time.process_time()
    result = {"utc": datetime.now(timezone.utc).isoformat(timespec="seconds"), "cpus": os.cpu_count()}
    readers = {
        "memory_kib": lambda: counters(proc / "meminfo", MEMORY_KEYS),
        "vm_events": lambda: counters(proc / "vmstat", {"oom_kill", "pswpin", "pswpout"}),
        "scheduler": lambda: counters(proc / "stat", {"ctxt", "procs_running", "procs_blocked"}),
        "load": lambda: [float(value) for value in (proc / "loadavg").read_text().split()[:3]],
        "processes": lambda: processes(proc),
        "cgroup": lambda: cgroups(proc, cgroup),
    }
    for resource in ("cpu", "memory", "io"):
        readers[f"pressure_{resource}"] = lambda name=resource: pressure(proc / "pressure" / name)
    missing = []
    for name, read in readers.items():
        try:
            result[name] = read()
        except (OSError, ValueError, IndexError):
            missing.append(name)
    result["disk"] = {}
    for name, path in (("workspace", workspace), ("temporary", temporary)):
        try:
            stat = os.statvfs(path)
            result["disk"][name] = {"free_bytes": stat.f_bavail * stat.f_frsize,
                                    "total_bytes": stat.f_blocks * stat.f_frsize,
                                    "free_inodes": stat.f_favail}
        except OSError:
            missing.append(f"disk_{name}")
    result.update(unavailable=missing, sample_ms=round((time.monotonic() - wall) * 1000, 3),
                  sample_cpu_ms=round((time.process_time() - cpu) * 1000, 3))
    return result


def emit(record, output):
    line = json.dumps(record, separators=(",", ":"), sort_keys=True)
    # The live log is primary: a runner shutdown can prevent all artifact-upload steps.
    print(PREFIX + line, flush=True)
    try:
        with output.open("a", encoding="utf-8") as stream:
            stream.write(line + "\n")
    except OSError:
        pass  # Lost diagnostic storage must not interfere with verification.


def monitor(output, workspace, interval):
    stopped = threading.Event()
    for name in (signal.SIGTERM, signal.SIGINT):
        signal.signal(name, lambda *_: stopped.set())
    sequence = 0
    event = "start"
    while True:
        try:
            record = snapshot(workspace, output.parent)
        except Exception:
            record = {"unavailable": ["snapshot"]}  # Never expose exception text or paths.
        emit(dict(record, event=event, sequence=sequence), output)
        if event == "stop":
            return
        sequence += 1
        event = "stop" if stopped.wait(interval) else "sample"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--interval-seconds", type=int, default=60)
    args = parser.parse_args()
    if args.interval_seconds < 1:
        parser.error("interval must be positive")
    try:
        monitor(args.output, args.workspace, args.interval_seconds)
    except (OSError, ValueError):
        # Diagnostic failures neither print sensitive context nor set the build status.
        pass


if __name__ == "__main__":
    main()
