"""Internal endpoints defined by ``docs/INTERNAL_API.md``.

Paths are fixed — do not invent new ones:
``GET /internal/health``, ``POST /internal/parse``, ``POST /internal/index``,
``POST /internal/search``, ``POST /internal/delete`` and
``POST /internal/chat/stream`` plus ``POST /internal/model/test``.
"""

from __future__ import annotations

import asyncio
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import AsyncIterator

import httpx
from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator, model_validator
from starlette.concurrency import run_in_threadpool

from app import __version__
from app.config import Settings, get_settings
from app.errors import (
    AppError,
    embedding_dim_mismatch,
    empty_content,
    internal_error,
    no_text_layer,
    not_found,
    page_limit_exceeded,
    parse_timeout,
    unsupported_format,
)
from app.logging_setup import get_logger
from app.security import require_internal_token
from app.services import qdrant_store
from app.services.chunker import chunk_text
from app.services.chat import (
    ChatHit,
    build_chat_provider,
    get_chat_provider,
    hits_from_search_results,
)
from app.services.embedding import (
    EmbeddingProvider,
    build_embedding_provider,
    get_embedding_provider,
)
from app.services.model_security import ModelEndpointError, validate_model_base_url
from app.services.rag_context import (
    MAX_CONTEXT_CHUNKS,
    MAX_CONTEXT_TOKENS,
    build_context,
)

logger = get_logger("app.api")

SERVICE_NAME = "ai-service"
SUPPORTED_EXTENSIONS = {"txt", "md", "pdf", "docx"}
MAX_FILE_BYTES = 20 * 1024 * 1024
NO_EVIDENCE_NOTICE = "知识库中未找到相关依据。\n\n"
_CITATION_MARKER = re.compile(r"\[(\d+)]")
_CONVERSATIONAL_MESSAGES = {
    "你好",
    "您好",
    "嗨",
    "哈喽",
    "hello",
    "hi",
    "hey",
    "在吗",
    "早上好",
    "上午好",
    "下午好",
    "晚上好",
    "谢谢",
    "谢谢你",
    "感谢",
    "你是谁",
    "介绍一下你自己",
    "你能做什么",
    "你可以做什么",
    "你能帮我做什么",
    "你可以帮我做什么",
    "使用帮助",
    "帮助",
}

router = APIRouter(
    prefix="/internal",
    tags=["internal"],
    dependencies=[Depends(require_internal_token)],
)


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------


class ParseRequest(BaseModel):
    doc_id: int = Field(ge=1)
    kb_id: int = Field(ge=1)
    storage_path: str = Field(min_length=1, max_length=4096)
    file_name: str = Field(min_length=1, max_length=255)
    ext: str = Field(min_length=1, max_length=16)
    max_chars: int | None = Field(default=None, ge=100, le=2000)
    overlap: int | None = Field(default=None, ge=0)

    @field_validator("ext")
    @classmethod
    def _normalize_ext(cls, value: str) -> str:
        normalized = value.strip().lower().lstrip(".")
        if not normalized:
            raise ValueError("ext must not be empty")
        return normalized

    @model_validator(mode="after")
    def _resolve_chunking(self) -> "ParseRequest":
        settings = get_settings()
        if self.max_chars is None:
            self.max_chars = settings.parse_max_chars
        if self.overlap is None:
            self.overlap = settings.parse_overlap
        assert self.max_chars is not None and self.overlap is not None
        if self.overlap >= self.max_chars:
            raise ValueError("overlap must be smaller than max_chars")
        return self


class ParsedChunk(BaseModel):
    seq: int
    content: str
    page_no: int | None = None
    heading: str | None = None
    char_count: int
    token_count: int | None = None


class ParseResponse(BaseModel):
    doc_id: int
    ext: str
    chunk_count: int
    chunks: list[ParsedChunk]


class DeleteRequest(BaseModel):
    doc_id: int = Field(ge=1)


class DeleteResponse(BaseModel):
    doc_id: int
    deleted_count: int


class ModelRuntimeConfig(BaseModel):
    config_id: int | None = Field(default=None, ge=1)
    provider: str = Field(default="", max_length=64)
    model_type: str = Field(default="", max_length=16)
    model_name: str = Field(min_length=1, max_length=128)
    base_url: str = Field(min_length=1, max_length=512)
    api_key: str | None = Field(default=None, max_length=1024)
    params: dict = Field(default_factory=dict)


class ModelTestResponse(BaseModel):
    success: bool
    latency_ms: int
    error_type: str | None = None
    message: str
    tested_at: str


class IndexRequestChunk(BaseModel):
    seq: int = Field(ge=0)
    content: str = Field(min_length=1, max_length=20000)

    @field_validator("content")
    @classmethod
    def _reject_blank(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("content must not be blank")
        return stripped


class IndexRequest(BaseModel):
    doc_id: int = Field(ge=1)
    kb_id: int = Field(ge=1)
    chunks: list[IndexRequestChunk] = Field(min_length=1, max_length=2000)
    embedding_model: ModelRuntimeConfig | None = None

    @model_validator(mode="after")
    def _check_sequence(self) -> "IndexRequest":
        seqs = sorted(chunk.seq for chunk in self.chunks)
        if seqs != list(range(len(self.chunks))):
            raise ValueError("chunks[].seq must be unique and continuous starting from 0")
        return self


class IndexResponse(BaseModel):
    doc_id: int
    vector_count: int
    collection: str
    embedding_provider: str
    dim: int


class SearchRequest(BaseModel):
    kb_ids: list[int] = Field(max_length=200)
    query: str = Field(min_length=1, max_length=2000)
    top_k: int | None = Field(default=None, ge=1, le=20)
    score_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    embedding_model: ModelRuntimeConfig | None = None

    @field_validator("kb_ids")
    @classmethod
    def _validate_kb_ids(cls, value: list[int]) -> list[int]:
        for kb_id in value:
            if kb_id < 1:
                raise ValueError("kb_ids must contain positive integers")
        return value

    @field_validator("query")
    @classmethod
    def _reject_blank_query(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("query must not be blank")
        return stripped

    @model_validator(mode="after")
    def _resolve_search_defaults(self) -> "SearchRequest":
        settings = get_settings()
        if self.top_k is None:
            self.top_k = settings.search_top_k
        if self.score_threshold is None:
            self.score_threshold = settings.search_score_threshold
        return self


class SearchHit(BaseModel):
    doc_id: int
    kb_id: int
    seq: int
    score: float
    content: str


class SearchResponse(BaseModel):
    query: str
    embedding_provider: str
    hits: list[SearchHit]


class ChatTurn(BaseModel):
    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=4000)


class ChatStreamRequest(BaseModel):
    kb_ids: list[int] = Field(max_length=200)
    question: str = Field(min_length=1, max_length=2000)
    history: list[ChatTurn] = Field(default_factory=list, max_length=20)
    top_k: int | None = Field(default=None, ge=1, le=20)
    score_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    context_token_budget: int = Field(default=MAX_CONTEXT_TOKENS, ge=200, le=12000)
    max_context_chunks: int = Field(default=MAX_CONTEXT_CHUNKS, ge=1, le=20)
    embedding_model: ModelRuntimeConfig | None = None
    chat_model: ModelRuntimeConfig | None = None

    @field_validator("kb_ids")
    @classmethod
    def _validate_chat_kb_ids(cls, value: list[int]) -> list[int]:
        for kb_id in value:
            if kb_id < 1:
                raise ValueError("kb_ids must contain positive integers")
        return value

    @field_validator("question")
    @classmethod
    def _strip_question(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("question must not be blank")
        return stripped

    @model_validator(mode="after")
    def _resolve_chat_defaults(self) -> "ChatStreamRequest":
        settings = get_settings()
        if self.top_k is None:
            self.top_k = settings.search_top_k
        if self.score_threshold is None:
            self.score_threshold = settings.search_score_threshold
        return self


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _read_text_file(storage_path: str) -> str:
    path = Path(storage_path)
    if not path.is_file():
        raise not_found("source file not found")

    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise no_text_layer("document encoding is not supported (expected UTF-8 or GB18030)")


def _read_pdf(path: Path, max_pages: int) -> str:
    from pypdf import PdfReader

    try:
        reader = PdfReader(str(path), strict=False)
        pages = len(reader.pages)
        if pages > max_pages:
            raise page_limit_exceeded(pages, max_pages)
        parts: list[str] = []
        for page in reader.pages:
            try:
                text = page.extract_text() or ""
            except Exception:
                text = ""
            if text.strip():
                parts.append(text)
        if not parts:
            raise no_text_layer("scanned PDF has no text layer; OCR is not supported")
        return "\n\n".join(parts)
    except AppError:
        raise
    except Exception as exc:
        raise unsupported_format("pdf: file is damaged or encrypted") from exc


def _read_docx(path: Path) -> str:
    from docx import Document

    try:
        document = Document(str(path))
        parts = [paragraph.text.strip() for paragraph in document.paragraphs if paragraph.text.strip()]
        for table in document.tables:
            for row in table.rows:
                cells = [cell.text.strip() for cell in row.cells if cell.text.strip()]
                if cells:
                    parts.append(" | ".join(cells))
        return "\n\n".join(parts)
    except Exception as exc:
        raise unsupported_format("docx: file is damaged or unsupported") from exc


def _parse_document(path: Path, ext: str, max_pages: int) -> str:
    if not path.is_file():
        raise not_found("source file not found")
    size = path.stat().st_size
    if size == 0:
        raise empty_content()
    if size > MAX_FILE_BYTES:
        raise AppError(413, "FILE_TOO_LARGE", "document exceeds 20 MB")
    if ext in {"txt", "md"}:
        return _read_text_file(str(path))
    if ext == "pdf":
        return _read_pdf(path, max_pages)
    if ext == "docx":
        return _read_docx(path)
    raise unsupported_format(ext)


def _sse_event(event: str, payload: dict) -> str:
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {data}\n\n"


def _citation_payload(rank: int, hit: ChatHit) -> dict:
    return {
        "rank": rank,
        "doc_id": hit.doc_id,
        "kb_id": hit.kb_id,
        "seq": hit.seq,
        "score": hit.score,
        "quote": hit.content,
    }


def _used_citation_indexes(answer: str, available: int) -> set[int]:
    indexes = {
        int(match.group(1))
        for match in _CITATION_MARKER.finditer(answer)
        if 1 <= int(match.group(1)) <= available
    }
    # Some models omit markers despite the prompt. Keep the retrieved sources
    # available to the Java integrity layer rather than fabricating new ones.
    return indexes or set(range(1, available + 1))


def _is_conversational_question(question: str) -> bool:
    normalized = re.sub(r"[\s,，。.!！?？~～、]+", "", question).lower()
    normalized = re.sub(r"(呀|啊|呢|吧)+$", "", normalized)
    return normalized in _CONVERSATIONAL_MESSAGES


async def _conversation_event_stream(
    *,
    request: ChatStreamRequest,
    embedding_provider: str,
    provider,
) -> AsyncIterator[str]:
    yield _sse_event(
        "retrieval",
        {
            "mode": "conversation",
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": [],
        },
    )

    answer_parts: list[str] = []
    history = [turn.model_dump() for turn in request.history]
    try:
        async for delta in provider.stream_conversation(request.question, history):
            if not delta:
                continue
            answer_parts.append(delta)
            yield _sse_event("delta", {"text": delta})
    except AppError as exc:
        yield _sse_event(
            "error",
            {"code": exc.code, "message": exc.message, "status": exc.status_code},
        )
        return

    answer = "".join(answer_parts).strip()
    if not answer:
        yield _sse_event(
            "error",
            {
                "code": "EMPTY_COMPLETION",
                "message": "模型未返回有效内容",
                "status": 502,
            },
        )
        return

    yield _sse_event(
        "done",
        {
            "finish_reason": "stop",
            "answer": answer,
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": [],
        },
    )


async def _general_answer_event_stream(
    *,
    request: ChatStreamRequest,
    embedding_provider: str,
    provider,
) -> AsyncIterator[str]:
    yield _sse_event(
        "retrieval",
        {
            "mode": "general_fallback",
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": [],
        },
    )
    yield _sse_event("delta", {"text": NO_EVIDENCE_NOTICE})

    answer_parts: list[str] = []
    history = [turn.model_dump() for turn in request.history]
    try:
        async for delta in provider.stream_general_answer(request.question, history):
            if not delta:
                continue
            answer_parts.append(delta)
            yield _sse_event("delta", {"text": delta})
    except AppError as exc:
        yield _sse_event(
            "error",
            {"code": exc.code, "message": exc.message, "status": exc.status_code},
        )
        return

    if not answer_parts:
        yield _sse_event(
            "error",
            {
                "code": "EMPTY_COMPLETION",
                "message": "模型未返回有效内容",
                "status": 502,
            },
        )
        return

    answer = NO_EVIDENCE_NOTICE + "".join(answer_parts).strip()
    yield _sse_event(
        "done",
        {
            "finish_reason": "stop",
            "mode": "general_fallback",
            "answer": answer,
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": [],
        },
    )


async def _chat_event_stream(
    *,
    request: ChatStreamRequest,
    hits: list[ChatHit],
    embedding_provider: str,
    chat_model: dict | None = None,
) -> AsyncIterator[str]:
    context = build_context(
        hits,
        token_budget=request.context_token_budget,
        max_chunks=request.max_context_chunks,
    )
    if not context.blocks:
        provider = (
            build_chat_provider(get_settings(), chat_model)
            if chat_model
            else get_chat_provider()
        )
        async for event in _general_answer_event_stream(
            request=request,
            embedding_provider=embedding_provider,
            provider=provider,
        ):
            yield event
        return

    retrieved = [
        _citation_payload(block.rank, block.hit)
        for block in context.blocks
    ]
    provider = build_chat_provider(get_settings(), chat_model) if chat_model else get_chat_provider()
    yield _sse_event(
        "retrieval",
        {
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": retrieved,
        },
    )

    answer_parts: list[str] = []
    history = [turn.model_dump() for turn in request.history]
    try:
        async for delta in provider.stream_answer(request.question, context, history):
            if not delta:
                continue
            answer_parts.append(delta)
            yield _sse_event("delta", {"text": delta})
    except AppError as exc:
        yield _sse_event(
            "error",
            {"code": exc.code, "message": exc.message, "status": exc.status_code},
        )
        return

    answer = "".join(answer_parts).strip()
    if not answer:
        yield _sse_event(
            "error",
            {
                "code": "EMPTY_COMPLETION",
                "message": "模型未返回有效内容",
                "status": 502,
            },
        )
        return

    used = _used_citation_indexes(answer, len(retrieved))
    citations = [citation for citation in retrieved if citation["rank"] in used]
    yield _sse_event(
        "done",
        {
            "finish_reason": "stop",
            "answer": answer,
            "chat_provider": provider.name,
            "embedding_provider": embedding_provider,
            "citations": citations,
        },
    )
def _runtime_payload(config: ModelRuntimeConfig | None) -> dict | None:
    return config.model_dump() if config is not None else None


async def _embed(provider: EmbeddingProvider, texts: list[str], settings: Settings) -> list[list[float]]:
    vectors = await provider.embed(texts)
    if len(vectors) != len(texts):
        raise internal_error("embedding provider returned an unexpected number of vectors")
    for vector in vectors:
        if len(vector) != settings.embedding_dim:
            raise embedding_dim_mismatch(len(vector), settings.embedding_dim)
    return vectors


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/health", response_model=None)
async def health():
    settings = get_settings()
    qdrant_component = await qdrant_store.health_component(settings)
    embedding_provider = get_embedding_provider()
    embedding_component = {
        "name": "embedding",
        "status": "UP",
        "provider": embedding_provider.name,
        "detail": None,
    }
    components = [qdrant_component, embedding_component]
    healthy = all(component["status"] == "UP" for component in components)
    body = {
        "status": "UP" if healthy else "DEGRADED",
        "service": SERVICE_NAME,
        "version": __version__,
        "components": components,
    }
    if not healthy:
        return JSONResponse(status_code=503, content=body)
    return body


@router.post("/parse", response_model=ParseResponse)
async def parse(request: ParseRequest) -> ParseResponse:
    settings = get_settings()
    if request.ext not in SUPPORTED_EXTENSIONS:
        raise unsupported_format(request.ext)

    try:
        text = await asyncio.wait_for(
            run_in_threadpool(
                _parse_document, Path(request.storage_path), request.ext, settings.parse_max_pages
            ),
            timeout=settings.parse_timeout_seconds,
        )
    except TimeoutError as exc:
        raise parse_timeout(settings.parse_timeout_seconds) from exc
    if not text.strip():
        if request.ext == "pdf":
            raise no_text_layer("scanned PDF has no text layer; OCR is not supported")
        raise empty_content()

    max_chars = request.max_chars or settings.parse_max_chars
    overlap = request.overlap if request.overlap is not None else settings.parse_overlap
    chunks = chunk_text(text, max_chars=max_chars, overlap=overlap)
    if not chunks:
        raise no_text_layer()

    logger.info(
        "parse_completed",
        extra={
            "fields": {
                "doc_id": request.doc_id,
                "kb_id": request.kb_id,
                "ext": request.ext,
                "chunk_count": len(chunks),
            }
        },
    )

    return ParseResponse(
        doc_id=request.doc_id,
        ext=request.ext,
        chunk_count=len(chunks),
        chunks=[
            ParsedChunk(
                seq=chunk.seq,
                content=chunk.content,
                page_no=None,
                heading=None,
                char_count=chunk.char_count,
                token_count=chunk.char_count,
            )
            for chunk in chunks
        ],
    )


@router.post("/index", response_model=IndexResponse)
async def index(request: IndexRequest) -> IndexResponse:
    settings = get_settings()
    provider = build_embedding_provider(settings, _runtime_payload(request.embedding_model))

    seqs = [chunk.seq for chunk in request.chunks]
    contents = [chunk.content for chunk in request.chunks]
    vectors = await _embed(provider, contents, settings)

    vector_count = await qdrant_store.upsert_chunks(
        doc_id=request.doc_id,
        kb_id=request.kb_id,
        seqs=seqs,
        contents=contents,
        vectors=vectors,
        settings=settings,
    )

    logger.info(
        "index_completed",
        extra={
            "fields": {
                "doc_id": request.doc_id,
                "kb_id": request.kb_id,
                "vector_count": vector_count,
                "embedding_provider": provider.name,
            }
        },
    )

    return IndexResponse(
        doc_id=request.doc_id,
        vector_count=vector_count,
        collection=settings.qdrant_collection,
        embedding_provider=provider.name,
        dim=settings.embedding_dim,
    )


@router.post("/search", response_model=SearchResponse)
async def search(request: SearchRequest) -> SearchResponse:
    settings = get_settings()
    provider = build_embedding_provider(settings, _runtime_payload(request.embedding_model))

    if not request.kb_ids:
        # Authorised scope is empty: answer immediately, never touch Qdrant.
        logger.info("search_skipped_empty_scope")
        return SearchResponse(query=request.query, embedding_provider=provider.name, hits=[])

    vectors = await _embed(provider, [request.query], settings)
    hits = await qdrant_store.search(
        kb_ids=request.kb_ids,
        vector=vectors[0],
        top_k=request.top_k or settings.search_top_k,
        score_threshold=(
            request.score_threshold if request.score_threshold is not None else settings.search_score_threshold
        ),
        settings=settings,
    )

    logger.info(
        "search_completed",
        extra={
            "fields": {
                "kb_id_count": len(request.kb_ids),
                "hit_count": len(hits),
                "embedding_provider": provider.name,
            }
        },
    )

    return SearchResponse(
        query=request.query,
        embedding_provider=provider.name,
        hits=[SearchHit(**hit) for hit in hits],
    )


@router.post("/chat/stream")
async def chat_stream(request: ChatStreamRequest) -> StreamingResponse:
    """Retrieve, build a bounded context, then stream a grounded answer.

    Retrieval happens before the response body starts so Qdrant/embedding
    failures still return the documented structured 503 JSON. Once generation
    starts, provider failures are represented as an ``error`` SSE event.
    """

    settings = get_settings()
    embedding_provider = build_embedding_provider(
        settings, _runtime_payload(request.embedding_model)
    )
    chat_model = _runtime_payload(request.chat_model)
    if _is_conversational_question(request.question):
        provider = build_chat_provider(settings, chat_model) if chat_model else get_chat_provider()
        return StreamingResponse(
            _conversation_event_stream(
                request=request,
                embedding_provider=embedding_provider.name,
                provider=provider,
            ),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
    if not request.kb_ids:
        provider = build_chat_provider(settings, chat_model) if chat_model else get_chat_provider()
        return StreamingResponse(
            _general_answer_event_stream(
                request=request,
                embedding_provider=embedding_provider.name,
                provider=provider,
            ),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    vectors = await _embed(embedding_provider, [request.question], settings)
    raw_hits = await qdrant_store.search(
        kb_ids=request.kb_ids,
        vector=vectors[0],
        top_k=request.top_k or settings.search_top_k,
        score_threshold=(
            request.score_threshold
            if request.score_threshold is not None
            else settings.search_score_threshold
        ),
        settings=settings,
    )
    hits = hits_from_search_results(raw_hits)
    logger.info(
        "chat_stream_started",
        extra={
            "fields": {
                "kb_id_count": len(request.kb_ids),
                "hit_count": len(hits),
                "embedding_provider": embedding_provider.name,
            }
        },
    )
    return StreamingResponse(
        _chat_event_stream(
            request=request,
            hits=hits,
            embedding_provider=embedding_provider.name,
            chat_model=chat_model,
        ),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post("/delete", response_model=DeleteResponse)
async def delete(request: DeleteRequest) -> DeleteResponse:
    deleted_count = await qdrant_store.delete_document(request.doc_id)
    logger.info(
        "delete_completed",
        extra={"fields": {"doc_id": request.doc_id, "deleted_count": deleted_count}},
    )
    return DeleteResponse(doc_id=request.doc_id, deleted_count=deleted_count)


@router.post("/model/test", response_model=ModelTestResponse)
async def model_test(request: ModelRuntimeConfig) -> ModelTestResponse:
    """Probe an OpenAI-compatible endpoint using the same SSRF validator as real calls."""

    started = asyncio.get_running_loop().time()

    def elapsed_ms() -> int:
        return int((asyncio.get_running_loop().time() - started) * 1000)

    def result(success: bool, error_type: str | None, message: str) -> ModelTestResponse:
        return ModelTestResponse(
            success=success,
            latency_ms=elapsed_ms(),
            error_type=error_type,
            message=message,
            tested_at=datetime.now(timezone.utc).isoformat(),
        )

    settings = get_settings()
    try:
        base_url = validate_model_base_url(
            request.base_url, allow_private=settings.allow_private_model_urls
        )
    except ModelEndpointError as exc:
        return result(False, exc.code, exc.message)

    endpoint = base_url if base_url.endswith("/models") else f"{base_url}/models"
    headers = {"Accept": "application/json"}
    if request.api_key:
        headers["Authorization"] = f"Bearer {request.api_key}"
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=False) as client:
            response = await client.get(endpoint, headers=headers)
    except httpx.TimeoutException:
        return result(False, "TIMEOUT", "连接模型服务超时")
    except httpx.ConnectError as exc:
        text = str(exc).lower()
        if "getaddrinfo" in text or "name or service" in text:
            return result(False, "DNS_ERROR", "无法解析模型服务域名")
        return result(False, "CONNECTION_ERROR", "无法连接模型服务")
    except httpx.HTTPError:
        return result(False, "NETWORK_ERROR", "模型服务网络请求失败")

    if 200 <= response.status_code < 300:
        return result(True, None, "连接成功")
    if response.status_code in {401, 403}:
        return result(False, "AUTH_ERROR", "模型服务认证失败，请检查 API Key")
    if response.status_code == 404:
        return result(False, "ENDPOINT_NOT_FOUND", "模型服务未提供 /models 端点")
    if response.status_code == 429:
        return result(False, "RATE_LIMITED", "模型服务请求过于频繁")
    return result(False, f"HTTP_{response.status_code}", f"模型服务返回 HTTP {response.status_code}")


__all__ = ["router"]
