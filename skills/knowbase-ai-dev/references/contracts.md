# Contracts (read only what the task needs)

Authoritative docs live in `docs/`. This file is a router plus the conflict resolutions agents get wrong. If this file and `docs/` disagree, `docs/` wins except where a resolution is noted below.

## Versions: docs/STACK.md

- JDK 21.0.8, Boot 4.1.2, Security 7 (lambda DSL), Flyway 12.4.0 plus `flyway-mysql`, MySQL connector managed by Boot. Maven 3.9.4, no Gradle.
- Python 3.12.9 in `backend-python/.venv` via `uv`. FastAPI 0.162.0, Pydantic 2.14.1 (v1 style banned), uvicorn 0.54.0, httpx 0.28.1, openai 3.41.0 (3.x call signatures, not 1.x), qdrant-client 1.20.0, pdfplumber 0.11.10, pypdf 6.21.4, python-docx 1.2.0. PyMuPDF banned (AGPL).
- Vue 3.5.44, Vite 8.3.5 (needs Node 22.12+, repo uses 22.20), Pinia 4.1.0, Router 5.4.0, Element Plus 2.14.7, pnpm 12.3.4.
- Infra tags pinned: `mysql:8.4`, `redis:8`, `qdrant/qdrant:v1.20.0`, `nginx:1.28-alpine`. Never `latest`.
- No major upgrades mid-stage. Version change updates STACK.md plus build files plus RUNBOOK verification together.

## Boot 4 pitfalls

- Flyway auto-config split out: both `spring-boot-starter-flyway` and `flyway-mysql` required or no `Flyway` bean at startup. MySQL is free in Community edition.
- Security 7: custom `OncePerRequestFilter` for self-signed JWT, `SessionCreationPolicy.STATELESS`, lambda DSL. Online Boot 3 / Security 6 snippets read fine but do not copy: `and()` chains removed, `oauth2ResourceServer().jwt()` is for external IdP only.

## Plan and scope: docs/DEVELOPMENT_PLAN.md

- Vertical slices first: M1 at W2 is TXT upload to cited answer; permissions and recovery come in M2, not M1.
- P0 (auth, KB perms, ingest, RAG with real citations, deploy) never cut. P1 degrades before removal. P2 cut first.
- 7 pages only; doc history, retrieval debugger, eval platform, notifications, MQ, OCR, hybrid search, reranker, multi-tenancy, billing, SSO, mobile, i18n, microservices, K8s, CI/CD explicitly out.
- Stage gates: T0.8 green before T1, M1 real `[1]` citation to a chunk row before Stage 2, M2 cross-account isolation plus kill-Python recovery before Stage 3.

## Java API: docs/API_SPEC.md

- Base `http://localhost:8080`, UTF-8 JSON except multipart upload. Only `/auth/login` and `/health` are public.
- Envelope `{code,message,data,traceId,timestamp}`. Codes: 0/200, 40001/400, 40101/401, 40301/403, 40401/404, 40901/409, 50000/500, 50301/503. Branch on `code`, never parse `message`.
- JSON camelCase, ISO-8601 UTC `Z`, `page` from 1, `size` default 20 max 100. `X-Trace-Id` `[A-Za-z0-9_-]{8,64}` echoed always.
- Auth: `POST /auth/login` (BCrypt, unknown user vs wrong password indistinguishable, DISABLED distinct), `GET /auth/me`. JWT HS256 `sub/uid/ver/iat/exp`, 7200s, key `HMAC-SHA256(APP_MASTER_KEY, knowbase-jwt-v1)`.
- KB list/detail owner-only; non-owner detail returns 404 not 403. Duplicate name 409 on `uk_kb_owner_name`.
- Upload field `file`, ext `txt` in Stage 1, 20 MB max, rejects empty. Dedup `sha256` per KB 409. Sync Python call in Stage 1; failure still HTTP 200 with doc `FAILED` plus `error_code`/`error_msg`; Python down gives `DEPENDENCY_UNAVAILABLE`.
- Resolution: plan T1.8 says SUCCESS but DDL check `ck_doc_status` allows only `PENDING/PROCESSING/ACTIVE/FAILED/DELETING/DELETED/DELETE_FAILED`. Use `ACTIVE`. Do not edit V1.
- Ask: `{kbId, question 1-2000 chars}`, topK 5, threshold 0.2. Below-threshold or empty hits: fixed no-answer text, no LLM call. `[n]` must match `citations[].rank` and `chunkId` must exist in `kb_document_chunk`. Providers labeled honestly (`openai-compatible:<model>` vs `local-extractive` / `local-hash-v1`). Non-owner kbId 404, empty question 400, Python down 503.

## Python internal: docs/INTERNAL_API.md

- Base `http://127.0.0.1:8090`, loopback only, header `X-Internal-Token` constant-time compare, 401 on missing or wrong, no secret in body.
- `X-Trace-Id` echoed and logged on every line. Java timeout 30s (connect 5s). JSON snake_case. Error `{code,message,trace_id}` with 401 UNAUTHORIZED, 400 VALIDATION_ERROR, 404 NOT_FOUND, 413 FILE_TOO_LARGE, 422 NO_TEXT_LAYER, 500 INTERNAL_ERROR (no stack), 503 DEPENDENCY_UNAVAILABLE.
- Structured JSON log per request (`ts,level,logger,trace_id,method,path,status,duration_ms`). Never log file content, token, api_key.
- `GET /internal/health`: 200 UP or 503 DEGRADED with short detail codes only; Java `/health` may reuse it.
- `POST /internal/parse`: absolute `storage_path` from Java, `ext` txt only in Stage 1 (others 400 UNSUPPORTED_FORMAT), split blank-line then merge to `max_chars` 100-2000 default 600 with `overlap` default 80 and `< max_chars`, `seq` from 0, non-empty after trim. Missing file 404, blank text 422.
- `POST /internal/index`: `chunks` non-empty, `seq` unique continuous from 0, point id `uuid5(NAMESPACE_URL, knowbase:chunk:{doc_id}:{seq})` for idempotent upsert, payload `{doc_id,kb_id,seq,content}` with payload indexes, collection `QDRANT_COLLECTION` default `knowbase_chunks` size `EMBEDDING_DIM` 1024 Cosine auto-created. Dim mismatch 500 `EMBEDDING_DIM_MISMATCH`.
- `POST /internal/search`: `{kb_ids,query,top_k 1-20 default 5,score_threshold 0-1 default 0.2}`, filter `kb_id IN kb_ids AND score >= threshold` desc. Empty `kb_ids` short-circuits to `hits: []` with no Qdrant access. Java maps `(doc_id,seq)` via `uk_chunk_doc_seq` to chunk id for `ai_message_citation.quote`.
- Model fallback: real embedding/chat only when both base URL and key set; else `local-hash-v1` bigram hash 1024-dim L2-normalized and `local-extractive` top-3 concat with `[n]`. Always report provider honestly. Real-model failure returns 503, never silent downgrade.

## DB and state

- `database/migrations/V1__init.sql` creates 17 tables; `R__seed.sql` one admin. New changes are new migrations; V1 frozen after T0.8.
- Timestamps `DATETIME(3)` written by app, FKs `ON DELETE RESTRICT` (except `ai_call_log`), cascades in app code. Users disabled not deleted, KBs archived (physical delete only when empty), docs soft-deleted `ACTIVE to DELETING to DELETED`, sessions hard-deleted with app-level cascade, chunks physically removed with doc.
- Upload path `${UPLOAD_DIR}/yyyy/MM/<uuid>.<ext>`; `storage_key` relative; `dedup_key = sha256`.
- Task claim `@Scheduled` polling plus conditional `UPDATE` atomic claim; zombie `RUNNING` reclaimed on restart; retry cap with `error_msg` readable and manual retry entry.

## Security and config

- `application.yml` generic, `application-dev.yml` env-sourced with local trust, `application-prod.yml` env-only no defaults plus `sslMode=VERIFY_IDENTITY`. `.env` auto-loaded in dev only; prod uses real env. OS env beats `.env`.
- Fail fast on missing, empty, or example `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `APP_MASTER_KEY` (32-byte base64, encrypts `ai_model_config.api_key_cipher`, loss unrecoverable), `INTERNAL_TOKEN` 32 chars min, `REDIS_PASSWORD` in prod. Never run weak.
- JWT `uid + ver` against `sys_user.token_version`; logout, password change, disable increments version. Login-fail lockout (5 in 15 min) in T2.2 backed by Redis. `/health` returns short component codes (`TIMEOUT`, `CONNECTION_REFUSED`, `AUTH_FAILED`, `HTTP_503`) with `X-Trace-Id`, never exception text; Qdrant keyed probe uses `/collections` not `/healthz`.
- D12 boundary: retrieval `kb_ids` computed by Java `AuthzService.canReadKb` (first line of KB-domain services, ArchUnit-guarded). Python holds no user table, no JWT logic, no permission branch.

## Ports and modes

- MySQL 33307 (host 3306 taken), Redis 16380, Qdrant 6333/6334, Java 8080, Python 8090, Vite 5173.
- `dev`: MySQL/Redis/Qdrant in containers, Java/Python/frontend hot-reload locally. `demo`: all in containers plus Nginx. Separate scripts and acceptance each; script and doc must match.
