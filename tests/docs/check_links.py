#!/usr/bin/env python3
"""Validate relative Markdown links and heading fragments in project docs."""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit

LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]*]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HEADING_PATTERN = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.MULTILINE)
CODE_FENCE_PATTERN = re.compile(r"```.*?```", re.DOTALL)


def slugify_heading(text: str) -> str:
    value = text.strip().lower()
    value = re.sub(r"[`*_~]", "", value)
    value = re.sub(r"[^\w\u4e00-\u9fff\s-]", "", value)
    value = re.sub(r"\s+", "-", value)
    return value.strip("-")


def anchors(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    text = CODE_FENCE_PATTERN.sub("", text)
    result: set[str] = set()
    counts: dict[str, int] = {}
    for heading in HEADING_PATTERN.findall(text):
        base = slugify_heading(heading)
        count = counts.get(base, 0)
        counts[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    return result


def resolve_target(source: Path, target: str, root: Path) -> tuple[Path, str]:
    parts = urlsplit(target)
    raw_path = unquote(parts.path)
    destination = source if not raw_path else (source.parent / raw_path).resolve()
    if not destination.exists() and source == root / "README.md":
        destination = (root / raw_path).resolve()
    return destination, unquote(parts.fragment)


def check(root: Path) -> list[str]:
    files = [root / "README.md", *sorted((root / "docs").rglob("*.md"))]
    failures: list[str] = []
    anchor_cache: dict[Path, set[str]] = {}
    for source in files:
        if not source.is_file():
            continue
        text = source.read_text(encoding="utf-8")
        text_without_fences = CODE_FENCE_PATTERN.sub("", text)
        for target in LINK_PATTERN.findall(text_without_fences):
            split = urlsplit(target)
            if split.scheme in {"http", "https", "mailto", "tel"}:
                continue
            if target.startswith("codex://"):
                continue
            destination, fragment = resolve_target(source, target, root)
            if not destination.exists():
                failures.append(f"{source.relative_to(root)} -> missing {target}")
                continue
            if fragment and destination.is_file() and destination.suffix.lower() == ".md":
                destination = destination.resolve()
                if destination not in anchor_cache:
                    anchor_cache[destination] = anchors(destination)
                if fragment not in anchor_cache[destination]:
                    failures.append(
                        f"{source.relative_to(root)} -> missing anchor {target}"
                    )
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    failures = check(root)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        print(f"{len(failures)} broken local link(s)", file=sys.stderr)
        return 1
    print("All local Markdown links and fragments are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
