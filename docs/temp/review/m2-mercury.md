# M2 Workflow Engine Review - Mercury

**Дата:** 2026-09-18  
**Ревьюер:** Mercury-2.5  
**Статус:** REJECT

## Summary

Находок: 4 критических несоответствия, требующих исправления перед заморозкой спеки.

---

## Критические Findings

### 1. [CRITICAL] D-29: идемпотентность POST без хранилища

**Пункт:** proposal.md §18, D-29 (decisions.md), data-model §7.2  
**Цитата:** "Без идемпотентности-хранилища (D-29/D-41): replay-safe POST не требуется; вебхук задачи идемпотентен по построению (`409` вне WAIT_WEBHOOK)."  
**Проблема:** D-29 в decisions.md требует `idempotency_key` таблицы (TTL 24ч) для replay-safe контракта POST. M2-документация противоречит D-29 — заявляет отсутствие хранилища. Это конфликт между D-29 (оригинал) и текущей трактовкой.  
**Предложение:** Либо явно переопределить D-29 (ADR D-59) с обоснованием "для MVP replay-safe не требуется", либо добавить таблицу idempotency_key в миграции (как в D-29).

### 2. [HIGH] D-38: metaTools-гейт без ADR

**Пункт:** design.md D-52, proposal.md §11, spec task-engine §169-182  
**Цитата:** "Гейт metaTools (D-38): разрешён только при `instructionSource = USER`."  
**Проблема:** D-38 уже содержит формулу с instructionSource, но M2 не регистрирует это как отдельное решение (D-52 упоминается в design.md, но нет записи в decisions.md). Формально это не новый ADR, а уточнение D-38 для M2. Однако без явного ADR ревьюеры могут не видеть связь.  
**Предложение:** Добавить в decisions.md запись D-52: "metaTools-гейт в M2: разрешён только при instructionSource=USER; точка входа — USER-сообщение в STATE-сессию."

### 3. [MEDIUM] workflow-domain.md §8: `$CANCELLED` не отражён в api-contracts

**Пункт:** workflow-domain.md §8, api-contracts.md §4.1  
**Цитата:** workflow-domain: "$CANCELLED — зарезервированный псевдо-код вне codes ревизии". api-contracts: "stop → терминал `$CANCELLED` (workflow-domain)"  
**Проблема:** В каталоге ошибок (§6) нет `task-already-terminal` кода для resume CANCELLED-задачи. Он упоминается в spec task-engine, но не в api-contracts §6.  
**Предложение:** Добавить в api-contracts §6: `task-already-terminal` | 409 | resume/stop на терминальной задаче.

### 4. [MEDIUM] tasks.md: пачка 2.1 включает 7 миграций (слишком много за один шаг)

**Пункт:** tasks.md 2.1  
**Цитата:** "Миграции `010..016`..." (workflow, workflow_revision, task, task_dependency, task_comment, task_transition_history, trigger)  
**Проблема:** 7 таблиц + PARTIAL UNIQUE на session (отдельный changelog) — это 8 изменений БД в одной пачке. R1 (risks) говорит "пачки ≤1 подсистемы", но 7 таблиц — это больше, чем одна подсистема.  
**Предложение:** Разделить на: 2.1 (workflow_tables + session PARTIAL UNIQUE), 2.2 (task_tables), 2.3 (trigger).

---

## Проверка согласованности (пункты 1-7 из промпта)

| Пункт | Статус | Примечание |
|-------|--------|------------|
| 1. Артефакты согласованы | ❌ Есть противоречие D-29 |
| 2. api-contracts §4 полностью отражён | ⚠️ Не хватает task-already-terminal в §6 |
| 3. D-47…D-58 без конфликтов с D-01…D-46 | ⚠️ D-29 конфликтует с заявленным отсутствием idempotency-хранилища |
| 4. workflow-domain.md §1-§8 покрыт в specs | ✅ |
| 5. data-model §3-§5 точно отражено в tasks 2.1 | ✅ |
| 6. Tasks — пачки ≤1 подсистемы | ❌ 2.1 содержит 7 таблиц |
| 7. Риски 1-9 реалистичны | ✅ |

---

## Recommendations

1. **Срочно:** Уточнить D-29 трактовку — либо добавить idempotency_key таблицу, либо явно переопределить в ADR.
2. **Перед заморозкой:** Добавить task-already-terminal в api-contracts §6 каталог ошибок.
3. **Опционально:** Разделить пачку 2.1 на 2-3 меньшие пачки для более чёткого следования правилу "≤1 подсистема".

---

## Final Decision

**REJECT (4 находки)**

Требует исправлений критических противоречий перед заморозкой спеки.

## Re-approval

**Дата:** 2026-09-18  
**Статус:** APPROVED (после проверки fixed версий)  
**Закрытие находок:**
1.  D-29 (idempotency): R-1 — принято (idempotency POST без хранилища, как в D-41).
2.  metaTools-гейт: D-52 → D-59 — ADR добавлен в decisions.md (D-59: metaTools-гейт instructionSource=USER).
3.  task-already-terminal: уже есть в api-contracts §6 (строка 126) и task-engine spec (строки 57, 71, 81) — ошибка ревьюера.
4.  Батч H (7 миграций): принят как риск без разделения (согласовано в J-31).

**Итого:** 0 незакрытых находок.

## Cross-check

**Проверка чужих находок (GLM + DeepSeek) после fix-цикла J-1…J-31:**

| Находка | Ревьюер | Статус | Обоснование |
|---------|---------|--------|-------------|
| **N-1: sessions/tree POST vs GET** | GLM N-3, DS N-1 | ✅ **ЗАКРЫТО** | session-api delta изменена на `GET /api/v1/sessions/{id}/tree` — согласовано с api-contracts §2. |
| **M-6: task_event_seq без носителя** | DS M-6 | ✅ **ЗАКРЫТО** | data-model §4: добавлена колонка `task_event_seq` (bigint, default 0) в таблицу `task` — durable-носитель для SSE cursor. |
| **L-3: конфиги в proposal Impact** | GLM m6, DS L-3 | ✅ **ЗАКРЫТО** | proposal §39 включает `max-per-turn`, `kind-timeouts`, `webhook.base-url`. |
| **L-1: transition в agent-tools.md** | DS L-1 | ✅ **ЗАКРЫТО** | agent-tools.md синхронизирован с единой сигнатурой `transition(taskId, toState, kind, reason)`. |
| **M-8: индекс task_transition_history** | DS M-8 | ✅ **ЗАКРЫТО** | data-model §4: индекс приведён к `(task_id, created_at, id)` для согласованности с курсором `(created_at, id)`. |
| **N-3: ссылки на D-38** | DS N-3 | ✅ **ЗАКРЫТО** | proposal §11, design Goals (13): теперь ссылаются на D-38/D-59 (supersession D-41). |
| **M-2: architecture.md контракты** | DS M-2 | ✅ **ЗАКРЫТО** | architecture.md §3 обновлён: `TaskEngine` и `TriggerRegistry` явно включены в контракты модулей. |

**Итого cross-check:** 0 критических + 7 закрытых синк-проблем.

**Общий вердикт после cross-check 2:** **APPROVE**.

## Re-approval 2

**Дата:** 2026-09-18  
**Статус:** **APPROVE**  
**Проверка:** Все находки первого ревью (4 от Mercury) + все находки cross-check (7 от GLM/DS) — закрыты.

| Категория | Ключевые фиксы |
|-----------|----------------|
| D-29 idempotency | Принято: replay-safe POST без хранилища (D-41) |
| metaTools-гейт | D-59 в decisions.md (supersede D-41 в M2) |
| task-already-terminal | Уже в api-contracts §6, task-engine spec |
| Батч H (7 миграций) | Принят как риск без разделения |
| sessions/tree POST→GET | session-api delta исправлен |
| task_event_seq | Добавлена колонка в data-model §4 |
| Конфиги в proposal | max-per-turn, webhook.base-url, kind-timeouts включены |
| transition signature | agent-tools.md синхронизирован |
| Индексы data-model | task_dependency(blocked_task_id), task_event_seq учтены |
| D-38→D-59 | proposal/design обновлены |
| architecture.md контракты | TaskEngine, TriggerRegistry включены |

**Итого:** 0 незакрытых находок. Чендж готов к заморозке и apply-фазе.


