# Stage 4 测试报告

> 日期：2026-10-31
> 范围：T4.1-T4.7 模型配置、SSRF 防护、调用统计、审计、健康状态、工作台统计和系统设置页。
> 环境：Windows、Java 21、Spring Boot 4.1.2、MySQL 5.7.44、Python 3.13、Node/Vite 6。

## 1. 结果摘要

| 验证项 | 结果 | 实际输出 |
|---|---|---|
| Java 全量测试 | 通过 | 63 个测试，0 失败，0 错误，6 个 opt-in 跳过 |
| Stage 4 集成测试 | 通过 | 3 个测试，0 失败，真实 MySQL + MockMvc |
| Stage 3 全链路回归 | 通过 | 1 个测试，0 失败；覆盖无命中转通用回答 |
| Python 全量测试 | 通过 | `85 passed` |
| 前端生产构建 | 通过 | `vite v6.4.5`，1707 个模块，构建成功 |
| Flyway V2 | 通过 | schema version 2，校验并加载 5 个 migration |
| 代码差异检查 | 通过 | `git diff --check` 无空白错误 |
| DeepSeek 实机 RAG | 通过 | 连接测试、非流式问答和 SSE 流式问答均使用 `deepseek-chat` |

Java 全量测试默认的 6 个跳过项：

- 2 个既有 MySQL 并发集成测试，需要 `KNOWBASE_TEST_MYSQL_URL`。
- 1 个 Stage 3 全链路测试，需要 `KNOWBASE_STAGE3_IT=true`、Python 和 Qdrant。
- 3 个 Stage 4 管理集成测试，需要 `KNOWBASE_STAGE4_IT=true` 和真实 dev MySQL。

启用 `KNOWBASE_STAGE4_IT=true` 后，Stage 4 集成测试结果为
`3 passed, 0 skipped`。

## 2. 任务验收证据

| 任务 | 自动化证据 | 验收结论 |
|---|---|---|
| T4.1 模型配置 | `SecretCipherTest`、`ModelConfigServiceTest`、`Stage4AdminIntegrationTest` | 密钥以 AES-256-GCM 密文入库；接口只返回 `sk-****abcd`；不传新密钥时保留原密钥并换新 IV；`clearApiKey=true` 可清空；主密钥丢失处理已写入 `TROUBLESHOOTING.md` |
| T4.2 连接测试与 SSRF | Java `SsrfGuardTest`，Python `test_model_security.py`、`test_model_test.py` | Java 保存前和 Python 调用前双重校验 HTTPS、环回、私网、链路本地、云元数据及 IPv6 ULA；错误类型区分 DNS、超时、鉴权等状态；私网 Ollama 需显式开启开关 |
| T4.3 调用日志与统计 | `AiCallLogServiceTest`、Python 模型调用测试、Stage 4 状态集成测试 | Embedding、Chat、连接测试均写 `ai_call_log`；支持按模型、按天和错误类型聚合；状态接口错误 Top5 与数据库聚合一致 |
| T4.4 审计切面 | `AuditAspectTest`、`Stage4AdminIntegrationTest` | 登录、创建知识库、删除文档、模型新增/修改/删除/测试均写 `sys_operation_log`；审计不序列化密码、API Key 或请求体；模型创建审计行按真实 `resource_id` 落库 |
| T4.5 健康与积压 | `SystemStatusControllerTest`、Stage 4 集成测试 | `/health` 聚合 MySQL、Redis、Qdrant、Python；管理状态页返回四组件、队列状态、失败文档及任务/模型错误 Top5；单测以 5 个失败任务断言页面数据值为 5 |
| T4.6 工作台统计 | `WorkspaceSummaryServiceTest`、Stage 4 集成测试 | `/workspace/summary` 固定返回 `scope=PERSONAL`；五个计数按当前用户重新执行 SQL 后逐项一致；不属于该用户的知识库和文档不参与统计 |
| T4.7 系统设置页 | 前端 `npm run build`、`PermissionInterceptorTest`、Stage 4 集成测试 | `/admin` 路由有管理员守卫；普通用户访问模型、系统状态、审计接口均为 HTTP 403；管理员页面包含用户、模型服务、审计与健康三个 Tab |

## 3. Stage 4 集成测试

测试类：`backend-java/src/test/java/com/knowbase/system/Stage4AdminIntegrationTest.java`

该测试默认关闭，设置 `KNOWBASE_STAGE4_IT=true` 后执行，使用真实 dev MySQL 和 dev 种子账号。Redis 只在测试中替换登录限流存储；模型配置测试不调用外部 AI 服务。

覆盖流程：

1. `viewer` 登录后访问 `/admin/models`、`/admin/system/status`、`/admin/audit-logs`，均断言 403。
2. `admin` 创建 CHAT 模型，响应中只出现掩码后的 API Key。
3. 直接查询数据库，断言 `api_key_cipher` 以 `v1:` 开头且不包含明文。
4. 更新但不提交新 Key，断言密钥保留、响应继续脱敏、密文因新 IV 而变化。
5. 提交 `clearApiKey=true`，断言密钥被清空。
6. 查询 `sys_operation_log`，断言 `MODEL_CREATE / MODEL / SUCCESS` 审计行存在。
7. 删除模型并断言数据库记录消失，测试结束清理审计和调用日志。
8. 对 `viewer` 调 `/workspace/summary`，用当前用户 ID 逐项执行 SQL 复算五个统计值。
9. 对 `admin` 调 `/admin/system/status`，断言组件顺序为 `mysql/redis/qdrant/python`、积压计数与数据库一致、两个错误 Top5 字段均为数组。

结果：

```text
Stage4AdminIntegrationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

### DeepSeek 实机验证

通过管理 API 将 DeepSeek Key 写入 `ai_model_config` 后：

```text
Model: deepseek-chat
Storage: AES_GCM
Connection test: success, 2187 ms
Chat provider: openai-compatible:deepseek-chat
Question: distributionUrl 指向哪个地址？
Answer: distributionUrl 指向 file:///C:/Users/Administrator/.gradle/wrapper/dists/gradle-6.7.1-bin.zip [1]
Citation count: 1
Latency: 3104 ms
SSE events: meta, retrieval, delta..., done
SSE error events: 0
```

### 会话与无依据降级实机验证

问候不访问 Qdrant/Embedding，直接调用已启用的 Chat Provider：

```text
Question: 你好
Chat provider: openai-compatible:deepseek-chat
No answer: false
Citation count: 0
Answer: 你好！我是知库 AI 助手。我可以帮你阅读已上传的知识库资料……
```

知识问题仍以检索结果作为唯一事实依据：

```text
Question: distributionUrl 指向哪个地址？
Answer: distributionUrl 指向 file:///C:/Users/Administrator/.gradle/wrapper/dists/gradle-6.7.1-bin.zip [1]。
Citation count: 1
```

分析型问题会先理解、归纳检索资料，再给出判断和依据，而不是简单复述原文：

```text
Question: 根据资料分析 distributionUrl 的含义，并判断它是否指向 Gradle 官方在线地址。
Analysis: distributionUrl 用于指定 Gradle 发行包位置；file:/// 表示本地文件系统地址，
          不是 HTTP/HTTPS 在线地址，因此不指向 Gradle 官方在线地址。
Chat provider: openai-compatible:deepseek-chat
Citation count: 1
```

检索为空时先提示无知识库依据，再由 DeepSeek 使用通用知识回答：

```text
Question: 火星基地的氧气配额是多少？
Answer prefix: 知识库中未找到相关依据。
Chat provider: openai-compatible:deepseek-chat
No answer: false
Citation count: 0
SSE events: meta, retrieval(mode=general_fallback), delta..., done
SSE error events: 0
SSE no_answer events: 0
```

该回答明确声明“基于通用知识，不代表任何企业内部制度或事实”。没有可查询授权范围时
仍返回 `no_answer`，避免在权限范围为空时调用模型。

该验证使用真实 MySQL、Qdrant、Python AI 服务和 DeepSeek API。知识库向量仍由
`local-hash-v1` 生成，DeepSeek 负责基于检索上下文生成最终答案；回答中的
`chatProvider` 可用于确认模型是否真实生效。

## 4. 复现命令

### 4.1 Java

前置条件：MySQL `knowbaseai` 可连接，根目录 `.env` 已配置 `APP_MASTER_KEY` 等本地变量，dev migration 已加载测试账号。

```powershell
cd D:\thesis\KnowBase-AI\backend-java
mvn test
```

启用真实 MySQL Stage 4 集成测试：

```powershell
cd D:\thesis\KnowBase-AI\backend-java
$env:KNOWBASE_STAGE4_IT='true'
mvn "-Dtest=Stage4AdminIntegrationTest" test
```

全量并启用 Stage 4 集成测试：

```powershell
$env:KNOWBASE_STAGE4_IT='true'
mvn test
```

### 4.2 Python

项目要求 Python 3.12+：

```powershell
cd D:\thesis\KnowBase-AI\backend-python
uv sync --dev
uv run pytest -q
```

### 4.3 前端

```powershell
cd D:\thesis\KnowBase-AI\frontend
npm ci
npm run build
```

Element Plus vendor chunk 压缩前约 941 kB，Vite 输出大 chunk 警告；这是后续按需加载优化项，不影响本阶段功能。

## 5. 重点用例

Java：

- `SecretCipherTest`：加解密、随机 IV、错误密钥/损坏密文、脱敏格式。
- `SsrfGuardTest`：HTTPS、环回、私网、链路本地、元数据和 IPv6 ULA。
- `ModelConfigServiceTest`：更新保留/替换/清空 Key，敏感参数拒绝。
- `AuditAspectTest`：成功与失败均留痕，敏感请求不进入 detail。
- `PermissionInterceptorTest`：类级和方法级管理权限。
- `WorkspaceSummaryServiceTest`：统计范围绑定当前用户。
- `SystemStatusControllerTest`：五项积压数据和错误 Top5 聚合。
- `QaServiceTest`：无命中通用回答落库为 `OK/noAnswer=false`，引用为空。

Python：

- `test_model_security.py`：SSRF 地址矩阵和 DNS/连接失败分类。
- `test_model_test.py`：连接测试响应、超时与错误类型映射。
- `test_chat_stream.py`：问候绕过检索、混合问题不误判、无命中通用回答降级、
  有命中引用和无授权范围短路。
- `test_rag_context.py`：检索资料包裹、分析型 Prompt、注入文本隔离和上下文预算。

## 6. 环境限制与剩余风险

- 本轮已启动 Java `8080`、Python `8090` 和 Vite `5173`，并通过真实 HTTP 请求验证登录、非流式问答和 SSE 流式问答；自动化集成测试仍使用 MockMvc，不额外占用第二个监听端口。
- Docker daemon 在当前环境不可用，因此未重新执行 Compose 全栈启动；本阶段没有新增容器依赖，Java/Python/前端和 V2 migration 分别验证通过。
- 连接测试没有调用公网真实模型，避免产生费用和外部波动。地址校验、错误分类、调用日志和配置 CRUD 使用单元测试及可控地址覆盖；上线前应在目标网络对实际供应商执行一次连接测试。
- `/health` 返回按依赖实时变化，Python/Qdrant 未启动时状态为 `DEGRADED` 属预期行为；管理集成测试只断言四组件结构和数据库统计，不把外部依赖在线作为通过前提。
