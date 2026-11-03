"""Fallback embedding determinism and normalisation."""

from __future__ import annotations

import math

from app.services.embedding import LocalHashEmbedding, build_embedding_provider
from app.services.embedding import get_embedding_provider
from tests.conftest import run


def test_same_text_same_vector():
    provider = LocalHashEmbedding(dim=1024)
    text = "报销流程：提交发票，部门负责人审批。"

    async def _run():
        first = await provider.embed([text])
        second = await provider.embed([text])
        return first, second

    first, second = run(_run())
    assert first == second


def test_dimension_and_unit_norm():
    provider = LocalHashEmbedding(dim=1024)
    vectors = run(provider.embed(["第一段内容包含中文和 English words 以及数字 12345"]))
    vector = vectors[0]

    assert len(vector) == 1024
    norm = math.sqrt(sum(value * value for value in vector))
    assert math.isclose(norm, 1.0, rel_tol=1e-9, abs_tol=1e-9)
    assert any(value != 0.0 for value in vector)


def test_empty_text_produces_unit_vector():
    provider = LocalHashEmbedding(dim=1024)
    vector = run(provider.embed(["   "]))[0]
    assert len(vector) == 1024
    assert math.isclose(math.sqrt(sum(value * value for value in vector)), 1.0, rel_tol=1e-9)


def test_factory_uses_local_hash_without_keys():
    provider = build_embedding_provider()
    assert provider.name == "local-hash-v1"
    assert provider.dim == 1024
    assert get_embedding_provider().name == "local-hash-v1"
