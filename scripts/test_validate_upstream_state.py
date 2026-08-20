#!/usr/bin/env python3
"""Tests for validate-upstream-state.py."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).with_name("validate-upstream-state.py")
SPEC = importlib.util.spec_from_file_location("validate_upstream_state", SCRIPT)
assert SPEC and SPEC.loader
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)

A = "a" * 40
B = "b" * 40
C = "c" * 40
D = "d" * 40
E = "e" * 40


def state(version: int = 2) -> dict:
    data = {
        "schema_version": version,
        "fork_base": A,
        "review_start_exclusive": B,
        "reviewed_through": D,
        "entries": [
            {"commit": C, "disposition": "deferred", "reason": "feature-review"},
            {"commit": D, "disposition": "deferred", "reason": "product-scope"},
        ],
    }
    if version == 2:
        data["resolutions"] = []
    return data


def resolution(
    upstream: str = C, disposition: str = "accepted", **updates: object
) -> dict:
    result = {
        "upstream_commit": upstream,
        "disposition": disposition,
        "reason": "downstream-adaptation",
        "downstream_commit": E,
    }
    result.update(updates)
    return result


class SchemaTests(unittest.TestCase):
    def load(self, data: dict) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "UPSTREAM.json"
            path.write_text(json.dumps(data))
            return validator.load_state(path)

    def test_v1_compatibility(self) -> None:
        loaded = self.load(state(1))
        self.assertEqual(validator.validate_resolutions(loaded), [])

    def test_v2_empty_resolutions(self) -> None:
        loaded = self.load(state())
        self.assertEqual(validator.validate_resolutions(loaded), [])

    def test_v1_rejects_resolutions_key(self) -> None:
        data = state(1)
        data["resolutions"] = []
        with self.assertRaisesRegex(validator.ValidationError, "root keys"):
            self.load(data)

    def test_schema_version_rejects_boolean(self) -> None:
        data = state()
        data["schema_version"] = True
        with self.assertRaisesRegex(validator.ValidationError, "must be an integer"):
            self.load(data)

    def test_cli_summary_reports_raw_resolved_and_unresolved_deferred(self) -> None:
        data = state()
        data["resolutions"] = [resolution()]
        output = io.StringIO()
        with (
            mock.patch.object(validator, "load_state", return_value=data),
            mock.patch.object(validator, "validate_git_state"),
            mock.patch.object(validator.sys, "argv", [str(SCRIPT)]),
            redirect_stdout(output),
        ):
            self.assertEqual(validator.main(), 0)
        self.assertIn("raw_deferred=2", output.getvalue())
        self.assertIn("resolved=1", output.getvalue())
        self.assertIn("unresolved_deferred=1", output.getvalue())


class ResolutionTests(unittest.TestCase):
    def validate(self, resolutions: list[dict]) -> list[dict]:
        data = state()
        data["resolutions"] = resolutions
        validator.validate_entries(data)
        return validator.validate_resolutions(data)

    def test_valid_accepted_partial_and_excluded(self) -> None:
        entries = state()["entries"]
        entries.append(
            {"commit": "f" * 40, "disposition": "deferred", "reason": "security-review"}
        )
        data = state()
        data["entries"] = entries
        data["resolutions"] = [
            resolution(C),
            resolution(
                D,
                "partial",
                reason="source-compatibility-only",
                scope=["app/src/Main.kt"],
            ),
            resolution("f" * 40, "excluded", reason="product-scope"),
        ]
        validator.validate_entries(data)
        self.assertEqual(len(validator.validate_resolutions(data)), 3)

    def test_duplicate_upstream(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "exactly once"):
            self.validate([resolution(), resolution()])

    def test_non_deferred_entry(self) -> None:
        data = state()
        data["entries"][0]["disposition"] = "accepted"
        data["resolutions"] = [resolution()]
        validator.validate_entries(data)
        with self.assertRaisesRegex(
            validator.ValidationError, "original disposition deferred"
        ):
            validator.validate_resolutions(data)

    def test_unknown_upstream(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "existing entry"):
            self.validate([resolution("f" * 40)])

    def test_out_of_order(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "original entry order"):
            self.validate([resolution(D), resolution(C)])

    def test_bad_reason(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "reason is invalid"):
            self.validate([resolution(reason="not-allowed")])

    def test_malformed_enum_types_are_validation_errors(self) -> None:
        for key, value, message in (
            ("disposition", [], "disposition is invalid"),
            ("reason", {}, "reason is invalid"),
        ):
            malformed = resolution()
            malformed[key] = value
            with (
                self.subTest(key=key),
                self.assertRaisesRegex(validator.ValidationError, message),
            ):
                self.validate([malformed])

        data = state()
        data["entries"][0]["disposition"] = []
        with self.assertRaisesRegex(
            validator.ValidationError, "disposition is invalid"
        ):
            validator.validate_entries(data)

        data = state()
        data["entries"][0]["reason"] = {}
        with self.assertRaisesRegex(validator.ValidationError, "reason is invalid"):
            validator.validate_entries(data)

    def test_deferred_is_not_a_resolution_disposition(self) -> None:
        with self.assertRaisesRegex(
            validator.ValidationError, "disposition is invalid"
        ):
            self.validate([resolution(disposition="deferred")])

    def test_bad_scope(self) -> None:
        with self.assertRaisesRegex(validator.ValidationError, "scope is required"):
            self.validate([resolution(disposition="partial")])
        with self.assertRaisesRegex(
            validator.ValidationError, "scope requires partial"
        ):
            self.validate([resolution(scope=["app/src/Main.kt"])])


class GitResolutionTests(unittest.TestCase):
    def run_validation(
        self, git_side_effect, *, check_branch_base=True, old=None
    ) -> None:
        data = state()
        data["resolutions"] = [resolution()]
        commits = validator.validate_entries(data)
        resolutions = validator.validate_resolutions(data)
        previous = subprocess.CompletedProcess([], 0, json.dumps(old or state()), "")
        with (
            mock.patch.object(validator, "git", side_effect=git_side_effect),
            mock.patch.object(validator.subprocess, "run", return_value=previous),
        ):
            validator.validate_git_state(data, commits, resolutions, check_branch_base)

    @staticmethod
    def normal_git(*args: str) -> str:
        table = {
            ("rev-parse", "upstream/main"): D,
            ("rev-list", "--reverse", f"{B}..{D}"): f"{C}\n{D}",
            ("rev-parse", "origin/main"): A,
            ("merge-base", "HEAD", "origin/main"): A,
        }
        if args in table:
            return table[args]
        if args[0:2] == ("merge-base", "--is-ancestor"):
            return ""
        if args == ("cat-file", "-e", f"{E}^{{commit}}"):
            return ""
        raise AssertionError(args)

    def test_valid_new_resolution(self) -> None:
        self.run_validation(self.normal_git)

    def test_unknown_downstream(self) -> None:
        def fake(*args: str) -> str:
            if args == ("cat-file", "-e", f"{E}^{{commit}}"):
                raise validator.ValidationError("missing")
            return self.normal_git(*args)

        with self.assertRaisesRegex(validator.ValidationError, "does not exist"):
            self.run_validation(fake)

    def test_non_ancestor_downstream(self) -> None:
        def fake(*args: str) -> str:
            if args == ("merge-base", "--is-ancestor", E, "HEAD"):
                raise validator.ValidationError("not ancestor")
            return self.normal_git(*args)

        with self.assertRaisesRegex(validator.ValidationError, "ancestor of HEAD"):
            self.run_validation(fake)

    def test_pre_base_new_resolution(self) -> None:
        def fake(*args: str) -> str:
            if args == ("merge-base", "--is-ancestor", A, E):
                raise validator.ValidationError("predates")
            return self.normal_git(*args)

        with self.assertRaisesRegex(validator.ValidationError, "after fork_base"):
            self.run_validation(fake)

    def test_previous_resolution_mutation(self) -> None:
        old = state()
        old["resolutions"] = [resolution(reason="feature-review")]
        with self.assertRaisesRegex(validator.ValidationError, "previous resolutions"):
            self.run_validation(self.normal_git, old=old)

    def test_existing_resolution_may_predate_new_branch_base(self) -> None:
        old = state()
        old["resolutions"] = [resolution()]
        self.run_validation(self.normal_git, old=old)


if __name__ == "__main__":
    unittest.main()
