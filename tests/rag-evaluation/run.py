#!/usr/bin/env python3
"""KnowBase-AI RAG evaluation runner.

The dataset uses stable fixture keys (``expense`` and ``expense#0``). The
runner uploads the fixture documents into one fresh knowledge base, resolves
those keys to the real auto-increment IDs created by that run, asks every
question through ``POST /qa/ask``, and writes JSON plus Markdown reports.

Only the Python standard library is required.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import time
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

CATEGORIES = ("factual", "multi_doc", "procedural", "unanswerable", "adversarial")
NO_EVIDENCE_MARKERS = ("未找到相关依据", "没有找到相关依据", "没有可用的知识库依据")


class EvaluationError(RuntimeError):
    """Raised when the target system or the evaluation fixture is invalid."""


@dataclass(frozen=True)
class ApiResult:
    status: int
    body: dict[str, Any]
    elapsed_ms: int


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EvaluationError(f"无法读取 JSON {path}: {exc}") from exc


def load_dataset(path: Path, manifest_path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    manifest = read_json(manifest_path)
    rows: list[dict[str, Any]] = []
    try:
        with path.open("r", encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, start=1):
                if not line.strip():
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as exc:
                    raise EvaluationError(f"{path}:{line_no} 不是合法 JSON: {exc}") from exc
    except OSError as exc:
        raise EvaluationError(f"无法读取数据集 {path}: {exc}") from exc
    validate_dataset(rows, manifest)
    return rows, manifest


def validate_dataset(rows: list[dict[str, Any]], manifest: dict[str, Any]) -> None:
    if not 20 <= len(rows) <= 30:
        raise EvaluationError(f"数据集必须包含 20-30 题，当前 {len(rows)} 题")

    categories = Counter(str(row.get("category", "")) for row in rows)
    if set(categories) != set(CATEGORIES):
        raise EvaluationError(f"五类题目不完整: {dict(categories)}")
    if any(categories[category] < 4 for category in CATEGORIES):
        raise EvaluationError(f"每类至少 4 题: {dict(categories)}")

    documents = manifest.get("documents")
    if not isinstance(documents, list) or not documents:
        raise EvaluationError("manifest.documents 不能为空")
    document_keys = {str(item["key"]) for item in documents}
    if len(document_keys) != len(documents):
        raise EvaluationError("manifest 中文档 key 必须唯一")

    required = {
        "id",
        "category",
        "question",
        "expected_doc_ids",
        "expected_chunk_ids",
        "key_answer_points",
        "answerable",
        "human_verification",
    }
    ids: set[str] = set()
    for row in rows:
        missing = required - row.keys()
        if missing:
            raise EvaluationError(f"{row.get('id', '?')} 缺少字段: {sorted(missing)}")
        identifier = str(row["id"])
        if identifier in ids:
            raise EvaluationError(f"题目 ID 重复: {identifier}")
        ids.add(identifier)
        if not str(row["question"]).strip():
            raise EvaluationError(f"{identifier} question 不能为空")
        if not str(row["human_verification"]).strip():
            raise EvaluationError(f"{identifier} 缺少人工核验结论")
        expected_docs = [str(value) for value in row["expected_doc_ids"]]
        if any(value not in document_keys for value in expected_docs):
            raise EvaluationError(f"{identifier} 引用了未知文档 key")
        for logical_chunk in row["expected_chunk_ids"]:
            key, separator, seq = str(logical_chunk).rpartition("#")
            if not separator or key not in document_keys or not seq.isdigit():
                raise EvaluationError(f"{identifier} 的 expected_chunk_id 非法: {logical_chunk}")
        if bool(row["answerable"]) != bool(expected_docs):
            raise EvaluationError(f"{identifier} 的 answerable 与 expected_doc_ids 不一致")


def api(
    method: str,
    url: str,
    *,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    raw: bytes | None = None,
    content_type: str | None = None,
    timeout: float = 120,
) -> ApiResult:
    data = raw
    headers = {
        "Accept": "application/json",
        "X-Trace-Id": f"eval-{uuid.uuid4().hex[:24]}",
    }
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"

    request = Request(url, data=data, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=timeout) as response:
            raw_response = response.read()
            status = response.status
    except HTTPError as exc:
        raw_response = exc.read()
        status = exc.code
    except URLError as exc:
        raise EvaluationError(f"无法连接 {url}: {exc.reason}") from exc
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    try:
        parsed = json.loads(raw_response.decode("utf-8")) if raw_response else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = {"_raw": raw_response.decode("utf-8", "replace")}
    return ApiResult(status=status, body=parsed, elapsed_ms=elapsed_ms)


def require_success(result: ApiResult, action: str) -> dict[str, Any]:
    code = result.body.get("code")
    if result.status < 200 or result.status >= 300 or code != 0:
        message = result.body.get("message") or result.body.get("_raw") or "unknown error"
        raise EvaluationError(f"{action} 失败: HTTP {result.status}, code={code}, message={message}")
    return result.body.get("data") or {}


def multipart(field: str, filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = "----knowbase-eval-" + uuid.uuid4().hex
    payload = b"".join(
        [
            f"--{boundary}\r\n".encode(),
            (
                f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
                "Content-Type: application/octet-stream\r\n\r\n"
            ).encode("utf-8"),
            content,
            f"\r\n--{boundary}--\r\n".encode(),
        ]
    )
    return payload, f"multipart/form-data; boundary={boundary}"


def wait_until_indexed(
    base_url: str,
    token: str,
    kb_id: int,
    doc_ids: dict[str, int],
    timeout_seconds: float,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    pending = dict(doc_ids)
    while pending and time.monotonic() < deadline:
        for key, doc_id in list(pending.items()):
            result = api(
                "GET",
                f"{base_url}/knowledge-bases/{kb_id}/documents/{doc_id}",
                token=token,
            )
            document = require_success(result, f"查询文档 {key}")
            status = document.get("status")
            if status == "ACTIVE":
                pending.pop(key)
            elif status in {"FAILED", "DELETE_FAILED", "DELETED"}:
                raise EvaluationError(
                    f"文档 {key} 处理失败: status={status}, "
                    f"errorCode={document.get('errorCode')}, errorMsg={document.get('errorMsg')}"
                )
        if pending:
            time.sleep(0.5)
    if pending:
        raise EvaluationError(f"等待文档索引超时: {sorted(pending)}")


def fetch_chunks(base_url: str, token: str, kb_id: int, doc_id: int) -> list[dict[str, Any]]:
    result = api(
        "GET",
        f"{base_url}/knowledge-bases/{kb_id}/documents/{doc_id}/chunks?page=1&size=100",
        token=token,
    )
    return list(require_success(result, f"读取文档 {doc_id} 分片").get("items") or [])


def refuse_detected(data: dict[str, Any]) -> bool:
    if bool(data.get("noAnswer")):
        return True
    answer = str(data.get("answer") or "")
    citations = data.get("citations") or []
    return not citations and any(marker in answer for marker in NO_EVIDENCE_MARKERS)


def point_coverage(answer: str, points: list[str]) -> float:
    if not points:
        return 1.0
    lowered = answer.casefold()
    matched = sum(1 for point in points if str(point).casefold() in lowered)
    return matched / len(points)


def precision(actual: list[Any], expected: set[Any]) -> float:
    if not actual:
        return 1.0 if not expected else 0.0
    return sum(1 for value in actual if value in expected) / len(actual)


def recall(actual: set[Any], expected: set[Any]) -> float:
    if not expected:
        return 1.0
    return len(actual & expected) / len(expected)


def percentile(values: list[int], fraction: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * fraction) - 1)
    return ordered[index]


def summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    answerable = [row for row in records if row["answerable"]]
    unanswerable = [row for row in records if not row["answerable"]]
    latencies = [int(row["latency_ms"]) for row in records]
    refused = [row for row in records if row["refused"]]
    answerable_refused = [row for row in answerable if row["refused"]]
    unanswerable_refused = [row for row in unanswerable if row["refused"]]

    return {
        "sample_count": len(records),
        "answerable_count": len(answerable),
        "unanswerable_count": len(unanswerable),
        "category_counts": dict(Counter(row["category"] for row in records)),
        "recall_at_5": round(
            sum(row["doc_recall_at_k"] for row in answerable) / len(answerable), 4
        ) if answerable else 0.0,
        "chunk_recall_at_5": round(
            sum(row["chunk_recall_at_k"] for row in answerable) / len(answerable), 4
        ) if answerable else 0.0,
        "document_citation_accuracy": round(
            sum(row["doc_citation_precision"] for row in records) / len(records), 4
        ),
        "chunk_citation_accuracy": round(
            sum(row["chunk_citation_precision"] for row in records) / len(records), 4
        ),
        "refusal_rate": round(len(refused) / len(records), 4),
        "unanswerable_refusal_rate": round(
            len(unanswerable_refused) / len(unanswerable), 4
        ) if unanswerable else 0.0,
        "answerable_false_refusal_rate": round(
            len(answerable_refused) / len(answerable), 4
        ) if answerable else 0.0,
        "key_point_coverage": round(
            sum(row["key_point_coverage"] for row in answerable) / len(answerable), 4
        ) if answerable else 0.0,
        "latency_ms": {
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "max": max(latencies) if latencies else 0,
        },
    }


def evaluate_record(record: dict[str, Any], answer_data: dict[str, Any], latency_ms: int,
                    expected_docs_actual: set[int], expected_chunks_actual: set[int]) -> dict[str, Any]:
    citations = list(answer_data.get("citations") or [])
    actual_docs = [int(item["docId"]) for item in citations]
    actual_chunks = [int(item["chunkId"]) for item in citations]
    answer = str(answer_data.get("answer") or "")
    return {
        "id": record["id"],
        "category": record["category"],
        "question": record["question"],
        "answerable": bool(record["answerable"]),
        "expected_doc_ids": record["expected_doc_ids"],
        "expected_chunk_ids": record["expected_chunk_ids"],
        "resolved_expected_doc_ids": sorted(expected_docs_actual),
        "resolved_expected_chunk_ids": sorted(expected_chunks_actual),
        "retrieved_doc_ids": sorted(set(actual_docs)),
        "retrieved_chunk_ids": sorted(set(actual_chunks)),
        "doc_recall_at_k": round(recall(set(actual_docs), expected_docs_actual), 4),
        "chunk_recall_at_k": round(recall(set(actual_chunks), expected_chunks_actual), 4),
        "doc_citation_precision": round(precision(actual_docs, expected_docs_actual), 4),
        "chunk_citation_precision": round(precision(actual_chunks, expected_chunks_actual), 4),
        "key_point_coverage": round(point_coverage(answer, list(record["key_answer_points"])), 4),
        "refused": refuse_detected(answer_data),
        "answer": answer,
        "citation_count": len(citations),
        "latency_ms": latency_ms,
        "chat_provider": answer_data.get("chatProvider"),
        "embedding_provider": answer_data.get("embeddingProvider"),
        "human_verification": record["human_verification"],
    }


def write_reports(report: dict[str, Any], output_dir: Path, timestamp: str) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"rag-evaluation-{timestamp}.json"
    md_path = output_dir / f"rag-evaluation-{timestamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "latest.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    metrics = report["metrics"]
    lines = [
        "# KnowBase-AI RAG Evaluation",
        "",
        f"- 运行时间: {report['started_at']}",
        f"- Base URL: `{report['conditions']['base_url']}`",
        f"- 样本: {metrics['sample_count']} 题",
        f"- Top K: {report['conditions']['top_k']}",
        f"- 说明: {report['conditions']['evaluation_scope']}",
        "",
        "## 指标",
        "",
        "| 指标 | 结果 |",
        "|---|---:|",
        f"| Recall@5（文档级） | {metrics['recall_at_5']:.2%} |",
        f"| Recall@5（分片级，附加） | {metrics['chunk_recall_at_5']:.2%} |",
        f"| 引用正确率（文档级） | {metrics['document_citation_accuracy']:.2%} |",
        f"| 引用正确率（分片级） | {metrics['chunk_citation_accuracy']:.2%} |",
        f"| 总体拒答率 | {metrics['refusal_rate']:.2%} |",
        f"| 无答案题正确拒答率 | {metrics['unanswerable_refusal_rate']:.2%} |",
        f"| 可答案题误拒答率 | {metrics['answerable_false_refusal_rate']:.2%} |",
        f"| 关键要点覆盖率 | {metrics['key_point_coverage']:.2%} |",
        f"| P95 延迟 | {metrics['latency_ms']['p95']} ms |",
        "",
        "## 分题结果",
        "",
        "| ID | 类别 | 文档 Recall | 分片引用正确 | 要点覆盖 | 拒答 | 延迟 |",
        "|---|---|---:|---:|---:|---:|---:|",
    ]
    for row in report["records"]:
        lines.append(
            f"| {row['id']} | {row['category']} | {row['doc_recall_at_k']:.2%} | "
            f"{row['chunk_citation_precision']:.2%} | {row['key_point_coverage']:.2%} | "
            f"{'是' if row['refused'] else '否'} | {row['latency_ms']} ms |"
        )
    lines.extend(
        [
            "",
            "> 这是演示级固定评估集，不是行业基准，也不能外推为系统普遍准确率。",
            "> 指标定义与限制见 `docs/EVALUATION.md`。",
            "",
        ]
    )
    md_path.write_text("\n".join(lines), encoding="utf-8")
    (output_dir / "latest.md").write_text("\n".join(lines), encoding="utf-8")
    return json_path, md_path


def execute(args: argparse.Namespace, dataset_path: Path, dataset: list[dict[str, Any]],
            manifest: dict[str, Any]) -> dict[str, Any]:
    base_url = args.base_url.rstrip("/")
    started_at = datetime.now(timezone.utc).isoformat()
    login = api(
        "POST",
        f"{base_url}/auth/login",
        body={"username": args.username, "password": args.password},
        timeout=args.request_timeout,
    )
    login_data = require_success(login, "登录")
    token = login_data.get("token")
    if not token:
        raise EvaluationError("登录响应没有 token")

    kb_name = args.kb_name or f"rag-evaluation-{datetime.now():%Y%m%d-%H%M%S}"
    created = api(
        "POST",
        f"{base_url}/knowledge-bases",
        token=token,
        body={"name": kb_name, "description": "Stage 5 fixed RAG evaluation fixture"},
        timeout=args.request_timeout,
    )
    kb_id = int(require_success(created, "创建评估知识库")["id"])

    documents_root = dataset_path.parent
    doc_ids: dict[str, int] = {}
    for item in manifest["documents"]:
        file_path = documents_root / item["file"]
        try:
            content = file_path.read_bytes()
        except OSError as exc:
            raise EvaluationError(f"无法读取评估文档 {file_path}: {exc}") from exc
        payload, content_type = multipart("file", item["display_name"], content)
        result = api(
            "POST",
            f"{base_url}/knowledge-bases/{kb_id}/documents",
            token=token,
            raw=payload,
            content_type=content_type,
            timeout=args.request_timeout,
        )
        uploaded = require_success(result, f"上传 {item['key']}")
        doc_ids[str(item["key"])] = int(uploaded["id"])

    wait_until_indexed(base_url, token, kb_id, doc_ids, args.ingest_timeout)

    chunk_ids: dict[tuple[str, int], int] = {}
    for key, doc_id in doc_ids.items():
        for chunk in fetch_chunks(base_url, token, kb_id, doc_id):
            chunk_ids[(key, int(chunk["seq"]))] = int(chunk["id"])

    records: list[dict[str, Any]] = []
    for index, row in enumerate(dataset):
        expected_docs = {doc_ids[key] for key in row["expected_doc_ids"]}
        expected_chunks: set[int] = set()
        for logical_chunk in row["expected_chunk_ids"]:
            key, _, seq_text = logical_chunk.rpartition("#")
            actual = chunk_ids.get((key, int(seq_text)))
            if actual is None:
                raise EvaluationError(
                    f"{row['id']} 期望分片 {logical_chunk} 在本次索引中不存在"
                )
            expected_chunks.add(actual)

        if index and args.request_interval > 0:
            time.sleep(args.request_interval)
        answer = api(
            "POST",
            f"{base_url}/qa/ask",
            token=token,
            body={"kbId": kb_id, "question": row["question"]},
            timeout=args.request_timeout,
        )
        answer_data = require_success(answer, f"提问 {row['id']}")
        evaluated = evaluate_record(
            row, answer_data, answer.elapsed_ms, expected_docs, expected_chunks
        )
        records.append(evaluated)
        print(
            f"[{index + 1:02d}/{len(dataset)}] {row['id']} "
            f"doc={evaluated['doc_recall_at_k']:.2f} "
            f"chunk={evaluated['chunk_citation_precision']:.2f} "
            f"refused={'yes' if evaluated['refused'] else 'no'} "
            f"{evaluated['latency_ms']}ms"
        )

    summary = summarize(records)
    report = {
        "started_at": started_at,
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "dataset": str(dataset_path),
        "dataset_sha256": hashlib.sha256(dataset_path.read_bytes()).hexdigest(),
        "knowledge_base_id": kb_id,
        "knowledge_base_name": kb_name,
        "document_ids": doc_ids,
        "conditions": {
            "base_url": base_url,
            "top_k": args.top_k,
            "request_interval_seconds": args.request_interval,
            "request_timeout_seconds": args.request_timeout,
            "evaluation_scope": (
                "演示级固定评估集；25 道人工核验题，不代表行业基准或普遍准确率"
            ),
        },
        "metrics": summary,
        "records": records,
    }
    return report


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Run KnowBase-AI RAG evaluation")
    parser.add_argument("--base-url", default=os.getenv("KNOWBASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("KNOWBASE_USERNAME", "admin"))
    parser.add_argument("--password", default=os.getenv("KNOWBASE_PASSWORD", "Admin@123456"))
    parser.add_argument("--dataset", type=Path, default=script_dir / "dataset.jsonl")
    parser.add_argument("--manifest", type=Path, default=script_dir / "manifest.json")
    parser.add_argument("--output-dir", type=Path, default=script_dir / "results")
    parser.add_argument("--kb-name", default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--request-interval", type=float, default=3.1)
    parser.add_argument("--request-timeout", type=float, default=120)
    parser.add_argument("--ingest-timeout", type=float, default=180)
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        dataset, manifest = load_dataset(args.dataset.resolve(), args.manifest.resolve())
        if args.validate_only:
            counts = Counter(row["category"] for row in dataset)
            print(f"dataset valid: {len(dataset)} questions, categories={dict(counts)}")
            return 0
        if args.top_k != 5:
            print("warning: T5.2 reports Recall@5; --top-k is informational only", file=sys.stderr)
        report = execute(args, args.dataset.resolve(), dataset, manifest)
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        json_path, md_path = write_reports(report, args.output_dir.resolve(), timestamp)
        metrics = report["metrics"]
        print(
            "RESULT "
            f"recall@5={metrics['recall_at_5']:.4f} "
            f"doc-citation={metrics['document_citation_accuracy']:.4f} "
            f"chunk-citation={metrics['chunk_citation_accuracy']:.4f} "
            f"refusal={metrics['refusal_rate']:.4f} "
            f"p95={metrics['latency_ms']['p95']}ms"
        )
        print(f"json={json_path}")
        print(f"markdown={md_path}")
        return 0
    except EvaluationError as exc:
        print(f"evaluation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
