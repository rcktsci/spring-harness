# Performance & Capacity Design Review: spring-harness

**Целевой масштаб:** 50+ пользователей (не все активны), десятки одновременных задач/сессий, сессии до тысяч сообщений с MB-payload.  
**Инфраструктура:** Один инстанс (VM), Postgres локальный, корпоративный LLM-провайдер (LiteLLM-подобный).  
**Дата:** 2026-09-17

---

## 1. POLL сканы каждые 5 сек

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| Eligible-скан сессий | `execution-model.md` описывает скан уровней: сессии + задачи. В `data-model.md` **нет индекса** на `session.last_activity_at` или `session.locked_at` | **RISK** | Создать индексы: `session(last_activity_at DESC) WHERE archived_at IS NULL`, `session(locked_at) WHERE locked_by IS NOT NULL` |
| Стоимость двухуровневого скана | Задачи сканируются по индексам `(parent_task_id, status_projection)`, но сессии без индексов → полный scan при росте | **RISK** | + Частичные индексы на статусных полях, лимит выборки (batch) |
| Lock-конкуренция ShedLock | `execution-model.md`: `lockAtMostFor` ~10 сек, джоба отпускает сама. В `data-model.md` таблица ShedLock не описана | **GAP** | Документировать таблицу ShedLock, убедиться в индексе на `lock_id` |

---

## 2. `session_message` table: рост, TOAST, пагинация

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| append-only, bloat | `data-model.md`: UPDATE/DELETE запрещены. No vacuum strategy | **GAP** | Настроить autovacuum aggressive для append-only таблиц |
| TOAST при MB payload | `payload_jsonb` без ограничений, `tokens` отдельное поле | **RISK** | Ограничить размер payload (например, 10MB), документировать |
| Индексы для рендера/пагинации | **Только PK `(session_id, seq)`**, индексы на `created_at` или курсорной пагинации нет | **RISK** | Индекс `session_message(session_id, created_at, seq)`, курсор `?since=` использует `seq` из PK |
| Курсорная пагинация | `api-contracts.md`: `?since=<seq>` с полугрупповым интервалом `(since, …]` | **OK** | PK покрывает запрос, если фильтрация только по session_id |

---

## 3. SSE: лимиты соединений, keepalive

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| Кол-во коннектов | 50+ юзеров × 2–3 подписки (session/events + task/events) ≈ 100–150 коннектов | **RISK** | Тестировать Tomcat/Jetty лимиты (maxThreads, acceptCount), мониторить |
| Keepalive-нагрузка | `api-contracts.md`: `ping` каждые 15 сек, `retry: 5000` | **RISK** | Убедиться, что ping — комментарий, не body; при 150 коннектах ≈ 10 ping/sec |

---

## 4. Параллельные Turn'ы против rate-limit провайдера

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| Семафор/очередь | `execution-model.md`: Turn'ы — виртуальные потоки, CAS-лок по сессии | **GAP** | Ввести семафор на concurrent LLM-стримы (например, 20–50), очередевать wake |
| Пик wake (утро) | Нет упоминания планирования пиков | **GAP** | Мониторинг concurrent Turn'ов, адаптивный лимит |

---

## 5. Docker: память, диск, idle-вытеснение

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| Лимиты на контейнер | `execution-model.md`: `cpus=2`, `memory=2g`, `pids-limit=512` | **OK** | Задокументировать в deployment docs |
| Память VM | Нет упоминания общей памяти VM, idle-вытеснение | **GAP** | Установить total memory лимит на helper-контейнеры, например, 70% VM |
| Диск под workspace/git | Нет упоминания лимитов диска | **GAP** | Мониторинг диска, квоты на task-контейнеры (git-клоны) |

---

## 6. Списковые API: N+1, счётчики

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| N+1 при joins | SessionDto содержит `agent {key, rev}` — FK на `agent_revision_id` | **RISK** | Eager loading или JOIN в исходном запросе, не отдельный fetch |
| messageCount, tokensTotal | `api-contracts.md`: есть в SessionDto. В `data-model.md` нет денормализации полей | **GAP** | Либо считать on-the-fly (с индексами), либо денормализовать в `session` таблице |

---

## 7. `idempotency_key`: PK, TTL-чистка

| Аспект | Оценка по докам | Вердикт | Предложение |
|--------|-----------------|---------|-------------|
| PK `(scope, key)` | `data-model.md`: PRIMARY KEY `(scope, key)`, `expires_at` | **OK** | PK сериализует конкурентные запросы |
| TTL-чистка | `data-model.md`: "TTL 24 ч; чистка джобой" | **GAP** | Документировать джобу (периодичность, DELETE batch size) |

---

## Итоговая сводка

| Вердикт | Счёт |
|---------|------|
| OK | 3 |
| RISK | 7 |
| GAP | 7 |

**Top 3 критических риска:**

1. **POLL скан сессий без индексов** — при росте до тысяч сессий full table scan каждые 5 сек перегрузит Postgres.
2. **session_message TOAST/bloat** — MB-payloadы без стратегии vacuum и индексов для пагинации приведут к деградации производительности.
3. **Отсутствие семафора на LLM-стримы** — при пике wake (утро) dozens concurrent стримов превысят rate-limit провайдера и перегрузят VM.

---

## Cross-check

**GLM-5.3-Flash vs Mercury:**
- duplicate: A1, A2, B2, C2, D1, E1, E3, F1, G1 (9)
- agree (my RISK/GAP covers their RISK/GAP): 4, D2, E2, F2, G2 (5)
- disagree (my вердикт ≠ theirs): B1 (I GAP, they OK), C1 (I RISK, they OK) (2)
- stale: none (2)
- not covered: A3, A4, A5, C3, H1-H5 (7)

**DeepSeek-V4.1-Flash vs Mercury:**
- duplicate: P1, P2, P3, P4, P14, P16, P17, P18, P19, P20, P22, P23, P24, P25, P26, P27, P28, P29 (18)
- agree (my RISK/GAP covers their RISK/GAP): P8, P10 (2)
- disagree (my вердикт ≠ theirs): P6 (I GAP, they OK), P7 (I RISK, they OK), P11 (I RISK, they OK) (3)
- stale: none (0)
- not covered: P5, P9, P12, P13, P21 (5)

**Итог disagrees (total 5):**
- GLM B1: append-only bloat — я GAP, они OK
- GLM C1: SSE keepalive/limit — я RISK, они OK
- DeepSeek P6: append-only bloat — я GAP, они OK
- DeepSeek P7: TOAST projection — я RISK, они OK
- DeepSeek P11: SSE лимиты — я RISK, они OK

---

## Fixes approval

| № | Fix | Статус | Вердикт |
|---|-----|--------|---------|
| 1 | Денормализации session (`last_seq`, `last_consumed_seq`, `message_count`, `tokens_total`) + partial-индекс eligible (`last_seq > last_consumed_seq`) | OK | **approve** |
| 2 | Денормализации task (`current_state_kind`, `deadline_at`) + индексы WAIT/deadline | OK | **approve** |
| 3 | Индексы `share(resource_type, resource_id)`, `group_member(user_id)` | OK | **approve** |
| 4 | LLM bulkhead (дефолт 8) + парковка Turn'а при 429/5xx (3 retry) | OK | **approve** |
| 5 | Docker: квота workspace (5 GB), потолок (50), idle-вытеснение (30 мин), reconcile в POLL | OK | **approve** |
| 6 | Retention policy (~1 ТБ/год оценка) — задокументировано | OK | **approve** |
| 7 | Реестр `instance` (heartbeat 10с/порог 30с) + scoped recovery | OK | **approve** |

**Итого: 7/7 approve**
