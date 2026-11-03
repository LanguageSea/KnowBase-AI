"""Chat library: fallback extractive answers and provider selection.

``/internal/chat`` is intentionally **not** exposed (the contract has four
endpoints); the capability is a tested library used by the Java side's T1.10
work through the documented search results.
"""

from __future__ import annotations

import asyncio

import pytest

from app.services.chat import (
    CONVERSATION_SYSTEM_PROMPT,
    GENERAL_FALLBACK_SYSTEM_PROMPT,
    ChatHit,
    LocalExtractiveChat,
    OpenAICompatibleChat,
    build_chat_provider,
    get_chat_provider,
    hits_from_search_results,
)


def _hits(count: int = 4) -> list[ChatHit]:
    return [
        ChatHit(doc_id=100 + index, kb_id=7, seq=index, content=f"第{index}段内容", score=0.9 - index / 10)
        for index in range(count)
    ]


def test_local_extractive_uses_top_three_with_markers():
    provider = LocalExtractiveChat()
    result = asyncio.run(provider.answer("报销流程", _hits(4)))

    assert result.chat_provider == "local-extractive"
    assert "[1]" in result.answer
    assert "[2]" in result.answer
    assert "[3]" in result.answer
    assert "[4]" not in result.answer
    assert len(result.citations) == 3
    assert result.citations[0]["doc_id"] == 100
    assert result.citations[0]["quote"] == "第0段内容"
    assert result.answer.index("[1]") < result.answer.index("[2]") < result.answer.index("[3]")
    # Never pretend to be a real model.
    assert "openai" not in result.chat_provider


def test_local_extractive_handles_no_hits():
    provider = LocalExtractiveChat()
    result = asyncio.run(provider.answer("报销流程", []))
    assert result.citations == []
    assert result.answer


def test_chat_provider_factory_without_key_is_extractive():
    provider = build_chat_provider()
    assert isinstance(provider, LocalExtractiveChat)
    assert get_chat_provider().name == "local-extractive"


def test_hits_adapter_from_search_payload():
    hits = hits_from_search_results(
        [{"doc_id": 1, "kb_id": 2, "seq": 0, "score": 0.5, "content": "x"}]
    )
    assert hits == [ChatHit(doc_id=1, kb_id=2, seq=0, content="x", score=0.5)]


def test_openai_chat_provider_requires_configuration():
    class FakeSettings:
        llm_api_key = "secret-key"
        llm_base_url = "https://example.invalid/v1"
        llm_chat_model = "test-model"

    provider = build_chat_provider(FakeSettings())
    assert isinstance(provider, OpenAICompatibleChat)
    assert provider.name == "openai-compatible:test-model"
    assert provider._endpoint() == "https://example.invalid/v1/chat/completions"


def test_openai_chat_provider_uses_temperature_and_token_limit_params():
    provider = OpenAICompatibleChat(
        "https://example.invalid/v1",
        "secret-key",
        "deepseek-chat",
        params={"temperature": 0.35, "max_tokens": 2048},
    )

    body = provider._build_request_body("问题", _hits(1))

    assert body["temperature"] == 0.35
    assert body["max_tokens"] == 2048
    assert body["model"] == "deepseek-chat"
    assert body["messages"][0]["role"] == "system"


def test_openai_conversation_body_has_no_retrieved_sources():
    provider = OpenAICompatibleChat(
        "https://example.invalid/v1",
        "secret-key",
        "deepseek-chat",
        params={"max_tokens": 512},
    )

    body = provider._build_conversation_body(
        "你好",
        [{"role": "assistant", "content": "请提问。", "ignored": "field"}],
    )

    assert body["stream"] is True
    assert body["max_tokens"] == 512
    assert body["messages"][0] == {
        "role": "system",
        "content": CONVERSATION_SYSTEM_PROMPT,
    }
    assert body["messages"][-1] == {"role": "user", "content": "你好"}
    assert "<source" not in str(body)
    assert "<retrieved_sources>" not in str(body)


def test_openai_general_answer_body_has_only_plain_messages():
    provider = OpenAICompatibleChat(
        "https://example.invalid/v1",
        "secret-key",
        "deepseek-chat",
    )

    body = provider._build_general_answer_body(
        "什么是向量检索？",
        [{"role": "user", "content": "之前的问题"}],
    )

    assert body["stream"] is True
    assert body["messages"][0] == {
        "role": "system",
        "content": GENERAL_FALLBACK_SYSTEM_PROMPT,
    }
    assert body["messages"][-1] == {"role": "user", "content": "什么是向量检索？"}
    assert "<source" not in str(body)
    assert "<retrieved_sources>" not in str(body)


def test_openai_chat_failure_is_503(monkeypatch):
    """Simulate an unreachable endpoint: must raise 503, never degrade."""

    from app.errors import AppError, DEPENDENCY_UNAVAILABLE

    provider = OpenAICompatibleChat("http://127.0.0.1:1/v1", "key", "m")

    with pytest.raises(AppError) as excinfo:
        asyncio.run(provider.answer("q", _hits(1)))

    assert excinfo.value.status_code == 503
    assert excinfo.value.code == DEPENDENCY_UNAVAILABLE
