# 内部接口（Java → Python AI 服务）

> 覆盖 Stage 1 内部链路、Stage 2 文档工程、Stage 3 RAG，以及 Stage 4 动态模型配置、
> SSRF 校验和模型连接测试。
> 安全边界：Python 不解析 JWT、不查用户表、不做权限判断。可处理或检索的数据范围由 Java 先完成授权。

## 0. 通用约定

- Base URL：`http://127.0.0.1:8090`（`PYTHON_SERVICE_URL`）
- 监听范围：只监听回环地址；不经 Nginx 暴露到公网
- 认证：所有 `/internal/**` 请求必须带 `X-Internal-Token: <INTERNAL_TOKEN>`，常量时间比较；缺失或错误统一 `401`
- 链路：请求头 `X-Trace-Id` 原样回写响应头，并写入该请求每条日志
- 超时：Java 读取默认 65 秒；Python 解析默认 60 秒，确保 Python 能在 Java 断开前返回结构化 `PARSE_TIMEOUT`
- 流式生成：`POST /internal/chat/stream` 返回 `text/event-stream`；客户端断开时 Python 异步生成器被关闭，上游模型 HTTP 连接随之关闭
- 模型地址：默认只允许 HTTPS，并在 DNS 解析后拒绝私网、环回、链路本地和云元数据地址；`ALLOW_PRIVATE_MODEL_URLS=true` 仅用于本地 Ollama 开发
- 命名：内部 JSON 使用 snake_case
- 时间：ISO-8601；日志时间为 UTC

### 0.1 统一错误体

```json
{
  "code": "VALIDATION_ERROR",
  "message": "chunks 不能为空",
  "trace_id": "…"
}
```

| HTTP | code | 场景 |
|---:|---|---|
| 401 | `UNAUTHORIZED` | 内部 token 缺失或错误 |
| 400 | `VALIDATION_ERROR` | 字段缺失、类型错、越界、空字符串 |
| 400 | `UNSUPPORTED_FORMAT` | 扩展名不支持，或文件损坏/加密 |
| 404 | `NOT_FOUND` | 源文件或路由不存在 |
| 413 | `FILE_TOO_LARGE` | 文件超过 20 MB |
| 422 | `EMPTY_CONTENT` | 文件为空或没有有效文本 |
| 422 | `NO_TEXT_LAYER` | 扫描版 PDF 无文字层，暂不支持 OCR |
| 422 | `PAGE_LIMIT_EXCEEDED` | PDF 页数超过上限 |
| 500 | `EMBEDDING_DIM_MISMATCH` | 向量维度与 Qdrant collection 不一致 |
| 500 | `INTERNAL_ERROR` | 未预期异常，不返回堆栈 |
| 503 | `DEPENDENCY_UNAVAILABLE` | Qdrant / Embedding / 模型服务不可达 |
| 504 | `PARSE_TIMEOUT` | 文档解析超过配置时限 |

`DEPENDENCY_UNAVAILABLE` 的文案只带短码，例如 `CONNECTION_REFUSED`、`TIMEOUT`、`HTTP_401`，不暴露异常类、路径或密钥。

### 0.2 日志

每次请求输出一行 JSON，至少包含：`ts`、`level`、`logger`、`trace_id`、`method`、`path`、`status`、`duration_ms`。校验失败也记录日志。

禁止打印文件内容、完整存储路径、token、API key、Embedding 向量或原始异常堆栈到客户端响应。

---

## 1. GET /internal/health

需内部 token。用于 Java 聚合健康检查。

成功 `200`：

```json
{
  "status": "UP",
  "service": "ai-service",
  "version": "0.1.0",
  "components": [
    {
      "name": "qdrant",
      "status": "UP",
      "latency_ms": 3,
      "detail": null
    },
    {
      "name": "embedding",
      "status": "UP",
      "provider": "local-hash-v1",
      "detail": null
    }
  ]
}
```

任一组件 DOWN 返回 `503`，`status="DEGRADED"`。探活本身不抛未处理异常。

---

## 2. POST /internal/parse

解析并切分文档，不写入向量。

### 2.1 请求

```json
{
  "doc_id": 7,
  "kb_id": 1,
  "storage_path": "D:/thesis/KnowBase-AI/data/uploads/2026/10/6f1c….pdf",
  "file_name": "报销制度.pdf",
  "ext": "pdf",
  "max_chars": 600,
  "overlap": 80
}
```

- `storage_path` 是 Java 已规范化的绝对路径；Python 只读，不接受客户端路径拼接。
- `ext` 支持 `txt`、`md`、`pdf`、`docx`，大小写不敏感。
- `max_chars` 默认 600，范围 100–2000。
- `overlap` 默认 80，必须小于 `max_chars`。
- 文本切分时 `seq` 从 0 连续递增，每片内容去首尾空白后非空。
- 分片间保留配置的 overlap；超长段落会硬切，不丢尾部文本。

### 2.2 格式和限制

| 格式 | 解析方式 | 限制 |
|---|---|---|
| `txt` | UTF-8 BOM / UTF-8 / GB18030 | 20 MB |
| `md` | 按纯文本读取，保留 Markdown 原文 | 20 MB |
| `pdf` | pypdf 提取逐页文字 | 20 MB、300 页、60 秒 |
| `docx` | python-docx 读取段落和表格文本 | 20 MB、60 秒 |

- 0 字节文件返回 `422 EMPTY_CONTENT`。
- 纯空白文本返回 `422 EMPTY_CONTENT`。
- 扫描版 PDF 或没有可提取文字层返回 `422 NO_TEXT_LAYER`。
- PDF 损坏、加密或不支持返回 `400 UNSUPPORTED_FORMAT`。
- 解析超时返回 `504 PARSE_TIMEOUT`。
- 页数检查发生在逐页文本提取之前。

### 2.3 成功响应

```json
{
  "doc_id": 7,
  "ext": "pdf",
  "chunk_count": 12,
  "chunks": [
    {
      "seq": 0,
      "content": "……",
      "page_no": null,
      "heading": null,
      "char_count": 600,
      "token_count": 600
    }
  ]
}
```

当前版本 `page_no` 和 `heading` 可为 `null`；`token_count` 使用字符数近似，仅用于展示，不用于模型计费。

---

## 3. POST /internal/index

向量化并写入 Qdrant。

### 3.1 请求

```json
{
  "doc_id": 7,
  "kb_id": 1,
  "embedding_model": {
    "config_id": 4,
    "provider": "openai-compatible",
    "model_type": "EMBEDDING",
    "model_name": "text-embedding-3-small",
    "base_url": "https://api.example.com/v1",
    "api_key": "…",
    "params": {}
  },
  "chunks": [
    { "seq": 0, "content": "……" },
    { "seq": 1, "content": "……" }
  ]
}
```

- `chunks` 1–2000 条，内容不能为空白。
- `seq` 必须唯一、连续且从 0 开始。
- point id：`uuid5(NAMESPACE_URL, "knowbase:chunk:{doc_id}:{seq}")`。
- point payload：`doc_id`、`kb_id`、`seq`、`content`。
- `doc_id` 和 `kb_id` 创建 payload integer index。
- collection 不存在时按 `EMBEDDING_DIM`（默认 1024）和 Cosine 距离自动创建。
- 使用 upsert 且 `wait=True`，重复处理同一 `(doc_id, seq)` 会覆盖同一点，不产生重复。

### 3.2 成功响应

```json
{
  "doc_id": 7,
  "vector_count": 12,
  "collection": "knowbase_chunks",
  "embedding_provider": "local-hash-v1",
  "dim": 1024
}
```

Qdrant 不可达返回 `503 DEPENDENCY_UNAVAILABLE`。collection 维度与配置不一致返回 `500 EMBEDDING_DIM_MISMATCH`。
`embedding_model` 可选；存在时优先于 `LLM_EMBEDDING_*` 环境变量。Python 在发请求前重新执行 SSRF 校验。

---

## 4. POST /internal/search

### 4.1 请求

```json
{
  "kb_ids": [1, 2],
  "query": "报销流程",
  "top_k": 5,
  "score_threshold": 0.2,
  "embedding_model": {
    "config_id": 4,
    "provider": "openai-compatible",
    "model_type": "EMBEDDING",
    "model_name": "text-embedding-3-small",
    "base_url": "https://api.example.com/v1",
    "api_key": "…",
    "params": {}
  }
}
```

- `kb_ids` 必须由 Java 按当前用户权限计算；Python 不增删范围。
- 空 `kb_ids` 返回空 `hits`，且**不访问 Qdrant**。
- `top_k` 默认 5，范围 1–20。
- `score_threshold` 默认 0.2，范围 0–1。
- 过滤条件为 `kb_id IN kb_ids AND score >= score_threshold`，按 score 降序。

### 4.2 成功响应

```json
{
  "query": "报销流程",
  "embedding_provider": "local-hash-v1",
  "hits": [
    {
      "doc_id": 7,
      "kb_id": 1,
      "seq": 2,
      "score": 0.83,
      "content": "……原文片段……"
    }
  ]
}
```

Java 根据 `(doc_id, seq)` 映射真实 `chunk_id` 并再次校验文档所属知识库权限，再写引用。

---

## 5. POST /internal/delete

删除一个文档的全部向量。Java 删除任务会调用此接口，Python 只按 `doc_id` 执行数据清理，不做权限判断。

### 5.1 请求

```json
{ "doc_id": 7 }
```

### 5.2 成功响应

```json
{ "doc_id": 7, "deleted_count": 12 }
```

- Qdrant filter 精确匹配 payload `doc_id`。
- 删除前执行 exact count 得到 `deleted_count`。
- 使用 `wait=True`，调用返回后可立即验证点已删除。
- 接口幂等：重复删除同一文档返回 `deleted_count=0`。
- `doc_id <= 0` 返回 `400 VALIDATION_ERROR`。
- Qdrant 不可达返回 `503 DEPENDENCY_UNAVAILABLE`，Java 侧将文档置为 `DELETE_FAILED` 并进入补偿。

Java 只有在 Qdrant 删除、原文件删除、引用/分片清理都成功后，才把文档置为 `DELETED`。

---

## 6. 模型与 Embedding 抽象

| 能力 | 真实实现（有 key） | 回退实现（无 key） | 切换条件 |
|---|---|---|---|
| Embedding | OpenAI 兼容 `POST {LLM_EMBEDDING_BASE_URL}/embeddings` | `local-hash-v1`：字符 bigram + 词哈希到 1024 维，L2 归一化 | Embedding base URL 与 API key 同时非空 |
| Chat | OpenAI 兼容 `POST {LLM_BASE_URL}/chat/completions` | `local-extractive`：取 top-3 命中片段拼接并标注 `[n]` | `LLM_API_KEY` 非空 |

- 响应必须暴露 `embedding_provider` / `chat_provider`，禁止把回退实现伪装成真实模型。
- 回退嵌入只保证同一文本得到同一向量，不具备语义泛化能力。
- 真实模型服务超时、401 或 5xx 不得静默降级，统一返回 `503 DEPENDENCY_UNAVAILABLE`。

---

## 7. POST /internal/chat/stream

检索、上下文构建和模型生成由 Python 在同一请求内完成。Java 只传当前用户可读的
`kb_ids`；Python 不解析 JWT、不查用户表、不增删授权范围。

### 7.1 请求

```json
{
  "kb_ids": [1, 2],
  "question": "报销流程是什么？",
  "history": [
    { "role": "user", "content": "上一轮问题" },
    { "role": "assistant", "content": "上一轮回答" }
  ],
  "top_k": 5,
  "score_threshold": 0.2,
  "context_token_budget": 3000,
  "max_context_chunks": 8,
  "embedding_model": {
    "config_id": 4,
    "model_name": "text-embedding-3-small",
    "base_url": "https://api.example.com/v1",
    "api_key": "…",
    "params": {}
  },
  "chat_model": {
    "config_id": 3,
    "model_name": "deepseek-chat",
    "base_url": "https://api.deepseek.com",
    "api_key": "…",
    "params": { "temperature": 0.2 }
  }
}
```

- `kb_ids`：由 Java 计算；空数组不访问 Qdrant，直接进入通用回答并调用 Chat 模型。
- `history`：最多 20 轮，仅供多轮对话；其中的指令不能改变系统约束。
- `context_token_budget`：200–12000，按整块丢弃超预算分片，不截半句。
- `max_context_chunks`：1–20。
- 内容去重后按分数降序编号，来源包裹为 `<source id="n">…</source>` 数据块。
- 系统提示明确声明资料内的命令、角色设定和“忽略以上指令”等文本均无效。
- 有命中时要求模型先阅读、综合并分析全部可用来源，再进行归纳或解释，不能只复述原文；
  分析结论仍只能使用资料支持的企业事实，并保留 `[n]` 引用。
- 请求可携带 `embedding_model` 与 `chat_model` 两个运行时配置。Java 从数据库解密后仅通过内网发送；Python 不持久化 API Key，也不做任何权限判断。

### 7.2 SSE 事件

检索在响应体开始前完成，因此 Qdrant/Embedding 故障仍返回统一 JSON `503`。
`kb_ids` 为空时没有可检索的授权范围，不访问 Qdrant，进入通用回答降级。
固定先说明知识库无依据，再调用 Chat 模型分析；事件序列为：

```text
event: retrieval
data: {"mode":"general_fallback","chat_provider":"deepseek-chat","embedding_provider":"local-hash-v1","citations":[]}

event: delta
data: {"text":"知识库中未找到相关依据。\n\n"}

event: delta
data: {"text":"基于通用知识……"}

event: done
data: {"finish_reason":"stop","mode":"general_fallback","answer":"知识库中未找到相关依据。\n\n基于通用知识……","chat_provider":"deepseek-chat","embedding_provider":"local-hash-v1","citations":[]}
```

有命中时事件顺序：

1. `retrieval`：本次检索来源列表（`rank/doc_id/kb_id/seq/score/quote`）。
2. `delta`：`{"text":"增量文本"}`，可重复。
3. `done`：完整答案、最终引用、`chat_provider`、`embedding_provider`。
4. `error`：`{"code":"EMPTY_COMPLETION|DEPENDENCY_UNAVAILABLE","message":"…"}`。

有授权范围但命中为空（或上下文预算无法容纳任何分片）时，系统进入通用回答降级：

1. `retrieval`：`mode=general_fallback`，`citations=[]`，并返回实际 Chat Provider。
2. `delta`：首先输出固定提示 `知识库中未找到相关依据。\n\n`。
3. `delta`：通用模型增量回答；Prompt 要求声明“基于通用知识，不代表企业内部制度”，
   禁止编造企业内部流程、链接、人员、数据或政策。
4. `done`：`finish_reason=stop`、`mode=general_fallback`、完整回答、`citations=[]`。

该降级回答不计为 `no_answer`，但也不产生任何知识库引用。未配置通用模型时，
`local-extractive` 会明确说明无法提供知识库外的回答，不会伪造内容。

用户停止或连接关闭时，Java 关闭 Python 响应流；Python 关闭上游模型连接并停止生成。
Java 将 `ai_message.status` 更新为 `CANCELLED` 并保留已经生成的内容。

### 7.3 引用完整性

Java 不直接信任 `done.citations`：

1. 只接受本次 `retrieval` 事件出现过的 `(doc_id, seq)`；
2. `kb_id` 必须属于本次 Java 传入的授权集合；
3. 文档必须仍为 `ACTIVE` 且数据库中的 `kb_id` 与载荷一致；
4. `(doc_id, seq)` 必须映射到真实 `kb_document_chunk.id`；
5. 引用 rank 重新从 1 连续编号后落库。

---

## 8. POST /internal/model/test

测试 OpenAI-compatible 模型服务连通性。该接口与真实 Chat/Embedding 调用共享
`validate_model_base_url`，SSRF 校验发生在任何网络请求之前。

### 8.1 请求

```json
{
  "config_id": 3,
  "provider": "deepseek",
  "model_type": "CHAT",
  "model_name": "deepseek-chat",
  "base_url": "https://api.deepseek.com",
  "api_key": "…",
  "params": {}
}
```

### 8.2 响应

```json
{
  "success": true,
  "latency_ms": 231,
  "error_type": null,
  "message": "连接成功",
  "tested_at": "2026-10-31T08:00:00+00:00"
}
```

连接失败仍返回 HTTP 200，由 `success=false` 和稳定 `error_type` 表达：

- `INVALID_SCHEME / INVALID_URL`
- `PRIVATE_ADDRESS`
- `DNS_ERROR`
- `CONNECTION_ERROR`
- `TIMEOUT`
- `AUTH_ERROR`
- `RATE_LIMITED`
- `ENDPOINT_NOT_FOUND`
- `HTTP_xxx`

禁止在日志或错误文案中回显 `api_key`。
