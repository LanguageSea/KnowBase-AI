# Stage 5 测试报告

> 日期：2026-10-31
> 范围：T5.1–T5.12 综合测试与交付
> 环境：Windows、Java 21、Spring Boot 4.1.2、Python 3.13、Node 22、Vite 6.4.5
> 数据：本地 MySQL、Redis、Qdrant、Java 8080、Python 8090

## 1. 结果摘要

| 验证项 | 结果 | 实际输出 |
|---|---|---|
| Java 默认测试 | 通过 | 64 个测试，0 失败，0 错误，7 个环境依赖/opt-in 跳过 |
| 权限全量矩阵 | 通过 | 47 个接口用例 × 3 角色 = 141 次组合，0 不匹配 |
| Python 全量测试 | 通过 | `85 passed` |
| RAG 评估器测试 | 通过 | 4 个测试全部通过 |
| 真实 RAG 固定集 | 通过 | 25 题，Recall@5 97.50%，P95 1973 ms |
| HTTP 冒烟 | 通过 | 20/20 |
| 前端生产构建 | 通过 | 1707 modules，构建成功，有 Element Plus 大 chunk 警告 |
| 生产 Compose 配置 | 通过 | `docker compose config --quiet`，退出码 0 |
| Testcontainers 主干 IT | **未执行** | `mvn -Pintegration verify` 编译通过；Docker 不可访问，测试跳过 |
| 备份恢复演练 | **未执行** | 脚本语法通过，Docker 运行时不可用 |
| 干净环境 30 分钟 | **未执行** | Docker daemon 不可用 |
| T5.10 录屏 | 按用户要求取消 | 保留 [DEMO.md](DEMO.md) 作为可选脚本 |

结论：当前环境下所有非 Docker 运行项通过；Docker 运行时验收项未完成，不能整体标记为
Stage 5 全量验收通过。

## 2. Java

### 2.1 默认测试

命令：

```powershell
cd D:\thesis\KnowBase-AI\backend-java
mvn -q "-Dmaven.repo.local=D:\thesis\KnowBase-AI\.m2-work" test
```

Surefire XML 汇总：

```text
Tests:   64
Failures: 0
Errors:   0
Skipped:  7
```

7 个跳过项是：

- 2 个 MySQL 并发集成测试，需显式提供测试数据库。
- 1 个 Stage 3 全链路测试，需 Python/Qdrant 和 opt-in 开关。
- 1 个 Stage 5 权限矩阵，需 `KNOWBASE_STAGE5_IT=true`。
- 3 个 Stage 4 管理集成测试，需真实 dev MySQL 和 opt-in 开关。

### 2.2 权限矩阵

测试类：

[PermissionMatrixIntegrationTest.java](../backend-java/src/test/java/com/knowbase/system/PermissionMatrixIntegrationTest.java)

执行方式：

```powershell
$env:KNOWBASE_STAGE5_IT = "true"
$env:KNOWBASE_STAGE5_MYSQL_URL = "jdbc:mysql://127.0.0.1:3306/knowbaseai?...&sslMode=DISABLED&allowPublicKeyRetrieval=true"
mvn -q "-Dmaven.repo.local=D:\thesis\KnowBase-AI\.m2-work" `
  "-Dtest=PermissionMatrixIntegrationTest" test
```

覆盖：

- 公开：登录、健康。
- 登录后：当前用户、登出、改密、会话、工作台、知识库列表/创建。
- 知识库读：详情、成员、文档、分片、下载、会话创建。
- 知识库管理：成员变更、文档上传/删除/重试。
- 系统管理：用户、模型、统计、审计、系统状态。

三个角色：

```text
SYS_ADMIN
KB_ADMIN
USER
```

每个接口按“应允许时不得 401/403，应拒绝时必须 403”断言。测试使用
`stage5_admin / stage5_kbadmin / stage5_viewer` 专用账号，结束后清理临时知识库、文档、
会话、用户角色、登录日志和操作审计，避免修改开发管理员账号的 token 状态。

结果：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
Interface/role combinations: 47 × 3 = 141
```

### 2.3 Testcontainers 主干链路

测试类：[MainFlowIT.java](../backend-java/src/it/java/com/knowbase/MainFlowIT.java)

目标链路：

```text
登录 -> 创建知识库 -> 上传 TXT -> MySQL 任务
     -> 真实 Python 容器解析/索引 -> Qdrant 容器
     -> Java QA -> 引用回到原文档/分片
```

执行：

```powershell
mvn -q -Pintegration "-Dmaven.repo.local=D:\thesis\KnowBase-AI\.m2-work" verify
```

结果：

- 首次执行发现缺少 `testcontainers-junit-jupiter`，已补齐依赖。
- 再次执行时测试源码和 Failsafe 配置编译通过。
- Testcontainers 检测 Windows named pipe 时得到 `AccessDeniedException`。
- `@Testcontainers(disabledWithoutDocker = true)` 使 Maven 返回成功，但 MainFlowIT
  **实际跳过，未执行任何容器**。

因此 T5.4 状态是“代码完成、编译通过、运行验收未完成”。

## 3. Python

命令：

```powershell
cd D:\thesis\KnowBase-AI\backend-python
$env:TEMP = "D:\thesis\KnowBase-AI\tmp\pytest-temp"
$env:TMP = $env:TEMP
.\.venv313\Scripts\python.exe -m pytest -q
```

结果：

```text
85 passed, 1 warning in 1.61s
```

唯一警告是 pytest 无法写入仓库内 `.pytest_cache`，不影响测试结果。

评估器测试：

```powershell
.\backend-python\.venv313\Scripts\python.exe -m unittest discover `
  -s tests\rag-evaluation -p "test_*.py" -v
```

结果：

```text
Ran 4 tests
OK
```

## 4. 真实 HTTP 冒烟

命令：

```powershell
.\backend-python\.venv313\Scripts\python.exe tests\smoke\e2e.py `
  --base-url http://127.0.0.1:8080
```

最终结果：

```text
共 20 项，通过 20，失败 0
```

覆盖：

- `/health` 为 `UP`。
- 登录、`/auth/me`、无 token 401。
- 创建知识库、列表可见、重名 409。
- 上传返回 `PENDING`，轮询后变为 `ACTIVE` 且分片数大于 0。
- 文件大小、SHA-256、UUID 存储路径。
- 问答回答非空，引用可追溯，包含 `[1]` 且引用顺序连续。
- 不存在/无权知识库返回 403，空问题返回 400。

测试过程中发现并修正了旧冒烟脚本的两个过期假设：

1. 上传接口是异步的，不能要求响应立即为 `ACTIVE`。
2. 不存在与无权资源统一返回 403，避免资源枚举。

## 5. RAG 固定集评估

结果文件：

- [latest.json](../tests/rag-evaluation/results/latest.json)
- [latest.md](../tests/rag-evaluation/results/latest.md)
- 时间戳原始文件
  `rag-evaluation-20261031-190927.json/.md`

条件：

| 项目 | 值 |
|---|---|
| 样本 | 25 题，5 类各 5 题 |
| 文档 | 10 份短 Markdown |
| Chat Provider | `openai-compatible:deepseek-chat` |
| Embedding | `local-hash-v1` |
| Top K | 5 |
| 请求间隔 | 3.1 秒 |
| 数据集 SHA-256 | `1d6f3e676bb34c77dc360ae54ee90a6493769738a24dd537e836f2e72d63a3cf` |

指标：

| 指标 | 结果 |
|---|---:|
| 文档级 Recall@5 | 97.50% |
| 分片级 Recall@5 | 97.50% |
| 文档级引用正确率 | 100.00% |
| 分片级引用正确率 | 100.00% |
| 总体拒答率 | 20.00% |
| 无答案题正确拒答率 | 100.00% |
| 可答案题误拒答率 | 0.00% |
| 关键要点覆盖率 | 81.67% |
| P50 | 1222 ms |
| P95 | 1973 ms |
| Max | 2226 ms |

唯一 Recall 缺口是 M04：多文档问题只覆盖一份期望文档，文档 Recall 为 50%。由于返回的
引用都属于期望集合，引用正确率仍为 100%。这证明评价必须同时看召回和引用精度。

该结果只适用于固定数据与上述模型条件，不是行业基准，也不能表述为“系统准确率 97.5%”。

## 6. 前端

命令：

```powershell
cd D:\thesis\KnowBase-AI\frontend
npm run build
```

结果：

```text
vite v6.4.5
1707 modules transformed
built in 10.26s
```

警告：

```text
element-plus vendor chunk 940.67 kB
Some chunks are larger than 800 kB after minification
```

这是已有性能问题，不阻塞构建，已记录在 [KNOWN_ISSUES.md](KNOWN_ISSUES.md)。

## 7. 部署配置

命令：

```powershell
docker compose --env-file .env.production.example `
  -f deploy\compose.prod.yml config --quiet
```

结果：退出码 0。Docker CLI 仅报告无法读取用户级 `config.json`，未影响项目 Compose 解析。

静态确认：

- 仅 Nginx 发布宿主 80/443。
- `data` 网络为 `internal: true`。
- 只有 `ai-service` 同时接入 `egress`。
- Java 健康检查依赖 MySQL/Redis/Qdrant/AI service。
- Nginx 不代理 `/internal/**`。

这不等于容器已启动，T5.6 运行时部分仍由 T5.8 覆盖。

## 8. 文档与脚本检查

文档链接：

```powershell
.\backend-python\.venv313\Scripts\python.exe tests\docs\check_links.py
```

PowerShell 语法：

```powershell
$files = Get-ChildItem deploy\scripts -Filter *.ps1
foreach ($file in $files) {
  $tokens = $null
  $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile(
    $file.FullName, [ref]$tokens, [ref]$errors
  ) | Out-Null
  if ($errors.Count) { throw "$file has parse errors" }
}
```

实际结果：

```text
All local Markdown links and fragments are valid.
OK backup.ps1
OK check-env.ps1
OK cleanroom.ps1
OK restore.ps1
```

`git diff --check` 退出码为 0。Git 对既有 `docs/项目书.md` 输出
`CRLF will be replaced by LF` 提示，这不是空白错误，也不是本次 Stage 5 文档的内容变更。

语法和链接检查通过不等同于脚本已完成真实业务演练。

## 9. 未执行项

| 项目 | 原因 | 后续动作 |
|---|---|---|
| T5.4 容器主干链路 | Docker named pipe 拒绝访问 | 修复 Docker 权限后运行 `mvn -Pintegration verify` |
| T5.7 备份恢复 | Docker daemon 不可用 | 按 [RUNBOOK.md](RUNBOOK.md) 完成删库恢复演练 |
| T5.8 干净环境 | Docker daemon 不可用 | 运行 `cleanroom.ps1 -ResetVolumes` |

## 10. 复现注意

- Java 测试使用本地依赖缓存 `.m2-work`，避免网络受限导致失败。
- Python pytest 需要把 `TEMP/TMP` 指向仓库内可写目录。
- 权限矩阵会写真实 MySQL，必须使用测试数据库/测试账号，不能与生产共用。
- RAG 评估会创建真实知识库并调用模型，可能产生费用。
- Compose 环境变量示例含占位密钥，只用于配置解析，不能直接启动。

完整验收门槛见 [ACCEPTANCE.md](ACCEPTANCE.md)。
