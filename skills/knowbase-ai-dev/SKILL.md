---
name: knowbase-ai-dev
description: Develop KnowBase-AI RAG repo (Vue 3 plus Spring Boot plus FastAPI plus Qdrant) per staged plan. Use for code, API, ingestion, or deploy work inside this repo.
---
# Knowbase AI Dev

Work inside `D:/thesis/KnowBase-AI`. Repo docs (`docs/STACK.md`, `docs/DEVELOPMENT_PLAN.md`, `docs/API_SPEC.md`, `docs/INTERNAL_API.md`) are authoritative; this skill tells you which rule wins.

## When to use / not use

Use when editing `backend-java/`, `backend-python/`, `frontend/`, `database/`, `deploy/`, or `tests/` in this repo. Do not use for generic Spring/Vue/Python help outside this repo.

## Execution rules (speed mode)

Follow `references/workflow.md` for every task: backup only the touched file then edit directly, touch only directly-related files, one hypothesis at a time, mechanical checks run as commands, prefer reuse over from-scratch, stop at task boundary.

Bug-search order is fixed: social fresh reports > community threads > GitHub issue/PR/source > official docs. Boot 4.1 / Vite 8 / openai 3.x docs lag reality.

## Project invariants (do not violate)

Full list in `references/contracts.md`. The ones agents break most:

- Versions frozen in `docs/STACK.md`. No major upgrades mid-stage.
- Boot 4.1.2 plus Security 7 lambda DSL only. No `and()` chains, no `oauth2ResourceServer().jwt()`; JWT is self-signed `uid + ver` with `token_version` invalidation.
- Flyway needs `spring-boot-starter-flyway` plus `flyway-mysql`. Never edit `V1__init.sql`; new tables go in new migrations. 17 tables.
- Java JSON is camelCase with `{code,message,data,traceId,timestamp}`; Python internal JSON is snake_case with `{code,message,trace_id}`. Client branches on `code` only.
- Auth boundary: Java computes `kb_ids`, passes to Python via `X-Internal-Token`. Python never parses JWT or decides permissions. Empty `kb_ids` returns `hits: []` without touching Qdrant.
- Uploads go to `${UPLOAD_DIR}/yyyy/MM/<uuid>.<ext>`; filename never enters disk path. Doc status `ACTIVE`, not `SUCCESS`. Chunk `seq` from 0, point id `uuid5(NAMESPACE_URL, knowbase:chunk:{doc_id}:{seq})`.
- Python env is `backend-python/.venv` via `uv` (CPython 3.12). Never use Windows Store `python.exe` or system conda.
- Ports: MySQL 33307, Redis 16380, Qdrant 6333/6334, Java 8080, Python 8090, Vite 5173. MySQL tag `mysql:8.4`, Qdrant `qdrant/qdrant:v1.20.0`, never `latest`.
- Secrets: `.env` never committed. Missing or placeholder `MYSQL_PASSWORD`, `APP_MASTER_KEY`, `INTERNAL_TOKEN` must fail fast, never run with weak defaults. Prod needs `SPRING_PROFILES_ACTIVE=prod` plus `sslMode=VERIFY_IDENTITY`.

## Task flow

1. Identify Stage/Task id (e.g. T2.7) in `docs/DEVELOPMENT_PLAN.md`. Respect Stage exit gates, do not skip stages.
2. Read only the contract you need: `references/contracts.md` routes to the exact doc section.
3. Implement the narrowest change that satisfies Acceptance in the plan table.
4. Verify with a single scoped command (module test, single endpoint, `docker compose ps`), not full suite. Full e2e only after all stages.
5. Report per workflow spec: changed files, key points, assumptions, unverified items marked 待验证.

## References

- For step-by-step speed rules and output shape, read [references/workflow.md](references/workflow.md).
- For versions, API shapes, DB/state, security, and paths, read [references/contracts.md](references/contracts.md).
