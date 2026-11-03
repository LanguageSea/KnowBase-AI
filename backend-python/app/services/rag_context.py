"""Context construction for grounded RAG answers (Stage 3).

The context builder is deliberately deterministic:

* hits are ordered by score, then by stable location fields;
* duplicate content and duplicate ``(doc_id, seq)`` hits are removed;
* the whole chunk is either accepted or dropped, never truncated;
* source numbers are assigned only after the final set is known.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Sequence

if TYPE_CHECKING:
    from app.services.chat import ChatHit

MAX_CONTEXT_TOKENS = 3000
MAX_CONTEXT_CHUNKS = 8
SOURCE_OVERHEAD_TOKENS = 16


@dataclass(frozen=True)
class ContextBlock:
    rank: int
    hit: "ChatHit"
    token_count: int


@dataclass(frozen=True)
class ContextResult:
    blocks: list[ContextBlock]

    @property
    def hits(self) -> list["ChatHit"]:
        return [block.hit for block in self.blocks]

    @property
    def text(self) -> str:
        return "\n\n".join(
            (
                f'<source id="{block.rank}" doc_id="{block.hit.doc_id}" '
                f'seq="{block.hit.seq}">\n{block.hit.content}\n</source>'
            )
            for block in self.blocks
        )


def estimate_tokens(text: str) -> int:
    """Conservative character-based estimate used only for the context budget.

    This is intentionally not marketed as model token accounting. Counting CJK
    characters and Latin words separately is stable, testable and avoids a
    tokenizer dependency in the online path.
    """

    if not text:
        return 0
    cjk = sum(
        1
        for char in text
        if "\u3400" <= char <= "\u4dbf"
        or "\u4e00" <= char <= "\u9fff"
        or "\uf900" <= char <= "\ufaff"
    )
    latin_words = sum(1 for word in text.replace("\n", " ").split() if word and word.isascii())
    other = max(0, len(text) - cjk)
    return max(1, cjk + latin_words + math.ceil(other / 4))


def _normalized_content(text: str) -> str:
    return " ".join(text.split()).casefold()


def build_context(
    hits: Sequence["ChatHit"],
    *,
    token_budget: int = MAX_CONTEXT_TOKENS,
    max_chunks: int = MAX_CONTEXT_CHUNKS,
) -> ContextResult:
    if token_budget < 1:
        raise ValueError("token_budget must be positive")
    if max_chunks < 1:
        raise ValueError("max_chunks must be positive")

    ordered = sorted(
        hits,
        key=lambda hit: (-(hit.score or 0.0), hit.doc_id, hit.seq),
    )
    seen_locations: set[tuple[int, int]] = set()
    seen_content: set[str] = set()
    blocks: list[ContextBlock] = []
    used_tokens = 0

    for hit in ordered:
        location = (hit.doc_id, hit.seq)
        normalized = _normalized_content(hit.content)
        if not normalized or location in seen_locations or normalized in seen_content:
            continue

        content_tokens = estimate_tokens(hit.content)
        if content_tokens + SOURCE_OVERHEAD_TOKENS > token_budget:
            # Whole-block policy: never cut a retrieved chunk in half.
            continue
        if used_tokens + content_tokens + SOURCE_OVERHEAD_TOKENS > token_budget:
            continue

        blocks.append(
            ContextBlock(
                rank=len(blocks) + 1,
                hit=hit,
                token_count=content_tokens,
            )
        )
        seen_locations.add(location)
        seen_content.add(normalized)
        used_tokens += content_tokens + SOURCE_OVERHEAD_TOKENS
        if len(blocks) >= max_chunks:
            break

    return ContextResult(blocks=blocks)


__all__ = [
    "ContextBlock",
    "ContextResult",
    "MAX_CONTEXT_CHUNKS",
    "MAX_CONTEXT_TOKENS",
    "build_context",
    "estimate_tokens",
]
