from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("run.py")
SPEC = importlib.util.spec_from_file_location("rag_evaluation_run", MODULE_PATH)
assert SPEC and SPEC.loader
run = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = run
SPEC.loader.exec_module(run)


class EvaluationMetricTest(unittest.TestCase):
    def test_dataset_has_five_categories_and_manual_review(self) -> None:
        dataset, manifest = run.load_dataset(
            Path(__file__).with_name("dataset.jsonl"),
            Path(__file__).with_name("manifest.json"),
        )
        self.assertEqual(len(dataset), 25)
        counts = run.Counter(row["category"] for row in dataset)
        self.assertEqual(set(counts), set(run.CATEGORIES))
        self.assertTrue(all(counts[category] == 5 for category in run.CATEGORIES))
        self.assertTrue(all(row["human_verification"] for row in dataset))
        self.assertEqual(len(manifest["documents"]), 10)

    def test_metrics_cover_recall_precision_refusal_and_p95(self) -> None:
        records = [
            {
                "category": "factual",
                "answerable": True,
                "refused": False,
                "doc_recall_at_k": 1.0,
                "chunk_recall_at_k": 1.0,
                "doc_citation_precision": 1.0,
                "chunk_citation_precision": 1.0,
                "key_point_coverage": 1.0,
                "latency_ms": 10,
            },
            {
                "category": "unanswerable",
                "answerable": False,
                "refused": True,
                "doc_recall_at_k": 1.0,
                "chunk_recall_at_k": 1.0,
                "doc_citation_precision": 1.0,
                "chunk_citation_precision": 1.0,
                "key_point_coverage": 1.0,
                "latency_ms": 100,
            },
        ]
        summary = run.summarize(records)
        self.assertEqual(summary["recall_at_5"], 1.0)
        self.assertEqual(summary["document_citation_accuracy"], 1.0)
        self.assertEqual(summary["refusal_rate"], 0.5)
        self.assertEqual(summary["unanswerable_refusal_rate"], 1.0)
        self.assertEqual(summary["latency_ms"]["p95"], 100)

    def test_refusal_supports_general_fallback_without_citations(self) -> None:
        self.assertTrue(
            run.refuse_detected(
                {
                    "noAnswer": False,
                    "answer": "知识库中未找到相关依据。\n\n这是通用知识说明。",
                    "citations": [],
                }
            )
        )
        self.assertFalse(
            run.refuse_detected(
                {
                    "noAnswer": False,
                    "answer": "有依据的回答",
                    "citations": [{"docId": 1, "chunkId": 2}],
                }
            )
        )

    def test_invalid_dataset_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "dataset.jsonl"
            path.write_text(json.dumps({"id": "bad"}) + "\n", encoding="utf-8")
            manifest = {"documents": [{"key": "expense"}]}
            with self.assertRaises(run.EvaluationError):
                run.load_dataset(path, Path(temp) / "missing.json")
            with self.assertRaises(run.EvaluationError):
                run.validate_dataset([{"id": "bad"}], manifest)


if __name__ == "__main__":
    unittest.main()
