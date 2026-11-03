# Workflow (Codex speed mode)

Source: `AI开发必看.md`. These override default cautious behavior.

## Execute, do not confirm

- Backup only the file you touch, then edit directly. No repeated confirmation.
- Never full-project scan or full validation before a change. Check only directly-related files and deps.
- Uncertain point: pick the most reasonable option, proceed, label assumption in output. Do not block.

## Machines check, model decides

Run as a command, never by reading line-by-line:
- file exists, path correct
- format, syntax, lint
- counts, field diffs, duplicate check
- bulk replace, bulk rename

Rule: if one command can verify it, do not ask the model to review it.

## Reuse first

- Search for existing implementation (GitHub project, official sample, community fix) before writing new code.
- Prefer adapting over from-scratch. From-scratch requires one line: why reuse fails.
- Applies to Boot 4.1 Security 7 config, Qdrant client fallback to REST, pdfplumber chunking: all have prior art in repo or upstream.

## Bug-search order (fixed)

1. Social fresh reports (new-version breakage, real error text, workarounds)
2. Community threads
3. GitHub issue / PR / source
4. Official docs
5. Own reasoning (only if 1-4 empty)

Reason: right after releases, docs lag; community reports carry the working fix first.

## One hypothesis at a time

- No unbounded brainstorming. No "think harder" loops.
- Same issue fails twice on one angle: reproduce, record symptoms, then switch angle.
- One investigation line at a time, never parallel guesses.
- One task, one exit condition. Hit it, stop. No drive-by optimization.

## Cross-check, not self-retry

- Unsure solution: ask another model or fresh session to review. Reviewer only finds risks, gaps, counterexamples; does not rewrite.
- Conflict between proposals: reproducible test result wins.

## Stage discipline

- Split work into stage plan, minimal change per stage.
- End of stage: short summary (changed, impact, to-verify). No full test or full build mid-stage.
- Full e2e only after all stages complete.

## Output shape

- Keep to necessary info. Do not re-paste full code.
- List: modified files, key points, assumptions, unverified items.
- Unconfirmed content marked 待验证, never stated as fact.
