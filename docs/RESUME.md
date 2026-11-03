# 简历项目描述

> 任务：T5.11
> 所有数字均来自 [EVALUATION.md](EVALUATION.md) 或 [STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md)。
> 不得把演示级指标改写为普遍准确率。

## 1. 项目名称

**知库 AI（KnowBase AI）｜企业知识库 RAG 问答系统**

技术栈：Vue 3、Spring Boot 4.1、FastAPI、MySQL 8.4、Redis 8、Qdrant、Docker Compose、
Nginx。

## 2. 一句话描述

面向团队知识管理的全栈 RAG 系统：支持文档异步解析与向量索引、知识库级权限隔离、
带原文引用的流式问答、模型配置管理和生产单机部署。

## 3. 简历要点

### 版本 A：后端/全栈岗位

- 设计并实现 Vue 3 + Spring Boot + FastAPI 三服务架构，Java 负责认证授权、知识库、
  文档状态机、会话和审计，Python 只处理解析、Embedding、检索与生成。
- 实现 JWT、`token_version` 即时失效和知识库成员权限；使用 47 个接口用例 × 3 角色
  的权限矩阵验证 141 个权限组合，测试账号和临时数据自动清理。
- 实现文档异步处理链路：任务条件认领、超时回收、失败重试、删除补偿，以及
  MySQL 分片与 Qdrant 向量写入的一致性保护。
- 实现带引用 RAG：Java 在检索前计算可读知识库，Python 只接收授权范围，Java 对引用
  再做知识库、文档状态和分片归属二次校验。
- 建立 25 题、5 类、10 份固定文档的演示级评估集；在 `local-hash-v1` Embedding 和
  `deepseek-chat` 条件下，文档级 Recall@5 97.50%、引用正确率 100%、P95 1973 ms，
  同时保留多文档缺口和评估局限。
- 使用 Docker Compose + Nginx 提供只暴露 80/443 的单机编排，设计 MySQL、上传卷、
  Qdrant snapshot 的备份清单和 SHA-256 完整性校验。

### 版本 B：AI 应用岗位

- 构建可追溯的企业知识库 RAG 流程，覆盖 TXT/Markdown/PDF/DOCX 解析、分片、向量化、
  Top-K 检索、上下文预算、流式回答和引用落库。
- 针对无答案场景实现拒答与通用知识降级，明确区分企业依据和模型常识；针对提示词注入
  使用来源包裹、引用白名单和 Java 二次鉴权。
- 建立可复现评估执行器，一条命令输出 Recall@5、文档/分片引用正确率、拒答率和 P95；
  固定集包含事实、多文档、流程、无答案和 5 道提示词注入题。
- 在实际 DeepSeek 链路上完成 25 题顺序评估，记录数据 SHA-256、模型、Embedding、
  Top-K 和时间，避免把一次演示结果包装成行业基准。

## 4. 可直接放进简历的短版

> KnowBase AI：Vue 3 + Spring Boot + FastAPI 的团队知识库 RAG 系统。负责权限、
> 异步索引、引用校验和生产编排；实施 141 个接口/角色权限组合测试。搭建 25 题固定评估集，
> 在 `local-hash-v1` + `deepseek-chat` 条件下 Recall@5 97.50%、引用正确率 100%、
> P95 1973 ms，并明确记录演示级评估的不能外推边界。

## 5. English Resume Draft

**KnowBase AI | Full-stack RAG knowledge base**

- Built a Vue 3, Spring Boot, and FastAPI knowledge platform with asynchronous document
  ingestion, Qdrant retrieval, streamed answers, and chunk-level citations.
- Implemented JWT invalidation, server-side knowledge-base authorization, citation
  re-validation, and a 47-endpoint × 3-role permission matrix.
- Created a reproducible 25-question evaluation suite; under `local-hash-v1` embedding
  and `deepseek-chat`, measured Recall@5 97.50%, citation accuracy 100%, and P95
  latency 1973 ms, with documented benchmark limitations.
- Packaged the system with Docker Compose and Nginx, exposing only ports 80/443 and
  documenting backup, restore, health, and failure-recovery procedures.

## 6. 可核验数字

| 表述 | 数值 | 出处与条件 |
|---|---:|---|
| 固定评估题数 | 25 | [latest.md](../tests/rag-evaluation/results/latest.md) |
| 文档级 Recall@5 | 97.50% | 10 份短文档、`local-hash-v1`、`deepseek-chat` |
| 文档/分片引用正确率 | 100% | 同上，人工核验期望集合 |
| 无答案正确拒答率 | 100% | 5 道无答案题 |
| P95 延迟 | 1973 ms | 单机顺序请求，不含上传/索引等待 |
| 权限组合 | 141 | 47 个接口用例 × 3 角色 |
| Python 测试 | 85 passed | [STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md) |
| 前端构建 | 1707 modules | Vite 6.4.5 生产构建 |

## 7. 不应写进简历的表述

- 不写“RAG 准确率 97.5%”。
- 不写“生产环境已稳定运行/已通过全量容器验收”，除非完成 Docker 验收。
- 不写“支持 OCR/复杂表格/多租户”，当前 V1.0 不支持。
- 不写“语义 Embedding”，当前可重复评估主要使用 `local-hash-v1`。
- 不写“向量数据库集群”或“高可用”，当前是单机 Compose。
