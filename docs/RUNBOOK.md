# KnowBase AI 生产运行手册

> 任务：T5.6–T5.9
> 适用：单机 Docker Compose 的演示/小规模生产环境
> 架构见 [ARCHITECTURE.md](ARCHITECTURE.md)，安全边界见 [SECURITY.md](SECURITY.md)。

## 1. 部署边界

生产编排文件为 [`deploy/compose.prod.yml`](../deploy/compose.prod.yml)，只向宿主机发布：

| 宿主机端口 | 服务 | 说明 |
|---:|---|---|
| 80 | Nginx | 301 跳转到 HTTPS |
| 443 | Nginx | TLS、前端、API 反向代理、SSE |

MySQL、Redis、Qdrant、Java、Python 均不映射宿主端口。Nginx 不代理 `/internal/**`。
AI 服务额外接入 `egress` 网络访问外部模型，其它内部数据服务没有公网出口。

## 2. 前置要求

- Docker Engine / Docker Desktop，支持 Compose v2
- 可用磁盘空间：镜像和数据至少预留 10 GB
- PowerShell 7（桌面验收脚本使用 `-SkipCertificateCheck`）
- 可选：`openssl`，用于生成随机主密钥和本地自签名证书

检查环境：

```powershell
Set-Location D:\thesis\KnowBase-AI
.\deploy\scripts\check-env.ps1
```

首次运行前 `.env` 尚不存在时，环境检查会明确失败，这是预期行为。

## 3. 准备生产配置

### 3.1 创建 `.env`

```powershell
Copy-Item .env.production.example .env
```

逐项替换占位值。以下值必须独立生成，不能复用开发密钥：

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `REDIS_PASSWORD`
- `QDRANT_API_KEY`
- `INTERNAL_TOKEN`，至少 32 字符
- `APP_MASTER_KEY`，32 字节随机值的 base64

生成主密钥示例：

```powershell
openssl rand -base64 32
```

`APP_MASTER_KEY` 用于解密 `ai_model_config.api_key_cipher`。丢失后已保存的模型 API Key
无法恢复，必须单独放入密码库保存。

### 3.2 模型配置

可任选一种方式：

1. 在 `.env` 配置 `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_CHAT_MODEL`。
2. 首次启动后通过管理端录入数据库模型配置，API Key 会以 AES-GCM 密文保存。

推荐生产使用管理端配置。`LLM_API_KEY` 是启动时的后备配置，不会自动迁移到数据库。

### 3.3 TLS 证书

本地验收如果没有证书，Nginx 启动脚本会自动生成自签名证书到 `deploy/certs/`。

互联网或内网正式域名必须替换为受信任证书：

```text
deploy/certs/tls.crt
deploy/certs/tls.key
```

私钥权限应为 `600`，不得提交到 Git。证书域名必须与用户访问地址一致。

## 4. 启动和验收

### 4.1 静态检查

```powershell
Set-Location D:\thesis\KnowBase-AI
.\deploy\scripts\check-env.ps1
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml config --quiet
```

`check-env.ps1` 会检查命令、关键变量、占位值和 Compose 语法。它不会验证证书是否受信任，
也不会替代实际启动。

### 4.2 构建和启动

```powershell
$compose = @(
  "compose", "--project-name", "knowbase-prod",
  "--env-file", ".env", "-f", "deploy\compose.prod.yml"
)
docker @compose build --pull
docker @compose up -d
docker @compose ps
```

预期依赖顺序：

```text
MySQL / Redis / Qdrant -> AI service -> Java backend -> Nginx
```

首次启动由 Flyway 建表并写入系统角色和默认管理员。生产首次登录后立即修改
`admin / Admin@123456`。

### 4.3 健康检查

```powershell
Invoke-RestMethod -SkipCertificateCheck https://127.0.0.1/health |
  ConvertTo-Json -Depth 6
```

四项组件应为 `UP`：

- `mysql`
- `redis`
- `qdrant`
- `python`

检查宿主端口：

```powershell
docker @compose ps --format "table {{.Service}}\t{{.Ports}}"
```

除 Nginx 的 80/443 外，不应出现宿主端口映射。

### 4.4 HTTP 冒烟

```powershell
.\backend-python\.venv313\Scripts\python.exe tests\smoke\e2e.py `
  --base-url https://127.0.0.1 --insecure
```

通过标准：

- 健康、登录、当前用户和无 token 401 正常。
- 创建知识库、上传文件并等待异步索引完成。
- 回答带引用，引用能追溯到本次上传文档。
- 不存在/无权知识库返回 403，空问题返回 400。

正式环境已使用受信任证书时，去掉 `--insecure`。

## 5. 日志与排障入口

```powershell
docker @compose ps
docker @compose logs --tail=200 nginx
docker @compose logs --tail=200 backend
docker @compose logs --tail=200 ai-service
docker @compose logs --tail=200 mysql
docker @compose logs --tail=200 qdrant
```

健康响应中的 `X-Trace-Id` 可用于同时检索 Java 和 Python 日志：

```powershell
docker @compose logs backend | Select-String "<trace-id>"
docker @compose logs ai-service | Select-String "<trace-id>"
```

详细错误分类见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

## 6. 日常运维

### 6.1 查看资源

```powershell
docker @compose stats --no-stream
docker system df
```

重点关注 Java/Python 内存、MySQL volume 增长、Qdrant volume 增长和上传卷容量。

### 6.2 重启单个服务

```powershell
docker @compose restart backend
docker @compose restart ai-service
docker @compose restart nginx
```

修改 `.env` 后使用：

```powershell
docker @compose up -d
```

仅 `restart` 不会重新读取环境变量。

### 6.3 停止和启动

```powershell
docker @compose stop
docker @compose start
```

不要使用 `down -v`，除非确认要删除 MySQL、Redis、Qdrant 和上传文件。

## 7. 升级与回滚

### 7.1 升级前

1. 在维护窗口暂停 Nginx 和后端写入：

   ```powershell
   docker @compose stop nginx backend
   ```

2. 执行完整备份，见下一节。
3. 记录当前镜像 ID：

   ```powershell
   docker image ls --format "{{.Repository}}:{{.Tag}} {{.ID}}"
   ```

4. 检查新版本 migration 和配置变更。

### 7.2 升级

```powershell
docker @compose build --pull
docker @compose up -d
docker @compose ps
```

升级后：

1. 检查 `/health` 四项为 `UP`。
2. 运行 HTTP 冒烟。
3. 登录检查关键页面。
4. 查询 `flyway_schema_history`，确认迁移成功。

### 7.3 回滚

镜像使用本地构建时，回滚前必须已经保存旧镜像 tag/ID。推荐升级前显式打 tag：

```powershell
docker tag <current-image-id> knowbase-backend:previous
docker tag <current-ai-image-id> knowbase-ai:previous
```

数据库迁移默认只向前。若新迁移已执行，不能只回滚镜像；必须先评估迁移兼容性，必要时
恢复升级前备份。不要修改已执行过的 migration。

## 8. 备份与恢复

### 8.1 备份内容

`deploy/scripts/backup.ps1` 备份：

- MySQL 全库 `mysql.sql`
- 上传卷 `uploads.tar.gz`
- Qdrant collection snapshot
- SHA-256 `manifest.json`

`APP_MASTER_KEY` 故意不进入备份，必须独立保管。

### 8.2 执行备份

为保证数据库、文件和向量快照处于同一维护窗口，先停止入口和业务后端：

```powershell
docker @compose stop nginx backend
.\deploy\scripts\backup.ps1
```

备份完成后恢复服务：

```powershell
docker @compose up -d
```

### 8.3 恢复演练

```powershell
.\deploy\scripts\restore.ps1 -BackupDirectory .\backups\backup-YYYYMMDD-HHMMSS
```

脚本会：

1. 校验 manifest 与三个文件的 SHA-256。
2. 要求输入 `RESTORE`，使用 `-Force` 可跳过交互。
3. 恢复 MySQL、上传卷和 Qdrant snapshot。
4. 启动全套服务并检查 `/health`。
5. 输出 `sys_user` 行数。

恢复后必须另做业务验收：

- 用原管理员登录。
- 打开一个历史引用。
- 下载一个原文档。
- 对同一文档提问并确认引用仍可追溯。
- 确认 `APP_MASTER_KEY` 是备份时的原值。

## 9. 干净环境验收

干净环境验收会删除本项目全部容器和 volume：

```powershell
.\deploy\scripts\cleanroom.ps1 -ResetVolumes -BaseUrl https://127.0.0.1
```

脚本执行：

1. 检查环境。
2. `compose down -v --remove-orphans`。
3. 重新拉取/构建镜像。
4. 启动并等待五分钟。
5. 运行 HTTP 冒烟。

验收目标是 30 分钟内从干净 Docker 环境完成启动。执行前必须确认本机没有需要保留的
本项目数据。

## 10. 应急处置

| 场景 | 立即动作 | 后续 |
|---|---|---|
| 健康检查 503 | 查看组件 detail 与日志 | 按错误类型修复依赖 |
| 模型密钥不可读 | 找回原 `APP_MASTER_KEY` | 无法找回则重新录入 Key |
| 文档积压 | 检查 worker、数据库和 Python 日志 | 修复依赖后重试 |
| Qdrant 异常 | 停止上传并保留 volume | 从 snapshot 恢复 |
| 数据库迁移失败 | 停止 Java，保留现场 | 备份恢复或新 migration 修复 |
| 疑似权限泄露 | 立即禁用账号/移除成员 | 审计 Trace ID、引用访问和登录记录 |
| 证书过期 | 替换 `tls.crt/tls.key` | `docker compose restart nginx` 并复测 |

## 11. 验收记录

本仓库的当前实际验证状态见 [ACCEPTANCE.md](ACCEPTANCE.md) 和
[STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md)。运行手册本身不代表所有步骤已在当前环境执行。
