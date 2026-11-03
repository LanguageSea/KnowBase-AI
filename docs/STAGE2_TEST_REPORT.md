# Stage 2 测试报告

> 日期：2026-10-31
> 范围：T2.1–T2.12 权限、文档任务、解析、重试、删除与前端文档页。
> 环境：Windows、Java 21、Maven、MySQL 5.7.44、Redis、Qdrant 1.x、Python 3.12、Node/Vite。

## 1. 结果摘要

| 验证项 | 结果 | 说明 |
|---|---|---|
| Java 全量单测 | 通过 | 36 个测试，0 失败，2 个 MySQL IT 在无环境变量时按设计跳过 |
| MySQL 并发集成测试 | 通过 | 2 个测试，覆盖并发认领与僵尸任务回收 |
| Python 全量单测 | 上一轮通过 | 58 passed；本轮因沙箱无法读取 uv 托管 Python，未重复执行 |
| 前端生产构建 | 通过 | Vite 构建成功；仅 Element Plus chunk 体积提示 |
| 图形界面验收 | 通过 | 管理员、知识库权限、文档处理、文档详情和问答页面均已实际操作 |
| Qdrant 真实删除过滤 | 通过 | 已入库测试文档删除后，`doc_id=8` 的 point 计数为 0 |
| 服务级 M2 全链路 | 通过 | MD 上传、解析、切分、向量索引、问答引用、权限拦截与异步删除均实际执行 |

图形界面和默认测试账号的测试路径见 `docs/STAGE2_UI_TEST_GUIDE.md`。

## 2. Java

### 2.1 全量单测

命令：

```powershell
cd backend-java
mvn "-Dmaven.repo.local=D:/thesis/KnowBase-AI/tmp/m2repo" test
```

结果：

```text
Tests run: 36, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

主要覆盖：

- `AuthServiceTest`：登出、改密、禁用、`token_version` 校验。
- `LoginAttemptServiceTest`：5 次失败和 15 分钟锁定 TTL。
- `PermissionInterceptorTest`：系统管理权限 403。
- `UserAdminServiceTest`、`AuthzServiceTest`、`AuthzArchitectureTest`：用户角色和知识库资源权限。
- `KbIsolationTest`：6 条越权矩阵。
- `IngestionTaskRepositoryTest`、`DocumentCompletionServiceTest`：任务认领、状态且并发完成动作。
- `PointIdsTest`：Java 与 Python 的 Qdrant point id 一致。

### 2.2 真实 MySQL 并发测试

命令：

```powershell
$env:KNOWBASE_TEST_MYSQL_URL='jdbc:mysql://127.0.0.1:3306/knowbase_stage2_it?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true'
$env:KNOWBASE_TEST_MYSQL_USER='root'
$env:KNOWBASE_TEST_MYSQL_PASSWORD='root'
mvn "-Dmaven.repo.local=D:/thesis/KnowBase-AI/tmp/m2repo" "-Dtest=IngestionTaskMysqlIntegrationTest" test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

断言：

- 两个实例并发认领同一任务时，恰好一个返回成功。
- 成功后任务 `attempt=1`，文档状态变为 `PROCESSING`。
- 超过 10 分钟的 `RUNNING` 任务被回收为 `QUEUED`，文档回到 `PENDING`。
- 第二次回收不重复处理已被回收的任务。

## 3. Python

上一轮完整执行结果：

```text
58 passed
```

覆盖的主要行为：

- 内部 token 认证、traceId 透传、统一错误体。
- TXT、Markdown、PDF、DOCX 解析。
- 扫描版 PDF 返回 `NO_TEXT_LAYER`。
- PDF 页数在文本提取前检查。
- 切分连续性、overlap 和超长段落保留。
- Embedding 维度、确定性 point id、Qdrant upsert。
- 授权 `kb_ids` 过滤和空范围不访问 Qdrant。
- `/internal/delete` 按 `doc_id` 删除且可重复调用。

本轮复跑命令 `uv run pytest -q` 被环境权限阻断：现有 `.venv` 指向的 uv 托管 Python 位于沙箱不可读的用户目录，系统 Python 又没有安装项目依赖。本轮没有修改 Python 源码，保留上一轮 `58 passed` 作为有效结果。

## 4. Qdrant

执行的真实 Qdrant 验证：

1. 写入一个临时 point，payload 含唯一 `doc_id=2147483000`。
2. 使用 exact count 和 `doc_id` filter 查询，结果为 `1`。
3. 使用相同 `doc_id` filter 删除，`wait=true`。
4. 再次 exact count，结果为 `0`。

输出：

```json
{"docId":2147483000,"before":1,"after":0}
```

该结果验证了 T2.10 中 Qdrant 过滤删除与计数归零所依赖的存储行为。

## 5. 前端

命令：

```powershell
cd frontend
npm run build
```

结果：构建成功。

构建产物包含知识库列表、知识库详情、文档详情、智能问答和用户管理页面。唯一警告是 Element Plus 主包压缩前约 940 kB，属于后续性能优化项，不影响构建或功能。

## 6. 服务级与图形界面验收

运行中的 Java 服务健康检查返回 `UP`，MySQL、Redis、Qdrant 三项依赖均正常。

真实接口与浏览器验收结果：

- 四个默认账号均可登录，角色分别为 `SYS_ADMIN`、`KB_ADMIN + USER`、`USER`、`USER`。
- 管理员界面实际完成新建用户、编辑资料、分配角色、重置密码、禁用账号和当前管理员保护验证。
- 普通用户直达 `/system/users` 会被前端路由拦截并返回知识库页，后端管理接口仍执行独立 403 校验。
- 知识库管理员界面显示文档上传、搜索、状态筛选、成员添加、角色修改和移除操作。
- 只读用户看不到上传、删除、重试、成员配置和成员移除入口，直接调用上传或删除接口均返回 403。
- `editor` 上传 `API_SPEC.md` 后任务由 `PENDING` 进入 `ACTIVE`，生成 23 个分片；重复上传返回 409。
- `editor` 删除测试文档后任务进入 `DELETING`，文档从列表消失，Qdrant 中对应 `doc_id=8` 的 point 计数为 0。
- 问答页面选择知识库并询问“如何上传文档？”后返回回答、引用来源和本地回退模型标识。

Python 本轮复跑仍受环境权限限制，未修改 Python 源码，保留上一轮 `58 passed` 结果。
