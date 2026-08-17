#!/usr/bin/env python3
"""Validate the machine-owned upstream reconciliation state."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

HASH_RE = re.compile(r"^[0-9a-f]{40}$")
ROOT_KEYS = {
    "schema_version",
    "fork_base",
    "review_start_exclusive",
    "reviewed_through",
    "entries",
}
ENTRY_KEYS = {"commit", "disposition", "reason"}
DISPOSITIONS = {"accepted", "deferred", "excluded", "partial"}
REASONS = {
    "distribution-policy",
    "downstream-adaptation",
    "downstream-conflict",
    "feature-review",
    "patch-dependency",
    "product-scope",
    "release-correctness",
    "security-review",
    "source-compatibility-only",
    "toolchain-validation",
}


class ValidationError(Exception):
    """Raised when reconciliation state is invalid."""


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ValidationError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout.strip()


def require_hash(value: Any, field: str) -> str:
    if not isinstance(value, str) or not HASH_RE.fullmatch(value):
        raise ValidationError(f"{field} must be a full lowercase commit hash")
    return value


def load_state(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"cannot read {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ValidationError("state root must be an object")
    if set(data) != ROOT_KEYS:
        raise ValidationError(
            f"state root keys must be exactly {sorted(ROOT_KEYS)}",
        )
    if data["schema_version"] != 1:
        raise ValidationError("unsupported schema_version")
    return data


def validate_entries(data: dict[str, Any]) -> list[str]:
    entries = data["entries"]
    if not isinstance(entries, list):
        raise ValidationError("entries must be an array")

    commits: list[str] = []
    for index, entry in enumerate(entries):
        label = f"entries[{index}]"
        if not isinstance(entry, dict):
            raise ValidationError(f"{label} must be an object")
        keys = set(entry)
        allowed_with_scope = ENTRY_KEYS | {"scope"}
        if keys != ENTRY_KEYS and keys != allowed_with_scope:
            raise ValidationError(f"{label} has invalid keys: {sorted(keys)}")

        commit = require_hash(entry.get("commit"), f"{label}.commit")
        disposition = entry.get("disposition")
        reason = entry.get("reason")
        if disposition not in DISPOSITIONS:
            raise ValidationError(f"{label}.disposition is invalid")
        if reason not in REASONS:
            raise ValidationError(f"{label}.reason is invalid")

        scope = entry.get("scope")
        if disposition == "partial":
            if not isinstance(scope, list) or not scope:
                raise ValidationError(f"{label}.scope is required for partial")
            if not all(isinstance(item, str) and item for item in scope):
                raise ValidationError(f"{label}.scope must contain paths")
        elif scope is not None:
            raise ValidationError(f"{label}.scope requires partial disposition")
        commits.append(commit)

    if len(commits) != len(set(commits)):
        raise ValidationError("each commit must appear exactly once")
    return commits


def validate_previous_entries(
    old_entries: Any,
    entries: list[dict[str, Any]],
) -> None:
    if not isinstance(old_entries, list):
        raise ValidationError("old entries must be an array")
    if entries[: len(old_entries)] != old_entries:
        raise ValidationError("previous entries must remain an unchanged prefix")


def validate_git_state(
    data: dict[str, Any],
    commits: list[str],
    check_branch_base: bool,
) -> None:
    fork_base = require_hash(data["fork_base"], "fork_base")
    start = require_hash(
        data["review_start_exclusive"],
        "review_start_exclusive",
    )
    reviewed = require_hash(data["reviewed_through"], "reviewed_through")

    upstream = git("rev-parse", "upstream/main")
    git("merge-base", "--is-ancestor", reviewed, upstream)

    expected = git("rev-list", "--reverse", f"{start}..{reviewed}")
    expected_commits = expected.splitlines() if expected else []
    if commits != expected_commits:
        raise ValidationError(
            "entries must exactly match the ordered reviewed commit range",
        )

    if check_branch_base:
        origin = git("rev-parse", "origin/main")
        if fork_base != origin:
            raise ValidationError("fork_base must equal freshly fetched origin/main")
        merge_base = git("merge-base", "HEAD", "origin/main")
        if merge_base != fork_base:
            raise ValidationError("branch must start at recorded fork_base")

    previous = subprocess.run(
        ["git", "show", "origin/main:UPSTREAM.json"],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )
    if previous.returncode:
        return
    old = json.loads(previous.stdout)
    old_reviewed = require_hash(old.get("reviewed_through"), "old reviewed")
    git("merge-base", "--is-ancestor", old_reviewed, reviewed)
    if old.get("review_start_exclusive") != start:
        raise ValidationError("review_start_exclusive may not change")
    validate_previous_entries(old.get("entries"), data["entries"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--state",
        type=Path,
        default=Path("UPSTREAM.json"),
    )
    parser.add_argument("--check-branch-base", action="store_true")
    args = parser.parse_args()

    try:
        data = load_state(args.state)
        commits = validate_entries(data)
        validate_git_state(data, commits, args.check_branch_base)
    except (ValidationError, json.JSONDecodeError) as exc:
        print(f"upstream state invalid: {exc}", file=sys.stderr)
        return 1

    counts: dict[str, int] = {}
    for entry in data["entries"]:
        disposition = entry["disposition"]
        counts[disposition] = counts.get(disposition, 0) + 1
    summary = " ".join(
        f"{key}={counts.get(key, 0)}"
        for key in sorted(DISPOSITIONS)
    )
    print(f"upstream state valid: entries={len(commits)} {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
