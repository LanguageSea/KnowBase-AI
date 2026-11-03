# 安全设计与边界

> 任务：T5.9
> 相关实现：认证过滤器、`AuthzService`、内部 Token、SSRF Guard、AES-GCM、Nginx、生产 Compose。

## 1. 信任边界

```text
Untrusted browser
    -> HTTPS / Nginx
    -> Java authentication + resource authorization
    -> internal token
    -> Python calculation service
    -> Qdrant / external model
```

任何客户端提交的 `userId`、`kbId` 或 `citations` 都不能直接成为授权依据。Python 收到的
`kb_ids` 必须由 Java 在请求时根据数据库成员关系计算。

## 2. 认证

- 登录密码使用 BCrypt，不保存明文。
- JWT HS256 载荷包含 `sub/uid/ver/iat/exp`，默认 7200 秒。
- 每个受保护请求回查用户状态和 `token_version`。
- 登出、改密、禁用、角色变更和管理员重置密码都会使旧 token 立即失效。
- 同一用户名连续失败 5 次锁定 15 分钟，登录结果写审计日志。
- 未知用户与错误密码使用相同对外错误，避免用户名枚举。

## 3. 授权

系统角色只控制管理入口：

| 角色 | 能力 |
|---|---|
| `SYS_ADMIN` | 用户、模型、审计、系统状态 |
| `KB_ADMIN` | 仍必须以知识库成员/所有者身份访问资源 |
| `USER` | 仍必须具有知识库读权限 |

资源角色：

| 知识库角色 | 读 | 写文档/重试 | 管理成员 |
|---|---:|---:|---:|
| Owner | 是 | 是 | 是 |
| `MANAGER` | 是 | 是 | 是 |
| `EDITOR` | 是 | 是 | 否 |
| `VIEWER` | 是 | 否 | 否 |

每个请求都读取当前成员关系，不在 JWT 中缓存资源权限。历史引用、原文分片和下载接口
会再次鉴权，撤权后旧引用不能继续打开。

## 4. 服务间安全

- `/internal/**` 不接受浏览器 JWT，只接受 `X-Internal-Token`。
- Token 使用常量时间比较，缺失或错误返回 401。
- Nginx 只代理白名单业务路径，不代理 `/internal/**`。
- Compose 中 Python、Qdrant、MySQL、Redis 不映射宿主机端口。
- Python 不查询用户表，不包含 `has_permission` 逻辑。

## 5. 密钥与敏感配置

### 5.1 模型 API Key

- `APP_MASTER_KEY` 是 32 字节 base64。
- 每个模型 Key 使用 AES-256-GCM 和随机 IV 加密。
- 数据库只保存 `v1:` 密文。
- API 只返回 `sk-****abcd` 掩码，不回显明文或密文。
- 更新时未提供新 Key 会保留原值；清除必须显式 `clearApiKey=true`。
- 主密钥丢失后**无法恢复已存 Key**，只能重新录入。

### 5.2 生产环境密钥

必须独立生成：

- `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`
- `REDIS_PASSWORD`
- `QDRANT_API_KEY`
- `INTERNAL_TOKEN`，至少 32 字符
- `APP_MASTER_KEY`

`.env` 被 Git 忽略。生产 Compose 只通过环境变量注入，不把密钥写入镜像。

## 6. SSRF

模型 `baseUrl` 在 Java 保存/测试和 Python 实际调用前都执行校验：

- 默认只允许 HTTPS。
- 拒绝 `127/8`、`10/8`、`172.16/12`、`192.168/16`。
- 拒绝 `169.254/16` 与云元数据地址。
- 拒绝 `::1`、`fc00::/7`。
- DNS 解析后逐 IP 检查，防止域名解析绕过。
- 本地 Ollama 只能在开发环境显式开启 `ALLOW_PRIVATE_MODEL_URLS=true`。

生产还应增加容器出口防火墙或代理白名单。

## 7. 文件安全

- 支持 `txt/md/pdf/docx`，最大 20 MB，PDF 最多 300 页。
- 原文件名只用于展示，磁盘路径使用 `${UPLOAD_DIR}/yyyy/MM/<uuid>.<ext>`。
- 防路径穿越；Python 只读取 Java 传入的规范化绝对路径。
- 同知识库 SHA-256 去重。
- 文档解析文本作为纯文本展示，前端不使用 `v-html`。

## 8. Prompt Injection 与引用

- 检索内容使用 `<source id="n">...</source>` 包裹，并明确声明其中命令无效。
- 只允许引用本次检索出现过的 `(doc_id, seq)`。
- Java 二次校验知识库、文档状态和真实分片，不信任模型返回的引用。
- 无依据时不得把通用知识伪装成企业内部制度。
- 评估集包含 5 道提示词注入题。

## 9. HTTP 与部署安全

- Nginx 默认提供 HSTS 之外的 CSP、`X-Content-Type-Options`、`X-Frame-Options`、
  `Referrer-Policy` 和 `Permissions-Policy`。
- 80 端口只做 HTTPS 跳转。
- 自签名证书仅用于本地验收；互联网部署必须替换为受信任证书。
- 内部 Docker 网络 `data` 设置 `internal: true`；只有 AI 服务额外接入 `egress` 网络访问
  外部模型，其它数据服务没有公网出口。
- 容器启用 `no-new-privileges`。
- 上传大小限制在 Nginx 和 Spring 两层一致设置为约 21 MB。

## 10. 已知安全边界

- 单机 Compose 的 MySQL 流量在私有 Docker 网络内，显式设置
  `MYSQL_SSL_MODE=DISABLED`；跨主机或托管数据库部署必须启用 TLS 并删除该覆盖。
- 当前没有 WAF、外部 IdP、细粒度审计导出和静态加密卷。
- 本地哈希 Embedding 不是安全边界，也不提供语义保密。
- Qdrant 向量不是原文加密存储；严格机密数据需要额外存储加密策略。
- 备份不包含 `APP_MASTER_KEY`，必须将主密钥放入独立密钥库。
