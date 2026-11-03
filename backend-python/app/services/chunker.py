"""Deterministic text chunking (docs/INTERNAL_API.md §2).

Rules
-----
1. Split on blank lines into paragraphs (``\\r\\n``/``\\r`` normalized to ``\\n``).
2. Merge consecutive paragraphs as long as the result stays within ``max_chars``.
3. When a new chunk is started, keep the tail ``overlap`` characters of the
   previous chunk as its prefix (dropped only if the prefix would make the chunk
   longer than ``max_chars``).
4. Every chunk is ``strip()``-ed and non-empty; ``seq`` starts at 0 and increases
   by one with no gaps.
5. Lengths are counted in Unicode code points, so one Chinese character == 1.

The algorithm is pure: same input and parameters always yield the same chunks.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_PARAGRAPH_SPLIT = re.compile(r"\n[ \t]*\n+")


@dataclass(frozen=True)
class Chunk:
    seq: int
    content: str

    @property
    def char_count(self) -> int:
        return len(self.content)


def normalize_text(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _split_paragraphs(text: str, max_chars: int) -> list[str]:
    """Split into non-empty paragraphs, hard-splitting over-long ones."""

    paragraphs: list[str] = []
    for raw in _PARAGRAPH_SPLIT.split(text):
        paragraph = raw.strip()
        while len(paragraph) > max_chars:
            head = paragraph[:max_chars].strip()
            if head:
                paragraphs.append(head)
            paragraph = paragraph[max_chars:].lstrip()
        if paragraph:
            paragraphs.append(paragraph)
    return paragraphs


def chunk_text(text: str, max_chars: int = 600, overlap: int = 80) -> list[Chunk]:
    if max_chars < 1:
        raise ValueError("max_chars must be >= 1")
    if overlap < 0:
        raise ValueError("overlap must be >= 0")

    paragraphs = _split_paragraphs(normalize_text(text), max_chars)
    raw_chunks: list[str] = []
    buffer = ""

    def flush() -> None:
        nonlocal buffer
        content = buffer.strip()
        if content:
            raw_chunks.append(content)
        buffer = ""

    for paragraph in paragraphs:
        if not buffer:
            buffer = paragraph
            continue

        candidate = f"{buffer}\n\n{paragraph}"
        if len(candidate) <= max_chars:
            buffer = candidate
            continue

        tail = buffer[-overlap:] if overlap else ""
        flush()
        if tail:
            carried = f"{tail}\n{paragraph}"
            buffer = carried if len(carried) <= max_chars else paragraph
        else:
            buffer = paragraph

    flush()

    return [Chunk(seq=index, content=content) for index, content in enumerate(raw_chunks)]


__all__ = ["Chunk", "chunk_text", "normalize_text"]
