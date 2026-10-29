# STACK.md — 技术栈与版本锁定

> 本文件是 T0.6 的产出。**所有版本号以此处为准**，`pom.xml` / `package.json` / `requirements.txt` / `docker-compose.yml` 必须与本文一致。
> 核对时间：2026-10-29。核对范围：本机 Windows 开发环境。

---

## 1. 本机环境核对

| 组件 | 项目要求 | 本机实际 | 状态 | 需要的动作 |
|---|---|---|---|---|
| Git | ≥ 2.40 | 2.50.1.windows.1 | ✅ | — |
| JDK | 17–26（Boot 4.1 支持区间） | **21.0.8 LTS**（`C:\Program Files\Java\jdk-21`） | ✅ | 补设 `JAVA_HOME` |
| `JAVA_HOME` | 必须设置 | **已设为 User 级**：`C:\Program Files\Java\jdk-21`（Machine 级需管理员，未提权） | ✅ | 已解决，见 §6.1 |
| Maven | ≥ 3.6.3 | 3.9.4（`D:\idea\apache-maven-3.9.4`） | ✅ | — |
| Gradle | 不需要（本项目用 Maven） | 未安装 | ✅ 无需 | — |
| Node.js | ≥ 22.12（Vite 8 要求） | **22.20.0** | ✅ | — |
| npm | 随 Node | 10.9.3 | ✅ | — |
| pnpm | ≥ 9 | 12.3.4 | ✅ | 用 pnpm，不用 npm |
| Python | 3.12 / 3.13 | 系统 PATH 上是 **Windows Store 占位程序**；真实解释器在 `D:\anaconda\python.exe`（3.13.9） | ⚠️ | **不要用系统 Python**，改用 uv 建项目级 venv（见 §6.2） |
| uv | 必需 | 0.12.13 | ✅ | 用它管理 Python 与依赖 |
| Poetry | 不需要（uv 替代） | 未安装 | ✅ 无需 | — |
| Docker Engine | ≥ 24 | 29.4.1 | ✅ | — |
| Docker Compose | v2+ | v5.1.3 | ✅ | — |
| Docker 守护进程 | 运行中 | **已启动**，`docker info` 返回 29.4.1 | ✅ | 已解决 |
| 磁盘可用 | ≥ 30 GB | D 盘 117 GB | ✅ | — |

**结论：环境已可用。** 唯一遗留的是 Python 侧——它绕过系统解释器，用 uv 在 `backend-python/.venv` 内隔离（§6.2）。

---

## 2. 版本锁定

### 2.1 Java 生态

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21.0.8 LTS** | Boot 4.1 最低要求 17，支持到 26。本机已有 21，**不升级**，减少环境摩擦 |
| Spring Boot | **4.1.2** | 唯一有支持周期的选择，见 §3.1 |
| Spring Security | **7.1.2**（由 Boot 托管） | lambda DSL，和网上 6.x 教程写法不同 |
| Flyway | **12.4.0**（由 Boot 托管） | 需额外引入 `flyway-mysql` 模块，见 §4.1 |
| MySQL Connector/J | 9.7.0（由 Boot 托管） | 不手动指定版本 |
| Maven | 3.9.4 | 不换 Gradle |

### 2.2 Python 生态

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| Python | **3.12.9**（uv 管理，`.venv` 内） | PSF | 与系统 Anaconda 3.13 **隔离**，已就绪 |
| FastAPI | 0.162.0 | MIT | 已安装 |
| Pydantic | **2.14.1** | MIT | v1 已 EOL，禁止出现 v1 写法 |
| uvicorn | 0.54.0（`[standard]`） | BSD | 已安装 |
| httpx | 0.28.1 | BSD-3 | 健康检查、内部调用 |
| openai | **3.41.0** | Apache-2.0 | 走 `base_url` 兼容任意 OpenAI 协议服务；注意是 3.x，与网上 1.x 示例的调用签名可能有差异 |
| qdrant-client | **1.20.0** | Apache-2.0 | 必须与 server 同 minor |
| pdfplumber | 0.11.10 | **MIT** | PDF 主解析器 |
| pypdf | 6.21.4 | BSD-3 | PDF 兜底（扫描件判定、坏文件） |
| python-docx | 1.2.0 | MIT | DOCX |
| ~~PyMuPDF~~ | — | **AGPL-3.0** | **禁用**，见 §3.3 |

> 依赖已装好并在 `backend-python/uv.lock` 中锁定（51 个包）。`uv.lock` 必须提交到版本库。`openai` 3.x 同时引入了 `httpx2` 作为传输层，属正常现象，不要手动卸载。

### 2.3 前端

| 组件 | 版本 | 说明 |
|---|---|---|
| Vue | **3.5.44** | 3.6 仍是 RC，不用 |
| Vite | **8.3.5** | 需 Node ≥ 22.12（本机 22.20 ✅） |
| Pinia | 4.1.0 | — |
| Vue Router | 5.4.0 | — |
| Element Plus | **2.14.7** | 选型理由见 §3.2 |
| pnpm | 12.3.4 | 包管理器 |

### 2.4 基础设施（Docker 镜像，**必须钉死 tag**）

| 组件 | 镜像 | 版本 | 说明 |
|---|---|---|---|
| MySQL | `mysql:8.4` | 8.4.13 LTS | **禁止 `mysql:latest`**（当前指向 26.10 innovation 版） |
| Redis | `redis:8` | 8.10.3 | 许可证见 §3.4 |
| Qdrant | `qdrant/qdrant:v1.20.0` | 1.20.0 | 必须带 `v` 前缀和精确版本 |
| Nginx | `nginx:1.28-alpine` | 1.28 | 仅 demo/prod 编排使用 |

---

## 3. 选型理由（为什么是这个，不是那个）

### 3.1 Spring Boot 4.1.x，不是 3.5，也不是 4.0

- 3.x 全线的 OSS 支持已在 **2026-06-30 结束**。
- 4.0.x 的 OSS 支持 **2026-12-31 到期**——项目还没做完它就 EOL 了。
- 4.1.x 的支持周期到 **2027-07-31**，是唯一有跑道的版本。

**代价（必须提前知道）**：Boot 4 做了模块化拆分，Flyway 自动配置不再内置；Spring Security 是 7.x，配置写法与 6.x 不兼容。凡是网上搜到的 Spring Boot 3 + Security 6 教程，**代码能看懂但不能照抄**。

> Spring 官方不发布 OSS "LTS"。所谓"选 LTS 版本"对 Boot 不适用，只能看每个 minor 的 12 个月支持窗口。

### 3.2 Element Plus，不是 Ant Design Vue

| 库 | 态势 | 结论 |
|---|---|---|
| Element Plus | 2.14.7，2026-09 仍在发版 | ✅ 选它 |
| Ant Design Vue | 4.2.6，**最后一次发版 2024-11-11** | ❌ 已停止维护 |
| Naive UI | 2.45.3，活跃但生态小 | 备选 |

### 3.3 pdfplumber + pypdf，不用 PyMuPDF

PyMuPDF 的文本抽取速度和版式还原确实更好，但它是 **AGPL-3.0 / 商业双许可**。AGPL 的传染性对"以后可能闭源"的项目是实质风险。pdfplumber（MIT）在文本与表格还原上足够用，代价是速度慢一些——对一个知识库的离线处理链路，这个代价可以接受。

### 3.4 Redis 8 的许可证提示

Redis 从 7.4 起改用 RSALv2/SSPL，**不再是 OSI 认可的开源许可证**。对毕业设计/个人项目无实质影响，但如果这个项目将来要写进公司产品，应换成 `valkey/valkey:8-alpine`（FOSS 分支，协议兼容）。**本项目 V1 用 Redis，不做替换。**

### 3.5 MySQL 8.4，不是 9.7

9.7 是更新的 LTS，但 8.4 的生态、文档、踩坑记录压倒性多。这个项目的数据库设计没有任何需要 9.x 的特性。**稳定性优先于新版本。**

### 3.6 Qdrant Java 官方客户端，不手写 REST

`io.qdrant:client:1.20.0` 是官方维护的，与 server 同日发版。社区很小（约 100 star），所以保留一条后路：**如果客户端出问题，Java 侧直接打 Qdrant REST，Python 侧用 `qdrant-client`**——两边协议一致，不会形成锁定。

---

## 4. Spring Boot 4 的两个具体坑

### 4.1 Flyway 需要两个依赖

Boot 4 把 Flyway 自动配置拆到了独立模块。只加 starter 会在启动时报找不到 `Flyway` bean：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
</dependency>
```

MySQL 在 Flyway Community 版中**完全免费可用**（不是付费墙功能），这点已核实，不构成风险。

### 4.2 Spring Security 7 的 JWT 写法

本项目用**自签 JWT**（D9 的 `token_version` 方案），不走 OAuth2 资源服务器。因此：

- 需要自己实现 `OncePerRequestFilter` 解析 Bearer token。
- 不使用 `oauth2ResourceServer().jwt()`（那是给外部 IdP 用的）。
- 会话策略必须是 `SessionCreationPolicy.STATELESS`。
- **网上 Security 6 的 `and()` 链式写法在 7.x 已移除**，全部改用 lambda DSL。

---

## 5. 版本变更规则

1. **不在开发中途升级主版本。** 遇到 bug 先找 workaround，记录进 `KNOWN_ISSUES`。
2. 升级只允许发生在 **Stage 0** 或 **Stage 5 之后**。
3. 任何版本变更必须同时更新：本文件、`pom.xml` / `package.json` / `pyproject.toml`、以及 `docs/RUNBOOK.md` 的验证步骤。
4. Docker 镜像 tag **永不用 `latest`**，也不要用 `8` 这种浮动大版本（除 Redis，因其 patch 级更新风险极低且镜像 tag 习惯如此）。

---

## 6. 本机待办（三条命令）

### 6.1 设置 JAVA_HOME（已执行 ✅）

```powershell
# User 级（无需管理员）；如需全局，用管理员 PowerShell 把 'User' 换成 'Machine'
[Environment]::SetEnvironmentVariable('JAVA_HOME','C:\Program Files\Java\jdk-21','User')
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

**已验证**：`mvn -v` 显示 `Java version: 21.0.8, runtime: C:\Program Files\Java\jdk-21`。
**注意**：已打开的终端不会自动继承，新开一个窗口才生效；当前会话需手动 `$env:JAVA_HOME=...`。

### 6.2 用 uv 建项目级 Python 环境（已执行 ✅）

```powershell
cd D:\thesis\KnowBase-AI\backend-python
uv init --bare --python 3.12
uv add fastapi "pydantic>=2.14,<3" "uvicorn[standard]" httpx openai "qdrant-client==1.20.0" pdfplumber pypdf python-docx
```

**已验证**：`.venv` 内解释器为 **CPython 3.12.9**，51 个包锁定在 `uv.lock`。

**为什么不用系统 Python**：`python.exe` 在 PATH 上实际是 Windows Store 的占位程序（`...\WindowsApps\python.exe`），调用它会静默失败或弹商店。真解释器是 Anaconda 的 3.13.9，但用系统级 conda 环境跑 Web 服务容易和别的项目串味。**uv + 项目内 `.venv` 是唯一可靠路径。**

**日常用法**：
```powershell
uv run python main.py        # 不需要手动激活 venv
uv add <pkg>                 # 加依赖并自动更新 lock
uv sync                      # 按 lock 复现环境（换机器时用）
```

### 6.3 启动 Docker 守护进程（已执行 ✅）

```powershell
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
docker info --format '{{.ServerVersion}}'   # 已验证返回 29.4.1
```

**已验证**：`docker info` 返回 `29.4.1`，`docker compose version` 返回 `v5.1.3`。

> 注意：Docker Desktop 必须开机启动，否则每次开发前都要手动拉起来。建议在设置里勾选 "Start Docker Desktop when you sign in"。

---

## 7. 一键自检脚本

Stage 1 的 T1.7 会把它固化成 `deploy/scripts/check-env.ps1`。当前先手动跑：

```powershell
@(
  @{n='git';    ok={ git --version }},
  @{n='java';   ok={ java -version 2>&1 }},
  @{n='maven';  ok={ mvn -v }},
  @{n='node';   ok={ node -v }},
  @{n='pnpm';   ok={ pnpm -v }},
  @{n='uv';     ok={ uv --version }},
  @{n='docker'; ok={ docker info --format '{{.ServerVersion}}' }}
) | ForEach-Object { try { & $_.ok | Out-Null; "$($_.n): OK" } catch { "$($_.n): FAIL" } }
```

全部 7 项 OK 之后，Stage 1 才能开始。

---

## 8. 未在本机安装、且本项目不需要的东西

| 工具 | 为什么不需要 |
|---|---|
| Gradle | 用 Maven |
| Poetry / Pipenv / Conda env | 用 uv |
| MinIO | 文件存宿主目录（D3） |
| RabbitMQ / Kafka | 用 `@Scheduled` 轮询（D4） |
| Kubernetes | Docker Compose 单机（D1） |
| pgAdmin / DBeaver | 可选，非必需 |
