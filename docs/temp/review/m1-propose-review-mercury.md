# Review: m1-session-core OpenSpec Change

Date: 2026-09-17
Reviewer: Mercury

## Findings

1. **minor | design.md:76 | ShedLock fencing risk**
   - Defect: Document acknowledges ShedLock lacks fencing tokens and relies on generous TTL + extend, but doesn't specify exact TTL values recommended.
   - Suggestion: Add concrete recommendation (e.g., session-ttl ≥ 30min, heartbeat interval ≤ 5min) to guide configuration.

2. **minor | tasks.md:14 | Partial index naming**
   - Defect: Spec mentions "partial index eligible-скан" but doesn't provide index name conventions.
   - Suggestion: Add naming pattern (e.g., idx_session_eligible = WHERE last_seq > last_consumed_seq AND status != TERMINAL).

3. **nit | spec/llm-gateway:43 | FAILED status semantics**
   - Defect: Spec says Turn ends with FAILED after retry exhaustion, but execution-model §2 defines only COMPLETED/CANCELLED/LOST.
   - Suggestion: Clarify FAILED is a terminal runtimeStatus on session, not a Turn outcome code.

4. **minor | tasks.md:39 | Bash timeout config key**
   - Defect: Task 6.3 mentions "таймаут bash" but @ConfigurationProperties template in 1.3 doesn't list harness.turn.bash-timeout.
   - Suggestion: Add harr.turn.bash-timeout to config properties template.

5. **major | design.md:56 | Container lifecycle gap**
   - Defect: D-M1-7 says container lifecycle = session lifecycle, but doesn't specify when container is destroyed (on session archive? on FREE->STATE transition?).
   - Suggestion: Add explicit container destruction trigger (e.g., on session.status=ARCHIVED or on agent rev change).

6. **minor | specs/session-api:66 | SSE ping interval**
   - Defect: Spec says ping interval from config with default 15s, but execution-model doesn't mention this parameter.
   - Suggestion: Document harness.sse.ping-interval in execution-model §8 or design.md.

7. **minor | tasks.md:7.4 | LockAtMostFor value**
   - Defect: Task 7.4 says lockAtMostFor ~10s for poll job, but design.md:41 says poll lockAtMostFor is job parameter (not session ttl).
   - Suggestion: Clarify poll job lock name (e.g., "poll-wake") and separate job-lock TTL from session-lock TTL.

8. **minor | specs/workspace-tools:67 | Truncated marker location**
   - Defect: Spec says truncated in TOOL_RESULT but doesn't show where truncation info appears in stream.
   - Suggestion: Add example payload showing truncated flag location.

9. **nit | design.md:88 | Helper base image**
   - Defect: Open question about alpine vs debian-slim resolved at Dockerfile time, but CI smoke test needs a decision.
   - Suggestion: Pick one now (alpine 3.19 minimal) to avoid build CI failure.

10. **major | tasks.md:7.6 | Container cleanup timing**
    - Defect: Task 7.6 orphan container removal at startup, but doesn't address containers left mid-execution on crash.
    - Suggestion: Add docker-java event listener or periodic cleanup to catch in-flight orphan containers.

## Summary

| Severity    | Count |
|-------------|-------|
| blocker     | 0     |
| major       | 2     |
| minor       | 6     |
| nit         | 2     |

Overall: Artifacts are well-aligned with design baseline. M1 scope correctly bounded. Two major issues (container lifecycle timing, crash cleanup) should be resolved before implementation. Specs are testable; configuration keys need minor completion.

## Cross-check

| Коллега | Находка | Verdict | Комментарий |
|---------|---------|---------|-------------|
| GLM | cancel_requested сброс не определён | agree | Принято J-2: флаг снимается при любом исходе Turn'а. |
| GLM | ls в proposal | agree | Удалено (J-11). |
| GLM | users → app_user | agree | Исправлено (J-11). |
| GLM | agent в миграциях | agree | Добавлено (J-11). |
| GLM | D-41 → D-39 | agree | Исправлено (J-11). |
| GLM | llm_model иммутабельность | agree | Исправлено (J-11). |
| GLM | timeout в едином отчёте | agree | Добавлено timedOut (J-11). |
| DeepSeek | USER-допись под локом | agree | Принято J-1: row-lock вместо sess-лок. |
| DeepSeek | FAILED retry-шторм | agree | Принято J-3: SYSTEM-событие + last_consumed_seq. |
| DeepSeek | ручной DataSource/JPA task | agree | Принято J-5: задача 1.5. |
| DeepSeek | config inventory | agree | Принято J-4: добавлены ключи в 1.3. |
| DeepSeek | lockAtMostFor POLL | agree | Добавлен job-ttl в 1.3. |
| DeepSeek | Named volume vs bind mount | agree | Принято J-9: bind-mount. |
| DeepSeek | sess-* cleanup by lock_until | agree | Принято J-10. |

## Fixes approval

| Моя находка | Verdict | Комментарий |
|-------------|---------|-------------|
| M-1: конкретные TTL-числа | reject | R-1: против правила «числа — конфиг». |
| M-2: конвенции имён индексов | reject | R-2: имена — имплементация. |
| M-3: FAILED не исход Turn'а | reject | R-3: data-model:148 подтверждает FAILED как Turn outcome. |
| M-4: конфиг-инвентарь неполон | approve | J-4: добавлены ключи в 1.3. |
| M-5: lifecycle контейнера | reject | R-4: триггеры не существуют (D-41). |
| M-6: ping в execution-model | reject | R-5: это API-контракт, не execution-model. |
| M-7: путаница job-lock vs session-ttl | reject | R-6: уже разведено в design.md. |
| M-8: truncated не показано | reject | R-7: поле есть в контракте. |
| M-9: выбрать образ сейчас | reject | R-8: Open Question решается в задаче 6.1. |
| M-10: docker events/периодическая чистка | reject | R-9: sync-модель M1 не требует. |
| **Итог:** | 1 approve, 9 reject (все по обоснованиям R-1..R-10). |
| **Финал:** | Согласен со всеми вердиктами судьи (R-1…R-10) и закрытием M-4. |
