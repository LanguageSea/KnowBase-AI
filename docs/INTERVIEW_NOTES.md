# 面试讲解与问答笔记

> 任务：T5.11
> 数字口径以 [EVALUATION.md](EVALUATION.md) 和 [STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md)
> 为准；不要把演示级指标说成行业准确率。

## 1. 30 秒介绍

KnowBase AI 是一个面向团队知识库的 RAG 系统。前端用 Vue 3，业务后端用 Spring Boot，
AI 服务用 FastAPI，MySQL 存业务状态，Redis 做登录锁定和问答限流，Qdrant 存分片向量。
我做的最重要的三件事是：服务端知识库权限、异步文档索引状态机、带二次校验的引用问答。
部署使用 Docker Compose 和 Nginx，只暴露 80/443。

## 2. 两分钟架构讲解

1. 浏览器只访问 Nginx，Nginx 终止 TLS 并代理业务接口。
2. Java 是业务权威源，负责 JWT、用户角色、知识库成员、文档、会话、引用和审计。
3. 用户上传文档后，Java 写入文件和任务表，后台 worker 条件认领任务。
4. worker 调用 Python 解析和索引；Python 只接受内部 Token 和 Java 已授权的知识库 ID。
5. Python 把分片写入 Qdrant，Java 回写分片、文档状态和任务结果。
6. 问答时 Java 先计算当前用户可读知识库，再调用 Python 检索和生成。
7. Python 返回引用候选，Java 再校验知识库、文档状态和分片归属，最后落库并返回。
8. 引用、原文下载和历史消息每次打开都会重新鉴权。

## 3. 关键设计问题

### Q1：为什么拆 Java 和 Python？

Java 适合业务事务、权限、状态机、审计和关系数据；Python 适合文档解析、模型调用和
向量处理。边界固定为“Java 决策授权，Python 执行计算”，避免两边各自实现一套权限。

代价是多一次网络调用、部署复杂度和链路追踪要求。V1.0 用同一台机器上的 Docker 网络
降低复杂度，后续可按解析、Embedding、Chat 再拆。

### Q2：为什么 Python 不校验 JWT？

JWT 只代表用户身份，不直接代表当前知识库权限。权限可能随时被撤销，且多知识库会话需要
逐个校验。Java 每次请求从数据库计算授权范围，再通过内部 Token 调用 Python。Python
不信任浏览器传入的任意 `kb_id`。

这样做的代价是 Java 是授权单点，必须重点测试；收益是权限只有一个权威实现。

### Q3：`token_version` 解决什么？

普通 JWT 在过期前无法撤销。每个用户保存 `token_version`，签发 token 时写入当前值；
每个受保护请求回查用户状态和版本。登出、改密、禁用、角色变更或管理员重置密码时自增
版本，旧 token 立即失效。

这是“每次请求查数据库”的即时撤销方案，换来更强安全性，也增加数据库读压力。后续可在
Redis 缓存版本或使用短期 access token + refresh token。

### Q4：权限矩阵怎么测？

[PermissionMatrixIntegrationTest.java](../backend-java/src/test/java/com/knowbase/system/PermissionMatrixIntegrationTest.java)
定义 47 个接口用例，覆盖公开、登录、知识库读、知识库管理和系统管理员五类权限；对
`SYS_ADMIN / KB_ADMIN / USER` 三角色执行 141 次组合。断言规则不是“能访问就成功”，
而是：

- 应允许时不能返回 401/403。
- 应拒绝时必须返回 403。
- 非公开接口不能因登录态丢失返回 401。

测试使用专用账号，结束后清理用户、角色、知识库、文档、会话和日志，避免污染开发库。

### Q5：文档索引为什么异步？

PDF/DOCX 解析、Embedding 和 Qdrant 写入可能持续数秒到数十秒。如果同步阻塞 HTTP，
连接容易超时，任务也无法可靠重试。因此上传只负责校验、落文件和入队，worker 异步处理。

文档状态：

```text
PENDING -> PROCESSING -> ACTIVE
    |          |
    +-------> FAILED
ACTIVE -> DELETING -> DELETED / DELETE_FAILED
```

任务使用条件更新 + `SKIP LOCKED` 认领，服务重启后能回收超时任务。写入分片和更新成功
状态前后都会重新检查文档状态，优先中止已删除文档。

### Q6：如何保证 MySQL 与 Qdrant 一致？

没有引入分布式事务，而是使用“任务状态 + 幂等写入 + 补偿”：

1. Python upsert 使用稳定向量 ID。
2. Java 仅在文档仍为 `PROCESSING` 时写入分片并设为 `ACTIVE`。
3. 失败任务按次数重试，超过阈值进入 `DEAD`。
4. 删除文档先进入 `DELETING`，清理向量和文件后再完成；失败进入补偿状态。

这允许短时间不一致，但有明确状态和重试依据。生产还应有定期对账任务，当前 V1.0 尚未
实现全量自动对账。

### Q7：RAG 如何避免“编造企业制度”？

- 检索前由 Java 限定授权知识库。
- 低分/空结果不生成引用。
- 有命中时把资料包在来源标签中，并说明资料里的指令无效。
- 模型回答后用服务端重新解析并校验引用。
- 无命中时先提示“知识库中未找到相关依据”，通用知识必须明确标注。
- 没有可查询授权范围时直接无答案，不调用模型。

Prompt 注入不能靠一段提示词彻底解决，所以还叠加输出引用白名单、二次鉴权和不渲染原文
HTML 等工程边界。

### Q8：评估为什么只看 Recall@5 不够？

Recall 高不代表引用都对。例如检索到了多个资料，但模型引用了错误分片。反过来，只返回
一个正确引用可能 precision 100%，却漏掉多文档答案。

因此同时报告：

- 文档/分片 Recall@5
- 文档/分片引用正确率
- 无答案正确拒答率
- 可答案误拒答率
- 关键要点覆盖
- P50/P95/Max 延迟

当前唯一 Recall 缺口是 M04：问题需要“数据分级”和“远程办公”两份文档，最终只覆盖一份，
文档 Recall 为 50%。这说明引用正确率 100% 不能替代召回评估。

### Q9：97.50% 能不能说成准确率？

不能。它是 25 道人工核验题、10 份短文档、`local-hash-v1` Embedding 和
`deepseek-chat` 条件下的一次演示级评估。样本小、文档简单，Recall 还用最终引用近似，
可能低估真实检索。它只能证明固定条件下的流程表现，不能外推到任意企业知识库。

### Q10：部署为什么只暴露 80/443？

MySQL、Redis、Qdrant、Java、Python 都不应直接暴露给公网。Nginx 做 TLS、静态文件和
白名单 API 代理；内部网络 `data` 设置 `internal: true`。唯一需要外部模型访问的 Python
接入单独 `egress` 网络，避免整个数据网络拥有公网出口。

当前是单机单主机部署，没有跨主机高可用。跨主机数据库必须启用 TLS，不能沿用 Compose
中的 `MYSQL_SSL_MODE=DISABLED`。

### Q11：备份恢复的难点是什么？

要同时处理 MySQL、上传文件和 Qdrant 三份状态。只备份数据库会留下文件或向量不一致。
脚本先生成三份产物和 SHA-256 manifest，恢复前逐个校验，再恢复数据库、上传卷和 Qdrant
snapshot。

`APP_MASTER_KEY` 不写进备份，因为它应独立存密码库。备份时还应停止 Nginx/backend，
避免数据库和文件处于不同时间点。当前脚本尚未在本环境完成真实删库恢复演练。

### Q12：项目最大的技术债是什么？

按优先级：

1. 使用真实语义 Embedding、重排和混合检索，重建评估基线。
2. 完成 Testcontainers、备份恢复和干净环境运行验收。
3. 增加长文档、扫描 PDF、复杂表格、并发和故障注入测试。
4. 前端 Element Plus 按需加载，拆分 940 kB 级 vendor chunk。
5. 单机 Compose 演进到托管数据库、对象存储和可观测平台。

## 4. 可以主动展示的证据

- 固定集结果：[latest.md](../tests/rag-evaluation/results/latest.md)
- 指标局限：[EVALUATION.md](EVALUATION.md)
- 权限矩阵：[PermissionMatrixIntegrationTest.java](../backend-java/src/test/java/com/knowbase/system/PermissionMatrixIntegrationTest.java)
- 架构图：[ARCHITECTURE.md](ARCHITECTURE.md)
- ER 图：[DATABASE.md](DATABASE.md)
- 部署与边界：[RUNBOOK.md](RUNBOOK.md)、[SECURITY.md](SECURITY.md)
- 完整测试状态：[STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md)

## 5. 面试时不要回避的边界

- Docker 运行时验收在当前环境未完成。
- 备份恢复脚本未做真实恢复演练。
- 当前评估用 `local-hash-v1`，不是真实语义 Embedding。
- 未覆盖 OCR、复杂表格、多租户、高并发和高可用。
- T5.10 录屏已按用户要求取消，不应声称已有视频；可展示演示脚本。

能清楚说明边界，比把演示结果包装成“生产级准确率”更可信。
