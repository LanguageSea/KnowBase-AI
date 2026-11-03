# 知库 AI（KnowBase AI）

一个面向企业/团队的知识库问答系统：上传文档，自动切分、向量化，并基于 RAG 给出带引用的回答。

## 当前状态

**Stage 5 交付实现完成（T5.1–T5.12）** —— 固定评估集、真实 RAG 评估、Testcontainers
主干测试、权限矩阵、生产 Compose/Nginx、备份恢复脚本、用户/运维/验收文档、演示脚本、
简历与已知问题均已落盘。

Stage 1–4 的上传、解析、向量检索、带引用问答、流式回答、权限隔离、模型配置和可观测性
保持可用。

当前环境已通过 Java/Python/前端/RAG/权限矩阵/HTTP 冒烟；Docker daemon 拒绝访问，
因此跨容器 IT、备份恢复演练和干净环境启动仍需在可用 Docker 环境执行。真实状态见
[ACCEPTANCE.md](docs/ACCEPTANCE.md) 与 [STAGE5_TEST_REPORT.md](docs/STAGE5_TEST_REPORT.md)。
T5.10 录屏已按用户要求取消，演示脚本保留在 [DEMO.md](docs/DEMO.md)。

### Stage 5 交付入口

| 交付物 | 入口 |
|---|---|
| 用户使用手册 | [USER_MANUAL.md](docs/USER_MANUAL.md) |
| 部署与运维 | [RUNBOOK.md](docs/RUNBOOK.md)、[TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) |
| 系统架构 | [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 数据库与 ER 图 | [DATABASE.md](docs/DATABASE.md) |
| API 文档 | [API_SPEC.md](docs/API_SPEC.md)、[INTERNAL_API.md](docs/INTERNAL_API.md) |
| RAG 评估 | [EVALUATION.md](docs/EVALUATION.md)、[latest.md](tests/rag-evaluation/results/latest.md) |
| 验收结果 | [ACCEPTANCE.md](docs/ACCEPTANCE.md)、[STAGE5_TEST_REPORT.md](docs/STAGE5_TEST_REPORT.md) |
| 演示录像脚本 | [DEMO.md](docs/DEMO.md) |
| 简历与面试 | [RESUME.md](docs/RESUME.md)、[INTERVIEW_NOTES.md](docs/INTERVIEW_NOTES.md) |
| 已知问题 | [KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) |

## 技术栈

Vue 3 + Spring Boot 4.1 + FastAPI + MySQL 8.4 / Redis 8 / Qdrant 1.20

## 目录结构

```
KnowBase-AI/
├── deploy/
│   ├── docker-compose.yml
│   ├── compose.prod.yml
│   ├── nginx/
│   ├── certs/
│   └── scripts/             # check-env / cleanroom / backup / restore
├── database/
│   ├── migrations/          # Flyway：V1__init.sql、R__seed.sql
│   └── seed/
├── backend-java/            # Spring Boot 4.1 业务后端
│   └── src/main/java/com/knowbase/
├── backend-python/          # FastAPI AI 服务（Stage 1 T1.5 起）
├── frontend/                # Vue 3 前端（Stage 1 T1.11 起）
├── tests/
│   ├── smoke/               # HTTP 闭环冒烟
│   ├── rag-evaluation/      # 25 题固定集、执行器和结果
│   └── docs/                # Markdown 链接检查
├── docs/
│   ├── DEVELOPMENT_PLAN.md
│   ├── USER_MANUAL.md
│   ├── RUNBOOK.md
│   ├── ACCEPTANCE.md
│   └── ...
├── .env.example
├── .gitignore
└── README.md
```

## 前置要求

- JDK 21
- Maven 3.9+
- Node 22+ 与 npm
- uv（Python 依赖与环境管理）
- Docker Desktop

## 快速开始

1. 复制环境变量模板并填写密码等配置：

   ```powershell
   Copy-Item .env.example .env
   ```

   至少需要填入 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`APP_MASTER_KEY`。

2. 从**仓库根目录**启动基础设施（MySQL / Redis / Qdrant）：

   ```powershell
   docker compose --env-file .env -f deploy/docker-compose.yml up -d
   ```

3. 检查健康状态，应看到 3 个服务均为 `healthy`：

   ```powershell
   docker compose --env-file .env -f deploy/docker-compose.yml ps
   ```

4. 启动 Java 后端。`.env` 不会自动被 Spring 读取，需先导入当前终端会话：

   ```powershell
   Get-Content .env | Where-Object { $_ -match '^\s*[A-Z_][A-Z0-9_]*=' } | ForEach-Object {
       $i = $_.IndexOf('='); Set-Item -Path "Env:$($_.Substring(0,$i).Trim())" -Value $_.Substring($i+1).Trim()
   }
   cd backend-java
   mvn spring-boot:run
   ```

   首次启动 Flyway 会自动建 17 张表并写入种子数据（管理员 `admin / Admin@123456`）。
   健康检查：`curl http://localhost:8080/health` 返回 `200` 且三个组件均 `UP`；
   停掉任一依赖（如 `docker stop knowbase-qdrant`）应返回 `503` 并在 `components` 里指出是哪个组件。

5. 启动 Python AI 服务：

   ```powershell
   cd backend-python
   uv run uvicorn app.main:app --host 127.0.0.1 --port 8090
   ```

   另开终端启动前端：

   ```powershell
   cd frontend
   npm run dev
   ```

   打开 `http://127.0.0.1:5173` 进入面向面试官的项目展示页。点击「进入系统」登录后，
   管理员可在「系统设置」中维护模型、查看健康/积压和审计记录；登录后也可从顶部导航返回项目展示。

   前端 E2E 测试复用该 dev server：

   ```powershell
   cd frontend
   npm run test:e2e
   ```

   后端与数据库已启动时，验证真实登录和返回展示页：

   ```powershell
   $env:E2E_LIVE_LOGIN='1'
   npm run test:e2e
   ```

## 配置与安全

### 三个配置文件的分工

| 文件 | 作用 |
| --- | --- |
| `application.yml` | 通用配置：端口、连接池、Redis、Flyway、日志格式。不含任何密码或弱默认值 |
| `application-dev.yml` | 本机开发：账号密码取自环境变量，数据库连接不加密（容器只监听 `127.0.0.1`） |
| `application-prod.yml` | 生产：所有敏感值只能由环境注入，没有任何默认值，数据库连接强制 `sslMode=VERIFY_IDENTITY` |

### 本机怎么把密钥交给进程

`dev` profile 会自动导入仓库根的 `.env`（`optional:file:./.env[.properties]` 与 `../.env[.properties]`），IDE 里直接点运行、命令行 `mvn spring-boot:run` 都不必先手工导出变量。`prod` 不读任何文件，只认真实环境变量；且 OS 环境变量优先级高于 `.env`，临时覆盖直接写在命令前即可。

### 没有默认密码

后端刻意不为 `MYSQL_PASSWORD`、`APP_MASTER_KEY` 提供默认值。缺失、留空或仍是 `.env.example` 里的示例值时，进程会直接启动失败，而不是带着弱口令跑起来：

```
配置校验失败，拒绝启动：
  - MYSQL_PASSWORD 仍是示例占位值，必须替换
  - APP_MASTER_KEY 未设置
请参考 .env.example 与 README 的「配置与安全」章节。
```

`APP_MASTER_KEY` 必须是 **32 字节**随机密钥的 base64 编码：

```bash
openssl rand -base64 32
```

它用于加密 `ai_model_config.api_key_cipher`，**一旦丢失，已存的模型 API Key 无法解密恢复**，请单独备份。

模型服务地址默认只允许 HTTPS，并拒绝环回、私网、链路本地和云元数据地址。仅本地 Ollama
开发时可显式设置 `ALLOW_PRIVATE_MODEL_URLS=true`，不得在生产环境开启。

### 用 DeepSeek 驱动知识库问答

登录系统管理员账号后，在「系统设置 → 模型服务」新增一条 `CHAT` 配置：

- Provider：`deepseek`
- 模型名称：`deepseek-chat`
- Base URL：`https://api.deepseek.com`
- API Key：在页面录入，保存后数据库只保留 AES-GCM 密文，接口只显示掩码
- 参数：例如 `{"temperature":0.2,"max_tokens":2048}`

保存后先执行「测试连接」，再进入知识库问答。回答元数据中的
`chatProvider=openai-compatible:deepseek-chat` 表示 DeepSeek 已生效；若没有启用的
`CHAT` 配置且 `LLM_API_KEY` 为空，系统仍会明确回退到 `local-extractive`。

问答会先检索当前用户有权访问的知识库。检索有依据时，DeepSeek 会先阅读并综合分析
检索到的资料，再基于引用资料回答；
检索为空时，先明确提示“知识库中未找到相关依据”，再使用通用模型回答并注明这是通用知识，
不会把模型常识伪装成企业内部制度，也不会生成引用。

模型配置、健康检查、审计和故障排查详见 `docs/API_SPEC.md` 与 `docs/TROUBLESHOOTING.md`。

### 生产部署

完整步骤见 [RUNBOOK.md](docs/RUNBOOK.md)。生产入口为
[`deploy/compose.prod.yml`](deploy/compose.prod.yml)，只向宿主暴露 Nginx 80/443。

```powershell
Copy-Item .env.production.example .env
.\deploy\scripts\check-env.ps1
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml config --quiet
docker compose --project-name knowbase-prod --env-file .env `
  -f deploy\compose.prod.yml up -d
```

`.env` 中的密钥必须独立生成；生产首次登录后立即修改默认管理员密码。互联网部署前替换
`deploy/certs/tls.crt` 和 `tls.key` 为受信任证书。

### 健康检查不回显异常原文

`/health` 只返回依赖名与稳定状态码（如 `TIMEOUT`、`CONNECTION_REFUSED`、`AUTH_FAILED`、`HTTP_503`），不含异常类名或异常消息。排查时用响应头 `X-Trace-Id` 到服务端日志里检索同一条链路。

配置了 `QDRANT_API_KEY` 时，Qdrant 探针改走 `/collections`（需要鉴权的接口），密钥不对会返回 `AUTH_FAILED`；Qdrant 的 `/healthz`、`/readyz` 本身不校验密钥，用它们探活发现不了密钥配错。

> T1.6 接入 Spring Security 后补齐 HTTP 安全响应头与接口鉴权。

## 本地开发端口

| 服务 | 端口 |
| --- | --- |
| MySQL | 33307 |
| Redis | 16380 |
| Qdrant HTTP | 6333 |
| Qdrant gRPC | 6334 |
| Java 服务 | 8080 |
| Python AI 服务 | 8090 |
| 前端（Vite） | 5173 |

> 宿主机 3306 已被本机已有的 MySQL 占用，故 MySQL 对外映射为 33307，Redis 同样错开到 16380。

生产 Compose 只发布：

| 服务 | 宿主端口 |
| --- | --- |
| Nginx HTTP | 80（跳转 HTTPS） |
| Nginx HTTPS | 443 |

MySQL、Redis、Qdrant、Java、Python 不映射宿主端口。

## 安全提示

- `.env` 已在 `.gitignore` 中，**禁止提交任何真实密钥**。
- `APP_MASTER_KEY` 用于加密敏感配置，一旦丢失将**无法恢复**已加密的数据，请妥善备份。
