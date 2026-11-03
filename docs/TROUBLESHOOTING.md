# 部署与故障排查手册（Stage 5）

## 1. 启动顺序

1. 从仓库根目录启动 MySQL、Redis、Qdrant：

   ```powershell
   docker compose --env-file .env -f deploy/docker-compose.yml up -d
   ```

2. 启动 Python AI 服务：

   ```powershell
   cd backend-python
   uv run uvicorn app.main:app --host 127.0.0.1 --port 8090
   ```

3. 启动 Java 后端：

   ```powershell
   cd backend-java
   mvn spring-boot:run
   ```

4. 启动前端：

   ```powershell
   cd frontend
   npm run dev
   ```

## 2. 健康检查

- 公开探针：`GET http://127.0.0.1:8080/health`
- 管理聚合：`GET /admin/system/status`（仅 SYS_ADMIN）

`/health` 聚合 MySQL、Redis、Qdrant、Python。返回 `503` 时按 `components[].detail` 定位：

| detail | 排查方向 |
|---|---|
| `TIMEOUT` | 目标端口是否监听、网络策略、容器状态 |
| `CONNECTION_REFUSED` | 服务未启动或端口配置错误 |
| `DNS_ERROR` | 主机名错误或容器 DNS 不可用 |
| `AUTH_FAILED` | Redis/Qdrant/Python 内部令牌或密码不一致 |
| `AI_DEGRADED` | Python 自身探活失败，继续查 Python `/internal/health` |
| `HTTP_xxx` | 上游服务已响应但状态异常 |

排障时复制响应中的 `X-Trace-Id`，在 Java/Python 日志中检索同一条链路。

## 3. APP_MASTER_KEY 丢失或更换

AES-256-GCM 密文只能由原 `APP_MASTER_KEY` 解密。**主密钥丢失后无法恢复任何已保存的模型
API Key，也不存在后门或离线破解方案。**

现象：

- 模型列表中的 `keyStatus` 为 `UNREADABLE`。
- 模型测试返回“模型密钥无法解密”。
- RAG 调用返回 `503`，文档任务错误码可能为 `DEPENDENCY_UNAVAILABLE`。

处理：

1. 找回并恢复原 `APP_MASTER_KEY`（首选）。
2. 若确认无法找回，生成新的 32 字节 base64 密钥：

   ```powershell
   openssl rand -base64 32
   ```

3. 更新环境变量并重启 Java。
4. 在「系统设置 → 模型服务」逐条重新录入 API Key。
5. 确认模型测试通过后重新执行文档索引。

不要把主密钥和服务账号密码、数据库备份放在同一位置；应使用独立密码库或密钥管理系统备份。

## 4. 模型连接失败

### 4.1 SSRF 拒绝

默认只允许 HTTPS。以下地址会被 Java 保存校验和 Python 真实调用校验同时拒绝：

- `127.0.0.0/8`、`::1`
- `10/8`、`172.16/12`、`192.168/16`
- `169.254/16`，包括 `169.254.169.254`
- IPv6 `fc00::/7`
- `localhost`、云元数据主机名

本地 Ollama 需要使用 HTTP 时，仅可在开发环境设置：

```text
ALLOW_PRIVATE_MODEL_URLS=true
```

开启后允许私网和 HTTP，生产环境禁止开启。即使开启，也应只允许受信任的本机模型服务。

### 4.2 错误类型

| errorType | 处理 |
|---|---|
| `DNS_ERROR` | 检查域名拼写、DNS 与代理 |
| `TIMEOUT` | 检查出口网络、上游状态、代理和超时配置 |
| `AUTH_ERROR` | 重新录入正确 API Key，确认账户权限 |
| `RATE_LIMITED` | 降低请求频率或检查账户额度 |
| `ENDPOINT_NOT_FOUND` | `baseUrl` 通常应包含 `/v1`，且服务需提供 `/models` |
| `HTTP_5xx` | 上游服务故障，稍后重试并查看供应商状态 |

### 4.3 模型未生效

- 同类模型只选用最新更新时间且 `enabled=1` 的配置。
- 未配置数据库模型时回退到 `LLM_*` 环境变量或本地哈希/抽取式实现。
- 修改模型后检查 `updatedAt`，并执行一次连接测试。

## 5. 任务积压与失败

管理端「审计与健康」显示：

- `queued`：等待认领。
- `running`：正在执行；超过 10 分钟会被回收。
- `failed`：可人工重试。
- `dead`：尝试次数耗尽，需要管理员介入。
- `failedDocuments`：`FAILED` 与 `DELETE_FAILED` 文档总数。

错误 Top5 来自最近 7 天失败任务。常见错误：

- `PARSE_TIMEOUT`：检查大文件和 Python 解析上限。
- `NO_TEXT_LAYER`：扫描 PDF，无 OCR 支持。
- `DEPENDENCY_UNAVAILABLE`：Qdrant、Embedding 或模型不可用。
- `TASK_TIMEOUT`：服务重启或任务卡死，等待回收后重试。
- `DOCUMENT_STATE_CHANGED`：处理中发生删除，结果被安全丢弃。

## 6. 审计记录

登录、创建知识库、删除文档、模型新增/修改/删除/测试均写入 `sys_operation_log`。

- 审计写入失败不会改变业务结果，但 Java 日志会出现“写操作审计失败”。
- `detail` 不记录密码、API Key、请求体原文或文件内容。
- 若审计页为空，先确认 `AuthFilter`、`AuditAspect` 已加载，再检查 `sys_operation_log` 表。

## 7. 数据库迁移

Stage 4 新增 `V2__stage4_observability_indexes.sql`，只追加审计与错误统计索引。

检查：

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

迁移失败时：

1. 先停止 Java，避免重复启动继续重试。
2. 阅读 Flyway 明确报错和对应 SQL。
3. 不要修改已执行过的 V1/V2；修复环境后使用新版本迁移。
4. 生产环境执行前先备份，并记录 migration checksum。

## 8. 验证清单

```powershell
cd backend-java
mvn test

cd ..\backend-python
uv run pytest

cd ..\frontend
npm run build
```

Stage 4 详细测试证据见 [STAGE4_TEST_REPORT.md](STAGE4_TEST_REPORT.md)。

## 9. 生产 Compose 启动失败

先做静态检查：

```powershell
Set-Location D:\thesis\KnowBase-AI
.\deploy\scripts\check-env.ps1
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml config --quiet
```

常见原因：

| 现象 | 原因 | 处理 |
|---|---|---|
| `variable is not set` | `.env` 缺字段 | 对照 `.env.production.example` 补齐 |
| `check-env` 报告 placeholder | 仍使用示例密码 | 为每个密钥独立生成随机值 |
| MySQL 反复重启 | 旧 volume 与当前密码不一致 | 找回原密码；确认可删数据后再重建 volume |
| Java 启动后退出 | 主密钥、内部 Token 或配置校验失败 | 查看 `docker compose logs backend` |
| AI service unhealthy | Qdrant key/地址错误 | 检查 `QDRANT_API_KEY` 和 `data` 网络 |
| 模型调用超时 | `egress` 网络无出口或上游不可达 | 检查 Docker 出口、DNS、代理和供应商状态 |

不要在排障时直接 `down -v`，它会删除数据库、上传文件和向量。

## 10. TLS 和 Nginx

自签名证书只用于本地验收：

```text
deploy/certs/tls.crt
deploy/certs/tls.key
```

浏览器出现证书警告时，本地脚本可加 `--insecure`；正式域名必须安装受信任证书，不得
关闭浏览器校验。

检查：

```powershell
Invoke-WebRequest -SkipCertificateCheck https://127.0.0.1/health
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml logs --tail=100 nginx
```

现象与处理：

| 现象 | 处理 |
|---|---|
| `cannot load certificate` | 确认 `tls.crt/tls.key` 存在且 Nginx 容器可读 |
| `certificate has expired` | 更换证书并重启 Nginx |
| HTTP 301 循环 | 检查前置代理的 `X-Forwarded-Proto` |
| SSE 中途断开 | 检查代理超时，Nginx 已为业务路径关闭 buffering |
| 上传返回 413 | 同时检查 Nginx `client_max_body_size` 和 Spring multipart 限制 |

## 11. 备份和恢复

备份：

```powershell
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml stop nginx backend
.\deploy\scripts\backup.ps1
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml up -d
```

恢复：

```powershell
.\deploy\scripts\restore.ps1 -BackupDirectory .\backups\backup-YYYYMMDD-HHMMSS
```

检查点：

- manifest 的三个 SHA-256 全部匹配。
- `sys_user` 行数大于 0。
- `/health` 四项为 `UP`。
- 历史引用、原文下载和新提问都能工作。
- `APP_MASTER_KEY` 与备份时一致，否则模型 Key 为 `UNREADABLE`。

脚本语法通过不代表恢复演练通过；真实状态见 [ACCEPTANCE.md](ACCEPTANCE.md)。

## 12. 内部端口误暴露

生产 `compose.prod.yml` 不应出现 MySQL、Redis、Qdrant、Java 或 Python 的宿主 `ports`。

检查：

```powershell
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml ps --format "table {{.Service}}\t{{.Ports}}"
```

应只看到 Nginx 的 80/443。若误加端口：

1. 删除 Compose 中对应 `ports`。
2. `docker compose up -d` 重建容器。
3. 在主机和防火墙层确认端口关闭。
4. 审查该端口暴露期间日志和访问记录。

宿主机排查内部 AI 接口时，使用容器内网，不要把 `/internal/**` 通过 Nginx 暴露：

```powershell
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml exec backend bash
```

## 13. 干净环境验收

```powershell
.\deploy\scripts\cleanroom.ps1 -ResetVolumes -BaseUrl https://127.0.0.1
```

该命令会删除本项目 volume。失败时先查看脚本打印的 `compose ps` 和最近 200 行日志；
不要只重试启动而忽略首次失败原因。

## 14. 评估失败或结果漂移

固定集评估：

```powershell
.\backend-python\.venv313\Scripts\python.exe tests\rag-evaluation\run.py
```

先确认：

- `/health` 四项为 `UP`。
- Chat Provider 与上次记录一致。
- Embedding Provider、Top-K 和分片参数一致。
- 数据 SHA-256 与 [EVALUATION.md](EVALUATION.md) 一致。
- 默认每分钟 20 次限流不会打断 25 题顺序执行。

结果必须绑定时间、模型和 Embedding 条件；不能只看百分比窗口。指标定义和限制见
[EVALUATION.md](EVALUATION.md)。
