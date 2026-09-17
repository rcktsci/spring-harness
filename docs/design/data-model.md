# Модель данных spring-harness

> СУБД: PostgreSQL. Миграции: Liquibase (+ Preliquibase для pre-DDL). Все конечные решения — из `docs/design/decisions.md`.
> Соглашения: id — UUID v7 (кроме `session_message.id` — короткий ULID); генератор — единая точка `IdGenerator` (библиотека владельца — D-31); время — `timestamptz`; гибкие структуры — `jsonb`; удаления каскадные от владельца, если не указано иное.

## 1. identity

### app_user
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| keycloak_subject | text UNIQUE | идентификатор из SSO |
| username | text | |
| display_name | text | |
| created_at | timestamptz | |

Группы/папки/шары **не моделируются**: вход — SSO-гейт по `groups`-claim (конфиг `harness.security.allowed-groups`), внутри — «аутентифицированный видит всё» (D-41).

## 2. llm

### llm_credentials
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| name | text | |
| base_url | text | OpenAI-совместимый |
| api_key_encrypted | text | шифрование на стороне приложения; `key_version` — идентификатор ключа шифрования (ротация без порчи данных) |
| key_version | int DEFAULT 1 | |
| created_at | timestamptz | |

### llm_model
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| credentials_id | uuid FK llm_credentials | |
| model_id | text | имя модели у провайдера |
| params_jsonb | jsonb | например `{"reasoning_effort": "high"}` |
| created_at | timestamptz | |

### agent (иммутабельные ревизии)
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | конкретная ревизия |
| key | text | логическое имя агента |
| name | text | человекочитаемое (для каталога `GET /agents`) |
| description | text NULL | |
| rev | int | |
| role_prompt | text | системный промпт |
| tools_jsonb | jsonb | декларация: нативные + MCP + spawn/transition |
| permissions_jsonb | jsonb | разрешения |
| skills_jsonb | jsonb | навыки |
| llm_model_id | uuid FK llm_model | |
| created_at | timestamptz | |

UNIQUE `(key, rev)`. UPDATE запрещён — правка = новая строка ревизии. **Retention: бессрочно** — сессии пинят ревизии; чистка старых ревизий осознанно отсутствует (как у workflow_revision).

## 3. workflow

### workflow
| Поле | Тип |
|---|---|
| id | uuid PK |
| key | text UNIQUE |
| name | text |
| owner_user_id | uuid FK app_user |
| created_at | timestamptz |

### workflow_revision (иммутабельные)
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | на него пинится задача |
| workflow_id | uuid FK workflow | |
| rev | int | |
| graph_jsonb | jsonb | граф: состояния + переходы; JSON-Schema-контракт (см. `workflow-domain.md`) |
| created_at | timestamptz | |

UNIQUE `(workflow_id, rev)`. UPDATE запрещён.

## 4. task

### task
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| title | text | |
| description | text | |
| author_user_id | uuid FK app_user NULL | агент-автор не является user; авторство агента — через историю |
| owner_user_id | uuid FK app_user | |
| workflow_revision_id | uuid FK workflow_revision | пин к ревизии при создании |
| current_state | text | `state.code` из ревизии |
| current_state_kind | enum AGENT \| BASH_SCRIPT \| WAIT_WEBHOOK \| WAIT_TASKS \| TERMINAL | денормализация для задачного POLL-скана; обновляется транзакционно с current_state |
| state_attempt | int DEFAULT 0 | счётчик входов в текущее системное состояние (идемпотентность повторных прогонов bash) |
| deadline_at | timestamptz NULL | дедлайн таймаута состояния (BASH/WAIT/AGENT-timeout); таймаут-скан по индексу |
| status_projection | enum RUNNING \| WAITING \| SUCCEEDED \| FAILED \| CANCELLED | денормализация текущего состояния, обновляется транзакционно с current_state |
| params_jsonb | jsonb | параметр-мапа инстанса (`${task.params.<key>}`); **иммутабельны после создания** (api §4.1) |
| parent_task_id | uuid FK task NULL | подзадачи |
| tags | text[] DEFAULT '{}' | группировка для `WAIT_TASKS/TAGGED(x)` |
| suspended | bool DEFAULT false | аварийный стоп; планировщик пропускает |
| created_at / updated_at | timestamptz | |

INDEX `(parent_task_id, status_projection)` — оценка `WAIT_TASKS / ALL_CHILDREN`. INDEX `(tags)` GIN — для `TAGGED(x)`. INDEX `(current_state_kind)` WHERE WAIT_* — задачный POLL-скан; PARTIAL INDEX WHERE `current_state_kind = 'AGENT' AND status_projection = 'RUNNING'` — bootstrap-скан AGENT-без-сессии. INDEX `(deadline_at)` WHERE deadline_at IS NOT NULL — таймаут-скан. `owner_user_id` при создании задачи агентом наследуется от породившей сессии (см. глоссарий, Session).

### task_dependency
| Поле | Тип |
|---|---|
| blocker_task_id | uuid FK task |
| blocked_task_id | uuid FK task |
PK `(blocker_task_id, blocked_task_id)`. INDEX `(blocked_task_id)` — обратный поиск «кто ждёт эту задачу» для переоценки WAIT_TASKS. Семантика: блокирующая задача должна прийти в нужный терминал; **циклы (включая транзитивные) запрещены валидацией** (обход в глубину при установке ребра, `422 dependency-invalid`).

### task_comment (append-only, иммутабельно)
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| task_id | uuid FK task | |
| author_user_id | uuid FK app_user NULL | NULL + агент-пометка в payload для агентских комментариев |
| body | text | |
| created_at | timestamptz | |

### task_transition_history (append-only)
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| task_id | uuid FK task | |
| from_state | text | code |
| to_state | text | code |
| kind | enum NEXT \| ERROR \| TIMEOUT \| CANCEL | |
| reason_jsonb | jsonb | bash: stdout/stderr + exit-код; агент: обязательный текст-обоснование; вебхук: источник + сводка; WAIT_TASKS: какие задачи закрыли условие |
| created_at | timestamptz | |

INDEX `(task_id, created_at)`.

## 5. session

### session
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| title | text NULL | название (SessionDto, поиск `?q=`) |
| owner_user_id | uuid FK app_user | у STATE — владелец задачи; при создании агентом — наследуется от породившей сессии |
| kind | enum FREE \| STATE | |
| task_id | uuid FK task NULL | только STATE |
| state_code | text NULL | только STATE; code состояния ревизии задачи |
| agent_revision_id | uuid FK agent NULL | чем обрабатывается |
| parent_session_id | uuid FK session NULL | субагентские сессии; поддерево для stop |
| cancel_requested | bool DEFAULT false | проверяется между вызовами инструментов |
| last_seq | bigint DEFAULT 0 | денормализация: max(seq) сообщений — основа eligible-скана |
| last_consumed_seq | bigint DEFAULT 0 | денормализация: последний seq, вошедший в законченный модельный ход |
| last_turn_outcome | enum COMPLETED \| FAILED \| CANCELLED NULL | проекция исхода последнего Turn'а |
| last_activity_at | timestamptz | |
| created_at | timestamptz | |

PARTIAL UNIQUE `(task_id, state_code) WHERE kind = 'STATE'` — повторный вход в состояние резюмирует ту же сессию. PARTIAL INDEX `(last_seq)` WHERE `last_seq > last_consumed_seq` — eligible-скан POLL (index-only; занятость лока проверяется попыткой взятия). Денормализации `last_seq/last_consumed_seq` обновляются в той же транзакции, что и допись сообщения.

**Локи сессий хранятся не здесь**: ShedLock-таблица библиотеки, ключи `sess-{sessionId}` (+ имя джобы-сканера); колонок локов в `session` нет (D-40). Старые `sess-*`-строки чистит джоба.

### session_message (append-only, UPDATE запрещён)
| Поле | Тип | Примечание |
|---|---|---|
| session_id | uuid FK session | |
| seq | bigint | сквозная нумерация сессии |
| id | text UNIQUE | ULID (26 символов, Crockford base32, монотонный) — будущие маркеры в контексте |
| kind | enum USER \| ASSISTANT \| SYSTEM \| TOOL_CALL \| TOOL_RESULT \| COMPACT | |
| author_user_id | uuid FK app_user NULL | атрибуция USER-сообщений |
| payload_jsonb | jsonb | контент сообщения; для COMPACT — `covers` (id/диапазоны) + пересказ |
| tokens | int NULL | учёт стоимости |
| created_at | timestamptz | |

PK `(session_id, seq)`. Видимость скрытых сообщений — производная от `COMPACT.covers` (см. `execution-model.md` §5). Retention: журнал бессрочен; политика выгрузки/очистки старых сессий — отдельное решение при появлении объёмов (верхняя оценка роста ~1 ТБ/год на 50+ пользователей).

## 6. Интеграции

### trigger
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| name | text | |
| workflow_key | text | |
| rev | int | пин ревизии при создании триггера |
| params_jsonb | jsonb | параметры создаваемых задач |
| tags | text[] DEFAULT '{}' | |
| owner_user_id | uuid FK app_user | |
| revoked_at | timestamptz NULL | DELETE API = revoke; URL умирает мгновенно |
| created_at | timestamptz | |

Таблица `idempotency_key` **выброшена** (D-41): дубль POST переживём; вебхук задач идемпотентен по построению (`409` вне WAIT_WEBHOOK).

Вебхуки задач — stateless capability-URL (`токен = HMAC(secret, kind + ':' + entityId)` в пути, см. `workflow-domain.md` §7); очередь — сама `session_message`; история попыток обработки — не хранится (D-04).

## 7. Инварианты схемы

1. `session_message` и `task_transition_history` — только INSERT; UPDATE/DELETE запрещены на уровне приложения (и проверяются ревью миграций).
2. `current_state` ∈ codes своей `workflow_revision` **или равен зарезервированному `'$CANCELLED'`** (виртуальный терминал принудительной отмены). Смена — движком переходов **атомарным CAS** (`UPDATE … SET current_state = next WHERE id = ? AND current_state = expected AND NOT suspended`); гонки stop↔transition, двойной `transition`, дубль терминала и ретрай вебхука — один победитель, проигравшие no-op. **Исключение — `stop`**: его CAS-запись `'$CANCELLED'` гварда `NOT suspended` не имеет (иначе сам себя заблокировал бы) и выигрывает у любых переходов; отмена Turn'ов — после записи терминала. Переоценка WAIT_TASKS идемпотентна. Транзакционно: `task_transition_history` + `status_projection` + `current_state_kind`.
3. Видимость и доступ: SSO-гейт (`groups`-claim) + правило «аутентифицированный видит всё» (D-41); отдельных проверок нет.
4. Локи сессий — в ShedLock-таблице (`sess-{id}`), не в колонках `session`; обращение — только через `LockProvider`/`LockExtender` библиотеки.
5. Ревизии (`agent`, `workflow_revision`) — иммутабельны; ссылки всегда на конкретную ревизию.
