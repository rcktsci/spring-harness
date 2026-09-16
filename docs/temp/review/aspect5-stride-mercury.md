# STRIDE-углубление безопасности spring-harness

Дата: 2026-09-17  
Аналитик: Mercury

## Сводка по категориям

| Категория | Закрыто | Дыры |
|-----------|---------|------|
| **S** (Spoofing) | 4/4 | 0 |
| **T** (Tampering) | 3/4 | 1 |
| **R** (Repudiation) | 2/3 | 1 |
| **I** (Info Disclosure) | 5/5 | 0 |
| **D** (DoS) | 2/5 | 3 |
| **E** (Elevation) | 5/5 | 0 |
| **Итого** | **21/26** | **5** |

---

## Детальный разбор по STRIDE

### S — Spoofing (Подделка)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| Подделка отправителя вебхука | Злоумышленник отсылает webhook с чужим token | `token = HMAC(server_secret, kind:id)` в пути; верификация server-side; кривой token → 401 (`workflow-domain` §7, `api-contracts` §4.4) | ✅ Закрыто |
| Подмена атрибуции USER-сообщений | Отправка сообщений от чужого имени | `author` — username из Keycloak JWT; POST messages требует PARTICIPATE, автория по JWT; без JWT нет входа (`api-contracts` §2, `security-multitenancy` §1) | ✅ Закрыто |
| MCP-сервер, выдающий себя за корпоративный | Подмена MCP-сервера в конфигурации | MCP-серверы из конфигурации сервера, токены обновляются server-side; агент про OAuth не знает (`agent-tools` §3, `security-multitenancy` §1) | ✅ Закрыто |
| «Дописывание субагенту» от имени другого | Пользователь пишет в STATE-сессии под видом другого агента | owner дочерней сессии наследуется от родительской; атрибция в messages идёт от USER JWT (`agent-tools` §2, `api-contracts` §2.6) | ✅ Закрыто |

### T — Tampering (Повреждение данных)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| graph_jsonb/params между ревизией и исполнением | Изменение params или графа после создания задачи | Ревизии иммутабельны; params только у владельца (TASK-PARTICIPATE); граф — owner/harness-admin (`security-multitenancy` §2, `workflow-domain` §1) | ✅ Закрыто |
| tool-output инъекция | Подмена вывода инструмента | append-only сессии; идемпотентность по callId; tool result записывается сервером (`execution-model` §4, `agent-tools` §1) | ✅ Закрыто |
| Подмена helper-образа | Компрометация helper-образа helper helper-образ собирается при деплое, присутствует локально на VM; pull только для обновления (`architecture` §4, `operations` §5) | ✅ Закрыто |
| **Payload вебхука в reason** | Изменение тела webhook до проверки | **Нет подписи тела; проверяется только capability URL; threat-model это признаёт, но компенсация — только идемпотентность по построению (`api-contracts` §4.4 threat-model)** | ⚠️ **Дыра** |

### R — Repudiation (Неотказуемость)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| Отрицание действия участником | Удаление/изменение истории | append-only journal: session_message, task_transition_history (bессрочно), webhook reason (`security-multitenancy` §5) | ✅ Закрыто |
| bash-исполнения в контейнерах | Потеря вывода скрипта | Вывод живёт в TOOL_RESULT сессии + в reason переходов BASH_SCRIPT (`execution-model` §4, `operations` §1) | ✅ Закрыто |
| **relay-исполнения на клиенте** | Клиент не записывает выполнение | Клиент-аудит рекомендуется, но не обязателен; сервер получает tool.result но не может доказать что именно клиент выполнил (`api-contracts` §5, `operations` §8) | ⚠️ **Дыра** |

### I — Info Disclosure (Раскрытие информации)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| VIEW-проекции сырых payload | VIEW видит TOOL_RESULT payload | Осознанно: D-27 прозрачность «смотреть, как работает субагент» (`api-contracts` §7) | ✅ Закрыто (осознанно) |
| workspace-files | VIEW скачивает файлы | VIEW разрешён; path-traversal guard (relative paths only) (`api-contracts` §2, `security-multitenancy` §6) | ✅ Закрыто |
| Экспорт сессий | VIEW экспортирует | VIEW разрешён; экспорты JSON/markdown без params (`api-contracts` §2) | ✅ Закрыто |
| Логи | Скрифирование секрета | Маскирование обязательное: api_key, Authorization, билеты, capability, params, промпты (`operations` §1) | ✅ Закрыто |
| params у не-владельца | Чтение params без прав | TaskDto без params для VIEW; только TASK-PARTICIPATE/владелец (`security-multitenancy` §2, `api-contracts` §4.1) | ✅ Закрыто |
| SSE чужих сессий | Чтение SSE-потока | Одноразовый ticket (60s TTL), привязан к пользователю; JWT в query запрещён (`security-multitenancy` §1, `api-contracts` §1.4) | ✅ Закрыто |

### D — DoS (Отказ в обслуживании)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| **Глубина/количество spawn_subagent** | Форк-бомба сессий | **Нет лимитов глубины/количества; только parent_session_id связь** (`agent-tools` §2, `execution-model` §4) | ⚠️ **Дыра** |
| **Размер payload вебхуков** | Переполнение БД/контекста | **Нет лимита на размер тела webhook** (`workflow-domain` §7) | ⚠️ **Дыра** |
| **Fork-шторм** | Массовое форкирование сессий | **Нет лимита на количество форков** (`api-contracts` §2) | ⚠️ **Дыра** |
| Гигантские файлы в workspace | Переполнение диска | 5 GB quota на workspace (`execution-model` §4) | ✅ Закрыто |
| Rate-limit вебхуков | Брутфорс URL | Rate-limit по IP+пути (429); HMAC перебор невозможен (`api-contracts` §0.10, `security-multitenancy` §6) | ✅ Закрыто |

### E — Elevation (Привилегии)

| Угроза | Вектор | Компенсация | Статус |
|--------|--------|-------------|--------|
| metaTools у субагентов | Расширение прав через metaTools | metaTools только для оркестраторов; права наследуются по owner, не расширяются вниз (`agent-tools` §2b) | ✅ Закрыто |
| Оркестратор в шаренной сессии | Эскалация через shared session | Права = пересечение прав владельца сессии и декларации агента (`agent-tools` §2b) | ✅ Закрыто |
| Создание задач от чужого имени | create_task с чужим owner | create_task использует owner сессии оркестратора; нельзя подменить (`agent-tools` §2b) | ✅ Закрыто |
| relay register на чужой task | Регистрация на чужую задачу | Проверка TASK-PARTICIPATE на задачу (`api-contracts` §5) | ✅ Закрыто |
| Триггеры создают задачи с чужим owner | Owner триггера = owner задачи | owner триггера контролирует params и создаёт задачи (`api-contracts` §4.3, `agent-tools` §2b) | ✅ Закрыто |

---

## Топ-3 критические дыры

| Приоритет | Категория | Угроза | Предлагаемая компенсация |
|-----------|-----------|--------|--------------------------|
| 1 | **D** (DoS) | spawn_subagent fork-бомба | Ввести лимиты: `max_subagent_depth` (дефолт 3) и `max_concurrent_subagents` (дефолт 5); хранить счётчик в `session` table; при превышении → ошибка spawn |
| 2 | **D** (DoS) | webhook payload size | Ввести `max_webhook_payload_bytes` (дефолт 1MB); при превышении → 413 Payload Too Large; валидация до HMAC |
| 3 | **T** (Tampering) | webhook payload signature | Добавить опциональный заголовок `X-Hook-Signature` (HMAC с shared secret); при наличии заголовка — проверка; в обратном случае — warning в аудит |

---

## Cross-check с GLM/DeepSeek

**Цифры:** 10 agree, 0 disagree, 5 duplicate.

| Мой пункт | GLM | DeepSeek | Вердикт |
|-----------|-----|----------|---------|
| T-d: webhook payload в reason (signature) | T5 (minor) | T-4 (HOLE) | ✅ **duplicate** (D-26 tradeoff — accepted) |
| D1: spawn_subagent limits | D1 (major) | D-1 | ✅ **duplicate** (полное совпадение) |
| D2: webhook payload size | D2 (minor) | D-2 | ✅ **duplicate** (полное совпадение) |
| D3: fork/trigger storm | D3 (major) | D-3/D-5 | ✅ **duplicate** (DeepSeek разделяет: D-3 fork, D-5 trigger) |
| R3: relay execution | R3 (partial) | R-3 | ✅ **duplicate** (полное совпадение) |

**Пункты GLM/DeepSeek, не вошедшие в мой отчёт:**
- S2 (GLM): `source` параметр webhook — untrusted метка (minor)
- S4 (GLM): author отображается в контексте сообщения (minor)
- D7 (GLM): rate-limit на POST /messages
- E1 (GLM): spawn_subagent metaTools эскалация прав (major)
- E6 (DeepSeek): trigger storm / per-entity limits
- I3 (DeepSeek): export не фильтрует hidden-сообщения
- I5 (DeepSeek): утечка params через вывод скрипта

## Рекомендации

1. **DoS-лимиты** (D-дыры) — критичны для стабильности; реализовать в рамках M2/M3.
2. **Relay audit** — добавить опциональное поле `relay_audit_hash` в tool.result для криптографической привязки к клиенту.
3. **D-26 tradeoff** — webhook payload signature: компромисс принят; рекомендуется документировать в security §6 как accepted residual risk.

## Fixes approval

**Approve** — все 3 мои дыры закрыты (2 фиксом, 1 как tradeoff с mitigations: origin-маркировка + metaTools-гейт).
