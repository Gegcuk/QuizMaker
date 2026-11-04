"""Restore a trusted caller's NUL-delimited environment after a privilege boundary."""

import os
import sys


def main():
    if len(sys.argv) < 2:
        print("Usage: run_with_ci_environment.py COMMAND [ARG ...]", file=sys.stderr)
        return 2
    payload = sys.stdin.buffer.read()
    environment = {}
    if not payload or not payload.endswith(b"\0"):
        print("Invalid captured CI environment", file=sys.stderr)
        return 2
    for entry in payload[:-1].split(b"\0"):
        name, separator, value = entry.partition(b"=")
        if not name or not separator or name in environment:
            print("Invalid captured CI environment", file=sys.stderr)
            return 2
        environment[name] = value
    if not environment.get(b"JAVA_HOME"):
        print("Captured CI environment must include JAVA_HOME", file=sys.stderr)
        return 2
    arguments = [os.fsencode(argument) for argument in sys.argv[1:]]
    try:
        os.execvpe(arguments[0], arguments, environment)
    except OSError:
        # Environment values and command arguments can contain secrets; do not print diagnostics.
        print("Cannot execute CI command with captured environment", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
