"""Chunker determinism, overlap and sequence guarantees."""

from __future__ import annotations

from app.services.chunker import chunk_text


def _paragraphs(count: int, length: int) -> list[str]:
    base = "报销流程需要提交发票并经部门负责人审批。"
    return [(base * ((length // len(base)) + 1))[:length] for _ in range(count)]


def test_seq_is_continuous_and_chunks_fit_budget():
    text = "\n\n".join(_paragraphs(30, 90))
    chunks = chunk_text(text, max_chars=300, overlap=60)

    assert chunks
    assert [chunk.seq for chunk in chunks] == list(range(len(chunks)))
    assert all(len(chunk.content) <= 300 for chunk in chunks)
    assert all(chunk.content == chunk.content.strip() and chunk.content for chunk in chunks)


def test_overlap_is_shared_between_neighbours():
    text = "\n\n".join(_paragraphs(12, 60))
    chunks = chunk_text(text, max_chars=200, overlap=30)

    assert len(chunks) > 1
    for previous, current in zip(chunks, chunks[1:]):
        tail = previous.content[-30:].strip()
        assert current.content.lstrip().startswith(tail)


def test_long_paragraph_is_hard_split_without_data_loss():
    text = "第一段内容" * 800  # 4000 chars, no blank lines at all
    chunks = chunk_text(text, max_chars=500, overlap=50)

    assert len(chunks) >= 8
    assert all(len(chunk.content) <= 500 for chunk in chunks)
    assert "第一段内容" in chunks[-1].content
    # Every original character is present in at least one chunk.
    joined = "".join(chunk.content for chunk in chunks)
    assert joined.count("第一段内容") >= 800


def test_trailing_text_is_preserved():
    text = "\n\n".join(_paragraphs(5, 120)) + "\n\n最后一段必须保留"
    chunks = chunk_text(text, max_chars=300, overlap=40)

    assert chunks[-1].content.endswith("最后一段必须保留")


def test_deterministic_output():
    text = "\n\n".join(_paragraphs(10, 100))
    first = [(chunk.seq, chunk.content) for chunk in chunk_text(text, 250, 50)]
    second = [(chunk.seq, chunk.content) for chunk in chunk_text(text, 250, 50)]
    assert first == second


def test_blank_text_yields_no_chunks():
    assert chunk_text("   \n\n \t \n", 600, 80) == []
