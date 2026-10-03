"""Identify documentation-only PR diffs without GitHub's changed-file limits."""

import os
from pathlib import PurePosixPath
import re
import subprocess
import sys


MARKUP_SUFFIXES = {".md", ".rst", ".adoc"}
DOC_SUFFIXES = MARKUP_SUFFIXES | {
    ".txt", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".pdf",
}


def is_documentation(filename):
    path = PurePosixPath(filename)
    suffix = path.suffix.lower()
    if len(path.parts) == 1:
        return suffix in MARKUP_SUFFIXES
    if path.parts[0] == "docs":
        return suffix in DOC_SUFFIXES
    if filename == ".github/PULL_REQUEST_TEMPLATE.md":
        return True
    return (
        path.parts[:2] in {
            (".github", "ISSUE_TEMPLATE"),
            (".github", "PULL_REQUEST_TEMPLATE"),
        }
        and suffix == ".md"
    )


def main():
    if len(sys.argv) != 3 or any(
        re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", sha) is None
        for sha in sys.argv[1:]
    ):
        raise SystemExit("Expected PR base and head commit SHAs")

    # Three dots include all PR commits, excluding unrelated base-branch changes.
    # Disable renames so moving code into docs also includes the old code path.
    changed = subprocess.check_output([
        "git", "diff", "--no-ext-diff", "--no-renames", "--name-only", "-z",
        f"{sys.argv[1]}...{sys.argv[2]}", "--",
    ])
    paths = [os.fsdecode(path) for path in changed.split(b"\0") if path]
    docs_only = bool(paths) and all(is_documentation(path) for path in paths)
    # Output is written only after a complete successful diff. Errors never skip tests.
    print(f"docs_only={str(docs_only).lower()}")


if __name__ == "__main__":
    main()
