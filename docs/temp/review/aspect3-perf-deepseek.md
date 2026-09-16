# Aspect 3: производительность и ёмкость дизайн-базиса spring-harness

> Ревьюер: DeepSeek-V4.1-Flash. Дата: 2026-09-17.
> Предмет: `docs/design/{data-model,execution-model,api-contracts,architecture}.md` (+ `workflow-domain.md`, `decisions.md`, `glossary.md` как контекст).
> Артефакт промежуточный (`docs/temp/`), не коммитится.

## 0. Целевой масштаб и метод

**Масштаб (из задания):** 50+ пользователей (не все активны), десятки одновременных задач/сессий, сессии до тысяч сообщений с MB-payload; один инстанс (VM), Postgres локально, LLM-провайдер корпоративный (LiteLLM-подобный).

**Рабочая модель ёмкости** (используется во всех прикидках ниже; это inference, не факт из доков):

| Параметр | Обозн. | Значение |
|---|---|---|
| Зарегистрированные / активные | U / Ua | 50+ / 20–30 |
| Одновременные сессии | S | 30–60 (задачи + подзадачи + субагенты) |
| Одновременные Turn'ы | T | 20–40 |
| Одновременные LLM-стримы (с субагентами) | L | 60–120 |
| Сообщений в сессии | N | 1·10³–5·10³ |
| Размер payload | P | 0.2–1 MB (пики выше) |
| Всего session_message | M | 5·10⁴–6·10⁶ строк; 50–250 GB |
| Poll-тиков/сутки | — | 17 280 (раз в 5 с) |

**Легенда вердиктов:**
- **OK** — по докам покрытие есть, на целевом масштабе укладывается (число — оценка).
- **RISK** — покрытие формально есть, но ломается на целевом масштабе/в конкретном failure mode (число — оценка).
- **GAP** — покрытия нет: ни поля, ни индекса, ни механизма в доках.

**Дисциплина разбора:** отделяю `Факт` (ссылка на доку/строку) от `Оценка` (моя прикидка) и `Вывод`. Для ревью это критично: риск без конкретного failure mode не засчитываю.

### Сводная таблица

| # | Вопрос | Вердикт |
|---|---|---|
| P1 | POLL: поле-маркер «есть непоглощённые события» | **GAP** |
| P2 | POLL: индекс AGENT-задач без сессии (`current_state_kind`) | **RISK** |
| P3 | POLL: глобальный скан WAIT_* (`status_projection` вне составного индекса) | **RISK** |
| P4 | POLL: вычисление timeout'ов WAIT_*/BASH (`deadline_at`/`state_entered_at`) | **GAP** |
| P5 | ShedLock: конкуренция за строку джобы | **OK** |
| P6 | session_message: bloat/vacuum append-only | **OK** |
| P7 | TOAST + лёгкая проекция при рендере | **OK** |
| P8 | Индексы запроса рендера (PK + `INCLUDE`) | **OK** |
| P9 | Курсорная пагинация списков (sessions/tasks: sort/filter) | **GAP** |
| P10 | HOT-цепочки от heartbeat `session.locked_at` | **OK** |
| P11 | SSE: лимиты соединений/потоков, keepalive | **OK** |
| P12 | SSE: backpressure на медленного клиента | **RISK** |
| P13 | SSE: механизм fan-out и мульти-инстанс | **GAP** |
| P14 | Параллельные Turn'ы: семафор/очередь к провайдеру | **RISK** |
| P15 | 429/5xx провайдера → FAILED без retry/backoff | **RISK** |
| P16 | Docker: агрегатная память VM, потолок числа контейнеров | **RISK** |
| P17 | Docker: idle-вытеснение helper-контейнеров | **RISK** |
| P18 | Диск: workspace-тома и git-клоны, quota/GC | **GAP** |
| P19 | Счётчики SessionDto (`messageCount`, `tokensTotal`, `lastSeq`) | **GAP** |
| P20 | N+1 на списках (agent/workflow/owner) | **GAP** |
| P21 | Индексы AccessPolicy (`share` resource-side, `group_member.user_id`) | **GAP** |
| P22 | idempotency_key: точечные PK-вставки, конкурентный повтор | **OK** |
| P23 | idempotency_key: TTL-чистка (индекс `expires_at`, батчи) | **RISK** |
| P24 | `task_transition_history`: курсор `id` vs индекс `(task_id, created_at)` | **GAP** |
| P25 | Дерево сессий: индекс `session.parent_session_id` | **GAP** |
| P26 | `GET /export` MB-сессии: потоковость | **GAP** |
| P27 | Пул соединений к БД: размер не задан | **GAP** |
| P28 | Бэкап/IO: конкуренция БД и docker-томов на одном диске | **GAP** |
| P29 | JVM-heap под сборку контекста (MB × параллельные Turn'ы) | **RISK** |

**Счёт: OK = 7, RISK = 9, GAP = 13 (всего 29).**

### Топ-3

1. **LLM-параллелизм без ограничителя** (P14+P15). Утренний пик: L ≈ 60–120 одновременных стримов при отсутствии семафора; `429` провайдера по `execution-model.md:64` (условие 6) даёт `FAILED` задачи без retry. Это самый вероятный массовый инцидент.
2. **Списковые API на MB-данных** (P19+P20+P21). `SessionDto` требует `messageCount`, `tokensTotal`, `lastSeq`, но в `session` нет денормализованных колонок — на каждый элемент страницы идёт агрегат по `session_message`; плюс N+1 по `agent`/`workflow`/`owner` и неиндексируемые проверки `AccessPolicy`.
3. **POLL-скан непроектируем по текущей схеме** (P1+P2+P3+P4). Нет поля «есть непоглощённые события», нет `current_state_kind`, нет `deadline_at`; заявление `decisions.md D-33` «дёшево индексируемыми сканами» схемой не подтверждено.

Runner-up: P16/P18 (память VM и диск под контейнеры) — риск деградации/падения всего инстанса, а не отдельной задачи.

---

## 1. POLL каждые 5 сек

### P1. Точная форма eligible-скана и индекс — **GAP**

**Вопрос:** какой запрос делает index-only scan eligible-сессий?

**Факт:**
- Eligibility (`execution-model.md:29`): «есть события после конца последнего модельного хода И сессия не терминальна И задача не suspended И лок свободен или стух».
- В `session` (`data-model.md:165-187`) есть `locked_by`, `locked_at`, `cancel_requested`, `last_turn_outcome`, `last_activity_at`, `archived_at` — **но нет** ни `last_rendered_seq` / `turn_end_seq` / `last_consumed_seq`, ни `pending_events` / `runtime_status`.
- Единственный индекс на `session` — PARTIAL UNIQUE `(task_id, state_code) WHERE kind='STATE'` (`data-model.md:189`).

**Оценка (прикидка):** предикат «есть события после конца последнего модельного хода» невозможно выразить по колонкам `session` — нужен доступ к `session_message`. Кандидатный запрос:

```sql
SELECT s.id FROM session s
WHERE s.archived_at IS NULL
  AND (s.locked_by IS NULL OR s.locked_at < now() - interval '60 seconds')
  AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id = s.task_id AND t.suspended)
  AND EXISTS (SELECT 1 FROM session_message m
              WHERE m.session_id = s.id AND m.seq > /* неизвестное поле */ );
```

- `locked_by IS NULL` — **нормальное состояние почти всех покоящихся сессий**, поэтому этот предикат отсеивает почти ничего. При S = 2000 неархивных сессий получаем ~2000 проб «хвоста» `session_message` на каждый тик.
- Проба хвоста по PK `(session_id, seq)` DESC LIMIT 1 ≈ 2–4 buffer hits. Итого 2000 × 3 × 17 280 ≈ **1·10⁸ buffer hits/сутки ≈ 1200/s** — терпимо по CPU, но это **не index-only scan** и не то, что описано в `D-33`.
- Хуже, если реализация «в лоб» соединит `session` со `session_message` без маркера: на M = 10⁶ строк это полный проход `session_message` каждые 5 с.

**Вердикт: GAP.** Утверждение «дёшево индексируемыми сканами» (`decisions.md:39`, `execution-model.md:15`) не обеспечено схемой.

**Предложение:** денормализовать на `session` счётчик непоглощённых событий, обновляемый транзакционно на append и на закрытии Turn'а:

```sql
ALTER TABLE session ADD COLUMN unconsumed_events int NOT NULL DEFAULT 0;
CREATE INDEX idx_session_eligible
  ON session (last_activity_at)
  WHERE unconsumed_events > 0 AND archived_at IS NULL AND cancelled_at IS NULL;
```

Тогда eligible-скан — index-only по частичному индексу; при S_eligible ≈ 5–20 (реально «горячих» сессий) стоимость падает до ~единиц операций на тик. Инкремент/декремент делать в той же транзакции, что INSERT `session_message` / CAS закрытия Turn'а (append-only инвариант не нарушается).

---

### P2. Двухуровневый скан, уровень задач: AGENT-состояние «без живой сессии» — **RISK**

**Факт:**
- `execution-model.md:15`: «*задачи* (бессессионные инварианты, **дёшево по индексам**): (а) задача в AGENT-состоянии без живой сессии → bootstrap STATE-сессии».
- `task` (`data-model.md:114-132`) не имеет поля «тип текущего состояния». Тип состояния (`AGENT | BASH_SCRIPT | WAIT_WEBHOOK | WAIT_TASKS | TERMINAL`) — часть `workflow_revision.graph_jsonb` (`workflow-domain.md:16,40-46`).
- Индексы `task`: `(parent_task_id, status_projection)` и GIN `(tags)`. Индекса по `current_state` или `status_projection` в одиночку нет.

**Оценка (прикидка):** запрос вида «AGENT-задача без сессии» требует для каждой `RUNNING`-задачи достать `graph_jsonb`, распарсить и классифицировать `current_state`. Это seq scan `task` + jsonb-parse на каждую строку, **не** индексный скан. При T_running ≈ 100–1000 задач × 17 280 тиков = 1.7·10⁶–1.7·10⁷ jsonb-парсингов/сутки. На «десятках» задач это терпимо; формулировка «дёшево по индексам» — неверна, масштабируется линейно и упирается в CPU/парсинг.

**Вердикт: RISK (низкий, но растёт линейно).**

**Предложение:** денормализовать `current_state_kind` на `task`, заполнять при CAS-переходе из ревизии (тип состояния известен в момент перехода):

```sql
ALTER TABLE task ADD COLUMN current_state_kind text;   -- AGENT|BASH_SCRIPT|WAIT_WEBHOOK|WAIT_TASKS|TERMINAL
CREATE INDEX idx_task_agent_no_session
  ON task (current_state_kind) WHERE status_projection = 'RUNNING';
```

---

### P3. Уровень задач: переоценка WAIT_TASKS / WAIT_WEBHOOK — **RISK**

**Факт:**
- `execution-model.md:15(б)`: «задачи в WAIT_TASKS / WAIT_WEBHOOK → переоценка условий».
- `task.status_projection` — enum RUNNING|WAITING|SUCCEEDED|FAILED|CANCELLED (`data-model.md:124`).
- Единственный индекс, где `status_projection` участвует — второй колонкой: `(parent_task_id, status_projection)` (`data-model.md:132`). Для глобального `WHERE status_projection='WAITING'` он **неприменим** (ведущая колонка `parent_task_id`).
- `workflow-domain.md:45` указывает те же три индекса как источник поиска «кто ждёт» — но они обслуживают **обратный** поиск (от родителя/блокера/тега), а не глобальный скан ожидающих.

**Оценка (прикидка):** глобальный скан «все WAITING» = seq scan `task`. При T = 10⁴ строк × 17 280 тиков = 1.7·10⁸ row-visits/сутки; ~2·10³ row-visits/s и ~17 GB логического чтения/сутки (при ~100 B/строку). Формально переживаемо, но каждый тик линейно зависит от размера таблицы задач.

**Вердикт: RISK.**

**Предложение:** частичный индекс:

```sql
CREATE INDEX idx_task_waiting ON task (current_state_kind, updated_at)
  WHERE status_projection = 'WAITING';
```

---

### P4. Timeout'ы WAIT_* и BASH_SCRIPT: нет `deadline_at` — **GAP**

**Факт:**
- `workflow-domain.md:24,35,44-45`: `timeout` обязателен для BASH_SCRIPT, WAIT_WEBHOOK, WAIT_TASKS; по таймауту — переход `TIMEOUT`.
- В `task` (`data-model.md:114-130`) есть `created_at`/`updated_at`, но **нет** `state_entered_at` / `deadline_at` / `state_deadline`.
- `updated_at` не годится: меняется и от `PATCH` title/description/tags, и не отражает «вход в конкретное состояние».

**Оценка (прикидка):** без deadline-колонки вычисление «какие WAIT_* пора завершить по таймауту» невозможно сделать индексно; остаётся тот же seq scan ожидающих задач с парсингом `timeout` из `graph_jsonb` и сверкой с временем входа в состояние (которого нет). То есть timeout-механика из `workflow-domain.md` **не может быть реализована эффективно по текущей схеме**, и даже корректно — без времени входа в состояние.

**Вердикт: GAP (блокирующий для timeout-семантики).**

**Предложение:** `state_entered_at timestamptz` (обновляется в той же транзакции, что CAS `current_state`) и вычисляемый/записываемый `state_deadline_at`:

```sql
ALTER TABLE task ADD COLUMN state_entered_at timestamptz;
ALTER TABLE task ADD COLUMN state_deadline_at timestamptz;  -- state_entered_at + timeout
CREATE INDEX idx_task_deadline ON task (state_deadline_at)
  WHERE status_projection = 'WAITING' AND state_deadline_at IS NOT NULL;
```

Тогда POLL-часть «timeout» — index-only по `state_deadline_at < now()`.

---

### P5. ShedLock: конкуренция за строку — **OK**

**Факт:** `execution-model.md:13,33`: ShedLock-джоба раз в ~5 с, `lockAtMostFor` ~10 с, отпускает сама; ShedLock 7.10.1 jdbc-template (`architecture.md:48`). Инстанс один (MVP), горизонтальный запуск объявлен безопасным (`execution-model.md:99-100`).

**Оценка (прикидка):** один ряд таблицы `shedlock` (job `harness-poll`), ~17 280 UPDATE/сутки = **0.2 записи/с**. Даже при 3 инстансах конкуренция за одну строку пренебрежима. `lockAtMostFor=10s > interval=5s`: при превышении 10 с другой инстанс может войти в параллельный poll — но перекрытие идемпотентно (CAS-локи сессий), поэтому это не коррапт, а лишняя работа.

**Вердикт: OK.** Оговорка: бюджет длительности poll'а не зафиксирован — при P1/P2/P3 «в лоб» он может приблизиться к 10 с.

**Предложение:** зафиксировать SLO: «poll < 1 с при S=2000, T=10⁴»; метрика длительности джобы + алерт при > 2 с. `lockAtMostFor` оставить, но добавить warning, если run > interval.

---

## 2. session_message: рост, bloat, TOAST, рендер, пагинация

### P6. Append-only: vacuum/bloat — **OK**

**Факт:** `session_message` append-only, UPDATE запрещён (`data-model.md:191,235`); PK `(session_id, seq)`; `id` — монотонный ULID (`data-model.md:196`).

**Оценка (прикидка):** dead tuples образуются только при откате транзакций (ошибка LLM и т.п.), массовых UPDATE нет → HOT-цепочек нет. Вставки монотонны по `seq` и по ULID, поэтому B-tree растут «вправо» — фрагментации/блота почти нет. При 10⁴–10⁵ новых сообщений/сутки autovacuum справляется с запасом; критично лишь поддержание visibility map для index-only сканов (см. P8) — на PG 13+ insert-триггерный autovacuum это закрывает.

**Вердикт: OK.** Небольшая оговорка: точная версия PostgreSQL в `architecture.md` не зафиксирована — для insert-порогов вакуума она важна.

**Предложение:** зафиксировать требуемую версию PG (≥16) в `architecture.md`; на `session_message` выставить `autovacuum_vacuum_insert_threshold` заметно ниже дефолта, чтобы visibility map не отставал на больших партициях.

---

### P7. TOAST и лёгкая проекция — **OK**

**Факт:** `execution-model.md:76`: «Один проход: лёгкая проекция (без payload) → SET покрытий → полные payload только видимых». `payload_jsonb` — `jsonb` (`data-model.md:199`); для COMPACT внутри payload лежит `covers`.

**Оценка (прикидка):** payload'ы > ~2 КБ уходят в TOAST out-of-line, Postgres читает TOAST-значение лениво — только если атрибут реально нужен. Поэтому `SELECT id, seq, kind, tokens FROM session_message WHERE session_id=? ORDER BY seq` **не тянет** TOAST-страницы. Единственная тонкость: `covers` живёт внутри `payload_jsonb`, поэтому для вычисления «SET покрытий» придётся вычитать payload'ы COMPACT'ов — но их число мало (≈1 на окно контекста), стоимость пренебрежима. Ограничение jsonb ~1 ГБ на значение тут не достигается (MB).

**Вердикт: OK.**

**Предложение:** явно записать в `execution-model.md §5`, что `covers` — исключение из правила «payload только для видимых», и что COMPACT-события читаются с payload всегда (их мало). Опционально — вынести `covers` в отдельную колонку/таблицу, чтобы «SET покрытий» был полностью без payload.

---

### P8. Индексы запроса рендера — **OK**

**Факт:** PK `(session_id, seq)` (`data-model.md:203`); «шапка + дельта между раундами», «стабильный префикс = prompt-cache» (`execution-model.md:76`).

**Оценка (прикидка):** лёгкая проекция `ORDER BY seq` обслуживается PK (index scan по `(session_id, seq)`), но это **не** index-only, пока `kind`/`tokens` не в индексе → на каждую строку heap-fetch. При N = 5000 сообщений и S = 40 → 2·10⁵ heap-fetch на построение шапки. Вторая фаза (payload видимых) — точечные PK-выборки `seq = ANY(...)` / range. Обе формы индексированы.

**Вердикт: OK**, с рекомендацией сделать шапку index-only:

```sql
CREATE INDEX idx_session_message_projection
  ON session_message (session_id, seq) INCLUDE (kind, tokens);
```

Это делает первую фазу index-only на «all-visible» страницах (после P6-настройки вакуума) и не тянет payload.

**Предложение:** добавить include-индекс; зафиксировать, что `rewind_seq`/`fork_seq_cutoff` фильтруются по `seq` (PK), а `includeHidden=false` не требует доп. индекса.

---

### P9. Курсорная пагинация списков — **GAP**

**Факт:**
- `api-contracts.md:11,46`: `GET /sessions` — фильтры `?folder=&mine=&kind=&q=&archived=&cursor=&limit=`, сортировка `lastActivityAt desc`; конверт `{items,nextCursor}`.
- В `session` (`data-model.md:165-187`) индексов по `last_activity_at`, `owner_user_id`, `kind`, `archived_at`, `folder_id` **нет**; триграммного индекса по `title` для `?q=` нет.
- `GET /tasks` (`api-contracts.md:86`): `?parent=&status=&mine=&tags=&q=` — `parent`/`tags` покрыты, `status`/`mine`/`q` — нет (`data-model.md:132`).

**Оценка (прикидка):** при S = 2000 сессий один seq scan + sort 2000 строк — субмиллисекунды, но: (а) сортировка по `lastActivityAt desc` без индекса делает top-N сортировку всей выборки на каждый листающий запрос; (б) с учётом union'а «мои + расшаренные» (P21) растёт база; (в) `ILIKE '%q%'` по title без trigram — seq scan с вычислением подстроки. На текущем масштабе больно не будет, но каждый листинг линейно зависит от размера `session`.

**Вердикт: GAP (нет документированных индексов под курсорные списки).**

**Предложение:**
```sql
CREATE INDEX idx_session_owner_activity ON session (owner_user_id, last_activity_at DESC)
  WHERE archived_at IS NULL;
CREATE INDEX idx_session_folder_activity ON session (folder_id, last_activity_at DESC)
  WHERE archived_at IS NULL;
CREATE INDEX idx_session_title_trgm ON session USING gin (title gin_trgm_ops);
CREATE INDEX idx_task_status ON task (status_projection);
```
Курсор — композитный ключ `(last_activity_at, id)` (keyset-пагинация), а не offset; зафиксировать это в `api-contracts.md` явно.

---

### P10. HOT-цепочки от heartbeat `locked_at` — **OK**

**Факт:** `execution-model.md:33`: Turn продлевает `locked_at` heartbeat'ом каждые 30 с; `session` — UPDATE допускается (`data-model.md:178-179`).

**Оценка (прикидка):** 40 активных Turn'ов × 2 UPDATE/мин = 80 обновлений/мин ≈ **1.3/с** на маленькой таблице `session`. HOT-update проходит в ту же страницу при дефолтном fillfactor, autovacuum успевает. Dead tuples малы.

**Вердикт: OK.**

**Предложение:** при желании — `fillfactor=80` на `session` и явный `autovacuum_vacuum_scale_factor` поменьше, но это микрооптимизация.

---

## 3. SSE-потоки

### P11. Соединения/потоки сервера, keepalive — **OK**

**Факт:** `api-contracts.md:75`: `ping` каждые 15 с, `retry: 5000`; `:53` — до 1 подписки на сессию, ticket. Java 25 + виртуальные потоки (`architecture.md:46`), JDK 25 без пиннинга (JEP 491, `architecture.md:49`).

**Оценка (прикидка):** 50 пользователей × 2–3 подписки = **100–150 одновременных SSE**. При `SseEmitter` open-соединение не держит платформенный поток (async servlet); с виртуальными потоками запас огромен. Keepalive: 150 / 15 с = **10 сообщений/с** суммарно — фоновая нагрузка пренебрежима. Tomcat default max-connections 8192 — с запасом. Память на emitter — единицы КБ, т.е. < 1 МБ на всех.

**Вердикт: OK.**

**Предложение:** зафиксировать в конфиге `spring.mvc.async.request-timeout` и лимит числа подписок на пользователя (rate-limit билетов уже заявлен, `api-contracts.md:17`).

---

### P12. Backpressure на медленного клиента — **RISK**

**Факт:** контракт SSE (`api-contracts.md:68-78`) не описывает поведение при нечитающем клиенте; событие `message.created` несёт `MessageDto` (payload MB).

**Оценка (прикидка):** `SseEmitter`/`ResponseBodyEmitter` буферизует записи; при остановившемся клиенте буфер растёт неограниченно. Если один такой клиент подписан на «горячую» сессию с MB-событиями, а сообщений в минуту десятки — рост буфера десятки–сотни МБ на подписчика; несколько таких → OOM всего инстанса (единая JVM). Это конкретный failure mode, не абстракция.

**Вердикт: RISK (высокий по последствию — падает весь сервер, не одна сессия).**

**Предложение:** ограниченная очередь на emitter по объёму/числу событий; при переполнении — `complete()`/разрыв с последующим реконнектом (контракт уже умеет восстанавливаться снапшотом + `Last-Event-ID`, `api-contracts.md:74`). Для MB-событий — не пушить payload целиком, а сигналить `{seq}` и давать клиенту добрать через `GET /messages?since=` (вариант: бинарный фрейм/усечение). Явно зафиксировать политику в `api-contracts.md §3`.

---

### P13. Механизм fan-out и мульти-инстанс — **GAP**

**Факт:** `execution-model.md:99-100`: «Один инстанс MVP… локи в БД делают безопасным горизонтальный запуск нескольких инстансов». Контракт SSE/SseEmitter не описывает, **как** событие доезжает до подписки (in-process шина? поллинг БД?).

**Оценка (прикидка):** при in-process fan-out (наиболее вероятная реализация) событие, порождённое Turn'ом на инстансе A, не дойдёт до SSE-подписчика на инстансе B. Заявление о безопасности горизонтального запуска верно для **исполнения** (CAS-локи), но не для **доставки SSE**. Для MVP (один инстанс) это не проявляется; при масштабировании — тихая потеря событий.

**Вердикт: GAP (механизм не определён; мульти-инстанс-claim не покрыт для доставки).**

**Предложение:** явно зафиксировать: MVP — in-process шина + ограничение «SSE только на том же инстансе», либо fan-out через `LISTEN/NOTIFY` (дёшево, Postgres уже есть) / локальную шину. Добавить в `architecture.md §5` как точку эволюции.

---

## 4. Параллельные Turn'ы против rate-limit провайдера

### P14. Нет семафора/очереди к провайдеру — **RISK**

**Факт:** `execution-model.md:99`: «все Turn'ы — виртуальные потоки процесса»; wake EVENT вызывает `tryStart` немедленно (`execution-model.md:7-11`); `spawn_subagent` синхронный, дочерние сессии — отдельные Turn'ы (`execution-model.md:71`). Ограничителя конкурентности в доках нет.

**Оценка (прикидка):** утренний пик (все пришли к 9:00): 30 задач × (1 + 2 субагента) → **L ≈ 60–120 одновременных LLM-стримов**. У корпоративного LiteLLM обычно per-key concurrency и/или TPM-лимиты (типовые значения 10–50 одновременных). Превышение → `429`, а по `execution-model.md:64` ошибка LLM = `FAILED` задачи. То есть пик валит задачи, а не просто замедляет. Дополнительно: 120 Turn'ов × контекст (P29) упирается в heap.

**Вердикт: RISK (высокий).**

**Предложение:** ввести глобальный/per-credentials `Semaphore` (permits = согласованная concurrency провайдера) вокруг `LlmGateway.stream`; ожидающие Turn'ы паркуются (условие №2/парковка уже есть), чтобы не жечь потоки и контекст. Метрика `llm_in_flight` + очередь; конфигурируемо. Плюс отдельный лимит на LLM-вызовы компакции.

---

### P15. `429`/`5xx` → `FAILED` без retry/backoff — **RISK**

**Факт:** `execution-model.md:64` (условие 6): «Ошибка LLM → статус FAILED, выход». `architecture.md:49`: регрессия Spring AI 2.0.1 (#6915) — «в каждом `OpenAiChatOptions` задавать `.timeout()`/`.maxRetries()` явно».

**Оценка (прикидка):** transient-ошибки провайдера (429, 502/503, таймауты) трактуются как терминальный провал задачи. При пиковых 60–120 стримах доля 429 высока; без retry каждая такая ошибка = потерянная задача/сессия и ручной перезапуск. `maxRetries` явно требуется, но в доках не зафиксирована политика (сколько, с каким backoff, что считать retryable).

**Вердикт: RISK (высокий, усиливает P14).**

**Предложение:** классификация ошибок в `LlmGateway`: retryable (429/5xx/timeout/network) → экспоненциальный backoff + jitter, ограниченное число попыток, затем **парковка + wake** (не `FAILED`); non-retryable (4xx кроме 429, schema) → `FAILED`. Учесть `Retry-After`. Зафиксировать политику в `execution-model.md §3`.

---

## 5. Docker: память, idle, диск

### P16. Агрегатная память VM и потолок контейнеров — **RISK**

**Факт:** `execution-model.md:70`: лимиты по умолчанию `cpus=2`, `memory=2g`, `pids-limit=512`; helper-контейнер на сессию + `harness-task-<taskId>` на задачу; образ собран локально (`architecture.md:50`). Размер VM и суммарный бюджет памяти в доках **не заданы**.

**Оценка (прикидка):** Docker memory-limit — жёсткий cap, но не резервация; при переподписке OOM-killer может убить любой процесс хоста, включая JVM оркестратора и Postgres. Сценарий: 5–10 одновременных git-клонов/сборок, каждая до 2 ГБ → 10–20 ГБ разом. Idle helper (минимальная ОС + coreutils) ~20–40 МБ × 40 контейнеров ≈ 1–1.6 ГБ — безопасно; опасен именно пик «тяжёлых» контейнеров. При VM 16 ГБ (JVM 4–6 ГБ + PG shared_buffers 2–4 ГБ) запас на контейнеры ≤ 6–8 ГБ → 3–4 «тяжёлых» контейнера одновременно.

**Вердикт: RISK (последствие — падение всего инстанса).**

**Предложение:** (1) зафиксировать размер VM и бюджет: `JVM heap + PG + Σ container caps ≤ 0.8 × RAM`; (2) ограничить число **одновременных** тяжёлых контейнеров (семафор на `BASH_SCRIPT`/git-состояния, а не только на LLM); (3) задать `--memory-swap` = memory (запрет свопа) и `--oom-score-adj` для оркестратора; (4) вынести отдельный лимит на `pids` уже есть.

---

### P17. Idle-вытеснение контейнеров — **RISK**

**Факт:** `execution-model.md:70`: lifecycle контейнера = lifecycle сессии/задачи; удаление — при архивации/чистке; ленивое создание при первом вызове. Политики вытеснения по idle в доках нет. Рестарт-скан удаляет только **осиротевшие** контейнеры (`execution-model.md:22`).

**Оценка (прикидка):** сессии не удаляются и архивируются не сразу (`api-contracts.md:49`), значит со временем на VM накапливаются десятки–сотни helper-контейнеров. Даже idle по 20–40 МБ: 200 контейнеров ≈ 4–8 ГБ + сущности Docker (сети, слои). Плюс `docker ps`/event-мониторинг деградирует.

**Вердикт: RISK.**

**Предложение:** TTL простоя (например, остановка/удаление контейнера после N минут без вызовов, с пересозданием при следующем вызове — создание лениво уже заложено); периодический `docker container prune` по метке сессии для неактивных; метрика `helper_containers_total`.

---

### P18. Диск под workspace-тома и git-клоны — **GAP**

**Факт:** workspace монтируется томом (`execution-model.md:70`), клонирование — bash-состояние подготовки (`decisions.md D-11`). Квот/GC для томов и клонов в доках нет; очистка — «при архивации/чистке».

**Оценка (прикидка):** git-клон монорепы 1–5 ГБ × десятки сессий = **десятки–сотни ГБ**; при отсутствии quota и диска под БД (50–250 ГБ, P0) на той же VM один неудачный клон/сборка заполняет ФС → падают и Postgres, и Docker. Это классический «диск переполнен» инцидент.

**Вердикт: GAP.**

**Предложение:** квота на workspace-том (`--storage-opt size=` для overlay/xfs pquota) либо soft-quota через периодический `du` + алерт; GC по TTL/архивации; метрика free disk с алертом на 80 %; вынести docker data-root и PG на разные ФС/диски, если возможно.

---

## 6. Списковые API: N+1 и счётчики

### P19. Счётчики SessionDto (`messageCount`, `tokensTotal`, `lastSeq`) — **GAP**

**Факт:**
- `api-contracts.md:62`: `SessionDto` содержит `lastSeq, messageCount, tokensTotal`.
- В `session` (`data-model.md:165-187`) **нет** колонок `message_count` / `tokens_total` / `last_seq`; они нигде не денормализованы (нет и счётчиков в `session_message`).
- «Денормализация» по документам есть только для `task.status_projection` (`data-model.md:124`).

**Оценка (прикидка):** значит счётчики вычисляются по `session_message` на каждую сессию. `count(*)` может быть index-only по PK, но `sum(tokens)` требует чтения колонки `tokens` из heap (в индексе её нет) → N heap-fetch на сессию. Страница `GET /sessions` = 50 сессий; при N = 5000 → **2.5·10⁵ heap-tuple fetch на страницу**. С MB-payload'ами часть страниц «холодные» → это десятки–сотни мс, а на больших N — секунды. И это на каждый листинг. `lastSeq` — та же проблема (`max(seq)`).

**Вердикт: GAP.**

**Предложение (предпочтительно — денормализация):**
```sql
ALTER TABLE session ADD COLUMN message_count int NOT NULL DEFAULT 0;
ALTER TABLE session ADD COLUMN tokens_total  bigint NOT NULL DEFAULT 0;
ALTER TABLE session ADD COLUMN last_seq      bigint NOT NULL DEFAULT 0;
```
Инкремент в той же транзакции, что INSERT в `session_message` (append-only не нарушается: `session_message` не мутируется, мутируется `session`). Альтернатива без записи в `session` — покрывающий индекс `session_message(session_id, seq) INCLUDE (tokens)` (счётчик через index-only), но `count/sum` на каждый запрос всё равно линейны по N, тогда как денормализация — O(1). Для fork/rewind/архивации пересчёт счётчиков — при командных операциях, не в горячем пути.

---

### P20. N+1 на списках — **GAP**

**Факт:**
- `SessionDto` (`api-contracts.md:62`) требует `owner` (username), `agent {key, rev}`.
- `TaskDto` (`api-contracts.md:98`) требует `workflow {key, rev}`.
- Схема соединений: `session.agent_revision_id → agent.id` (PK, `data-model.md:76`), `agent.key/rev` (UNIQUE `(key,rev)`); `task.workflow_revision_id → workflow_revision.id → workflow.id` (`data-model.md:104-108`); `owner_user_id → app_user`.

**Оценка (прикидка):** без явного fetch-join стратегии типовой N+1: страница 50 сессий → 1 + 50×(agent + owner) = 101 запрос; задачи → 1 + 50×(workflow + revision + owner) = 151 запрос. При 20–30 параллельных пользователях это 2–4 тыс. лишних запросов/с на пуле в 10–20 коннектов → очередь за соединениями. `agent`/`app_user` малы и кешируемы, `workflow_revision` — иммутабельны, так что лечится джойнами/L2-кешем.

**Вердикт: GAP (стратегия выборки не зафиксирована).**

**Предложение:** явно зафиксировать в `api-contracts.md`/`architecture.md`, что списочные DTO собираются одним запросом с join'ами (или батч-фетчем `IN (...)`), плюс локальный кеш иммутабельных ревизий (`agent`, `workflow_revision`) в памяти (они никогда не меняются — прямое следствие иммутабельности, `data-model.md:88,110`). Тогда N+1 исчезает, а ревизии не бьют по БД.

---

### P21. Индексы AccessPolicy на горячем пути — **GAP**

**Факт:**
- `share`: UNIQUE `(subject_type, subject_id, resource_type, resource_id)` (`data-model.md:51`) — ведущие колонки **subject**. Обратного индекса по ресурсу нет.
- `group_member`: PK `(group_id, user_id)` (`data-model.md:29`) — ведущая колонка `group_id`; запрос «мои группы» по `user_id` индекса не имеет.
- `session.owner_user_id`, `session.folder_id`, `folder.parent_id` — индексов нет.
- `AccessPolicy` — «единая точка» на **каждом** запросе (`architecture.md:33`, `data-model.md:237`).

**Оценка (прикидка):** проверка «видит ли пользователь ресурс X» = поиск гранта по ресурсу (`resource_id/resource_type`) с фильтром по субъекту (user + его группы). По текущему UNIQUE-индексу такой поиск неэффективен (нет ведущего `resource_id`); `group_member` по `user_id` — seq scan. При 50 пользователях и малых таблицах это терпимо сегодня, но AccessPolicy вызывается на каждый REST/SSE-запрос и на каждый элемент списка при видимости — то есть это горячий путь, который масштабируется с числом грантов. Для листинга «sessions visible to me» добавление `owner_user_id`-индекса (P9) снимает основную часть.

**Вердикт: GAP.**

**Предложение:**
```sql
CREATE INDEX idx_share_resource ON share (resource_type, resource_id, subject_type, subject_id);
CREATE INDEX idx_group_member_user ON group_member (user_id);
```
Плюс кеш эффективных грантов пользователя на время запроса (per-request memoization), чтобы не считать группы/шеры для каждого элемента страницы.

---

## 7. `idempotency_key`

### P22. Точечные PK-вставки, конкурентный повтор — **OK**

**Факт:** PK `(scope, key)` (`data-model.md:229`); TTL 24 ч (`data-model.md:227`); семантика: «конкурентные запросы с одним ключом — PK сериализует: проигравший ждёт коммита победителя и получает его ответ» (`api-contracts.md:10`).

**Оценка (прикидка):** вставки точечные по PK, объём при 50 пользователях — тысячи–десятки тысяч строк/сутки; уникальный индекс держит нагрузку без проблем. Блокировка дубликата до коммита победителя корректна, поскольку `Idempotency-Key` обязателен на коротких `POST .../messages` (202) и опционален на командных POST (202/204) — окно блокировки мало. Риск был бы при применении ключа к долгому синхронному POST — таких в контракте нет.

**Вердикт: OK.**

**Предложение:** зафиксировать в контракте, что `Idempotency-Key` не применяется к endpoint'ам с длинным синхронным ответом (иначе blocked-connection); сейчас это выполняется де-факто.

---

### P23. TTL-чистка: индекс `expires_at` и батчи — **RISK**

**Факт:** `data-model.md:227`: «`expires_at timestamptz` — TTL 24 ч; чистка джобой». Единственный индекс/ключ — PK `(scope, key)` (`data-model.md:229`).

**Оценка (прикидка):** `DELETE ... WHERE expires_at < now()` не имеет индекса → seq scan на каждой чистке. В steady-state при ~10⁴ строк это дёшево (единицы мс), но: (а) таблица churn-heavy, autovacuum должен отрабатывать часто; (б) один большой `DELETE` раз в час/сутки даёт всплеск bloat и держит блокировки; (в) без индекса время чистки линейно по объёму за TTL. На целевом масштабе это «низкий, но гарантированный» долг.

**Вердикт: RISK (низкий).**

**Предложение:**
```sql
CREATE INDEX idx_idempotency_expires ON idempotency_key (expires_at);
```
Чистка — батчами (например, `LIMIT 1000` в цикле), отдельная джоба, метрика удалённых строк; `autovacuum` по таблице почаще. Альтернатива — `PARTITION BY RANGE (expires_at)` с drop партиций (для такого объёма избыточно).

---

## 8. Расширение: прочие найденные места

### P24. `task_transition_history`: несоответствие курсора и индекса — **GAP**

**Факт:** индекс `(task_id, created_at)` (`data-model.md:161`); SSE `task.transition` несёт `id:` = id записи истории (UUIDv7, монотонный), «он же — курсор `?since=` и `nextCursor` в `GET /history`» (`api-contracts.md:78`).

**Оценка (прикидка):** `GET /tasks/{id}/history?since=<uuid>` = `WHERE task_id=? AND id > ?`. Индекс `(task_id, created_at)` такой предикат эффективно **не** обслуживает: он найдёт диапазон по `task_id`, но `id > ?` придётся фильтровать по всем записям задачи. На ретраях SSE-реконнекта (каждые 15 с ping, реконнект возможен) при сотнях переходов на задачу это полный перечит истории при каждом реконнекте. Порядок `id` ≈ порядку `created_at` (UUIDv7), но индекс этого не «знает».

**Вердикт: GAP.**

**Предложение:** индекс `(task_id, id)` (или использовать `created_at` как курсор вместо `id`). Унифицировать тип курсора в контракте.

---

### P25. Дерево сессий: индекс `session.parent_session_id` — **GAP**

**Факт:** `parent_session_id uuid FK session NULL` (`data-model.md:177`); `GET /sessions/{id}/tree` — рекурсивный обход всего поддерева (`api-contracts.md:60`); `stop(session)` — спуск по `parent_session_id` (`execution-model.md:83`). Индекса по этой колонке нет (у `task.parent_task_id` индекс есть — `data-model.md:132`, у сессии нет).

**Оценка (прикидка):** `WITH RECURSIVE ... WHERE parent_session_id = ?` без индекса = seq scan `session` на каждом уровне рекурсии. При S = 2000 и глубине 3–5 — несколько десятков тысяч row-visits на построение дерева; для `stop` (спуск) — то же на каждый вызов. Терпимо, но линейно и легко устраняется.

**Вердикт: GAP (низкий).**

**Предложение:** `CREATE INDEX idx_session_parent ON session (parent_session_id);`

---

### P26. `GET /export` MB-сессии: потоковость — **GAP**

**Факт:** `api-contracts.md:57`: `GET /sessions/{id}/export?format=markdown|json` → `200 file`, `Content-Disposition`. Механика генерации не описана.

**Оценка (прикидка):** сессия 1000 сообщений × 1 МБ = до **1 ГБ** текста. Сборка в `String`/`byte[]` в памяти в сочетании с параллельными Turn'ами (P29) — прямой путь к OOM. JSON через Jackson при полном дереве в памяти — та же проблема.

**Вердикт: GAP.**

**Предложение:** стримить (`StreamingResponseBody` / пишущий `SseEmitter`-подобный writer), не буферизовать; лимит на размер экспорта или предупреждение; тест на сессии максимального размера.

---

### P27. Размер пула соединений к БД — **GAP**

**Факт:** `architecture.md:47`: datasource собирается вручную; конкретные параметры пула (HikariCP) не заданы.

**Оценка (прикидка):** одновременные потребители соединений: T Turn'ов (рендер + запись сообщений), POLL-джоба, REST, SSE-снапшоты. При T = 40 и дефолтном Hikari `maximumPoolSize=10` образуется очередь; Postgres при этом недогружен (локальный, десятки соединений держит). Типичный безопасный размер для одной VM — 20–30, при этом важно не превысить `max_connections` с учётом SUPERUSER-резерва и воркеров autovacuum.

**Вердикт: GAP.**

**Предложение:** зафиксировать `maximumPoolSize ≈ T_max + headroom` (например, 25), `leakDetectionThreshold`, таймаут получения соединения; метрики pool-wait. Явно указать в `architecture.md`.

---

### P28. Бэкап и I/O: БД и docker-тома на одной VM — **GAP**

**Факт:** один инстанс, Postgres локальный, docker на той же VM (`architecture.md:50`, `execution-model.md:99`). Данные — 50–250 ГБ (оценка); docker data-root с томами — ещё десятки–сотни ГБ (P18).

**Оценка (прикидка):** (1) `pg_dump`/бэкап 250 ГБ — часы и конкуренция за I/O с активными Turn'ами; (2) git-клоны и сборки в контейнерах создают пиковый IOPS/throughput, конкурирующий с WAL/CHECKPOINT Postgres → рост latency рендера; (3) при переполнении общей ФС деградируют оба. В доках ни бэкап-стратегии, ни бюджета I/O нет.

**Вердикт: GAP.**

**Предложение:** раздельные ФС/диски под `PGDATA` и docker data-root; `pg_basebackup`/WAL-архивирование вместо `pg_dump` на больших объёмах; расписание бэкапа вне пиков; rate-limit на параллельные git-операции (общий с P16); алерт по free space и `checkpoint`-метрикам.

---

### P29. JVM-heap под сборку контекста — **RISK**

**Факт:** рендер собирает полные payload'ы видимых событий и **кеширует «шапку»** между раундами (`execution-model.md:76`); контекстный порог ~80 % окна, затем компакция (`execution-model.md:62,77`).

**Оценка (прикидка):** при окне, скажем, 200k токенов и MB-payload'ах кешированная шапка на сессию может занимать десятки–сотни МБ в JVM. 30–40 активных Turn'ов × 50–150 МБ = **2–6 ГБ** только под тексты контекста, плюс буферы стримов и Spring. При L ≈ 100 (пик с субагентами) — кратно больше → OOM всего инстанса. Это усиливается копированием строк при конкатенации без `StringBuilder`/интернирования.

**Вердикт: RISK (высокий по последствию).**

**Предложение:** ограничить/вытеснять кеш шапки (LRU по объёму, а не по числу сессий); не держать весь контекст отдельной строкой — стримить построение; явный `-Xmx` с бюджетом из P16; метрика `context_cache_bytes`; форсировать компакцию по **объёму**, а не только по числу токенов. Согласовать с P14: число одновременных Turn'ов — это и ограничитель heap'а.

---

## 9. Явные расхождения с доками (assertions без опоры в схеме)

| Утверждение | Источник | Что не подтверждено |
|---|---|---|
| «POLL дёшево по индексам» | `execution-model.md:15`, `decisions.md D-33` | Нет ни маркера eligible, ни `current_state_kind`, ни `deadline_at` (P1–P4). |
| «Горизонтальный запуск нескольких инстансов безопасен» | `execution-model.md:99-100` | Верно для исполнения (CAS), не для доставки SSE (P13) и не для docker-хоста (P16–P18: контейнеры/тома привязаны к VM). |
| Timeout'ы WAIT_*/BASH обязательны | `workflow-domain.md:24,35` | Нет `state_entered_at`/`deadline_at` в `task` (P4). |
| `idempotency_key` «чистка джобой» | `data-model.md:227` | Нет индекса `expires_at`, политики батчинга (P23). |
| `SessionDto.messageCount/tokensTotal` | `api-contracts.md:62` | Нет источника значений — ни колонок, ни документированных агрегатов (P19). |

---

## 10. Ограничения ревью

- Разбор **только по дизайн-докам**; кода реализации и миграций Liquibase не читал (их нет в репозитории на этой стадии) — все планы запросов предсказаны, не измерены.
- Версия PostgreSQL не зафиксирована в `architecture.md`; оценки вакуума/insert-порогов сделаны для PG ≥ 16.
- Числа — модель ёмкости из §0 (inference); они нужны для порядка величины, а не для точного прогноза.
- Spring-версии: оценки виртуальных потоков/async-servlet сделаны для Boot 4.1.1 / Java 25 (`architecture.md:46`) — на этих версиях пиннинга нет (JEP 491).

**Verified with:** полное чтение `data-model.md`, `execution-model.md`, `api-contracts.md`, `architecture.md`, `workflow-domain.md`, `decisions.md` (+ выборочно `glossary.md`); сопоставление каждого утверждения с полями/индексами схемы.
**Not verified:** реальные `EXPLAIN (ANALYZE)` планы, профиль памяти/нагрузочные тесты, поведение конкретного корпоративного LiteLLM (лимиты concurrency/TPM), фактический размер VM и диска.
**Residual risk:** количественные пороги (S, T, L, N, P) взяты из задания/типовых значений; при другом распределении вердикты RISK/GAP не меняются, меняется только острота.

---

## 11. Рекомендуемый порядок действий

1. **P14+P15** — семафор к провайдеру + retry/backoff (иначе утренний пик = массовые FAILED).
2. **P19+P20** — денормализовать счётчики на `session` и зафиксировать fetch-join/кеш ревизий для списков.
3. **P1–P4** — `unconsumed_events`, `current_state_kind`, `state_entered_at`/`state_deadline_at` + частичные индексы; иначе `D-33` остаётся декларацией.
4. **P16–P18** — бюджет VM/диска, потолок тяжёлых контейнеров, TTL простоя, quota на тома.
5. **P9, P21, P23, P24, P25** — пакет индексов (быстро, дёшево, снимает линейные сканы).
6. **P12, P13, P26, P27, P28, P29** — backpressure SSE, потоковость export, пул, бэкап/IO, heap-бюджет.

---

## Cross-check

Сверка с `aspect3-perf-glm.md` (GLM-5.3-Flash, 25 пунктов A1–H5) и `aspect3-perf-mercury.md` (Mercury-2.5, 18 строк). По каждому чужому пункту — `agree` / `disagree` / `duplicate (мой №)` / `stale`. Отдельно — аудит арифметики и собственных счётчиков коллег.

### A. GLM-5.3-Flash

| GLM | Пункт | Вердикт cross-check | Комментарий |
|---|---|---|---|
| A1 | POLL: маркер непоглощённых событий | **duplicate (P1)** + agree | Тот же вывод и то же решение (`absorbed_seq`/`last_event_seq` + partial index). |
| A2 | POLL: индексы скана сессий | **duplicate (P1, P9)** + agree (частично) | Дополняет сверх моего: отдельный `INDEX (locked_at) WHERE locked_by IS NOT NULL` под TTL-освобождение и старт-скан. Принимаю как addendum к P1. Но механизм «SELECT раздувает shared_buffers **грязными** страницами» — **disagree**: чтение страниц не делает их dirty; это верно только для записи/WAL. |
| A3 | POLL: AGENT-задача без сессии | **duplicate (P2)** | Совпадает, включая `current_state_type` на `task`. |
| A4 | POLL: индекс по `status_projection` | **duplicate (P3)** | Совпадает; partial `WHERE status_projection='WAITING'` — то же, что P3. |
| A5 | POLL: таймауты состояний | **duplicate (P4)** + agree | Совпадает; верно, что BASH_SCRIPT закрывается тем же `state_deadline_at`. |
| B1 | Bloat/vacuum append-only | **duplicate (P6)** | Совпадает (OK). |
| B2 | Неограниченный рост: TOAST/retention/партиционирование | **agree (новое)** | У меня отдельного пункта про **retention `session_message`** не было — принимаю как **P30** (RISK). Партиционирование по `created_at` и усечение `TOOL_RESULT`-payload — разумно. |
| C1 | SSE: keepalive/ticket/rate-limit | **duplicate (P11)** | Совпадает (OK). |
| C2 | SSE: лимиты/backpressure/MB-фреймы | **duplicate (P12)** | Совпадает; отправка `{id, seq, truncated}` + догрузка — согласен. |
| C3 | SSE: механизм fan-out | **duplicate (P13)** | Совпадает (GAP); after-commit in-process pub/sub — то же предложение. |
| D1 | Семафор/лимитер к провайдеру | **duplicate (P14)** | Тот же дефект. Расхождение только в ярлыке: GLM — GAP, у меня RISK (механизма нет, но failure mode конкретен). Не материально. |
| D2 | 429/5xx → FAILED | **duplicate (P15)** | Совпадает, включая «парковка вместо терминала» и неуместность ERROR-ребра. |
| E1 | Пер-контейнерные лимиты | **duplicate (P16, позитивная половина)** | Совпадает (OK по одному контейнеру). |
| E2 | Агрегатный потолок контейнеров/памяти | **duplicate (P16)** | Совпадает; арифметика верна (ниже). |
| E3 | Диск: тома/git/retention | **duplicate (P18)** + agree | Сильнее моей формулировки: старт-скан чистит контейнеры, но **не осиротевшие тома** — принимаю. |
| F1 | Счётчики SessionDto | **duplicate (P19)** + **disagree (арифметика)** | Вывод совпадает, но «десятки млн строк в агрегатах на страницу» завышено на ~1.5–2 порядка (см. аудит). |
| F2 | N+1 + сортировка без индекса | **duplicate (P9, P20)** | Совпадает. |
| F3 | AccessPolicy reverse-lookup | **duplicate (P21)** | Совпадает; ярлык GLM RISK vs мой GAP — не материально. |
| G1 | idempotency: вставки/повторы | **duplicate (P22)** | Совпадает (OK). |
| G2 | idempotency: TTL-чистка | **duplicate (P23)** + agree | Дополняет: cap на размер `response_json` (второй TOAST-гигант). Принимаю. |
| H1 | Пул соединений | **duplicate (P27)** + **disagree (рекомендация)** | Диагноз верен; формула `40×1.5+slop=60–80` арифметически сходится, но для **локального** PG 60–80 коннектов завышено (память на backend, контекст-свитчи; классическая формула Hikari ≈ `cores×2+spindles`). Рекомендую 20–30, как в P27. |
| H2 | JVM-heap под рендер | **duplicate (P29)** + **disagree (арифметика)** | Вывод совпадает; «200K токенов = сотни MB» неверно (см. аудит) — сотни MB даёт только сумма MB-payload, а не окно токенов. |
| H3 | `GET /export` | **duplicate (P26)** | Совпадает; ярлык RISK vs мой GAP — не материально. |
| H4 | Индекс `session.parent_session_id` | **duplicate (P25)** | Совпадает. |
| H5 | История: курсор `id` vs индекс | **duplicate (P24)** | Совпадает, включая оба варианта фикса. |

### B. Mercury-2.5

| Mercury | Пункт | Вердикт cross-check | Комментарий |
|---|---|---|---|
| §1 | Eligible-скан: нет индексов `last_activity_at`/`locked_at`, RISK | **duplicate (P1, P9)** + **disagree** | Индексы действительно нужны, но **одних индексов недостаточно**: без поля-маркера «есть непоглощённые события» предикат eligibility невыразим (P1/GLM A1). Индекс по `last_activity_at` не отличает «дописали» от «поглощено». |
| §1 | Двухуровневый скан: сессии без индексов, RISK | **duplicate (P2, P3)** | Совпадает по сути; «+ лимит выборки (batch)» — разумное addendum. |
| §1 | ShedLock: таблица не в data-model, GAP | **disagree (stale)** | Таблица ShedLock **создаётся и управляется библиотекой** (`architecture.md:48`, ShedLock 7.10.1 jdbc-template); её место — не `data-model` (как и служебные таблицы Liquibase). «Индекс на `lock_id`» не нужен: у ShedLock PK по `name`. Конкуренция — 0.2 записи/с (мой P5, OK). |
| §2 | append-only bloat: нет стратегии vacuum, GAP | **disagree (завышено)** | Append-only без UPDATE — best-case профиль: dead tuples почти нет, «aggressive autovacuum» не требуется. Согласен только с настройкой insert-порога ради visibility map (мой P6, OK; GLM B1 тоже OK). |
| §2 | TOAST при MB payload, RISK | **duplicate (P7/B2)** + agree | Чтение TOAST само по себе корректно (P7); риск — неограниченный объём → совпадает с B2/P30. Cap на payload (~2–10 MB) принимаю. |
| §2 | Индекс `session_message(session_id, created_at, seq)`, RISK | **disagree** | Избыточен: PK `(session_id, seq)` уже покрывает рендер и `?since=`; `created_at` в середине не помогает ни одному из этих путей. Корректное улучшение — `INCLUDE (kind, tokens)` (мой P8). |
| §2 | Курсорная пагинация `?since=<seq>`, OK | **duplicate (P8, P9)** | Совпадает. |
| §3 | SSE: кол-во коннектов, RISK (лимиты Tomcat/Jetty `maxThreads`/`acceptCount`) | **disagree** | Неверная модель: SSE — async servlet, соединение **не держит поток**; на Java 25/виртуальных потоках `maxThreads` — не ограничение. Значимый лимит — `maxConnections` (default 8192); 100–150 emitters с запасом (мой P11, OK). |
| §3 | Keepalive, RISK | **disagree (stale)** | Контракт уже фиксирует, что `ping` — **SSE-комментарий** `: ping` (`api-contracts.md:75`), т.е. не body. 150/15 = 10/с — пренебрежимо → OK (мой P11; GLM C1 тоже OK). |
| §4 | Семафор/очередь к LLM, GAP | **duplicate (P14)** | Совпадает. |
| §4 | Пик wake (утро), GAP | **duplicate (P14)** | Я это включил в P14; отдельный пункт не нужен. |
| §5 | Лимиты на контейнер, OK | **duplicate (P16, позитивная половина)** | Совпадает. |
| §5 | Память VM / idle-вытеснение, GAP | **duplicate (P16, P17)** | Совпадает; «70 % VM» — приемлемая эвристика, согласуется с бюджетом P16. |
| §5 | Диск под workspace/git, GAP | **duplicate (P18)** | Совпадает. |
| §6 | N+1 при joins, RISK | **duplicate (P20)** | Совпадает. |
| §6 | `messageCount`/`tokensTotal`, GAP | **duplicate (P19)** | Совпадает. |
| §7 | PK `(scope, key)`, OK | **duplicate (P22)** | Совпадает. |
| §7 | TTL-чистка, GAP | **duplicate (P23)** | Совпадает (GLM G2 добавляет cap `response_json`). |

### C. Аудит арифметики (грубо, порядок величины)

| Источник | Утверждение | Проверка | Итог |
|---|---|---|---|
| GLM A1/A4/A5 | 17 280 тиков/сутки | 86 400 / 5 ✓ | верно |
| GLM E2 | 60 × 2g = 120 GB; 60 × 2 cpu = 120 vCPU | ✓ | верно |
| GLM B2 | M = 10⁵–10⁶ строк × 0.2–1 MB → «десятки–сотни GB/год» | низ: 10⁵×0.2 = 20 GB; верх: 10⁶×1 = **~1 TB** | **недооценка верхней границы ~10×**; «сотни GB» верно лишь для середины |
| GLM F1 | «десятки млн строк в агрегатах на страницу» | 50 сессий × 5·10³ строк × 3 агрегата-выражения = 750 000 row-visits ≈ **0.75 M**, не десятки млн | **переоценка ~30–100×** (вывод GAP сохраняется) |
| GLM H1 | pool = 40×1.5 + slop = 60–80 | арифметика ✓ | арифметика верна; **рекомендация завышена** для локального PG (см. H1) |
| GLM H2 | «окно 200K токенов → сотни MB символов» | 200 k токенов × ~4 симв ≈ 0.8 M симв ≈ **0.8–2.4 MB** (UTF-16) | **переоценка ~100×**; сотни MB возможны лишь из-за суммы MB-payload, не из «окна токенов» |
| GLM A2 | «фулл-скан × 17 280 → раздувание shared_buffers грязными страницами» | SELECT не пишет страницы | **неверный механизм**: чтение не dirty'ит буферы |
| Mercury §3 | 150 коннектов / 15 с ≈ 10 ping/s | ✓ | верно |
| Mercury §2 | индекс `(session_id, created_at, seq)` | PK `(session_id, seq)` уже покрывает | **избыточный индекс** |

### D. Аудит счётчиков

- **GLM:** 25 пунктов, из них OK 4 / RISK 11 / GAP 10 — **сходится** (пересчитал по таблице A1–H5).
- **Mercury:** 18 строк с вердиктами; OK 3 + RISK 7 = 10 → **GAP = 8**, а заявлено «GAP = 7» (итог 3/7/7=17 ≠ 18 видимых строк). **Расхождение на 1** в пользу GAP.

### E. Список `disagree`

1. **Mercury §1 (ShedLock, GAP)** — таблица создаётся библиотекой, не место в `data-model`; конкуренция 0.2 записи/с → OK, не GAP.
2. **Mercury §2 (append-only bloat, GAP)** — append-only = best-case, dead tuples почти нет; aggressive autovacuum не нужен (я и GLM — OK).
3. **Mercury §2 (индекс `(session_id, created_at, seq)`)** — избыточен; PK `(session_id, seq)` покрывает рендер и `since=`.
4. **Mercury §3 (кол-во SSE-коннектов через `maxThreads`)** — async SSE не держит поток; лимит — `maxConnections` (8192), 100–150 emitters → OK.
5. **Mercury §3 (keepalive, RISK)** — stale: контракт уже определяет `ping` как SSE-комментарий; 10/с тривиально.
6. **Mercury §1 (eligible через индексы без маркера)** — индексов `last_activity_at`/`locked_at` **недостаточно**: нужен маркер непоглощённых событий (P1).
7. **GLM F1 (арифметика «десятки млн строк»)** — реально ~0.75 M; переоценка ~30–100×.
8. **GLM H2 (арифметика «сотни MB из 200K токенов»)** — реально ~0.8–2.4 MB; переоценка ~100× (сотни MB дают MB-payload'ы).
9. **GLM H1 (рекомендация пула 60–80)** — завышено для локального PG; рекомендую 20–30.
10. **GLM A2 (механизм «грязные страницы от SELECT»)** — чтение не dirty'ит shared_buffers.
11. **GLM B2 (верхняя граница объёма)** — «сотни GB» недооценивает верх до ~1 TB (10⁶ × 1 MB).

### F. Что принято в мой документ по итогам cross-check

- **P30 (новый, RISK):** retention-политика `session_message` — сессии/сообщения не удаляются (`execution-model.md:86`, `api-contracts.md:49`), рост только вверх; предложение: явный retention + export→cold→delete либо осознанный disk-budget, партиционирование по `created_at` как точка эволюции, усечение `TOOL_RESULT`-payload. *(из GLM B2)*
- Addendum к P1/P9: отдельный partial `INDEX session(locked_at) WHERE locked_by IS NOT NULL` под стухшие локи и старт-скан. *(из GLM A2)*
- Addendum к P23: cap на размер `response_json` (не заводить второй TOAST-гигант). *(из GLM G2)*

**Обновлённый счёт (после cross-check): OK = 7, RISK = 10, GAP = 13 (всего 30).** Новый GAP не появился; +1 RISK — P30, принято из GLM B2. Все 11 расхождений — по арифметике/механике/ярлыкам, а не по сути выводов; ни одного пункта GLM/Mercury не пришлось отклонить целиком.

**Verified with:** построчная сверка 25 пунктов GLM (A1–H5) и 18 строк Mercury с моими P1–P29; ручной пересчёт счётчиков и числовых прикидок.
**Not verified:** отклонённые арифметические оценки не проверялись рантаймом (это оценки из головы — порядок величины).

---

## Fixes approval

Проверка применённых судейских фиксов по `data-model.md`, `execution-model.md`, `decisions.md` (свежая редакция). По каждому — доказательство, `ок`/`не-ок`, вердикт.

| # | Фикс | Доказательство | Статус | Вердикт |
|---|---|---|---|---|
| 1 | Денормализации session + partial-индекс eligible | `data-model.md:184-187` (`last_seq`, `last_consumed_seq`, `message_count`, `tokens_total`), `:196` partial `INDEX (last_seq) WHERE last_seq > last_consumed_seq AND locked_by IS NULL AND archived_at IS NULL` + «обновляются в той же транзакции»; `execution-model.md:32` eligibility переписан на `last_seq > last_consumed_seq` | **ок** (с оговорками) | **approve** |
| 2 | Денормализации task + индексы WAIT/deadline | `data-model.md:124-126` (`current_state_kind`, `state_attempt`, `deadline_at`), `:135` индексы WAIT/deadline, `:251` CAS пересчитывает `current_state_kind` | **не-ок** (частично) | **approve c follow-up** |
| 3 | share/group_member lookups | `data-model.md:51`: `INDEX (resource_type, resource_id)` на `share` + `INDEX (user_id)` у `group_member` | **ок** | **approve** |
| 4 | LLM bulkhead 8 + backoff×3 → парковка (D-35) | `execution-model.md:34` (bulkhead дефолт 8, backoff×3 → парковка, FAILED после исчерпания); `decisions.md:41` D-35 | **не-ок** (противоречие) | **approve c follow-up** |
| 5 | Контейнеры: квота 5 GB / потолок 50 / idle-30 мин / reconcile в POLL | `execution-model.md:79` — все четыре параметра присутствуют | **ок** | **approve** |
| 6 | Retention-прикидка ~1 ТБ/год | `data-model.md:210`: «журнал бессрочен… верхняя оценка роста ~1 ТБ/год» | **ок** | **approve** |
| 7 | Реестр `instance` | `data-model.md:227-233` (`instance(id, last_seen)`, heartbeat 10с/порог 30с); `execution-model.md:20` scoped-recovery по реестру; `:23` докатка отмен; `:25` «первый финальный выигрывает»; `decisions.md:42` D-36 | **ок** | **approve** |

### Детали по «не-ок»

**#2 — not-ok (частично).**
- Добавлен только partial-индекс `WHERE kind WAIT_*` (`data-model.md:135`). Ветка POLL **(а) «AGENT-задача без живой сессии»** (`execution-model.md:15а`) им **не покрывается** — нужен отдельный индекс под `current_state_kind='AGENT'` (или общий непartial по `current_state_kind`). Остаётся seq scan `task` + join ревизии, ради которого денормализация и вводилась.
- Синтаксис «`WHERE kind WAIT_*`» неточен: у `task` нет колонки `kind` (есть `current_state_kind`), и `WAIT_*` должен быть явным `IN ('WAIT_WEBHOOK','WAIT_TASKS')`. Редакторская правка.
- `deadline_at` + `INDEX (deadline_at) WHERE deadline_at IS NOT NULL` — **ок**, таймаут-скан закрыт.

**#4 — not-ok (противоречие).**
- Новый текст (`execution-model.md:34`) и D-35 требуют: retryable 429/5xx → backoff → **парковка**, `FAILED` только после исчерпания.
- Но таблица исчерпывающих условий выхода (`execution-model.md:73`, строка №6) **осталась в старой редакции**: «Ошибка LLM → статус FAILED, выход». Таблица объявлена исчерпывающей — исполнитель может закодировать по ней и проигнорировать строку 34. Нужна одна строка: «6 | Неисправимая ошибка LLM (после retry/backoff) | статус FAILED, выход; retryable — парковка».

### Оговорки по «ок» (не блокеры)

- **#1:** partial-индекс `locked_by IS NULL` не покрывает случай «**стухший** лок при непоглощённых событиях» (eligible = лок свободен **или стух**). Runtime-recovery такого случая остаётся только на EVENT/start-скане; при простое он не подхватится index-only-сканом. Кандидат на отдельный partial `INDEX (last_seq) WHERE last_seq > last_consumed_seq AND (locked_by IS NULL OR locked_at < now() - TTL)`. Также для строгого index-only вывода списка сессий стоит `INCLUDE (id)` (иначе heap-fetch за `id`; на малом числе eligible-строк это дёшево, поэтому оговорка, а не дефект).
- **#1:** определение `last_consumed_seq` — «последний seq, вошедший в **законченный** модельный ход». Уточнить, что он двигается на **любом** выходе Turn'а, включая парковку по async, иначе сессия с `pending_tool_calls>0` останется `last_seq > last_consumed_seq` и POLL будет каждые 5 с дёргать tryStart впустую (без LLM-вызова, но с лок-чурном). Формулировка «законченный модельный ход» может не покрывать парковку — стоит явно сказать «на каждом выходе цикла».
- **#3:** `INDEX (group_id)` на `group_member` избыточен — PK `(group_id, user_id)` уже ведёт с `group_id`. Полезен только `(user_id)`.
- **#5:** reconcile контейнеров «периодически в POLL-джобе» при тике 5 с может быть слишком частым (docker API по всем контейнерам × 17 280/сутки). Лучше отдельная низкочастотная джоба или счётчик тиков (напр. каждые N минут).

### Вне списка фиксов — остаётся открытым (из моего aspect3)

| Мой пункт | Статус | Комментарий |
|---|---|---|
| P9 (индексы списков: `last_activity_at`/`owner_user_id`/`folder_id`/`archived_at`/trgm) | **открыт** | В `session` по-прежнему только partial-unique и eligible-partial (`:196`); сортировка `lastActivityAt desc` без индекса. |
| P23 (индекс `expires_at`) | **открыт** | `data-model.md:244` добавил батчи ≤1000, но `INDEX (expires_at)` так и нет → каждый батч = seq scan. |
| P24 (курсор истории `id` vs `(task_id, created_at)`) | **открыт** | `data-model.md:164` без изменений. |
| P25 (`session.parent_session_id`) | **открыт** | Индекса нет. |
| P20 (N+1 fetch-join) | **частично** | D-34 упоминает денормализации «под списки», но стратегия join/батч-кеша ревизий не зафиксирована. |
| P26/P27/P28/P29 (export-стриминг, пул БД, бэкап/IO, heap-бюджет) | **открыт** | В этих трёх файлах не затрагивались; bulkhead(8) частично снижает P29. |
| P12/P13 (SSE backpressure / fan-out) | **открыт** | Не затрагивались. |

### Итоговый вердикт

**approve** — все 7 фиксов применены по существу; два пункта (#2, #4) требуют одно-двухстрочных follow-up правок (индекс AGENT-ветки; реконсиляция строки №6 таблицы выхода), плюс редакторские уточнения по #1/#3/#5. Открытые пункты P9/P23/P24/P25 и SSE-блок остаются за рамками этого раунда и не блокируют аппрув темы 3.

**Verified with:** чтение свежих `data-model.md` (254 стр.), `execution-model.md` (110 стр.), `decisions.md` (42 стр.); сопоставление каждого из 7 фиксов с полями/индексами/строками.
**Not verified:** рантайм-проверка планов (`EXPLAIN`) для новых partial-индексов; факт применения миграций Liquibase (их в репозитории нет).
**Residual risk:** contradiction #4 (таблица выхода) — единственный пункт, где расхождение может привести к неверной реализации retry-политики, если правку не внести.
