# 知库 AI（KnowBase AI）

一个面向企业/团队的知识库问答系统：上传文档，自动切分、向量化，并基于 RAG 给出带引用的回答。

## 当前状态

**Stage 1 进行中（T1.1–T1.4）** —— 仓库骨架、基础设施编排、数据库迁移与后端工程初始化。

## 技术栈

Vue 3 + Spring Boot 4.1 + FastAPI + MySQL 8.4 / Redis 8 / Qdrant 1.20

## 目录结构

```
KnowBase-AI/
├── deploy/
│   ├── docker-compose.yml
│   └── scripts/
├── database/
│   ├── migrations/          # Flyway：V1__init.sql、R__seed.sql
│   └── seed/
├── backend-java/            # Spring Boot 4.1 业务后端
│   └── src/main/java/com/knowbase/
├── backend-python/          # FastAPI AI 服务（Stage 1 T1.5 起）
├── frontend/                # Vue 3 前端（Stage 1 T1.11 起）
├── tests/
│   ├── smoke/
│   └── rag-evaluation/
├── docs/
│   ├── DEVELOPMENT_PLAN.md
│   ├── STACK.md
│   └── 项目名.md
├── .env.example
├── .gitignore
└── README.md
```

## 前置要求

- JDK 21
- Maven 3.9+
- Node 22+ 与 pnpm
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

5. Python AI 服务与前端启动命令：待补充（T1.5 / T1.11 产出）。

## 配置与安全

### 三个配置文件的分工

| 文件 | 作用 |
| --- | --- |
| `application.yml` | 通用配置：端口、连接池、Redis、Flyway、日志格式。不含任何密码或弱默认值 |
| `application-dev.yml` | 本机开发：账号密码取自环境变量，数据库连接不加密（容器只监听 `127.0.0.1`） |
| `application-prod.yml` | 生产：所有敏感值只能由环境注入，没有任何默认值，数据库连接强制 `sslMode=VERIFY_IDENTITY` |

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

### 生产部署

部署到任何可被他人访问的环境，必须设置：

```
SPRING_PROFILES_ACTIVE=prod
```

此时数据库连接使用 `sslMode=VERIFY_IDENTITY`，且 `INTERNAL_TOKEN`（≥32 字符）与 `REDIS_PASSWORD` 必须非空。

### 健康检查不回显异常原文

`/health` 只返回依赖名与稳定状态码（如 `TIMEOUT`、`CONNECTION_REFUSED`、`AUTH_FAILED`、`HTTP_503`），不含异常类名或异常消息。排查时用响应头 `X-Trace-Id` 到服务端日志里检索同一条链路。

配置了 `QDRANT_API_KEY` 时，Qdrant 探针改走 `/collections`（需要鉴权的接口），密钥不对会返回 `AUTH_FAILED`；Qdrant 的 `/healthz`、`/readyz` 本身不校验密钥，用它们探活发现不了密钥配错。

> T1.6 接入 Spring Security 后补齐 HTTP 安全响应头与接口鉴权。

## 端口

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

## 安全提示

- `.env` 已在 `.gitignore` 中，**禁止提交任何真实密钥**。
- `APP_MASTER_KEY` 用于加密敏感配置，一旦丢失将**无法恢复**已加密的数据，请妥善备份。
