# API 规格（Stage 5）

> 覆盖 Stage 1 闭环、Stage 2 文档工程、Stage 3 RAG 体验与 Stage 4（T4.1–T4.7）
> 模型配置、系统质量和工作台契约；Stage 5 不新增对外业务接口，补充综合验收、权限矩阵和
> 生产代理边界。
> 来源：`docs/DEVELOPMENT_PLAN.md`、`database/migrations/V1__init.sql` 及当前 Java 实现。
> 已发布字段只做兼容性追加，不修改既有语义。

## 0. 通用约定

- Base URL：`http://localhost:8080`
- 编码：UTF-8；除上传和下载外，请求/响应均为 `application/json`
- 认证：`Authorization: Bearer <jwt>`
- 公开路径：`POST /auth/login`、`GET /health`
- 链路：每个请求可带 `X-Trace-Id`（`[A-Za-z0-9_-]{8,64}`）；服务端在成功和失败响应中回写同名响应头
- 命名：对外 JSON 一律 camelCase；时间一律 ISO-8601；分页 `page` 从 1 开始，`size` 默认 20、上限 100

### 0.1 统一响应体

除文件下载外，接口统一返回：

```json
{
  "code": 0,
  "message": "OK",
  "data": {},
  "traceId": "…",
  "timestamp": "2026-10-31T07:00:00Z"
}
```

| code | HTTP | 含义 |
|---|---:|---|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40101 | 401 | 未认证、token 无效或密码错误 |
| 40301 | 403 | 系统角色或知识库资源权限不足 |
| 40401 | 404 | 资源不存在，或资源不属于请求路径 |
| 40501 | 405 | 请求方法不允许 |
| 40901 | 409 | 唯一约束或资源状态冲突 |
| 42901 | 429 | 登录失败次数过多，账号临时锁定 |
| 42902 | 429 | 问答频率超过每分钟限制 |
| 50000 | 500 | 服务内部错误 |
| 50202 | 502 | 模型返回空内容 |
| 50301 | 503 | 依赖服务不可用 |

失败时 `data=null`。客户端只按 `code` 分支，不解析 `message`。

### 0.2 认证与失效规则

- JWT 为 HS256，载荷包含 `sub`、`uid`、`ver`、`iat`、`exp`，默认有效期 7200 秒。
- 每个受保护请求都回查用户 `status` 和 `token_version`；用户被禁用、登出、改密或管理员重置密码后，旧 token 立即失效。
- 登录成功会轮换 `token_version`；同一账号只保留最新登录设备，旧设备下一次请求返回 `401 / 40101`。
- 未登录或 token 失效：`401 / 40101`。
- 已登录但权限不足：`403 / 40301`。
- 缺失、伪造或不存在的知识库同样按资源权限拒绝，不向无权用户返回资源内容。

---

## 1. 认证

### 1.1 POST /auth/login

公开接口。

```json
{ "username": "admin", "password": "Admin@123456" }
```

成功 `data`：

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9…",
  "tokenType": "Bearer",
  "expiresInSeconds": 7200,
  "user": {
    "id": 1,
    "username": "admin",
    "nickname": "系统管理员",
    "roles": ["SYS_ADMIN"]
  }
}
```

- 用户名不存在或密码错误统一返回 `401 / 40101`，文案为「用户名或密码错误」。
- 账号 `DISABLED` 返回 `401 / 40101`，文案为「账号已禁用」。
- 同一规范化用户名连续失败 5 次后锁定 15 分钟；锁定期返回 `429 / 42901`。
- 登录成功轮换 `token_version`，只保留本次登录设备；其他设备已签发 token 立即失效。
- 登录成功清零失败计数，并写入 `sys_user_login_log`、更新 `last_login_at`。
- 失败尝试同样写登录日志，但日志失败不会改变认证错误码。

### 1.2 GET /auth/me

需认证。`data` 为当前用户对象：

```json
{ "id": 1, "username": "admin", "nickname": "系统管理员", "roles": ["SYS_ADMIN"] }
```

### 1.3 POST /auth/logout

需认证。成功 `data=null`。服务端自增当前用户 `token_version`，当前 token 与其它已签发 token 全部立即失效。

### 1.4 PUT /auth/password

需认证。

```json
{ "currentPassword": "Old@123456", "newPassword": "New@123456" }
```

- 当前密码错误或新旧密码相同：`400 / 40001`。
- 新密码长度 8–128 个字符。
- 成功后自增 `token_version`，所有旧会话立即失效。

---

## 2. 系统用户管理

所有 `/admin/users/**` 接口均要求系统角色 `SYS_ADMIN`。普通用户返回 `403 / 40301`。

### 2.1 GET /admin/users

`data`：

```json
{
  "items": [
    {
      "id": 2,
      "username": "alice",
      "nickname": "Alice",
      "email": "alice@example.com",
      "status": "ACTIVE",
      "roles": ["USER"],
      "lastLoginAt": "2026-10-31T06:00:00Z",
      "createdAt": "2026-10-30T08:00:00Z"
    }
  ],
  "total": 1
}
```

### 2.2 GET /admin/users/{userId}

返回单个用户对象，字段同列表项。不存在返回 `404 / 40401`。

### 2.3 POST /admin/users

```json
{
  "username": "alice",
  "password": "Alice@123456",
  "nickname": "Alice",
  "email": "alice@example.com",
  "roles": ["USER"]
}
```

- 用户名 3–64 位，只允许字母、数字、点、下划线和短横线。
- 密码 8–128 位；昵称最多 64 字；邮箱最多 128 字。
- `roles` 至少一个，可用值：`SYS_ADMIN`、`KB_ADMIN`、`USER`。
- 用户名重复返回 `409 / 40901`。

### 2.4 PUT /admin/users/{userId}

```json
{ "nickname": "Alice Zhang", "email": "alice@example.com" }
```

只更新昵称和邮箱，不修改用户名、密码或角色。

### 2.5 PATCH /admin/users/{userId}/status

```json
{ "status": "DISABLED" }
```

- 仅允许 `ACTIVE`、`DISABLED`。
- 禁用会自增 `token_version`，已签发 token 立即失效。
- 不能禁用自己，也不能禁用最后一个启用的 `SYS_ADMIN`；冲突返回 `409 / 40901`。

### 2.6 PUT /admin/users/{userId}/roles

```json
{ "roles": ["USER", "KB_ADMIN"] }
```

替换用户角色并自增 `token_version`。不能移除自己或最后一个启用管理员的 `SYS_ADMIN`。

### 2.7 PUT /admin/users/{userId}/password

```json
{ "newPassword": "Reset@123456" }
```

管理员重置密码；成功后该用户所有旧 token 立即失效。

### 2.8 DELETE /admin/users/{userId}

语义是禁用，不物理删除，保留审计主体。成功 `data=null`。

---

## 3. 知识库与成员

### 3.1 权限模型

知识库所有者自动拥有管理者能力；普通成员角色如下：

| 角色 | 读取库/文档/分片 | 上传、删除、重试 | 管理成员 |
|---|---:|---:|---:|
| 所有者 | 是 | 是 | 是 |
| `MANAGER` | 是 | 是 | 是 |
| `EDITOR` | 是 | 是 | 否 |
| `VIEWER` | 是 | 否 | 否 |

每次请求都读取当前成员关系。成员被移除或知识库被归档后，下一个请求立即失去访问权。资源级权限不通过 JWT 角色缓存。

文档管理权限按知识库角色判断，不按上传者判断。所有者、`MANAGER` 和 `EDITOR`
可管理该库内由任意成员上传的文档；`VIEWER` 只能读取。

### 3.2 GET /knowledge-bases

返回当前用户自有或作为成员被授权的 `ACTIVE` 知识库：

```json
{
  "items": [
    {
      "id": 1,
      "ownerId": 1,
      "name": "产品文档",
      "description": "…",
      "docCount": 3,
      "canWrite": true,
      "canManage": false,
      "createdAt": "2026-10-29T08:00:00Z"
    }
  ],
  "total": 1
}
```

- `docCount` 只统计 `status='ACTIVE'` 的文档。
- 归档库不出现在列表中，也不能通过直接 URL 访问。

### 3.3 POST /knowledge-bases

```json
{ "name": "产品文档", "description": "可选，最多 1024 字" }
```

- 名称必填，1–128 字，去首尾空格。
- 同一所有者下重名返回 `409 / 40901`。
- 创建者自动成为所有者和 `MANAGER` 成员。

### 3.4 GET /knowledge-bases/{kbId}

要求至少 `VIEWER` 权限。返回单个知识库，字段同 3.2 列表项。无权访问返回 `403 / 40301`。

### 3.5 GET /knowledge-bases/{kbId}/members

要求至少 `VIEWER` 权限。

```json
{
  "items": [
    {
      "userId": 2,
      "username": "alice",
      "nickname": "Alice",
      "role": "EDITOR",
      "createdAt": "2026-10-30T08:00:00Z"
    }
  ],
  "total": 1
}
```

### 3.6 GET /knowledge-bases/{kbId}/members/candidates?keyword=ali

要求 `MANAGER` 或所有者权限。按用户名/昵称模糊搜索启用的用户，最多返回 10 条：

```json
[
  { "userId": 2, "username": "alice", "nickname": "Alice" }
]
```

### 3.7 POST /knowledge-bases/{kbId}/members

要求 `MANAGER` 或所有者权限。

```json
{ "userId": 2, "role": "EDITOR" }
```

- `role` 只能是 `VIEWER`、`EDITOR`、`MANAGER`。
- 目标用户必须存在且为 `ACTIVE`。
- 重复授权执行 upsert，并按 `GRANT` 或 `ROLE_CHANGE` 写入 `kb_audit_grant`。

### 3.8 PUT /knowledge-bases/{kbId}/members/{userId}

```json
{ "role": "VIEWER" }
```

要求管理权限。不能修改知识库所有者的角色；不能降级所有者的成员记录。

### 3.9 DELETE /knowledge-bases/{kbId}/members/{userId}

要求管理权限。不能移除知识库所有者。撤权写入 `REVOKE` 审计记录，成功 `data=null`。

---

## 4. 文档

所有文档接口都先校验路径中的 `kbId`；文档 ID 还必须属于该知识库，否则返回 `404 / 40401`。

### 4.1 POST /knowledge-bases/{kbId}/documents

要求写权限（所有者、`MANAGER`、`EDITOR`）。`multipart/form-data` 字段名为 `file`。

- 支持扩展名：`txt`、`md`、`pdf`、`docx`。
- 单文件上限 20 MB；空文件拒绝。
- PDF 在上传阶段校验页数，最多 300 页。
- 原文件名只作为展示名；磁盘路径使用 `${UPLOAD_DIR}/yyyy/MM/<uuid>.<ext>`。
- 计算 SHA-256；同一知识库中相同内容返回 `409 / 40901`。
- 上传成功只代表文档已落盘并进入队列，不代表解析完成。

成功 `data`：

```json
{
  "id": 7,
  "kbId": 1,
  "name": "报销制度.pdf",
  "ext": "pdf",
  "sizeBytes": 123456,
  "sha256": "…",
  "storageKey": "2026/10/6f1c….pdf",
  "status": "PENDING",
  "chunkCount": 0,
  "indexedAt": null,
  "errorCode": null,
  "errorMsg": null,
  "taskStatus": "QUEUED",
  "attempt": 0,
  "maxAttempt": 3,
  "canRetry": false,
  "createdAt": "2026-10-31T07:00:00Z"
}
```

### 4.2 文档状态

`status` 为文档状态：

| status | 含义 |
|---|---|
| `PENDING` | 已上传，等待处理 |
| `PROCESSING` | 正在解析、切分与写入索引 |
| `ACTIVE` | 已完成，可检索、预览和下载 |
| `FAILED` | 处理失败，可按条件重试 |
| `DELETING` | 正在异步清理 |
| `DELETED` | 软删除完成，不再出现在列表 |
| `DELETE_FAILED` | 清理失败，等待补偿或人工重试 |

`taskStatus` 为最新任务状态：

| taskStatus | 含义 |
|---|---|
| `QUEUED` | 等待 worker 认领 |
| `RUNNING` | worker 正在执行 |
| `SUCCESS` | 最近任务成功 |
| `FAILED` | 本次失败，但仍有重试机会 |
| `DEAD` | 尝试次数耗尽，需要人工介入 |

`attempt` 是当前任务已开始的次数，`maxAttempt` 是上限（解析默认 3，删除默认 5）。`canRetry=true` 仅在最新任务为 `FAILED` 且 `attempt < maxAttempt` 时出现；达到上限后前端隐藏或禁用重试入口。

常见 `errorCode`：

| errorCode | 含义 |
|---|---|
| `NO_TEXT_LAYER` | 扫描版 PDF 无文字层，暂不支持 OCR |
| `EMPTY_CONTENT` | 文件为空或没有有效文本 |
| `PAGE_LIMIT_EXCEEDED` | 超过页数限制 |
| `PARSE_TIMEOUT` | Python 解析超时 |
| `UNSUPPORTED_FORMAT` | 文件损坏、加密或格式不支持 |
| `DEPENDENCY_UNAVAILABLE` | Qdrant、Embedding 或 AI 服务不可用 |
| `STORAGE_ERROR` | Java 侧文件访问失败 |
| `TASK_TIMEOUT` | RUNNING 超时，已被调度器回收 |
| `DOCUMENT_STATE_CHANGED` | 文档状态在任务执行期间变化，结果被丢弃 |
| `TASK_FAILED` | 未分类处理失败 |

`errorMsg` 面向用户且最多 2000 字符。

### 4.3 GET /knowledge-bases/{kbId}/documents

要求读权限。返回该库未删除文档，按创建时间倒序：

```json
{
  "items": [
    {
      "id": 7,
      "name": "报销制度.pdf",
      "ext": "pdf",
      "sizeBytes": 123456,
      "status": "ACTIVE",
      "chunkCount": 12,
      "taskStatus": "SUCCESS",
      "errorCode": null,
      "errorMsg": null,
      "attempt": 1,
      "maxAttempt": 3,
      "canRetry": false,
      "createdAt": "2026-10-31T07:00:00Z"
    }
  ],
  "total": 1
}
```

### 4.4 GET /knowledge-bases/{kbId}/documents/{docId}

要求读权限。返回完整文档对象，字段同上传成功响应。

### 4.5 GET /knowledge-bases/{kbId}/documents/{docId}/chunks?page=1&size=20

要求读权限。分片按 `seq` 升序：

```json
{
  "docId": 7,
  "items": [
    {
      "id": 501,
      "seq": 0,
      "content": "……",
      "pageNo": null,
      "heading": null,
      "tokenCount": 600,
      "createdAt": "2026-10-31T07:01:00Z"
    }
  ],
  "total": 12,
  "page": 1,
  "size": 20
}
```

### 4.6 GET /knowledge-bases/{kbId}/documents/{docId}/chunks/{seq}

要求读权限。返回单个分片。`seq` 从 0 开始；不存在返回 `404 / 40401`。

### 4.7 GET /knowledge-bases/{kbId}/documents/{docId}/download

要求读权限。成功返回原始文件二进制流，并带 UTF-8 `Content-Disposition: attachment`；不使用统一 JSON 响应体。

### 4.8 DELETE /knowledge-bases/{kbId}/documents/{docId}

要求写权限。首次删除将文档从当前状态原子改为 `DELETING`，取消未完成任务并创建删除任务，立即返回当前文档对象。

删除 worker 依次执行：

1. 调用 Python `POST /internal/delete` 删除该文档全部 Qdrant points。
2. 删除原始文件。
3. 删除引用与数据库分片。
4. 文档置为 `DELETED`，清空去重键。

任一步失败进入 `DELETE_FAILED`；补偿任务每 5 分钟重排，最多 5 次。重复删除 `DELETING` 文档是幂等读取；已删除文档返回 `404`。

### 4.9 POST /knowledge-bases/{kbId}/documents/{docId}/retry

要求写权限。

- `FAILED` 文档会重排最近失败任务，文档回到 `PENDING`。
- `DELETE_FAILED` 文档会重排删除任务，文档回到 `DELETING`。
- 任务达到 `maxAttempt` 或当前状态不允许重试：`409 / 40901`。

成功立即返回更新后的文档对象；实际处理仍异步进行。

---

## 5. 问答

### 5.1 POST /qa/ask

```json
{ "question": "报销流程是什么？" }
```

- `kbId` 可选。省略时，新会话自动绑定当前用户全部可读知识库；传入时只绑定该知识库，
  并要求至少有 `VIEWER` 读权限，无权返回 `403 / 40301`。
- `question` 1–2000 字。
- 处理链：问题向量化 → Qdrant 按全部已授权 `kbIds` 检索 → 阈值过滤 → 拼上下文 →
  模型阅读并综合分析检索资料 → 生成带引用回答。
- 没有可读知识库、命中为空或全部低于阈值时，先明确提示“知识库中未找到相关依据”，再由通用模型回答；
  回答必须声明这是通用知识、不代表企业内部制度，且 `citations` 为空。
- 落库会话、消息与引用；引用只能来自本次检索结果。

成功 `data`：

```json
{
  "conversationId": 12,
  "messageId": 34,
  "answer": "根据文档，报销需……[1]",
  "noAnswer": false,
  "citations": [
    {
      "rank": 1,
      "chunkId": 501,
      "docId": 7,
      "kbId": 1,
      "docName": "报销制度.pdf",
      "seq": 0,
      "score": 0.83,
      "quote": "……原文片段（最多 1000 字）"
    }
  ],
  "chatProvider": "local-extractive",
  "embeddingProvider": "local-hash-v1",
  "latencyMs": 321
}
```

Python 或模型依赖不可达返回 `503 / 50301`。

`kbId` 只用于新建会话并限制检索范围；省略时使用全部可读知识库。继续会话时传
`conversationId`，Java 会重新计算该会话关联知识库的当前可读集合。请求体兼容字段：

```json
{ "conversationId": null, "question": "报销流程是什么？" }
```

### 5.2 POST /qa/stream

使用与 `/qa/ask` 相同的请求体，返回 `text/event-stream`。

事件：

- `meta`：`conversationId/userMessageId/messageId/title`
- `retrieval`：本次授权检索命中数；无检索范围或命中为空转通用回答时 `mode=general_fallback`
- `delta`：`{"text":"增量"}`
- `no_answer`：兼容旧版 Python 事件的保留事件；当前空范围与空命中均返回通用回答
- `done`：完整答案、引用、模型名称、耗时和消息状态
- `cancelled`：用户停止后保留的部分回答
- `error`：统一 `code/message/traceId`

客户端断开或调用 `AbortController.abort()` 后，Java 关闭到 Python 的上游流，Python
关闭模型连接；消息状态落为 `CANCELLED`。空模型输出落为 `EMPTY_COMPLETION`。

### 5.3 GET /qa/conversations

查询当前用户会话，可用可选参数 `kbId` 过滤：

```text
GET /qa/conversations?kbId=1&page=1&size=20
```

只返回当前用户自己的 `ACTIVE` 会话；`kbId` 会再次做资源级鉴权。

### 5.4 POST /qa/conversations

```json
{ "kbId": 1, "title": "报销制度讨论" }
```

要求该知识库读权限。省略标题时使用「新对话」。

### 5.5 GET /qa/conversations/{conversationId}

```text
GET /qa/conversations/12?page=1&size=100
```

返回会话、关联知识库和消息分页。他人会话统一返回 `404 / 40401`。
历史引用若当前已失权或文档不再 `ACTIVE`，响应中的 `accessible=false` 且不返回缓存
`quote`。

### 5.6 PATCH /qa/conversations/{conversationId}

```json
{ "title": "新的会话标题" }
```

标题 1–255 字符；仅会话所有者可修改。

### 5.7 DELETE /qa/conversations/{conversationId}

物理删除会话、消息和引用；仅会话所有者可删除，他人会话返回 `404`。

### 5.8 GET /qa/citations/{citationId}

历史引用二次鉴权接口。服务端重新检查：

1. 引用消息所属会话属于当前用户；
2. 当前用户仍可读取引用文档所属知识库；
3. 文档仍为 `ACTIVE`；
4. 分片仍存在。

失权返回 `403 / 40301`，不存在或已失效返回 `404 / 40401`。前端必须通过此接口或
文档分片接口读取原文，不能直接信任历史消息中的引用快照。

### 5.9 问答限流

每个用户每自然分钟默认最多 20 次提问（`QA_RATE_LIMIT_PER_MINUTE`）。
超过限制返回 `429 / 42902`，问题超过 2000 字返回 `400 / 40001`。

---

## 6. 其他接口

- `GET /health`：公开，聚合检查 MySQL、Redis、Qdrant、Python 四个依赖；任一不可用返回
  `503`，`components` 按 `mysql / redis / qdrant / python` 顺序返回稳定错误码。
- `GET /auth/me`：会话恢复。
- 所有未知路由返回统一 `404 / 40401`，方法不匹配返回 `405 / 40501`。

---

## 7. 模型配置（仅 SYS_ADMIN）

所有 `/admin/models/**` 接口要求系统角色 `SYS_ADMIN`。API Key 在数据库中使用
`APP_MASTER_KEY` 做 AES-256-GCM 加密，每个值使用随机 IV；接口只返回掩码，不返回密文或明文。
主密钥丢失后无法恢复已保存的模型密钥，必须重新录入。

### 7.1 GET /admin/models

```json
{
  "items": [
    {
      "id": 3,
      "provider": "deepseek",
      "modelType": "CHAT",
      "modelName": "deepseek-chat",
      "baseUrl": "https://api.deepseek.com",
      "maskedApiKey": "sk-****abcd",
      "hasApiKey": true,
      "keyStatus": "OK",
      "params": { "temperature": 0.2 },
      "enabled": true,
      "lastTestAt": "2026-10-31T08:00:00Z",
      "lastTestOk": true,
      "lastTestError": null,
      "createdAt": "2026-10-31T07:00:00Z",
      "updatedAt": "2026-10-31T08:00:00Z"
    }
  ],
  "total": 1
}
```

`modelType` 为 `CHAT`、`EMBEDDING` 或 `RERANK`。`hasApiKey=false` 表示未保存 Key；
`keyStatus=UNREADABLE` 表示当前 `APP_MASTER_KEY` 无法解密该条记录。

### 7.2 POST /admin/models

```json
{
  "provider": "deepseek",
  "modelType": "CHAT",
  "modelName": "deepseek-chat",
  "baseUrl": "https://api.deepseek.com",
  "apiKey": "sk-...",
  "params": { "temperature": 0.2 },
  "enabled": true
}
```

- `baseUrl` 必须为 HTTPS；只有显式设置 `ALLOW_PRIVATE_MODEL_URLS=true` 才允许 HTTP 和私网地址。
- 域名解析后的任一地址落在环回、私网、链路本地、云元数据或 IPv6 唯一本地范围时拒绝。
- `params` 是 JSON 对象，不允许在其中放 API Key、token、secret、password 等敏感字段。
- 同一 `provider + modelName + modelType` 重复返回 `409 / 40901`。

### 7.3 PUT /admin/models/{modelId}

请求字段同创建接口，并增加：

```json
{ "apiKey": null, "clearApiKey": false }
```

- `apiKey=null` 且 `clearApiKey=false`：保留原 API Key。
- `clearApiKey=true`：清除已保存的 API Key。
- 接口永不回显原 API Key。

### 7.4 DELETE /admin/models/{modelId}

删除模型配置。历史 `ai_call_log` 保留，其 `model_config_id` 由数据库外键置空。

### 7.5 POST /admin/models/{modelId}/test

对模型服务 `GET {baseUrl}/models` 做连接测试。Java 与 Python 均执行 SSRF 校验，真实
Chat/Embedding 调用也在发请求前重新执行同一校验函数。

```json
{
  "success": false,
  "latencyMs": 1024,
  "errorType": "AUTH_ERROR",
  "message": "模型服务认证失败，请检查 API Key",
  "testedAt": "2026-10-31T08:00:00Z"
}
```

稳定 `errorType`：

| errorType | 含义 |
|---|---|
| `INVALID_SCHEME` / `INVALID_URL` | 协议或地址格式不合法 |
| `PRIVATE_ADDRESS` | 环回、私网或云元数据地址，已拒绝 |
| `DNS_ERROR` | 域名解析失败 |
| `CONNECTION_ERROR` | TCP 连接失败 |
| `TIMEOUT` | 连接或读取超时 |
| `AUTH_ERROR` | 上游返回 401/403 |
| `RATE_LIMITED` | 上游返回 429 |
| `ENDPOINT_NOT_FOUND` | 上游没有 `/models` |
| `HTTP_xxx` | 其它上游 HTTP 错误 |

每次连接测试都写入 `ai_call_log(scene=TEST)` 并更新该配置的最近测试状态。

### 7.6 GET /admin/models/stats?days=7

`days` 范围 1–90。统计从起始日 00:00 至当前时间，`totalCalls` 与
`ai_call_log` 的 `COUNT(*)` 使用同一时间条件。

```json
{
  "days": 7,
  "totalCalls": 120,
  "successCalls": 116,
  "failedCalls": 4,
  "byModel": [
    {
      "modelConfigId": 3,
      "provider": "deepseek",
      "modelType": "CHAT",
      "modelName": "deepseek-chat",
      "calls": 80,
      "successes": 78,
      "successRate": 0.975,
      "avgLatencyMs": 842.31
    }
  ],
  "daily": [
    { "day": "2026-10-31", "calls": 20, "successes": 19, "avgLatencyMs": 730.2 }
  ]
}
```

---

## 8. 个人工作台

### 8.1 GET /workspace/summary

需登录。所有统计固定为当前账号范围，不返回全站用户、全站文档或全站调用量。

```json
{
  "scope": "PERSONAL",
  "accessibleKbCount": 2,
  "activeDocumentCount": 15,
  "conversationCount": 4,
  "questionCount": 23,
  "processingDocumentCount": 1,
  "dailyQuestions": [
    { "date": "2026-10-25", "count": 0 },
    { "date": "2026-10-31", "count": 6 }
  ],
  "latestDocuments": [
    {
      "id": 12,
      "kbId": 2,
      "kbName": "产品文档",
      "name": "发布说明.md",
      "status": "ACTIVE",
      "createdAt": "2026-10-31T08:00:00Z"
    }
  ]
}
```

可访问库来自 `AuthzService.readableKbIds(userId)`；文档、会话和提问数均带当前
`user_id` 条件，可用相同 SQL 复算。

---

## 9. 审计与系统质量（仅 SYS_ADMIN）

### 9.1 GET /admin/audit-logs

查询参数：

- `page`：默认 1。
- `size`：默认 20，最大 100。
- `action`：可选，例如 `LOGIN / KB_CREATE / DOC_DELETE / MODEL_UPDATE`。
- `result`：可选，`SUCCESS / FAILURE`。

返回审计分页。`detail` 不包含密码、API Key、请求体或文件内容。

### 9.2 GET /admin/system/status

聚合真实依赖健康、任务积压与最近 7 天错误 Top5：

```json
{
  "status": "DEGRADED",
  "checkedAt": "2026-10-31T08:00:00Z",
  "components": [
    { "name": "mysql", "status": "UP", "latencyMs": 2, "detail": null },
    { "name": "python", "status": "DOWN", "latencyMs": 2001, "detail": "TIMEOUT" }
  ],
  "backlog": {
    "queued": 2,
    "running": 1,
    "failed": 5,
    "dead": 0,
    "failedDocuments": 5
  },
  "taskErrorTop5": [
    { "code": "PARSE_TIMEOUT", "count": 5 }
  ],
  "aiErrorTop5": [
    { "code": "HTTP_401", "count": 2 }
  ]
}
```

页面显示的失败任务数直接来自 `kb_ingestion_task.status='FAILED'`；制造 5 个失败任务时
接口和页面均显示 5。
