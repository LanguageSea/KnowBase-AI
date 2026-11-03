"""``POST /internal/parse``: txt parsing, chunk fields and error mapping."""

from __future__ import annotations

from dataclasses import replace

from docx import Document
from pypdf import PdfWriter

from tests.conftest import request

TXT_BODY = "第一段：报销需要发票。\n\n第二段：部门负责人审批。\n\n第三段：财务复核后打款。"


def _write(tmp_path, name: str, text: str) -> str:
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return str(path)


def test_parse_txt_returns_chunks(tmp_path):
    storage_path = _write(tmp_path, "报销制度.txt", TXT_BODY)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 11,
            "kb_id": 22,
            "storage_path": storage_path,
            "file_name": "报销制度.txt",
            "ext": "txt",
            "max_chars": 200,
            "overlap": 20,
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["doc_id"] == 11
    assert body["ext"] == "txt"
    assert body["chunk_count"] == len(body["chunks"]) == 1
    chunk = body["chunks"][0]
    assert chunk["seq"] == 0
    assert chunk["page_no"] is None
    assert chunk["heading"] is None
    assert chunk["char_count"] == len(chunk["content"])
    assert "第三段" in chunk["content"]


def test_parse_defaults_from_settings(tmp_path):
    storage_path = _write(tmp_path, "a.txt", TXT_BODY)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": storage_path,
            "file_name": "a.txt",
            "ext": "txt",
        },
    )
    assert response.status_code == 200
    assert response.json()["chunk_count"] >= 1


def test_parse_unsupported_extension_is_400(tmp_path):
    storage_path = _write(tmp_path, "a.csv", TXT_BODY)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": storage_path,
            "file_name": "a.pdf",
            "ext": "csv",
        },
    )
    assert response.status_code == 400
    assert response.json()["code"] == "UNSUPPORTED_FORMAT"


def test_parse_missing_file_is_404(tmp_path):
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(tmp_path / "missing.txt"),
            "file_name": "missing.txt",
            "ext": "txt",
        },
    )
    assert response.status_code == 404
    assert response.json()["code"] == "NOT_FOUND"


def test_parse_blank_file_is_empty_content(tmp_path):
    storage_path = _write(tmp_path, "blank.txt", "   \n\n \t\n")
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": storage_path,
            "file_name": "blank.txt",
            "ext": "txt",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == "EMPTY_CONTENT"


def test_parse_zero_byte_file_is_empty_content(tmp_path):
    path = tmp_path / "zero.txt"
    path.write_bytes(b"")
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(path),
            "file_name": "zero.txt",
            "ext": "txt",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == "EMPTY_CONTENT"


def test_parse_markdown(tmp_path):
    storage_path = _write(tmp_path, "guide.md", "# 标题\n\n这是 Markdown 正文。")
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": storage_path,
            "file_name": "guide.md",
            "ext": "md",
        },
    )
    assert response.status_code == 200
    assert "Markdown 正文" in response.json()["chunks"][0]["content"]


def test_parse_docx_paragraphs_and_tables(tmp_path):
    path = tmp_path / "guide.docx"
    document = Document()
    document.add_paragraph("Docx 正文")
    table = document.add_table(rows=1, cols=2)
    table.cell(0, 0).text = "字段"
    table.cell(0, 1).text = "值"
    document.save(path)

    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(path),
            "file_name": "guide.docx",
            "ext": "docx",
        },
    )
    assert response.status_code == 200, response.text
    content = response.json()["chunks"][0]["content"]
    assert "Docx 正文" in content
    assert "字段 | 值" in content


def test_scanned_pdf_without_text_layer_is_422(tmp_path):
    path = tmp_path / "scan.pdf"
    writer = PdfWriter()
    writer.add_blank_page(width=200, height=200)
    with path.open("wb") as stream:
        writer.write(stream)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(path),
            "file_name": "scan.pdf",
            "ext": "pdf",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == "NO_TEXT_LAYER"


def test_pdf_page_limit_is_checked_before_extraction(tmp_path, monkeypatch):
    from app.config import get_settings
    from app.routers import internal

    path = tmp_path / "many.pdf"
    writer = PdfWriter()
    for _ in range(5):
        writer.add_blank_page(width=200, height=200)
    with path.open("wb") as stream:
        writer.write(stream)

    settings = replace(get_settings(), parse_max_pages=2)
    monkeypatch.setattr(internal, "get_settings", lambda: settings)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": str(path),
            "file_name": "many.pdf",
            "ext": "pdf",
        },
    )
    assert response.status_code == 422
    assert response.json()["code"] == "PAGE_LIMIT_EXCEEDED"


def test_parse_rejects_overlap_not_smaller_than_max_chars(tmp_path):
    storage_path = _write(tmp_path, "a.txt", TXT_BODY)
    response = request(
        "POST",
        "/internal/parse",
        json={
            "doc_id": 1,
            "kb_id": 1,
            "storage_path": storage_path,
            "file_name": "a.txt",
            "ext": "txt",
            "max_chars": 200,
            "overlap": 200,
        },
    )
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
