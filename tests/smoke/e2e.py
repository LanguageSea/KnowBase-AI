#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Stage 1 闭环冒烟（T1.12）。

一条命令跑完：登录 → 建库 → 上传 TXT → 提问 → 断言引用非空且可追溯到分片。

只依赖标准库，任何 Python 3.12 都能跑：

    python tests/smoke/e2e.py
    python tests/smoke/e2e.py --base-url http://127.0.0.1:8080 --username admin --password Admin@123456

退出码：0 全部通过；1 有断言失败；2 环境不可达。
"""

from __future__ import annotations

import argparse
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

TIMEOUT = 60
_ssl_context: ssl.SSLContext | None = None

OK = "PASS"
FAIL = "FAIL"

_results: list[tuple[str, bool, str]] = []


def record(name: str, passed: bool, detail: str) -> None:
    _results.append((name, passed, detail))
    print(f"[{OK if passed else FAIL}] {name}: {detail}")


def http(method: str, url: str, *, body=None, token: str | None = None,
         raw: bytes | None = None, content_type: str | None = None) -> tuple[int, dict, dict]:
    """发一个请求，返回 (status, headers, json_body)。非 JSON 响应体返回 {'_raw': text}。"""
    data = None
    headers = {"Accept": "application/json"}
    if raw is not None:
        data = raw
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=_ssl_context) as resp:
            payload = resp.read()
            status, hdrs = resp.status, dict(resp.headers)
    except urllib.error.HTTPError as e:
        payload = e.read()
        status, hdrs = e.code, dict(e.headers)
    except urllib.error.URLError as e:
        print(f"无法连接 {url}: {e.reason}", file=sys.stderr)
        sys.exit(2)

    try:
        parsed = json.loads(payload.decode("utf-8")) if payload else {}
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = {"_raw": payload.decode("utf-8", "replace")}
    return status, hdrs, parsed


def multipart(field: str, filename: str, content: bytes) -> tuple[bytes, str]:
    boundary = "----knowbase" + uuid.uuid4().hex
    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode("utf-8"),
        b"Content-Type: text/plain\r\n\r\n",
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def sample_text(marker: str) -> str:
    return (
        f"公司报销制度说明 {marker}\n\n"
        "员工因公出差产生的交通费、住宿费，应当在费用发生后十五个工作日内提交报销申请，"
        "并附上发票原件与出差审批单。超过期限的费用需要部门负责人书面说明。\n\n"
        "单笔金额超过五千元的报销，需要财务负责人与总经理双重审批；"
        "金额低于五百元的小额费用，可由直属主管直接审批。\n\n"
        "报销款项在审批通过后的五个工作日内打入员工工资卡，"
        "如遇月末结账则顺延至下一个工作日。\n"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="KnowBase-AI Stage 1 e2e smoke")
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--username", default="admin")
    ap.add_argument("--password", default="Admin@123456")
    ap.add_argument("--insecure", action="store_true",
                    help="skip TLS certificate verification (self-signed acceptance environment)")
    args = ap.parse_args()
    global _ssl_context
    _ssl_context = ssl._create_unverified_context() if args.insecure else None
    base = args.base_url.rstrip("/")
    run_id = uuid.uuid4().hex[:8]
    marker = f"KB{run_id}"

    print(f"== KnowBase-AI e2e ({base}) run={run_id} ==\n")

    # 1. 健康检查
    status, _, body = http("GET", f"{base}/health")
    record("健康检查", status == 200 and body.get("status") in ("UP", "DEGRADED"),
           f"HTTP {status} status={body.get('status')}")
    if status != 200:
        print("后端未就绪，终止。", file=sys.stderr)
        return 2

    # 2. 登录
    status, _, body = http("POST", f"{base}/auth/login",
                           body={"username": args.username, "password": args.password})
    token = (body.get("data") or {}).get("token")
    record("登录", status == 200 and body.get("code") == 0 and bool(token),
           f"HTTP {status} code={body.get('code')} token={'有' if token else '无'}")
    if not token:
        return 1

    # 3. 当前用户
    status, _, body = http("GET", f"{base}/auth/me", token=token)
    me = body.get("data") or {}
    record("当前用户", status == 200 and me.get("username") == args.username,
           f"HTTP {status} username={me.get('username')} roles={me.get('roles')}")

    # 4. 无 token 必须 401（负向断言）
    status, _, body = http("GET", f"{base}/auth/me")
    record("无 token 拒绝", status == 401 and body.get("code") == 40101,
           f"HTTP {status} code={body.get('code')} traceId={bool(body.get('traceId'))}")

    # 5. 建库
    kb_name = f"e2e-{run_id}"
    status, _, body = http("POST", f"{base}/knowledge-bases",
                           body={"name": kb_name, "description": "自动化冒烟创建"}, token=token)
    kb = body.get("data") or {}
    kb_id = kb.get("id")
    record("创建知识库", status == 200 and body.get("code") == 0 and isinstance(kb_id, int),
           f"HTTP {status} kbId={kb_id} name={kb.get('name')}")
    if not kb_id:
        return 1

    # 6. 列表可见
    status, _, body = http("GET", f"{base}/knowledge-bases", token=token)
    ids = [item.get("id") for item in (body.get("data") or {}).get("items", [])]
    record("列表可见", status == 200 and kb_id in ids, f"HTTP {status} 库列表={ids}")

    # 7. 重名冲突
    status, _, body = http("POST", f"{base}/knowledge-bases",
                           body={"name": kb_name}, token=token)
    record("重名拒绝", status == 409 and body.get("code") == 40901,
           f"HTTP {status} code={body.get('code')}")

    # 8. 上传 TXT
    text = sample_text(marker)
    payload, ctype = multipart("file", f"{kb_name}.txt", text.encode("utf-8"))
    status, _, body = http("POST", f"{base}/knowledge-bases/{kb_id}/documents",
                           raw=payload, content_type=ctype, token=token)
    doc = body.get("data") or {}
    doc_id = doc.get("id")
    record("上传文档已受理", status == 200
           and doc.get("status") in ("PENDING", "PROCESSING", "ACTIVE"),
           f"HTTP {status} docId={doc_id} status={doc.get('status')} chunks={doc.get('chunkCount')} "
           f"sha256={(doc.get('sha256') or '')[:12]}…")
    if not doc_id:
        return 1
    record("大小与哈希", doc.get("sizeBytes") == len(text.encode("utf-8"))
           and len(doc.get("sha256") or "") == 64,
           f"sizeBytes={doc.get('sizeBytes')} sha256={len(doc.get('sha256') or '')} 位")
    record("文件落在 UUID 路径", (doc.get("storageKey") or "").count("/") >= 2
           and uuid_is_hex((doc.get("storageKey") or "").split("/")[-1].split(".")[0]),
           f"storageKey={doc.get('storageKey')}")

    doc = wait_for_document(base, token, kb_id, doc_id)
    record("异步索引完成", doc.get("status") == "ACTIVE" and (doc.get("chunkCount") or 0) > 0,
           f"status={doc.get('status')} chunks={doc.get('chunkCount')} "
           f"errorCode={doc.get('errorCode')}")

    # 9. 文档列表
    status, _, body = http("GET", f"{base}/knowledge-bases/{kb_id}/documents", token=token)
    docs = (body.get("data") or {}).get("items", [])
    record("文档列表", status == 200 and any(d.get("id") == doc_id for d in docs),
           f"HTTP {status} 文档数={len(docs)}")

    # 10. 提问
    question = f"{marker} 报销需要多久到账？"
    started = time.time()
    status, _, body = http("POST", f"{base}/qa/ask",
                           body={"kbId": kb_id, "question": question}, token=token)
    elapsed_ms = int((time.time() - started) * 1000)
    data = body.get("data") or {}
    answer = data.get("answer") or ""
    citations = data.get("citations") or []
    record("提问有回答", status == 200 and body.get("code") == 0 and bool(answer),
           f"HTTP {status} answer={answer[:40]!r} 耗时={elapsed_ms}ms provider={data.get('chatProvider')}")
    record("引用非空", len(citations) > 0, f"引用数={len(citations)}")

    if citations:
        first = citations[0]
        record("引用可追溯", isinstance(first.get("chunkId"), int) and first["chunkId"] > 0
               and first.get("docId") == doc_id,
               f"rank={first.get('rank')} chunkId={first.get('chunkId')} docId={first.get('docId')} "
               f"score={first.get('score')}")
        record("引用原文非空", bool((first.get("quote") or "").strip()),
               f"quote={ (first.get('quote') or '')[:32]!r}…")
        record("答案带 [1] 标记且能对上引用",
               "[1]" in answer and any(c.get("rank") == 1 for c in citations),
               f"answer 含[1]={'[1]' in answer}")
        record("引用顺序连续", [c.get("rank") for c in citations] == list(range(1, len(citations) + 1)),
               f"ranks={[c.get('rank') for c in citations]}")
    else:
        record("引用可追溯", False, "没有引用，跳过")
        record("答案带 [1] 标记且能对上引用", False, "没有引用，跳过")

    # 11. 越权：不存在和无权访问统一返回 403，避免枚举资源
    status, _, body = http("POST", f"{base}/qa/ask",
                           body={"kbId": kb_id + 99999, "question": question}, token=token)
    record("越权/不存在库拒绝", status == 403 and body.get("code") == 40301,
           f"HTTP {status} code={body.get('code')}")

    # 12. 空问题
    status, _, body = http("POST", f"{base}/qa/ask",
                           body={"kbId": kb_id, "question": "   "}, token=token)
    record("空问题拒绝", status == 400 and body.get("code") == 40001,
           f"HTTP {status} code={body.get('code')}")

    # 汇总
    failed = [r for r in _results if not r[1]]
    print("\n" + "=" * 60)
    print(f"共 {len(_results)} 项，通过 {len(_results) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name, _, detail in failed:
            print(f"  - {name}: {detail}")
        return 1
    print("闭环通过：登录 → 建库 → 上传 → 提问 → 引用可追溯")
    return 0


def uuid_is_hex(value: str) -> bool:
    try:
        uuid.UUID(value)
        return True
    except (ValueError, AttributeError, TypeError):
        return False


def wait_for_document(
    base: str, token: str, kb_id: int, doc_id: int, timeout_seconds: int = 60
) -> dict:
    deadline = time.time() + timeout_seconds
    last: dict = {}
    while time.time() < deadline:
        status, _, body = http(
            "GET", f"{base}/knowledge-bases/{kb_id}/documents/{doc_id}", token=token
        )
        if status == 200:
            last = body.get("data") or {}
            if last.get("status") in ("ACTIVE", "FAILED"):
                return last
        time.sleep(0.75)
    return last


if __name__ == "__main__":
    sys.exit(main())
