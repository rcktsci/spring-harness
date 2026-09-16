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

### app_group
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| external_id | text UNIQUE | идентификатор группы в Keycloak |
| name | text | |

### group_member
| Поле | Тип |
|---|---|
| group_id | uuid FK app_group |
| user_id | uuid FK app_user |
PK `(group_id, user_id)`.

### folder
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| owner_user_id | uuid FK app_user | |
| parent_id | uuid FK folder NULL | дерево; корень — NULL |
| name | text | |

### share
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| subject_type | enum USER \| GROUP | кому выдан |
| subject_id | uuid | app_user.id или app_group.id |
| resource_type | enum SESSION \| FOLDER \| TASK | |
| resource_id | uuid | |
| level | enum VIEW \| PARTICIPATE | PARTICIPATE ⊃ VIEW |
| granted_by | uuid FK app_user | |
| created_at | timestamptz | |

UNIQUE `(subject_type, subject_id, resource_type, resource_id)`.

## 2. llm

### llm_credentials
| Поле | Тип | Примечание |
|---|---|---|
| id | uuid PK | |
| name | text | |
| base_url | text | OpenAI-совместимый |
| api_key_encrypted | text | шифрование на стороне приложения |
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
| status_projection | enum RUNNING \| WAITING \| SUCCEEDED \| FAILED \| CANCELLED | денормализация текущего состояния, обновляется транзакционно с current_state |
| params_jsonb | jsonb | параметр-мапа инстанса (`${task.params.<key>}`) |
| parent_task_id | uuid FK task NULL | подзадачи |
| tags | text[] DEFAULT '{}' | группировка для `WAIT_TASKS/TAGGED(x)` |
| visibility | enum PRIVATE \| PUBLIC DEFAULT PRIVATE | как у сессий + точечные share (TASK) |
| suspended | bool DEFAULT false | аварийный стоп; планировщик пропускает |
| created_at / updated_at | timestamptz | |

INDEX `(parent_task_id, status_projection)` — оценка `WAIT_TASKS / ALL_CHILDREN`. INDEX `(tags)` GIN — для `TAGGED(x)`. `owner_user_id` при создании задачи агентом наследуется от породившей сессии (см. глоссарий, Session).

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
| visibility | enum PRIVATE \| PUBLIC | плюс точечные share |
| folder_id | uuid FK folder NULL | только FREE |
| parent_session_id | uuid FK session NULL | субагентские сессии; поддерево для stop |
| locked_by | text NULL | идентификатор инстанса-владельца лока |
| locked_at | timestamptz NULL | TTL-стух лока |
| cancel_requested | bool DEFAULT false | проверяется между вызовами инструментов |
| last_turn_outcome | enum COMPLETED \| FAILED \| CANCELLED NULL | проекция исхода последнего Turn'а |
| fork_source_session_id | uuid FK session NULL | форк: источник |
| fork_seq_cutoff | bigint NULL | форк: отсечка (входит `seq ≤ cutoff`) |
| rewind_seq | bigint NULL | мягкий откат: скрыто `seq > rewind_seq` |
| archived_at | timestamptz NULL | архивация (скрытие из списков) |
| last_activity_at | timestamptz | |
| created_at | timestamptz | |

PARTIAL UNIQUE `(task_id, state_code) WHERE kind = 'STATE'` — повторный вход в состояние резюмирует ту же сессию.

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

PK `(session_id, seq)`. Видимость скрытых сообщений — производная от `COMPACT.covers` (см. `execution-model.md` §5).

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

### idempotency_key
| Поле | Тип | Примечание |
|---|---|---|
| key | text | Idempotency-Key |
| scope | text | путь эндпоинта + субъект |
| request_hash | text | детект конфликта «тот же ключ — другое тело» |
| response_json | jsonb | replay: повтор → исходный ответ |
| expires_at | timestamptz | TTL 24 ч; чистка джобой |

PRIMARY KEY `(scope, key)`.

Вебхуки задач — stateless capability-URL (`токен = HMAC(secret, kind + ':' + entityId)` в пути, см. `workflow-domain.md` §7); очередь — сама `session_message`; история попыток обработки — не хранится (D-04).

## 7. Инварианты схемы

1. `session_message` и `task_transition_history` — только INSERT; UPDATE/DELETE запрещены на уровне приложения (и проверяются ревью миграций).
2. `current_state` ∈ codes своей `workflow_revision` **или равен зарезервированному `'$CANCELLED'`** (виртуальный терминал принудительной отмены, только через `stop`); смена — только через движок переходов, транзакционно с записью в `task_transition_history` и пересчётом `status_projection`.
3. Видимость и доступ — только через `AccessPolicy` (identity), никаких ad-hoc проверок.
4. `session.locked_by/locked_at` — только CAS-переходы (взятие/освобождение), не прямые UPDATE.
5. Ревизии (`agent`, `workflow_revision`) — иммутабельны; ссылки всегда на конкретную ревизию.
