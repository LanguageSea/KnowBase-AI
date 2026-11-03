# Stage 3 测试报告

> 日期：2026-10-31
> 范围：T3.1–T3.10 RAG 检索、上下文、流式问答、引用、会话、前端与限流。
> 环境：Windows、Java 21、Maven、MySQL 5.7.44、Redis、Qdrant 1.x、Python 3.12、Node/Vite。
>
> 后续变更：Stage 4 联调阶段根据产品要求，将“检索为空固定拒答”调整为
> “先提示知识库无依据，再由通用模型回答”。当前行为与实机证据见
> `docs/STAGE4_TEST_REPORT.md`；本报告以下内容保留当时 Stage 3 的测试记录。

## 1. 结果摘要

| 验证项 | 结果 | 说明 |
|---|---|---|
| Python 全量单测 | 通过 | `66 passed` |
| Java 全量测试 | 通过 | 44 个测试，0 失败，3 个按环境变量跳过 |
| Stage 3 全链路集成测试 | 通过 | 显式启用后 `1 passed`；真实 MySQL、Python、Qdrant、上传索引和问答链路 |
| 前端生产构建 | 通过 | Vite 构建成功；仅保留 Element Plus chunk 体积警告 |
| 代码差异检查 | 通过 | `git diff --check` 无错误 |

Java 全量测试默认跳过的 3 项是：

- 2 个既有 MySQL 并发集成测试，需要 `KNOWBASE_TEST_MYSQL_URL`。
- 1 个 Stage 3 全链路集成测试，需要 `KNOWBASE_STAGE3_IT=true` 和可用的 Python 服务。

## 2. 实现覆盖

| 任务 | 实现与验证 |
|---|---|
| T3.1 检索服务 | Java 根据当前用户实时计算可读 `kbId`；Python/Qdrant 只接受传入集合并在检索前做 `kb_id` filter。空授权集合不访问 Qdrant。 |
| T3.2 上下文构建 | `rag_context.py` 按分数排序、按位置和规范化正文去重、按整块控制 token 预算，并在最终集合确定后编号。 |
| T3.3 Prompt 与注入防护 | 检索片段用 `<source>` 数据块包裹；系统 Prompt 明确资料内指令无效。单测覆盖嵌入指令不进入系统指令区。 |
| T3.4 无答案短路 | 无命中时 Python 先发 `no_answer`，不调用生成模型；Java 正确落库固定话术。新增 Java 回归测试防止误报 `EMPTY_COMPLETION`。 |
| T3.5 SSE 流式与取消 | Java 转发 `meta/retrieval/delta/no_answer/done/cancelled/error`；上游 `HttpURLConnection` 流支持跨线程关闭；Python 生成器关闭会关闭模型连接；前端提供停止按钮。空输出映射为 `EMPTY_COMPLETION`。 |
| T3.6 引用完整性 | Java 只接受本次 `retrieval` 中出现过、属于本次授权库、且数据库状态为 `ACTIVE` 的分片；引用写入 `ai_message_citation`。 |
| T3.7 会话与消息 | 提供会话创建、列表、分页详情、重命名、删除；自动标题、他人会话 404、删除会话时清理消息和引用。 |
| T3.8 引用二次鉴权 | 历史引用读取时重新校验会话归属、知识库当前权限、文档状态和分片存在性；失权返回 403，失效返回 404。 |
| T3.9 前端对话页 | 会话列表、流式回答、停止生成、引用卡片、失权提示、跳转文档分片、滚动与高亮均已接入。 |
| T3.10 问答限流 | Redis 按用户和自然分钟计数；默认每分钟 20 次，超限 `429 / 42902`；问题长度 1–2000 字。 |

## 3. 全链路集成测试

测试类：`backend-java/src/test/java/com/knowbase/qa/Stage3RagIntegrationTest.java`

实际执行路径：

1. 使用种子管理员登录。
2. 创建文档知识库和空知识库。
3. 上传 TXT，等待异步任务从排队进入 `ACTIVE`。
4. Java 调 Python 完成解析、切分、Embedding 和 Qdrant 索引。
5. 调 `/qa/ask`，断言回答、引用、文档归属和引用原文。
6. 查询会话详情，验证引用落库与分页读取。
7. 通过 `/qa/citations/{id}` 读取引用原文。
8. 重命名会话并按知识库查询会话列表。
9. 在空知识库提问，断言固定无答案话术且无引用。
10. 调 `/qa/stream`，断言收到 `meta/retrieval/delta/done` 且没有 `error`。
11. 删除会话并断言后续读取返回 404。
12. 删除文档，等待异步 Qdrant 清理，再清理本次测试数据。

结果：

```text
Stage3RagIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

说明：由于当前工作区沙箱禁止 Java NIO 自唤醒管道和 Netty selector，集成测试使用 Spring
MVC 测试容器而不是再启动 Tomcat；Redis 依赖在该测试中仅为登录/限流计数提供内存替身。
Redis 限流与登录锁定算法分别由 `QaRateLimiterTest` 和 `LoginAttemptServiceTest` 覆盖。

## 4. 命令与输出

### 4.1 Python

```powershell
cd backend-python
$env:PYTHONPATH='D:\thesis\KnowBase-AI\backend-python\.venv\Lib\site-packages;D:\thesis\KnowBase-AI\backend-python'
$env:PYTHONDONTWRITEBYTECODE='1'
& 'C:\Users\Mr.world\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' `
  -m pytest tests -q -p no:cacheprovider --basetemp=D:\thesis\KnowBase-AI\tmp\pytest
```

```text
66 passed in 2.72s
```

重点用例：

- `test_context_sorts_deduplicates_and_numbers_sources`
- `test_context_budget_drops_whole_chunks_instead_of_truncating`
- `test_prompt_wraps_retrieved_text_as_data_and_rejects_embedded_instructions`
- `test_no_answer_does_not_call_chat_provider`
- `test_closing_stream_closes_provider_generator`
- `test_stream_emits_retrieval_deltas_done_and_only_retrieved_citations`

### 4.2 Java

```powershell
cd backend-java
mvn -q "-Dmaven.repo.local=D:\thesis\KnowBase-AI\tmp\m2repo" `
  "-Dmaven.compiler.fork=true" test
```

```text
Tests run: 44, Failures: 0, Errors: 0, Skipped: 3
```

Stage 3 集成测试启用命令：

```powershell
$env:KNOWBASE_STAGE3_IT='true'
$env:INTERNAL_TOKEN='<与 Python 服务一致的 32+ 位随机值>'
$env:APP_MASTER_KEY='<base64 编码的 32 字节密钥>'
$env:PYTHON_SERVICE_URL='http://127.0.0.1:8091'
$env:QDRANT_URL='http://127.0.0.1:6333'
$env:UPLOAD_DIR='D:\thesis\KnowBase-AI\tmp\stage3-it\uploads'
mvn -q "-Dmaven.repo.local=D:\thesis\KnowBase-AI\tmp\m2repo" `
  "-Dmaven.compiler.fork=true" "-Dtest=Stage3RagIntegrationTest" test
```

### 4.3 前端

```powershell
cd frontend
npm run build
```

```text
vite v6.4.5
1701 modules transformed
built in 8.22s
```

构建成功。Element Plus 相关 chunk 压缩前约 940 kB，是性能优化项，不影响本阶段功能。

## 5. 剩余风险

- 当前沙箱无法启动第二个 Tomcat 网络监听端口，因此没有在本轮完成 `8081` 真实 HTTP 监听和浏览器人工点击链路；改由 MockMvc 完整穿过控制器、鉴权、服务、数据库和真实 Python 服务。
- 本轮 RAG 生成使用本地 `local-extractive` 与 `local-hash-v1`，没有调用真实 LLM；真实 OpenAI-compatible 适配器通过单元测试覆盖错误状态和 SSE 读取。
- 流式取消已由 Python 生成器关闭测试和后端关闭语义覆盖；浏览器中“点停止”的人工演示需要在正常启动环境中复核。
