"""Stage 3 T3.4/T3.5 and Stage 4: retrieval and SSE fallback behavior."""

from __future__ import annotations

import asyncio
import json

from app.services import qdrant_store
from app.services.chat import ChatHit
from app.routers.internal import (
    ChatStreamRequest,
    _chat_event_stream,
    _is_conversational_question,
)
from tests.conftest import request


def _events(response) -> list[tuple[str, dict]]:
    parsed: list[tuple[str, dict]] = []
    event = None
    data_lines: list[str] = []
    for raw_line in response.text.splitlines():
        if raw_line.startswith("event:"):
            event = raw_line.split(":", 1)[1].strip()
        elif raw_line.startswith("data:"):
            data_lines.append(raw_line.split(":", 1)[1].strip())
        elif raw_line == "" and event and data_lines:
            parsed.append((event, json.loads("\n".join(data_lines))))
            event = None
            data_lines = []
    return parsed


def test_no_retrieval_uses_general_model_after_explicit_notice(monkeypatch):
    calls: list[tuple[str, list[dict[str, str]]]] = []

    async def _empty_search(**kwargs):
        return []

    class FakeGeneralProvider:
        name = "openai-compatible:deepseek-chat"

        async def stream_general_answer(self, query, history):
            calls.append((query, list(history)))
            yield "这是基于通用知识的回答，不代表企业内部制度。"

    monkeypatch.setattr(qdrant_store, "search", _empty_search)
    monkeypatch.setattr(
        "app.routers.internal.get_chat_provider",
        lambda: FakeGeneralProvider(),
    )

    response = request(
        "POST",
        "/internal/chat/stream",
        json={
            "kb_ids": [1],
            "question": "Java 的垃圾回收有哪些算法？",
            "history": [{"role": "user", "content": "之前的问题"}],
        },
    )

    assert response.status_code == 200, response.text
    events = _events(response)
    assert [name for name, _ in events] == ["retrieval", "delta", "delta", "done"]
    retrieval = events[0][1]
    assert retrieval["mode"] == "general_fallback"
    assert retrieval["chat_provider"] == "openai-compatible:deepseek-chat"
    assert retrieval["citations"] == []
    answer = "".join(data["text"] for name, data in events if name == "delta")
    assert answer.startswith("知识库中未找到相关依据。\n\n")
    assert "基于通用知识" in answer
    assert calls == [
        (
            "Java 的垃圾回收有哪些算法？",
            [{"role": "user", "content": "之前的问题"}],
        )
    ]
    done = events[-1][1]
    assert done["answer"] == answer
    assert done["finish_reason"] == "stop"
    assert done["mode"] == "general_fallback"
    assert done["citations"] == []
    assert all(name != "no_answer" for name, _ in events)


def test_stream_emits_retrieval_deltas_done_and_only_retrieved_citations(monkeypatch):
    async def _search(**kwargs):
        return [
            {
                "doc_id": 5,
                "kb_id": 1,
                "seq": 2,
                "score": 0.82,
                "content": "报销需提交发票。",
            },
            {
                "doc_id": 5,
                "kb_id": 1,
                "seq": 3,
                "score": 0.61,
                "content": "审批人为直属主管。",
            },
        ]

    class FakeProvider:
        name = "fake-stream"

        async def stream_answer(self, query, context, history):
            assert query == "报销怎么走？"
            assert len(context.blocks) == 2
            yield "先提交发票"
            yield "[1]，再等待审批。"

    monkeypatch.setattr(qdrant_store, "search", _search)
    monkeypatch.setattr("app.routers.internal.get_chat_provider", lambda: FakeProvider())

    response = request(
        "POST",
        "/internal/chat/stream",
        json={
            "kb_ids": [1],
            "question": "报销怎么走？",
            "history": [{"role": "user", "content": "之前的问题"}],
        },
    )

    assert response.status_code == 200, response.text
    events = _events(response)
    assert [name for name, _ in events] == ["retrieval", "delta", "delta", "done"]
    assert events[0][1]["citations"][0]["doc_id"] == 5
    assert "".join(data["text"] for name, data in events if name == "delta") == (
        "先提交发票[1]，再等待审批。"
    )
    done = events[-1][1]
    assert done["answer"] == "先提交发票[1]，再等待审批。"
    assert [citation["rank"] for citation in done["citations"]] == [1]
    assert done["citations"][0]["seq"] == 2


def test_empty_scope_uses_general_model_without_qdrant(monkeypatch):
    def _boom(**kwargs):
        raise AssertionError("Qdrant must not be called with an empty authorised scope")

    class FakeGeneralProvider:
        name = "openai-compatible:deepseek-chat"

        async def stream_general_answer(self, query, history):
            assert query == "任意问题"
            yield "这是基于通用知识的回答，不代表企业内部制度。"

    monkeypatch.setattr(qdrant_store, "search", _boom)
    monkeypatch.setattr(
        "app.routers.internal.get_chat_provider",
        lambda: FakeGeneralProvider(),
    )
    response = request(
        "POST",
        "/internal/chat/stream",
        json={"kb_ids": [], "question": "任意问题"},
    )
    assert response.status_code == 200
    events = _events(response)
    assert [name for name, _ in events] == ["retrieval", "delta", "delta", "done"]
    assert events[0][1]["mode"] == "general_fallback"
    answer = "".join(data["text"] for name, data in events if name == "delta")
    assert answer.startswith("知识库中未找到相关依据。\n\n")
    assert "这是基于通用知识的回答" in answer
    assert events[-1][1]["finish_reason"] == "stop"


def test_greeting_streams_conversation_without_retrieval(monkeypatch):
    conversation_calls: list[tuple[str, list[dict[str, str]]]] = []

    class FakeConversationProvider:
        name = "openai-compatible:deepseek-chat"

        async def stream_conversation(self, query, history):
            conversation_calls.append((query, list(history)))
            yield "你好，我是知库 AI 助手。"
            yield "我可以基于知识库资料回答并给出引用。"

    def _search_must_not_run(**kwargs):
        raise AssertionError("greetings must bypass Qdrant")

    async def _embedding_must_not_run(*args, **kwargs):
        raise AssertionError("greetings must bypass embedding")

    monkeypatch.setattr(qdrant_store, "search", _search_must_not_run)
    monkeypatch.setattr("app.routers.internal._embed", _embedding_must_not_run)
    monkeypatch.setattr(
        "app.routers.internal.get_chat_provider",
        lambda: FakeConversationProvider(),
    )

    response = request(
        "POST",
        "/internal/chat/stream",
        json={
            "kb_ids": [1],
            "question": "你好",
            "history": [{"role": "user", "content": "上一次问题"}],
        },
    )

    assert response.status_code == 200, response.text
    events = _events(response)
    assert [name for name, _ in events] == ["retrieval", "delta", "delta", "done"]
    retrieval = events[0][1]
    assert retrieval["mode"] == "conversation"
    assert retrieval["chat_provider"] == "openai-compatible:deepseek-chat"
    assert retrieval["citations"] == []
    assert conversation_calls == [("你好", [{"role": "user", "content": "上一次问题"}])]
    done = events[-1][1]
    assert done["answer"] == "你好，我是知库 AI 助手。我可以基于知识库资料回答并给出引用。"
    assert done["citations"] == []
    assert all(name != "no_answer" for name, _ in events)


def test_conversation_detection_does_not_swallow_knowledge_questions():
    assert _is_conversational_question("你好呀") is True
    assert _is_conversational_question("谢谢！") is True
    assert _is_conversational_question("你好，报销怎么走？") is False
    assert _is_conversational_question("你能做什么，顺便查一下报销流程") is False


def test_closing_stream_closes_provider_generator(monkeypatch):
    closed = False

    class CancellableProvider:
        name = "cancellable"

        async def stream_answer(self, query, context, history):
            nonlocal closed
            try:
                yield "第一段"
                await asyncio.sleep(10)
            finally:
                closed = True

    monkeypatch.setattr("app.routers.internal.get_chat_provider", lambda: CancellableProvider())
    request_model = ChatStreamRequest(kb_ids=[1], question="问题")
    hits = [
        ChatHit(doc_id=1, kb_id=1, seq=0, content="资料", score=0.9),
    ]

    async def run():
        generator = _chat_event_stream(
            request=request_model,
            hits=hits,
            embedding_provider="local-hash-v1",
        )
        assert (await generator.__anext__()).startswith("event: retrieval")
        assert (await generator.__anext__()).startswith("event: delta")
        await generator.aclose()

    asyncio.run(run())
    assert closed is True
