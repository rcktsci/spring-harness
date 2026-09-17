# Ревью патча D (7.1–7.6, TurnManager)

## Findings

| Severity | Файл:строка | Факт | Предложение |
|---|---|---|---|
| **HIGH** | `SessionLockManager.java:81-100` | `scheduleAtFixedRate` подавляет исключения из `heartbeat()` — если `extend()` упадёт (БД недоступна), поток мёртв, лок истекает без предупреждения. | Обернуть вызов в `try-catch` с логированием и принудительным завершением задачи (например, флагом `cancelled` и `task.cancel(true)`). |
| **MEDIUM** | `PollWakeJob.java:46` | `lock_until < now()` — сравнение без явного часового пояса; в PostgreSQL `now()` возвращает `timestamptz`, но если ShedLock хранит `timestamp without time zone`, возможна ошибка на разных TZ. | Явно преобразовать `lock_until` в `timestamptz` в запросе или использовать `AT TIME ZONE 'UTC'` для согласованности. |
| **MEDIUM** | `AgentTurnEngine.java:160-166` | При падении инструмента (`executeTool`) возвращается `ToolResult.error`, но Turn не завершается FAILED — продолжает/завершает COMPLETED. Это скрывает системные сбои. | Добавить счётчик tool-failures в памяти Turn'а; если превышен порог (конфиг), завершать Turn со статусом FAILED + SYSTEM-событие. |
| **LOW** | `SessionStoreImpl.java:127-132` | `finishTurn` обновляет `last_consumed_seq = last_seq` для CANCELLED. По спеке это правильно, но нет проверки, что Turn действительно завершился (возможна гонка). | В `TurnManagerImpl` перед `finishTurn` убедиться, что статус Turn уже не RUNNING (через `ActiveTurnRegistry` или DB-флаг). |
| **INFO** | `RestartScanRunner.java:54-81` | Порядок корректен: TOOL_CALL → контейнеры. При живом Turn на другом инстансе сессия пропускается. | — |
| **INFO** | `TurnManagerImpl.java:76-86` | При неожиданном падении Turn'а (Exception) дописывается SYSTEM-событие и FAILED. Логирование без traceId/MDC. | Добавить `MDC.put("traceId", ...)` на старте `runTurn` и использовать в `log.error`. |

## Summary

| Metric | Value |
|---|---|
| Total Findings | 6 |
| HIGH | 1 |
| MEDIUM | 2 |
| LOW | 1 |
| INFO | 2 |

### Top 3 Issues

1. **Heartbeat thread dies silently** (`SessionLockManager`) — если `extend()` падает из-за проблемы с БД, лок истекает и Turn может быть перезапущен.
2. **Tool failures don't fail the turn** (`AgentTurnEngine`) — инструментальные ошибки не прерывают Turn, что нарушает инвариант «Turn завершается при критическом сбое».
3. **Timezone risk in cleanup query** (`PollWakeJob`) — сравнение `lock_until < now()` может быть небезопасным при миграции БД или изменении TZ.
