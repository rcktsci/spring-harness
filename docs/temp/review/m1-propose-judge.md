# Судейские вердикты: m1-propose-review (оркестратор)

> Споры и пограничные находки трёх ревьюеров после кросс-чека. Судья — оркестратор. Формат: находка → вердикт → действие/обоснование.

## Принято (фикс)

| # | Находка | Вердикт | Решение |
|---|---|---|---|
| J-1 | DS-1 (blocker): USER-допись «под локом sess-{id}» несовместима с локом на весь Turn | принять | seq — транзакционный row-lock (`UPDATE session SET last_seq=last_seq+1 … RETURNING`); `sess-{id}` — только mutex Turn'а в tryStart; строку «USER-допись без лока запрещена» из design убрать. Покрывает и DS-6 (инверсия задач 4.2→7.1 исчезает) |
| J-2 | GLM-1: сброс `cancel_requested` не определён | принять | Флаг сбрасывается при завершении Turn'а (любой исход) и на старте нового; stop без активного Turn'а последующего эффекта не имеет; сценарий «сообщение после отмены — штатно» |
| J-3 | DS-3 + GLM-9 + DS-14: семантика после LLM-FAILED (retry-шторм POLL) | принять | При исчерпании ретраев: SYSTEM-событие с причиной + `last_consumed_seq := last_seq`; повторная попытка — только новой USER-дописью; счётчики/backoff Turn'ов не заводим (D-35/D-41) |
| J-4 | DS-4 + M-4: конфиг-инвентарь 1.3 неполон | принять | Добавить: `harness.llm.timeout`, `harness.limits.bash-timeout`, `harness.sse.ping-interval`, `harness.lock.job-ttl` |
| J-5 | DS-5: нет задачи ручной сборки DataSource/JPA/Liquibase | принять | Новая задача 1.5 (пул → preliquibase → liquibase → JPA + smoke-тест контекста); design.md Context переписать из «данности» в «задачу M1» |
| J-6 | DS-7: фактические ошибки о pom | принять | preliquibase/testcontainers-postgres/wiremock/awaitility уже в pom — proposal Impact и задача 1.1 переписать по факту; реально добавить: oauth2-resource-server, docker-java (+транспорт), archunit, testcontainers-keycloak |
| J-7 | GLM-2 + DS-10: pull-or-local helper-образа без задачи | принять | Дополнить задачу 6.2: локальный образ приоритетен; pull — backoff-обновление; недоступность registry не фейлит вызов + тест |
| J-8 | DS-2: абсолютная гарантия mutex без fencing | принять (смягчение) | Формулировка спеки: «не более одной активной попытки при нормальной работе; патологическое истечение TTL — принятый риск D-40» |
| J-9 | DS-12: named volume vs host bind-mount | принять | Bind-mount хост-каталога `workspaces/sessions/{sessionId}` (glossary §6); поправить design D-M1-7 и задачу 6.2 |
| J-10 | DS-13: чистка sess-* «по возрасту» опасна | принять | Критерий: `lock_until < now()` (просроченные), не возраст записи |
| J-11 | Миноры-косметика: GLM-3 (ls + имена инструментов), GLM-4/5 + DS-8 (app_user, agent), GLM-6 (MCP → Non-Goals), GLM-7 (D-39), GLM-8 (M3-пометка EVENT), GLM-10 (поле таймаута), GLM-11 (canonical-path), GLM-12 (уточнение 8.4), GLM-13 + DS-9 (кэш llm_model), GLM-14 (SessionDto M1-подмножество), GLM-15 (Sequence), GLM-16 (405), GLM-17 (STATE-фикстура), GLM-18 (late? M3), DS-11 (dangling credentials тест), DS-15 (§0/§6 + retry-константа), DS-16 (альтернатива D-M1-9 + унификация specs-путей) | принять все | Точечные правки proposal/specs/design/tasks |

## Отклонено (снято)

| # | Находка | Обоснование отказа |
|---|---|---|
| R-1 | M-1: конкретные TTL-числа в design.md | Против правила владельца «все числа — конфиг» (AGENTS.md, architecture.md §4); инвариант уже задан (D-M1-4, execution-model §2) |
| R-2 | M-2: конвенции имён индексов + предикат `status != 'TERMINAL'` | Имена индексов — имплементация; предложенный предикат ссылается на несуществующую терминальность сессий (glossary §5: сессии резюмируемы; eligibility = `last_seq > last_consumed_seq`) |
| R-3 | M-3: «FAILED — не исход Turn'а» | Факт-ошибка: data-model.md:148 (`last_turn_outcome: COMPLETED|FAILED|CANCELLED`), execution-model.md §3 п.6 («FAILED — после исчерпания попыток»); LOST — статус TOOL_RESULT |
| R-4 | M-5: lifecycle контейнера (ARCHIVED / смена agentRev) | Триггеры не существуют: архивация вырезана (D-41), kind фиксируется при создании, ревизия агента пинится иммутабельно. Lifecycle M1 определён: ленивое создание, sync-LOST при смерти, orphan-скан рестарта |
| R-5 | M-6: ping-интервал в execution-model | Пинг — контракт API (api-contracts §3.1, session-api spec, D-M1-8); execution-model SSE не описывает. Ключ добавлен в 1.3 по J-4 |
| R-6 | M-7: «путаница» job-lock vs session-TTL | Уже разведено дословно в design.md D-M1-4 и tasks 7.4 |
| R-7 | M-8: «не показано место truncated» | Поле есть в контракте отчёта (workspace-tools spec, agent-tools §5); пример payload не обязателен |
| R-8 | M-9: выбрать базовый образ сейчас | Open Question зафиксировано, точка принятия — задача 6.1, smoke-сборка ловит сразу; контракт инструментов не меняет |
| R-9 | M-10: docker events / периодическая чистка mid-execution | Реанимация M3-механизма против sync-модели M1: смерть контейнера — LOST в момент вызова; после краша — orphan-скан старта (execution-model §1) |
| R-10 | DS-6 (самостоятельная инверсия 4.2→7.1) | Растворено фиксом J-1: допись не использует sess-лок |

## Итог

Принято: 1 blocker + 3 major самостоятельных (J-2, J-3, J-5) + 5 major производных (J-4, J-6, J-7, J-8, J-9/J-10 в minor-ранге) + 17 косметических. Отклонено: 10 (все — Mercury-миноры кроме M-4, и DS-6). Консенсус по существу достигнут: противоречий «ревьюер vs ревьюер» после вердиктов не осталось.
