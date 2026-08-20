#!/usr/bin/env python3
"""Check localized Android string catalogs for exact key and placeholder parity."""

from __future__ import annotations

import collections
import pathlib
import re
import sys
from xml.etree import ElementTree

ROOT = pathlib.Path(__file__).resolve().parents[1]
RESOURCE_ROOT = ROOT / "app" / "src" / "main" / "res"
LOCALES = ("zh", "ja", "ko")
FORMAT_ARGUMENT = re.compile(r"%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%]")


def strings(path: pathlib.Path) -> dict[str, str]:
    root = ElementTree.parse(path).getroot()
    result: dict[str, str] = {}
    duplicates: list[str] = []
    for element in root.findall("string"):
        if element.get("translatable") == "false":
            continue
        name = element.attrib["name"]
        if name in result:
            duplicates.append(name)
        result[name] = "".join(element.itertext())
    if duplicates:
        raise ValueError(f"{path}: duplicate strings: {', '.join(sorted(duplicates))}")
    return result


def placeholders(value: str) -> collections.Counter[str]:
    return collections.Counter(FORMAT_ARGUMENT.findall(value))


def main() -> int:
    default_path = RESOURCE_ROOT / "values" / "strings.xml"
    default = strings(default_path)
    failures: list[str] = []
    for locale in LOCALES:
        path = RESOURCE_ROOT / f"values-{locale}" / "strings.xml"
        if not path.exists():
            failures.append(f"{locale}: missing {path.relative_to(ROOT)}")
            continue
        localized = strings(path)
        missing = sorted(default.keys() - localized.keys())
        extra = sorted(localized.keys() - default.keys())
        if missing:
            failures.append(f"{locale}: missing keys: {', '.join(missing)}")
        if extra:
            failures.append(f"{locale}: extra keys: {', '.join(extra)}")
        for name in sorted(default.keys() & localized.keys()):
            expected = placeholders(default[name])
            actual = placeholders(localized[name])
            if expected != actual:
                failures.append(f"{locale}:{name}: placeholders {actual} != {expected}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(
        f"Resource parity OK: {len(default)} translatable strings in {', '.join(LOCALES)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
