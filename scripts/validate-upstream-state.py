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
ROOT_KEYS_V1 = {
    "schema_version",
    "fork_base",
    "review_start_exclusive",
    "reviewed_through",
    "entries",
}
ROOT_KEYS_V2 = ROOT_KEYS_V1 | {"resolutions"}
ROOT_KEYS_V3 = ROOT_KEYS_V2
ENTRY_KEYS = {"commit", "disposition", "reason"}
RESOLUTION_KEYS = {"upstream_commit", "disposition", "reason", "downstream_commit"}
DISPOSITIONS = {"accepted", "deferred", "excluded", "partial"}
RESOLUTION_DISPOSITIONS = {"accepted", "excluded", "partial"}
REASONS = {
    "distribution-policy",
    "downstream-adaptation",
    "downstream-conflict",
    "downstream-equivalent",
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
        capture_output=True,
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
    version = data.get("schema_version")
    if type(version) is not int:
        raise ValidationError("schema_version must be an integer")
    expected_keys = {1: ROOT_KEYS_V1, 2: ROOT_KEYS_V2, 3: ROOT_KEYS_V3}.get(version)
    if expected_keys is None:
        raise ValidationError("unsupported schema_version")
    if set(data) != expected_keys:
        raise ValidationError(
            f"state root keys must be exactly {sorted(expected_keys)}",
        )
    return data


def validate_scope(scope: Any, disposition: Any, label: str) -> None:
    if disposition == "partial":
        if not isinstance(scope, list) or not scope:
            raise ValidationError(f"{label}.scope is required for partial")
        if not all(isinstance(item, str) and item for item in scope):
            raise ValidationError(f"{label}.scope must contain paths")
    elif scope is not None:
        raise ValidationError(f"{label}.scope requires partial disposition")


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
        if not isinstance(disposition, str) or disposition not in DISPOSITIONS:
            raise ValidationError(f"{label}.disposition is invalid")
        if not isinstance(reason, str) or reason not in REASONS:
            raise ValidationError(f"{label}.reason is invalid")
        validate_scope(entry.get("scope"), disposition, label)
        commits.append(commit)

    if len(commits) != len(set(commits)):
        raise ValidationError("each commit must appear exactly once")
    return commits


def validate_resolutions(data: dict[str, Any]) -> list[dict[str, Any]]:
    if data["schema_version"] == 1:
        return []
    resolutions = data["resolutions"]
    if not isinstance(resolutions, list):
        raise ValidationError("resolutions must be an array")

    entry_positions = {
        entry["commit"]: (index, entry["disposition"])
        for index, entry in enumerate(data["entries"])
    }
    upstream_commits: list[str] = []
    positions: list[int] = []
    for index, resolution in enumerate(resolutions):
        label = f"resolutions[{index}]"
        if not isinstance(resolution, dict):
            raise ValidationError(f"{label} must be an object")
        keys = set(resolution)
        allowed_with_scope = RESOLUTION_KEYS | {"scope"}
        if keys != RESOLUTION_KEYS and keys != allowed_with_scope:
            raise ValidationError(f"{label} has invalid keys: {sorted(keys)}")

        upstream_commit = require_hash(
            resolution.get("upstream_commit"), f"{label}.upstream_commit"
        )
        require_hash(resolution.get("downstream_commit"), f"{label}.downstream_commit")
        disposition = resolution.get("disposition")
        if (
            not isinstance(disposition, str)
            or disposition not in RESOLUTION_DISPOSITIONS
        ):
            raise ValidationError(f"{label}.disposition is invalid")
        reason = resolution.get("reason")
        if not isinstance(reason, str) or reason not in REASONS:
            raise ValidationError(f"{label}.reason is invalid")
        validate_scope(resolution.get("scope"), disposition, label)

        entry = entry_positions.get(upstream_commit)
        if entry is None:
            raise ValidationError(
                f"{label}.upstream_commit must refer to an existing entry"
            )
        position, original_disposition = entry
        if original_disposition != "deferred":
            raise ValidationError(
                f"{label}.upstream_commit must have original disposition deferred"
            )
        upstream_commits.append(upstream_commit)
        positions.append(position)

    if len(upstream_commits) != len(set(upstream_commits)):
        raise ValidationError("each resolved upstream_commit must appear exactly once")
    if data["schema_version"] == 2 and positions != sorted(positions):
        raise ValidationError("resolutions must follow original entry order")
    return resolutions


def validate_previous_prefix(old: Any, current: list[Any], name: str) -> None:
    if not isinstance(old, list):
        raise ValidationError(f"old {name} must be an array")
    if current[: len(old)] != old:
        raise ValidationError(f"previous {name} must remain an unchanged prefix")


def require_ancestor(ancestor: str, descendant: str, message: str) -> None:
    try:
        git("merge-base", "--is-ancestor", ancestor, descendant)
    except ValidationError as exc:
        raise ValidationError(message) from exc


def validate_downstream_commit(commit: str) -> None:
    try:
        git("cat-file", "-e", f"{commit}^{{commit}}")
    except ValidationError as exc:
        raise ValidationError(f"downstream_commit {commit} does not exist") from exc
    require_ancestor(
        commit, "HEAD", f"downstream_commit {commit} must be an ancestor of HEAD"
    )


def validate_git_state(
    data: dict[str, Any],
    commits: list[str],
    resolutions: list[dict[str, Any]],
    check_branch_base: bool,
) -> None:
    fork_base = require_hash(data["fork_base"], "fork_base")
    start = require_hash(data["review_start_exclusive"], "review_start_exclusive")
    reviewed = require_hash(data["reviewed_through"], "reviewed_through")

    upstream = git("rev-parse", "upstream/main")
    require_ancestor(reviewed, upstream, "reviewed_through must be on upstream/main")
    expected = git("rev-list", "--reverse", f"{start}..{reviewed}")
    expected_commits = expected.splitlines() if expected else []
    if commits != expected_commits:
        raise ValidationError(
            "entries must exactly match the ordered reviewed commit range"
        )

    for resolution in resolutions:
        validate_downstream_commit(resolution["downstream_commit"])

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
        old_resolutions: list[dict[str, Any]] = []
    else:
        old = json.loads(previous.stdout)
        old_reviewed = require_hash(old.get("reviewed_through"), "old reviewed")
        require_ancestor(
            old_reviewed, reviewed, "reviewed_through may not move backward"
        )
        if old.get("review_start_exclusive") != start:
            raise ValidationError("review_start_exclusive may not change")
        validate_previous_prefix(old.get("entries"), data["entries"], "entries")
        old_resolutions = old.get("resolutions", [])
        validate_previous_prefix(old_resolutions, resolutions, "resolutions")

    # Only resolutions appended on this branch must postdate this branch's base.
    # Historical resolutions remain valid after main advances and fork_base changes.
    if check_branch_base:
        for resolution in resolutions[len(old_resolutions) :]:
            downstream = resolution["downstream_commit"]
            if downstream == fork_base:
                raise ValidationError("new downstream_commit must be after fork_base")
            require_ancestor(
                fork_base,
                downstream,
                "new downstream_commit must be after fork_base",
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, default=Path("UPSTREAM.json"))
    parser.add_argument("--check-branch-base", action="store_true")
    args = parser.parse_args()

    try:
        data = load_state(args.state)
        commits = validate_entries(data)
        resolutions = validate_resolutions(data)
        validate_git_state(data, commits, resolutions, args.check_branch_base)
    except (ValidationError, json.JSONDecodeError) as exc:
        print(f"upstream state invalid: {exc}", file=sys.stderr)
        return 1

    counts: dict[str, int] = {}
    for entry in data["entries"]:
        disposition = entry["disposition"]
        counts[disposition] = counts.get(disposition, 0) + 1
    raw_deferred = counts.get("deferred", 0)
    resolved = len(resolutions)
    unresolved_deferred = raw_deferred - resolved
    summary = " ".join(f"{key}={counts.get(key, 0)}" for key in sorted(DISPOSITIONS))
    print(
        f"upstream state valid: entries={len(commits)} {summary} "
        f"raw_deferred={raw_deferred} resolved={resolved} "
        f"unresolved_deferred={unresolved_deferred}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
