#!/usr/bin/env python3
"""Check that the Russian resources match the English source strings.

The checker compares resource *names* (strings and plurals) and the format
placeholders inside them. A key marked `translatable="false"` in the source is
exempt: Android does not expect it in a translation.

Usage:
    python tools/check_localization.py
    python tools/check_localization.py --en path/to/values/strings.xml --ru path/to/values-ru/strings.xml

Exits with 0 when the files match, 1 on missing/extra keys, empty translations,
placeholder mismatches, or missing Russian plural categories, and 2 when a
file cannot be parsed or holds a duplicate name.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EN = REPO_ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
DEFAULT_RU = REPO_ROOT / "app" / "src" / "main" / "res" / "values-ru" / "strings.xml"

# Android format specifiers: %s, %1$d, %02d, %1$.2f, %%, etc.
FORMAT_SPEC = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# Russian needs all four Android plural categories; a missing one silently falls back to "other".
RU_PLURAL_QUANTITIES = ("one", "few", "many", "other")


@dataclass
class Entry:
    """One <string> or <plurals> resource."""

    name: str
    kind: str
    translatable: bool = True
    # A string uses the "" key; a plural uses its quantity names.
    values: dict[str, str] = field(default_factory=dict)


def read_resources(path: Path) -> dict[str, Entry]:
    root = ET.parse(path).getroot()
    entries: dict[str, Entry] = {}
    for element in root:
        if element.tag not in ("string", "plurals"):
            continue
        name = element.get("name")
        if not name:
            continue
        if name in entries:
            raise ValueError(f"{path}: duplicate resource name {name!r}")
        translatable = element.get("translatable") != "false"
        values: dict[str, str] = {}
        if element.tag == "string":
            values[""] = "".join(element.itertext())
        else:
            for item in element:
                if item.tag == "item" and item.get("quantity"):
                    values[item.get("quantity", "")] = "".join(item.itertext())
        entries[name] = Entry(name, element.tag, translatable, values)
    return entries


def placeholders(value: str) -> Counter[str]:
    """Format specifiers of [value], with literal %% removed."""
    return Counter(m.group(0) for m in FORMAT_SPEC.finditer(value) if m.group(0) != "%%")


def compare(en: dict[str, Entry], ru: dict[str, Entry]) -> list[str]:
    problems: list[str] = []

    en_required = {name for name, entry in en.items() if entry.translatable}
    missing = sorted(en_required - set(ru))
    extra = sorted(set(ru) - set(en))

    for name in missing:
        problems.append(f"missing in ru: {name}")
    for name in extra:
        problems.append(f"extra in ru: {name}")

    for name in sorted(en_required & set(ru)):
        source = en[name]
        target = ru[name]
        if source.kind != target.kind:
            problems.append(f"kind mismatch: {name} is <{source.kind}> in en, <{target.kind}> in ru")
            continue
        for quantity, value in target.values.items():
            if not value.strip():
                label = name if not quantity else f"{name}[{quantity}]"
                problems.append(f"empty translation: {label}")
        if target.kind == "plurals":
            for quantity in RU_PLURAL_QUANTITIES:
                if quantity not in target.values:
                    problems.append(f"missing Russian plural category: {name}[{quantity}]")
        for quantity, value in source.values.items():
            if quantity not in target.values:
                problems.append(f"missing plural quantity: {name}[{quantity}]")
                continue
            expected = placeholders(value)
            actual = placeholders(target.values[quantity])
            if expected != actual:
                label = name if not quantity else f"{name}[{quantity}]"
                problems.append(
                    f"placeholder mismatch: {label}: en {sorted(expected.elements())}, "
                    f"ru {sorted(actual.elements())}",
                )
    return problems


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--en", type=Path, default=DEFAULT_EN, help="source strings.xml")
    parser.add_argument("--ru", type=Path, default=DEFAULT_RU, help="Russian strings.xml")
    parser.add_argument("--quiet", action="store_true", help="print only problems")
    args = parser.parse_args()

    try:
        en = read_resources(args.en)
        ru = read_resources(args.ru)
    except (OSError, ET.ParseError, ValueError) as error:
        print(f"localization: {error}", file=sys.stderr)
        return 2

    problems = compare(en, ru)
    exempt = sum(1 for entry in en.values() if not entry.translatable)
    if not args.quiet:
        print(f"EN: {len(en)} resources ({exempt} non-translatable)")
        print(f"RU: {len(ru)} resources")
        print(f"missing in RU: {sum(1 for p in problems if p.startswith('missing in ru:'))}")
        print(f"extra in RU: {sum(1 for p in problems if p.startswith('extra in ru:'))}")
        print(f"other problems: {sum(1 for p in problems if not p.startswith(('missing in ru:', 'extra in ru:')))}")

    if problems:
        for problem in problems:
            print(f"  {problem}")
        print(f"FAIL: {len(problems)} localization problem(s)", file=sys.stderr)
        return 1

    if not args.quiet:
        print("OK: every translatable resource has a matching Russian translation")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
