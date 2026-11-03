# Evaluation Results

`run.py` writes one timestamped JSON/Markdown pair plus `latest.json` here.

Committed `latest.*` files are evidence for the environment described in the
report. They must not be copied into résumé claims without checking:

- sample count and category distribution;
- chat and embedding provider names;
- timestamp and target commit;
- `docs/EVALUATION.md` limitations.

This directory is intentionally small: real uploaded documents and vectors are
not stored here.
