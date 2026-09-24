# Архитектура spring-harness

> Вариант 1 (утверждён): **модульный монолит с контрактами**. Один Spring Boot 4.1 / Java 25 / Spring AI 2.0.1 / PostgreSQL. Эволюция в раннеры (control/execution split) — без смены контрактов.

## 1. Модули (жёсткие границы, ArchUnit-тест запрещает кросс-импорты мимо контрактов)

| Модуль | Ответственность | Исходящие контракты |
|---|---|---|
| `identity` | SSO-гейт (groups-claim), пользователи | (SSO-гейт — фильтр, контракта-матрицы нет, D-41) |
| `workflow` | шаблоны, ревизии, валидация графов | `WorkflowRegistry` |
| `task` | задачи, подзадачи, зависимости, suspended, история переходов | `TaskRegistry` |
| `session` | сессии, append-only сообщения, eligibility | `SessionStore` |
| `execution` | шедулер (ShedLock + poll), wake, локи, виртуальные потоки, отмена | `WorkspaceTools`, `TurnManager` |
| `intelligence` | Spring AI: ChatModel из LlmModel, агентный цикл, tool-calling, компакции | `LlmGateway` |
| `integration` | вебхуки, MCP-клиенты (SSO), будущие адаптеры (Jira/Trello/GitLab) | `InboundTriggers` |
| `api` | REST/OpenAPI + SSE, скачивание workspace-файлов (api-contracts §8) | OpenAPI 3.1 spec-first; SDK генерируется из спеки |
| `relay` | WS-релей `/api/v1/relay` (M4): handshake, реестр соединений, клиентский runtime-оверлей, маршрутизация tool-фреймов | `ClientToolBridge` (SPI в `execution`), `RelayConnectionRegistry` |

## 1a. Клиенты

spring-harness не навязывает UI — серверный контракт публичный (api-contracts.md), а UI выбирается за клиентом. На M5 первым и единственным полноценным клиентом стал **Web Desktop** (Electron + Vue 3, см. `web-desktop-client.md` и D-86…D-93).

| Клиент | Где | Контракт | Роль |
|---|---|---|---|
| Web Desktop | `web-desktop/` (Electron main + preload + Vue 3 renderer) | REST /api/v1/* + SSE §3.1/§3.2 + WS-релей §5 | Десктоп-клиент оператора: чат, дерево сессий/задач, артефакты, локальные инструменты через релей |

Архитектура клиента — D-91 (main владеет секретами и сетью; renderer — sandboxed, без Node API, JWT не покидает main). Контрактная схема server↔client — та же `api-contracts.md`; никакой отдельной схемы для «Web Desktop» нет, это просто первый серьёзный потребитель.

Будущие клиенты (evolution roadmap): тонкий браузерный клиент (потребует capability-билетов по D-42, реализация — post-M5); интеграционные боты (MCP-сервер наружу) живут в `integration` слое и через api, не как самостоятельные desktop-клиенты.

## 2. Правила зависимостей

```
api → execution → { task, session, workflow } → identity
api → relay → { execution, session }
execution ↛ relay — доменные классы знают только SPI ClientToolBridge (пакет execution),
реализация — relay, связывание — Spring (D-85)
intelligence, integration — драйвены контрактами (их знают только execution/api)
```

- ArchUnit: `api` mayOnlyAccessLayers("execution", "intelligence", "session", "identity", "task", "workflow", "relay"); `relay` — технический слой `relay → {execution, session}`; `execution ↛ relay` (строго).

- Домены `task`/`workflow` **не знают** про БД-детали сессий и про LLM — замена домена задач на Jira-адаптер или вынос исполнения в раннеры не трогает ядро.
- `WorkspaceTools` — единственная дверь к серверной ФС/bash. Реализация: `ContainerWorkspaceTools` (docker-java, per-session контейнер из helper-образа: минимальная ОС + find/grep/coreutils/git; workspace монтируется томом; имя `harness-<sessionId>` — D-30). В CLIENT-toolset нативные файловые **не резолвятся** (D-84) — их роль берёт клиентский оверлей релея (`ClientToolRegistry` в `relay`).
- Источники инструментов агента — четыре: нативные (`WorkspaceTools`), мета-инструменты (движок), MCP-клиенты и клиентский оверлей релея (D-80/D-85).

## 3. Контракты (Java-интерфейсы = документы того же ранга, что OpenAPI)

| Контракт | Модуль | Назначение |
|---|---|---|
| `WorkflowRegistry` | workflow | CRUD workflow/ревизий, валидация графа |
| `TaskRegistry` | task | создание (пин к ревизии), переходы, зависимости, suspend |
| `TaskEngine` | execution | цикл системных состояний (BASH/WAIT_*), CAS переходов, таймаут-скан; поверх `TaskRegistry` (execution → task) |
| `TriggerRegistry` | task | внутренний контракт пакета `task` (CRUD/revoke триггеров); наружу роль триггеров закрывают `TaskRegistry` + `InboundTriggers` (будущие внешние интеграции) |
| `SessionStore` | session | единственная дверь к сессиям/сообщениям (append-only) |
| `WorkspaceTools` | execution | нативные инструменты workspace по биндингу |
| `ClientToolBridge` | execution | SPI клиентского релея (D-85): `isClientSession`/`manifest`/`resolve`/`invoke`/`cancel`; реализация — `relay` |
| `TurnManager` | execution | запуск/парковка/отмена Turn'ов, локи |
| `LlmGateway` | intelligence | стриминг, отмена, счёт токенов |
| `InboundTriggers` | integration | вся внешняя входящая интеграция |

Правило оформления: Javadoc-контракт + инварианты + примеры; ревью интерфейса = ревью API.

## 4. Стек и конфигурация

- Spring Boot 4.1.1, Java 25 (виртуальные потоки для Turn'ов), Spring AI 2.0.1 (BOM).
- Автоконфигурация DataSource — **включена** (D-43): пул/`spring.datasource.*` биндит Boot, порядок инициализации **пул → preliquibase → liquibase → JPA** обеспечивает preliquibase-стартер. Отключены (`application.yml`) только автоконфиги Spring AI (клиенты собираются из `LlmModel` вручную).
- PostgreSQL + Liquibase + Preliquibase; ShedLock 7.10.1 (jdbc-template) для singleton-джоб; Keycloak (OIDC) — SSO.
- Стек-ловушки (зафиксировано ревью Т1): Boot 4 = **Jackson 3** — для jsonb нужен кастомный `FormatMapper` (`hibernate.type.json_format_mapper`) или осознанный Jackson 2; регрессия Spring AI 2.0.1 (#6915) — **в каждом `OpenAiChatOptions` задавать `.timeout()`/`.maxRetries()` явно**; в pom на M1 добавить: `docker-java` (+транспорт), MCP-клиент Spring AI, `spring-boot-starter-oauth2-resource-server`; UUID v7 — генератор владельца (проверен на Boot 3.4); пиннинга виртуальных потоков в JDK 25 нет (JEP 491).
- Деплой: оркестратор — контейнер на выделенной VM, `/var/run/docker.sock` смонтирован (docker-java создаёт helper-контейнеры — D-30); helper-образ собирается из Dockerfile в репозитории и присутствует на VM локально (pull — только backoff-обновление).
- Bootstrap: начальные `llm_credentials`/`agent` — **вручную в БД** (MVP; никакого бутстрап-кода).
- Правило конфигурации: **все числовые параметры — конфиг** (`application.yml`, `@ConfigurationProperties`): TTL/heartbeat локов, окно async, лимиты spawn, таймауты, backoff, лимит тела и т.п. Хардкод чисел в коде запрещён.
- Сборка: Maven 3.9 (wrapper), git-commit-id (git.properties), JaCoCo.

## 5. Точки эволюции (заложены, не реализуются)

| Точка | Механизм |
|---|---|
| Раннеры (control/execution split) | Turn-движок уже изолирован за `TurnManager`; вынос = другая реализация |
| Workflow во внешних трекерах (Jira/Trello) | адаптер над `TaskRegistry`/`WorkflowRegistry` |
| MCP-сервер наружу | адаптер над `InboundTriggers`/api |
| Multi-instance состояния (параллельные сессии состояния) | поле сессий состояния — список, MVP валидирует length==1 |
| Селективная компакция агентом | `COMPACT`-события уже первоклассные |
