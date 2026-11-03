# Stage 5 验收表

> 日期：2026-10-31
> 范围：T5.1–T5.12
> 原则：只有实际执行的证据才标记为通过；代码存在、语法检查通过和运行时验收分开记录。

## 1. 总体结论

Stage 5 的静态交付物已齐备，Java/Python/前端/RAG/权限矩阵等可在当前环境运行的验证已完成。

但当前机器的 Docker daemon 拒绝访问，因此以下三项不能宣称通过：

- T5.4 跨容器主干集成测试未实际启动。
- T5.7 备份→删库→恢复的实际演练未执行。
- T5.8 清空 Docker 后 30 分钟干净环境启动未执行。

T5.10 原要求的 5 分钟视频已由用户明确取消；[DEMO.md](DEMO.md) 保留为可选演示脚本和
录屏验收清单，不再作为本轮待办。

因此当前状态是：**实现完成，自动化可运行项通过，Docker 运行时验收待执行**。

## 2. 逐项验收

| ID | 交付物 | 当前状态 | 证据与说明 |
|---|---|---|---|
| T5.1 | 25 题 5 类评估集、10 份固定文档 | 通过 | [dataset.jsonl](../tests/rag-evaluation/dataset.jsonl)、[manifest.json](../tests/rag-evaluation/manifest.json)；结构测试通过 |
| T5.2 | 一条命令输出 Recall、引用正确率、拒答率和 P95 | 通过 | [run.py](../tests/rag-evaluation/run.py)；真实运行结果见 [latest.md](../tests/rag-evaluation/results/latest.md) |
| T5.3 | 演示级定位、样本量、条件和不能外推的结论 | 通过 | [EVALUATION.md](EVALUATION.md) |
| T5.4 | 上传→解析/索引→检索→引用 Testcontainers 主干 IT | **环境阻断** | `mvn -Pintegration verify` 编译通过，Testcontainers 因 Docker named pipe 拒绝访问而跳过，未执行容器链路 |
| T5.5 | 全部接口 × 3 角色权限矩阵 | 通过 | [PermissionMatrixIntegrationTest.java](../backend-java/src/test/java/com/knowbase/system/PermissionMatrixIntegrationTest.java)，47 个接口用例 × 3 角色，专用测试账号并自动清理 |
| T5.6 | 生产 Compose + Nginx，只暴露 80/443 | 通过（配置级） | [compose.prod.yml](../deploy/compose.prod.yml)、[nginx.conf](../deploy/nginx/nginx.conf)；`docker compose config --quiet` 通过。容器实际启动待 T5.8 |
| T5.7 | MySQL/上传卷/Qdrant 快照备份恢复 | **部分完成** | [backup.ps1](../deploy/scripts/backup.ps1)、[restore.ps1](../deploy/scripts/restore.ps1) 已实现并通过 PowerShell 语法检查；未做真实恢复演练 |
| T5.8 | 干净环境 30 分钟启动 | **未执行** | [cleanroom.ps1](../deploy/scripts/cleanroom.ps1)、[RUNBOOK.md](RUNBOOK.md) 已提供；Docker daemon 不可访问 |
| T5.9 | README、架构、数据库、API、安全、手册、排障、运维、验收 | 通过 | 文档交叉链接由 `tests/docs/check_links.py` 校验 |
| T5.10 | 演示脚本 + 5 分钟录屏 | **按用户要求取消录屏** | [DEMO.md](DEMO.md) 保留逐秒脚本、准备数据、失败恢复和可选验收清单 |
| T5.11 | 简历与面试笔记 | 通过 | [RESUME.md](RESUME.md)、[INTERVIEW_NOTES.md](INTERVIEW_NOTES.md)；数字均链接评估/测试报告并写明条件 |
| T5.12 | 已知问题 | 通过 | [KNOWN_ISSUES.md](KNOWN_ISSUES.md)；每条含当前表现、影响和大概耗时 |

## 3. 可运行验收结果

详细命令与原始结果见 [STAGE5_TEST_REPORT.md](STAGE5_TEST_REPORT.md)。

| 验收项 | 结果 |
|---|---|
| Java 默认测试 | 64 项，0 失败，0 错误，7 项 opt-in/环境依赖跳过 |
| 权限矩阵 | 1 个测试方法内执行 141 次接口/角色组合，0 不匹配 |
| Python 测试 | 85 passed |
| RAG 评估器单测 | 4 passed |
| 真实固定集评估 | 25 题；Recall@5 97.50%；引用正确率 100%；P95 1973 ms |
| HTTP 冒烟 | 20/20 通过 |
| 前端生产构建 | 成功，1707 个模块 |
| 生产 Compose 配置 | JSON/YAML 解析与引用检查通过 |
| 集成分支编译 | `mvn -Pintegration verify` 编译通过，T5.4 因 Docker 跳过 |
| Docker daemon | 失败：`permission denied while trying to connect ... docker_engine` |

## 4. 生产验收门槛

以下清单全部完成前，不应把本项目描述为“已完成生产验收”：

- [ ] `docker compose build --pull` 成功。
- [ ] `docker compose up -d` 后六服务均为 healthy。
- [ ] `/health` 四项组件为 `UP`。
- [ ] 宿主机只映射 80/443。
- [ ] 受信任 TLS 证书替代自签名证书。
- [ ] 首次登录后修改默认管理员密码。
- [ ] `mvn -Pintegration verify` 中 MainFlowIT 实际执行并通过。
- [ ] 备份后删除测试库/上传对象/Qdrant collection，再完整恢复并做引用核验。
- [ ] 干净 Docker 环境从零启动在 30 分钟内完成。
- [x] T5.10 录屏按用户要求取消，演示脚本保留。

## 5. 评估口径

只能表述为：

> 在 10 份固定短文档、25 道人工核验题、`local-hash-v1` Embedding 和
> `deepseek-chat` 条件下，得到文档级 Recall@5 97.50%、引用正确率 100%、P95 1973 ms。

不能表述为：

- “RAG 准确率 97.5%”
- “企业文档问答普遍准确率 97.5%”
- “生产级语义检索已达到该指标”

原因和统计限制见 [EVALUATION.md](EVALUATION.md)。

## 6. 验收签署建议

| 角色 | 必须确认 |
|---|---|
| 开发者 | 代码与自动化证据、已知问题、数据口径真实 |
| 演示者 | 使用固定数据集、保留失败过程、不依赖剪辑掩盖错误 |
| 运维 | TLS、密钥、备份、恢复和端口边界 |
| 答辩/评审 | 不把演示级评估外推为行业基准，不把未执行项写成通过 |
