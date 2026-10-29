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
