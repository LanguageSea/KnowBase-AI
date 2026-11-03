# RAG 评估说明

> 任务：T5.1–T5.3
> 数据集：[dataset.jsonl](../tests/rag-evaluation/dataset.jsonl)
> 执行器：[run.py](../tests/rag-evaluation/run.py)
> 最近结果：[latest.md](../tests/rag-evaluation/results/latest.md)

## 1. 定位与结论边界

这是**演示级固定评估集，不是行业基准测试**。

评估结论只能说明：在本文记录的时间、模型、提示词、分片参数和 10 份固定文档下，
系统能够完成检索、引用和拒答流程，并暴露了哪些具体失败样本。它不能推出：

- 系统对任意企业文档普遍准确；
- 真实业务问答达到某个“准确率百分比”；
- 当前指标能与其他 RAG 产品或公开榜单直接比较；
- 中文、扫描件、复杂表格和多跳推理已经全部覆盖。

简历、答辩和面试中只能引用“25 道人工核验的演示级评估”及具体条件，不能改写成
“RAG 准确率 97.5%”这类不可外推的表述。

## 2. 数据集

| 项目 | 内容 |
|---|---|
| 样本量 | 25 题 |
| 类别 | 事实问答、跨文档综合、流程理解、无答案、提示词注入对抗，各 5 题 |
| 文档 | 10 份短 Markdown 固定材料 |
| 可答案题 | 20 题 |
| 无答案题 | 5 题 |
| 人工核验 | 每题包含 `human_verification`，说明问题、文档和要点为什么匹配 |
| 字段 | `expected_doc_ids`、`expected_chunk_ids`、`key_answer_points`、`answerable` |

`expected_doc_ids` 和 `expected_chunk_ids` 使用稳定的逻辑标识，例如 `expense` 和
`expense#0`。运行器把 10 份文档上传到一个新知识库，再按实际响应把逻辑标识解析为
当次生成的数据库 ID。这样不会把自增 ID 写死在数据集里。

## 3. 指标定义

### 3.1 Recall@5（文档级）

对可答案题，取 `/qa/ask` 返回的 citations 中不同文档 ID，计算：

```text
文档 Recall@5 = |期望文档 ∩ 引用文档| / |期望文档|
```

当前 API 没有单独的“检索结果调试”接口，因此脚本以最终引用作为检索证据。这会低估
“检索到了但模型没有引用”的情况，所以 Recall@5 是保守近似，不是独立检索器指标。

### 3.2 引用正确率（文档级、分片级）

逐题计算 precision：

```text
文档级 = 引用中属于期望文档的数量 / 引用总数
分片级 = 引用中属于期望分片的数量 / 引用总数
```

无答案题的期望集合为空；没有引用记为 1，产生引用记为 0。报告对全部 25 题取平均。

### 3.3 拒答率

`noAnswer=true`，或回答没有引用且包含“未找到相关依据”之一，判定为拒答。分别报告：

- 总体拒答率；
- 5 道无答案题的正确拒答率；
- 20 道可答案题的误拒答率。

### 3.4 P95 延迟

P95 是 25 次 `/qa/ask` 端到端 HTTP 耗时（毫秒）升序排列后的第 95 百分位，包含 Java
鉴权与编排、Python 检索与生成、模型调用和会话落库，不包含上传和索引等待时间。

### 3.5 关键要点覆盖率

人工标注的 `key_answer_points` 在回答中按子串命中，计算覆盖比例。它是辅助检查，不能
代替人工判断，也不能用来证明事实正确。

## 4. 最近一次运行条件

| 条件 | 值 |
|---|---|
| 日期 | 2026-10-31 |
| 地址 | `http://127.0.0.1:8080` |
| Chat Provider | `openai-compatible:deepseek-chat` |
| Embedding Provider | `local-hash-v1` |
| Top K | 5 |
| 分片参数 | 默认 `max_chars=600`、`overlap=80` |
| 请求间隔 | 3.1 秒，避免默认每分钟 20 次限流 |
| 数据集 SHA-256 | `1d6f3e676bb34c77dc360ae54ee90a6493769738a24dd537e836f2e72d63a3cf` |

## 5. 最近结果

| 指标 | 结果 |
|---|---:|
| 文档级 Recall@5 | 97.50% |
| 分片级 Recall@5（附加） | 97.50% |
| 文档级引用正确率 | 100.00% |
| 分片级引用正确率 | 100.00% |
| 总体拒答率 | 20.00% |
| 无答案题正确拒答率 | 100.00% |
| 可答案题误拒答率 | 0.00% |
| 关键要点覆盖率 | 81.67% |
| P95 延迟 | 1973 ms |
| P50 / Max | 1222 ms / 2226 ms |

本次唯一检索缺口是 `M04`：问题同时需要“数据分级与传输规范”和“远程办公规范”，最终
引用只覆盖其中一个文档，因此该题文档 Recall@5 为 50%。引用正确率仍为 100%，因为所有
已返回引用都属于期望集合。这正说明 Recall 与 citation precision 必须同时看。

## 6. 复现

先确认 Java、Python、MySQL、Redis、Qdrant 健康：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health | ConvertTo-Json -Depth 5
```

运行固定评估：

```powershell
.\backend-python\.venv313\Scripts\python.exe tests\rag-evaluation\run.py
```

只校验数据集结构：

```powershell
python tests\rag-evaluation\run.py --validate-only
```

运行评估器自身测试：

```powershell
python -m unittest discover -s tests\rag-evaluation -p "test_*.py" -v
```

脚本会在 `tests/rag-evaluation/results/` 写入时间戳 JSON/Markdown 和 `latest.*`。
每次重新评估都应核对 Chat/Embedding Provider、知识库 ID 和时间，避免把旧结果当作新结果。

## 7. 当前局限

1. 样本只有 25 题，置信区间很宽，不具备统计显著性。
2. 文档是短文本，未覆盖长 PDF、表格、扫描件、页眉页脚和复杂版面。
3. Embedding 使用 `local-hash-v1`，不具备真实语义泛化能力；更换 Embedding 后必须重跑。
4. Recall 以最终引用近似检索结果，可能低估检索覆盖率。
5. 人工要点覆盖使用字符串匹配，同义表达可能被误判。
6. 延迟只在单机、单用户顺序请求下测量，不代表并发容量。
7. DeepSeek 温度和网络状态会变化；评估报告必须绑定模型名称和时间。
8. 当前测试没有覆盖多租户、OCR、重排器、混合检索或复杂多跳推理。
