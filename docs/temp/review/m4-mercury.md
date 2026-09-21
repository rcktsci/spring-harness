# M4 Planning Review — Mercury-2.5

**Дата:** 2026-09-21  
**Ревьюер:** Mercury-2.5  
**Объект:** `openspec/changes/m4-clients-relay/` (proposal, design, tasks)

---

## Резюме

M4 PROPOSE-артефакты соответствуют правилам AGENTS.md и архитектурным контрактам M1–M3. Новые решения (D-72…D-83) имеют чёткие альтернативы и обоснования. Архитектурный слой `relay` корректно ограничен ArchUnit. Критических нарушений нет; есть уточнения по тестированию и конфигурации.

---

## 1. Архитектурная целостность

### Слой `relay`

- **Связи (tasks.md 2.1):** `relay` доступен из `api`/`execution`; `relay` → `{session, task, workspace, common}`. Это согласуется с `architecture.md` (execution- и api-слои — верхнеуровневые, домен-модули — core).
- **ArchUnit:** проверка на отсутствие обратных зависимостей от домена на `relay` соответствует существующей практике (D-48 для `task`/`workflow`).
- **Отсутствует:** явное упоминание `relay` в `docs/design/architecture.md`. Рекомендуется добавить новый секцию "relay" с описанием границ после заморозки spec.

### WebSocket и servlet-стек (D-83)

- Решение использовать `@EnableWebSocket` на сервлет-стеке корректно: проект — MVC-монолит Boot 4, WebFlux потребовал бы переписывания.
- **Риск:** холдинг потоков на WS-соединениях. Обоснование — виртуальные потоки (Boot 4). **Рекомендация:** включить метрики `http.server.requests` для WebSocket-endpoint и настроить алерт на рост активных соединений.

---

## 2. Дизайн-решения (D-72…D-83)

| ID | Решение | Альтернативы (отвергнуты) | Оценка |
|----|---------|---------------------------|--------|
| **D-72** | Canonical-path гвард только в `/workspace/files` | Общий path-guard во всём workspace-слое | ✓ Согласуется с D-41 (без лишней глубины) |
| **D-77** | WS-handshake аудит — логи, не БД | Таблица `ws_connection_log` | ✓ Один инстанс, «сломалось — пофиксили» |
| **D-78** | In-memory реестр, CAS, без распределённой блокировки | ShedLock/Redis | ✓ Один инстанс на VM, D-36/D-40 |
| **D-79** | toolset: root+клиент=CLIENT, task=SERVER | per-agent `permissions_jsonb.workspace.toolset` | ✓ Семантика владельца, минимализм |
| **D-80** | Runtime-only оверлей, не в БД | Запись в БД с revisioning | ✓ Эфемерные данные, перезапись при reconnect |
| **D-81** | Идемпотентность по `callId` (first-final-wins) | Маршрутизация по `(taskId, binding)` напрямую | ✓ Переиспользование async/MCP-контракта |
| **D-82** | `source` — информативное поле, сервер не ходит к MCP-серверам | Сервер подключается к клиентским MCP-серверам | ✓ Изоляция, D-30 (контейнеры) |
| **D-83** | Spring MVC WebSocket, не WebFlux | WebFlux/Reactive | ✓ Минимальные изменения |

### Замечания к решениям

1. **D-80 (runtime-only):** при рестарте сервера клиентские инструменты исчезают, в-flight вызовы закрываются как LOST. Это покрыто рестарт-сканом (tasks.md 5.5).
2. **D-79 (toolset):** нет явного запрета на ручное изменение `permissions_jsonb` для переключения toolset. В коде должно быть жёсткое правило: `isTaskSession()` → SERVER, иначе → CLIENT.
3. **D-77 (логирование):** убедиться, что MDC-поля (taskId, binding, sessionId, principal) включены в JSON-логгирование (operations.md).

---

## 3. Соответствие AGENTS.md

| Правило | Проверка |
|---------|----------|
| **Один инстанс на VM, без энтерпрайз-раздутия** | ✓ D-78 (in-memory registry), D-77 (audit logs), D-36/D-40 (recovery) — всё соответствует |
| **Все числовые параметры — конфиг** | ✓ D-78: `harness.relay.*` и `harness.workspace.download.*` (tasks.md 2.2) |
| **Каждая сущность — сценарий необходимости** | ✓ Новый слой relay, toolset hierarchy, workspace-download — все имеют сценарии (proposal "Why") |
| **Design-решения в `docs/design/decisions.md`** | ✓ В tasks.md 6.3 указано: D-72…D-83 вносятся в decisions.md |
| **Contract-first** | ✓ OpenAPI расширен (1.1), контроллеры на сгенерированных интерфейсах (1.2) |
| **Jackson 2 — только provided/test-scope** | ✓ В proposal указано: новые зависимости не планируются, Spring WebSocket уже в classpath |

---

## 4. Риски и trade-offs

- **Тестирование workspace-download на Windows:** canonical-path resolver на Windows dev-машине может отличаться от Linux VM.tasksРекомендация:** использовать Testcontainers с Linux-образом или эмуляцию symlink-сценариев в unit-тестах.
- **tool-not-available тупик:** в CLIENT-сессии без подключённого клиента вызов отсутствующего инструмента возвращает ERROR. Модель может зациклиться. Владелец принял (proposal Non-goals). **Рекомендация:** зафиксировать в execution-model.md как инвариант.
- **Safe-лист расширений:** бинарные файлы (отчёты, скриншоты) не скачиваются. Эволюция через медиа-allow-list (design.md 94).

---

## 5. Уточнения для реализации

1. **ArchUnit-фикстуры:** добавить тест на отсутствие зависимости от `relay` в `session.impl`, `task.impl`, `workflow.impl`.
2. **Конфиг-параметры:** явный список в application.yml:
   ```yaml
   harness:
     relay:
       heartbeat-interval: 15s
       session-grace-period: 60m
       tool-call-timeout: 5m
     workspace:
       download:
         max-bytes: 10MB
         allow-extensions: [.txt, .log, .json, .xml, .csv]
   ```
3. **Спеки:** 5 специк (workspace-download, session-api, client-tool-bridge, agent-turn, client-relay) — синхронизировать в `openspec/specs/` при архивировании (tasks.md 7.1).

---

## 6. Итог

**Статус:** Принято с замечаниями (не blocking).  
**Аппрув ревьюера:** ✓

Следующие шаги:
1. Инкорпорировать уточнения в design.md / tasks.md.
2. После реализации — прогон `mvn clean verify` и ArchUnit.
3. Архивирование M4 по завершении.
