#!/usr/bin/env python3
"""Tests for localized-resource placeholder validation."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-localized-resources.py")
SPEC = importlib.util.spec_from_file_location("check_localized_resources", SCRIPT)
assert SPEC and SPEC.loader
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class PlaceholderTests(unittest.TestCase):
    def test_unindexed_arguments_preserve_order(self) -> None:
        self.assertTrue(checker.placeholders_match("%s: %d", "%s：%d"))
        self.assertFalse(checker.placeholders_match("%s: %d", "%d：%s"))

    def test_indexed_arguments_may_reorder(self) -> None:
        self.assertTrue(checker.placeholders_match("%1$s: %2$d", "%2$d：%1$s"))

    def test_literal_percent_is_not_an_argument(self) -> None:
        self.assertTrue(checker.placeholders_match("%1$d%%", "%1$d%%"))

    def test_missing_argument_fails(self) -> None:
        self.assertFalse(checker.placeholders_match("%1$s: %2$d", "%1$s"))


if __name__ == "__main__":
    unittest.main()
