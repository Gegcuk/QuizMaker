"""Summarize one Maven invocation; diagnostics never determine build success."""

import argparse
from pathlib import Path
import re


LANES = ("tests-parallel", "tests-db-serial")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
MESSAGE = re.compile(r"^\s*(\d+)\s+\[(?:INFO|WARNING|WARN|ERROR)\]\s+(.*)$")
PLUGIN = re.compile(r"^--- .* \(([^)]+)\) @ .* ---$")
COUNTS = r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)"
TOTAL = re.compile(COUNTS + r"\s*$")
SUITE = re.compile(COUNTS + r", Time elapsed: ([\d.,]+) s.*? -- in ([A-Za-z0-9_.$]+)\s*$")


def summarize(lines):
    lanes, suites = {}, []
    active, last_ms, outcome = None, 0, "not recorded"
    for line in lines:
        match = MESSAGE.match(ANSI.sub("", line).strip())
        if not match:
            continue
        last_ms, message = int(match[1]), match[2].strip()
        plugin = PLUGIN.match(message)
        finished = message in ("BUILD SUCCESS", "BUILD FAILURE")
        if plugin or finished:
            if active is not None:
                lanes[active]["end"] = last_ms
                active = None
            if finished:
                outcome = message.removeprefix("BUILD ")
            elif plugin[1] in LANES:
                active = plugin[1]
                lanes[active] = {"start": last_ms, "end": None, "counts": None}
            continue
        if active is None:
            continue
        total, suite = TOTAL.fullmatch(message), SUITE.fullmatch(message)
        if total:
            lanes[active]["counts"] = tuple(map(int, total.groups()))
        elif suite:
            suites.append((float(suite[5].replace(",", "")), suite[6], active))

    output = [
        "### Maven test timings", "",
        f"Maven result recorded in log: **{outcome}**.",
        "Diagnostic only; the Maven exit code remains authoritative.", "",
        "| Execution | Elapsed, s | Tests | Failures | Errors | Skipped | Report |",
        "|---|---:|---:|---:|---:|---:|---|",
    ]
    for name in LANES:
        lane = lanes.get(name)
        if lane is None:
            output.append(f"| {name} | — | — | — | — | — | not observed |")
            continue
        end = lane["end"] if lane["end"] is not None else last_ms
        elapsed = max(0, end - lane["start"]) / 1000
        duration = f"{elapsed:.3f}" if lane["end"] is not None else f">= {elapsed:.3f}"
        counts = " | ".join(map(str, lane["counts"])) if lane["counts"] is not None else "— | — | — | —"
        status = "reported" if lane["end"] is not None and lane["counts"] is not None else "partial"
        output.append(f"| {name} | {duration} | {counts} | {status} |")

    output += ["", "### Slowest Surefire classes/containers", "",
               "| Class/container | Execution | Reported time, s |", "|---|---|---:|"]
    for seconds, name, lane in sorted(suites, key=lambda item: (-item[0], item[1]))[:20]:
        output.append(f"| {name} | {lane} | {seconds:.3f} |")
    if not suites:
        output.append("| No class timing records found | — | — |")
    output += ["", "Execution time includes Surefire startup and shutdown. An unfinished execution",
               "shows only the observed interval. Class/container times may overlap, especially",
               "for nested or parallel tests; do not sum them as wall-clock time.",
               "Missing data is not a successful or empty test run.", ""]
    return "\n".join(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    try:
        with args.log.open(encoding="utf-8", errors="replace") as lines:
            report = summarize(lines)
    except OSError:
        report = "### Maven test timings\n\nVerification log unavailable; no test result inferred.\n"
    print(report)


if __name__ == "__main__":
    main()
