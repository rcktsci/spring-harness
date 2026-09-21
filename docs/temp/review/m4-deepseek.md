# Ревью ченджа `m4-clients-relay` (плановые артефакты)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-22.
> Объект: `openspec/changes/m4-clients-relay/{proposal.md, design.md, tasks.md, specs/*/spec.md}`.
> Контекст: `docs/design/{api-contracts,architecture,agent-tools,execution-model,workflow-domain,data-model,roadmap,decisions,glossary}.md`, M1–M3-спеки `openspec/specs/*`, код M1–M3 (`AgentTurnEngine`, `TurnManagerImpl`, `AsyncToolExecutor`, `SubtreeCanceller`, `RestartScanRunner`, `NativeAgentTools`, `ContainerWorkspaceTools`, `Application.yml`, `openapi.yaml`, `pom.xml`).
> `openspec validate m4-clients-relay --strict` → **exit 0** («valid»). Сборки не запускались — выводы по коду статичны, без компиляции.
> Severity: **HIGH** — ломает контракт/неисполнимо/регресс; **MEDIUM** — внутреннее противоречие/расхождение/гонка; **MINOR/NIT** — косметика.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 4 |
| MEDIUM | 9 |
| MINOR/NIT | 6 |
| **Итого** | **19** |

Особые зоны промпта: race conditions реестра (M-2, M-3, M-4), grace/ERROR (M-5), tool-not-available тупик (H-3), canonical-гвард symlink TOCTOU (M-8), совместимость M1–M3 (H-2, H-4, M-2, M-4, M-9).

---

## HIGH

### H-1. `D-79` (root FREE = CLIENT / task = SERVER) противоречит модели workspace-binding D-11/D-12, `agent-tools.md §1`, `api-contracts §5` и приёмке 6.2
- **Пункт:** design.md D-79 (стр. 24–30); proposal.md:9,20; specs/client-tool-bridge/spec.md:25–40; specs/client-relay/spec.md:30; tasks.md:40.
- **Цитата:** D-79: «Root FREE-сессия с зарегистрированным WS-клиентом → CLIENT… Сессии задач (`create_task` → собственный `session_id`, ролевой агент) → SERVER **жёстко**». Register: «`register { taskId, binding, … }` … регистрация исполнителя workspace `(taskId, binding)`» (spec:30).
- **Проблема:** по канону `CLIENT_EXEC` — это **тип workspace состояния** (`workflow-domain §2`: `workspace: { type: CLIENT_EXEC }`; D-11), а нативные инструменты «исполняются **по биндингу состояния**: `ContainerWorkspaceTools` … или `ClientRelayWorkspaceTools` (релей на подключённый клиент)» (`agent-tools.md:7`, `architecture.md:28`). Значит релей должен обслуживать **STATE-сессию состояния с CLIENT_EXEC**, а не «root FREE». Следствия:
  1. **Register физически не может адресовать root FREE-сессию** — у неё нет `taskId` (SessionDto.taskId только у STATE, `openspec/specs/session-api/spec.md:26`), а фрейм требует `taskId`. D-79-сценарий «root FREE + клиент = CLIENT» нереализуем заявленным протоколом.
  2. **Task-сессия с CLIENT_EXEC-состоянием не исполнится на клиенте** — D-79 объявляет task-сессии SERVER жёстко, но именно их workspace объявлен `CLIENT_EXEC`; приёмка 6.2 требует обратного («офис A регистрируется, исполняет bash/READ-вызовы» для задачи с CLIENT_EXEC).
  3. `TaskDto.workspaceBindings: [{stateCode, logicalKey}]` (openapi.yaml:1877–1889) и `register(taskId, binding)` реализуют модель **per-state binding**, прямо отрицаемую D-79.
- **Предложение:** выбрать одну модель. Рекомендую канон: единица маршрутизации — **STATE-сессия, чьё текущее состояние объявляет `workspace.type=CLIENT_EXEC`** (native-инструменты этой сессии уходят в релей; root FREE-сессии с SERVER_DIR остаются серверными). Либо, если действительно нужен «FREE + клиент», явно `supersede` D-12/agent-tools и добавить в register адресацию FREE-сессии — с переписыванием приёмки 6.2. Текущий набор артефактов сам себе противоречит.

### H-2. `agent-turn` MODIFIED «Отмена Turn'а (stop)» откатывает R-1 (персистентный `cancel_requested`) — регресс кода M3 и `SubtreeCanceller`
- **Пункт:** specs/agent-turn/spec.md:26–28 (MODIFIED) против `openspec/specs/agent-turn/spec.md:44` (R-1 supersede) и кода.
- **Цитата (M4):** «Флаг `cancel_requested` сбрасывается при завершении Turn'а (любой исход) и на старте нового Turn'а; stop по сессии без активного Turn'а не имеет последующего эффекта».
- **Проблема:** это дословно **M1-редакция** (`openspec/specs/agent-turn/spec.md:106`), тогда как M3 mini-amendment R-1 сделал флаг персистентным: `TurnManagerImpl.tryStart` — «под `cancel_requested` — попытка запуска без эффекта» (`TurnManagerImpl.java:59–66`), сброс только явным resume (`AgentStateBootstrapper.java:51`, `USER`-допись), `SubtreeCanceller` полагается на персистентность (спека:52–54). M4 берёт устаревший текст из line 106 и, помечая его MODIFIED, **переопределяет** действующее R-1-поведение обратно на M1 — это функциональный регресс (остановленная сессия начнёт самоподниматься POLL/поздним результатом).
- **Предложение:** в MODIFIED-тексте сохранить R-1: «флаг персистентен, не сбрасывается автоматически; `tryStart` при флаге — no-op; сброс — только явным resume» (можно просто не трогать строку 106, кроме добавления клиентского `tool.cancel`).

### H-3. Manifest ≠ resolver: в CLIENT-toolset нативные инструменты всё равно исполнятся серверно; клиентский `bash` не доедет до клиента
- **Пункт:** specs/agent-turn/spec.md:7; specs/client-tool-bridge/spec.md:25,44; design.md D-82; код `AgentTurnEngine.java:400–454`.
- **Цитата:** «В `CLIENT`-toolset нативные файловые инструменты … **НЕ подгружаются в манифест**»; порядок резолва: «(1) серверный `ToolCallback` (native + metaTools + MCP); (2) client_provided-оверлей».
- **Проблема:** фильтрация только **манифеста** не защищает исполнение:
  1. `AgentTurnEngine.executeToolCall` после известных имён безусловно вызывает `agentTools.execute(...)` (`AgentTurnEngine.java:448–453`), а `NativeAgentTools.execute` для `read_file|write_file|edit_file|bash|glob|grep` всегда бьёт в **серверный** `WorkspaceTools` (`NativeAgentTools.java:83–101`). Значит галлюцинированный `bash` в CLIENT-сессии исполнится на сервере — «SERVER по факту», что противоречит цели CLIENT_EXEC и D-79.
  2. Если клиент **декларирует** инструмент с именем `bash`/`read_file` (spec client-tool-bridge:11 «клиент либо декларирует их сам»), порядок «серверный колбэк → оверлей» отправит вызов в серверный native, а не клиенту.
  3. `isAsyncTool("bash")` (`AgentTurnEngine.java:466–477`) смотрит на **серверные** `asyncCapabilities` — клиентский `bash` будет классифицирован как async и уйдёт в `AsyncToolExecutor.execute` → снова серверный native. Ломается и окно, и маршрут.
  4. Манифест собирается **один раз на Turn** (`AgentTurnEngine.java:118–133`), поэтому при разрыве клиента посреди Turn его инструменты остаются в манифесте до конца Turn'а — модель гарантированно зациклится на `tool-not-available` (принятый владельцем риск, но здесь он усилен stale-манифестом). Появление клиента посреди Turn тоже не видно до следующего Turn'а, что расходится со спекой client-tool-bridge:60–63 («пропадают из манифестов … до reconnect»: в активном Turn'е — не пропадают).
- **Предложение:** гейтить не манифест, а **резолвер** (в CLIENT-toolset не вызывать `agentTools`; клиентский оверлей — до native-фолбэка для коллизий имён), вести async-классификацию только по серверным колбэкам, и пересобирать манифест/пере-резолв учитывать динамику соединения (минимум — задокументировать staleness внутри Turn'а).

### H-4. `PARKED_CLIENT` перетирается `TurnManagerImpl` и теряется при рестарте; «задача ждёт исполнителя» не выражается текущим `runtimeStatus`-контуром
- **Пункт:** specs/session-api/spec.md:7–22; tasks.md:31–32; design.md D-78; код `TurnManagerImpl.java:104–106`, `InMemorySessionEventBroadcaster.java:43–48`.
- **Цитата:** «`PARKED_CLIENT` — … присваивается при входе задачи в CLIENT_EXEC-состояние и снимается при успешной регистрации… восстанавливается при разрыве соединения».
- **Проблема:** `runtimeStatus` публикуется `TurnManagerImpl` в `finally` **безусловно** как `IDLE`/`PARKED_ASYNC` по `TurnResult.parkedAsync` (`TurnManagerImpl.java:100–106`) — о задаче/CLIENT_EXEC он не знает. При входе в CLIENT_EXEC `AgentStateBootstrapper` поднимает Turn (`AgentStateBootstrapper.java:46–53`), тот публикует `TURN_RUNNING`, затем `IDLE` — императивно выставленный `PARKED_CLIENT` немедленно перетирается. При разрыве соединения **во время активного Turn'а** (типовой случай: адаптер ждёт `tool.result`) Turn завершится и опубликует `IDLE`, а не `PARKED_CLIENT`. Плюс статус in-memory и на рестарте сбрасывается в `IDLE` (`InMemorySessionEventBroadcaster.java:45–47`), т.е. «задача остаётся в CLIENT_EXEC и ждёт» после рестарта из `runtimeStatus` не видна. И, наоборот, если клиента нет и Turn крутится на `tool-not-available`, статус не должен быть `PARKED_CLIENT` — а он периодически будет.
- **Предложение:** сделать `PARKED_CLIENT` **производным** (как `parkedAsync`): в `TurnResult`/окончании Turn'а учитывать «сессия связана с CLIENT_EXEC-состоянием задачи **и** клиент не подключён», а на снапшоте — восстанавливать из task-state (не только из volatile-карты). Задание 5.1 «присвоение при входе задачи в CLIENT_EXEC-состояние» без этого нереализуемо корректно.

---

## MEDIUM

### M-1. «WebSocket-поддержка уже в classpath» — неверно; новых зависимостей не избежать
- **Пункт:** proposal.md:34; design.md D-83 (стр. 80–86); pom.xml.
- **Цитата:** «**Зависимости**: WebSocket-поддержка Spring (уже в classpath через Boot starter) — новых артефактов не планируется».
- **Проблема:** `spring-boot-starter-web` (pom.xml:90–93) **не содержит** `org.springframework:spring-websocket` (API `@EnableWebSocket`/`WebSocketHandler`); отдельный стартер `spring-boot-starter-websocket` в pom отсутствует. Проверка локального репозитория (`D:\.java\.m2\org\springframework`): `spring-websocket` нет вовсе (есть только `tomcat-embed-websocket`/`jakarta.websocket` из контейнера, чего для Spring-ового `WebSocketHandler` мало). Утверждение Impact-секции ложно; нужен новый артефакт.
- **Предложение:** добавить `spring-boot-starter-websocket` (последней версии, Boot-managed) в план/Impact; убрать тезис «новых артефактов не планируется».

### M-2. Реестр: `unregister(key)` без identity-check вытеснит новое соединение; «идемпотентность re-register» противоречит «occupied → 4409»; half-open reconnect ловит 4409 внутри grace
- **Пункт:** design.md D-78 (стр. 72–78), D-81:40–46; tasks.md:12; proposal.md:19; specs/client-relay/spec.md:30,92.
- **Проблема:**
  1. `RelayConnectionRegistry.unregister` в формулировке задачи 2.3 — обычное удаление по ключу. Если heartbeat-таймаут старого сокета сработает **после** реконнекта (старый сокет не получил FIN → detected death до 2×heartbeat = 30 с), `remove(key)` старого соединения снесёт **новое** — реестр опустеет при живом клиенте. Нужен CAS-по-identity (`remove(key, expectedConnection)`).
  2. «Идемпотентность re-register» (proposal:19) не определена: спекулятивный повтор `register` с **того же** соединения упрётся в «workspace уже занято живым соединением → 4409» (spec:30,40). Кто именно «идемпотентен» — то же соединение? новый сокет того же клиента?
  3. Сценарий «переподключение в пределах grace» (spec:94–97) для half-open сокета **не сработает**: старый ещё не признан мёртвым (до 30 с), а новый `register` получит `workspace-occupied` 4409. Нужно явно разрешить takeover (по аутентификации/владельцу) либо апеллировать к таймауту.
- **Предложение:** `register`/`unregister` — CAS по identity; определить takeover-политику (тот же principal/owner вытесняет «протухшее» соединение) и сценарий повторного `register` с того же соединения (idempotent success, не 4409).

### M-3. Конкурентные `send` в один `WebSocketSession` из нескольких виртуальных потоков (root + субагенты)
- **Пункт:** design.md D-81 (маршрутизация по сессии), D-83 (виртуальные потоки); tasks.md:12–13,24; specs/client-relay/spec.md:49.
- **Проблема:** одна WS-сессия релея обслуживает root-сессию **и** её `spawn_subagent`-подсессии (D-79: sub-сессии наследуют CLIENT). `tool.call`/`tool.cancel` для разных сессий могут испускаться из разных виртуальных потоков (Turn'ы параллельны), плюс `ping` — из планировщика heartbeat. `org.springframework.web.socket.WebSocketSession.sendMessage` **не потокобезопасен** (JSR-356: concurrent sends требуют синхронизации) — возможны порча кадров/IllegalStateException. В плане нет ни send-мьютекса, ни очереди исходящих кадров.
- **Предложение:** ввести единую исходящую очередь/мьютекс на соединение (или `ConcurrentWebSocketSessionDecorator`), явно указать в задачах пачки T.

### M-4. «Первый финальный выигрывает» для клиентских вызовов не задан и не стыкуется с M3-механизмом (`sess`-лок + `hasToolResultForCall`)
- **Пункт:** design.md D-81:40–46; specs/client-relay/spec.md:49,56–64,73; код `AsyncToolExecutor.java:120–155`, `SubtreeCanceller.java:60–86`, `SessionStore.hasToolResultForCall`.
- **Проблема:** в M3 идемпотентность финалов обеспечивается **под программным локом `sess-{id}`** через `hasToolResultForCall` (D-64). Клиентский `tool.result` приходит на WS-поток, тогда как Turn держит `sess`-лок (адаптер блокирующе ждёт результат). Значит WS-поток лок взять не сможет — журналировать должен Turn-поток. При этом гоняются: поздний `tool.result`, синтетический `tool-call-timeout` (ERROR) и `LOST` при разрыве. Механизм «first-final-wins» (in-memory map + tombstones) и время их жизни в плане не описаны → риск двойной финальной записи, что нарушит инвариант M3.
- **Предложение:** явно закрепить: completion-map на соединение (`callId → future`), tombstones до конца соединения, запись финала — только Turn-поток; описать взаимодействие с рестарт-сканом LOST и `tool-call-timeout`.

### M-5. Grace-период: нет durable-таймера, нет отмены Turn'а при ERROR, не стартует без disconnect, stale после рестарта/возврата в состояние
- **Пункт:** design.md D-78, Risks:91–92; tasks.md:34–35; specs/client-relay/spec.md:90–102.
- **Проблема:**
  1. Реестр соединений in-memory (D-78): timestamp disconnect теряется при рестарте → grace-джоба после рестарта **никогда не сработает** (а `PARKED_CLIENT` тоже потерян — H-4) → задача «навсегда» в CLIENT_EXEC.
  2. Grace стартует только по disconnect. Сценарий «клиент вообще не подключился» таймера не имеет — ждём kind-таймаут `agent: 24h` (`application.yml:46`).
  3. ERROR-переход вызывается `TaskEngine.processTaskTransition` (CAS `current_state = from`), но активный AGENT-Turn сессии при этом **не останавливается** — модель продолжает крутиться на `tool-not-available` и писать в журнал сессии состояния, из которого задача уже вышла. В плане (5.4) нет cancel/stop сессии перед переходом.
  4. Нет generation/attempt-защиты: если задача вернётся в тот же CLIENT_EXEC-код в пределах grace, старый таймер может выстрелить ERROR (CAS спасёт только при другом `from`; при том же — ложный переход).
  5. AGENT-состояния не обязаны иметь ERROR-ребро (workflow-domain §2 правило 4 требует ERROR только от BASH_SCRIPT) → «иначе остаётся» — вероятный основной исход (принято владельцем, но стоит зафиксировать).
- **Предложение:** durable-хранение факта/дедлайна ожидания (или явно принять «grace переживает только рестарт-скан, не таймер»), остановка Turn'а перед ERROR-переходом, generation-токен для возврата в состояние, отдельный таймер и для «никогда не подключился».

### M-6. `binding`/`logicalKey` не определены в graph-схеме, а OpenAPI `WorkspaceBinding` не умеет CLIENT_EXEC
- **Пункт:** specs/client-relay/spec.md:30; design.md D-79:26–28; openapi.yaml:1487–1499 (`WorkspaceBinding`, enum `[SERVER_DIR]`, без `pathTemplate`/`logicalKey`), :1877–1889 (`workspaceBindings`); tasks.md:3–5.
- **Проблема:** `register { taskId, binding }` и `TaskDto.workspaceBindings[{stateCode, logicalKey}]` требуют источник `logicalKey`, но `WorkflowWorkspace` (workflow-domain §2, openapi.yaml:~2295) `logicalKey` не объявляет и валидатор графа его не знает (`WORKSPACE_TYPES = SERVER_DIR, CLIENT_EXEC`). `SessionDto.workspaceBinding` (openapi.yaml:1487–1499) — enum `[SERVER_DIR]` без `pathTemplate`/`logicalKey`, значит CLIENT_EXEC-биндинг не выражается. Task 1.1 правит OpenAPI только под download-эндпоинт + коды ошибок. Без этого register не может резолвить binding.
- **Предложение:** добавить `logicalKey` в graph-схему состояния (и в `WorkflowGraphSchemaValidator`), расширить `SessionDto.workspaceBinding` до CLIENT_EXEC + `logicalKey`/`pathTemplate`, наполнять `TaskDto.workspaceBindings`; внести `binding-required`, `task-not-client-exec`, `duplicate-tool-name` в каталог §6.

### M-7. workspace-download и CLIENT_EXEC-роуминг физически не сходятся: сервер не видит файлов клиента
- **Пункт:** specs/workspace-download/spec.md:5,11,35; design.md D-72:56–62; tasks.md:40; `application.yml:99`; `ContainerWorkspaceTools`.
- **Проблема:** корень гварда/отдачи — `workspaces/sessions/{sessionId}` (задан `harness.docker.workspace-root: workspaces/sessions`, `workspaceDir = workspaceRoot/<id>`), т.е. **серверный** каталог сессии, который монтирует `ContainerWorkspaceTools`. При CLIENT_EXEC файлы лежат **локально у клиента** (`client-cli.md`: `basePath = cwd`; design D-79 «basePath — локальный корень клиента, информативно»). Приёмка 6.2 «офис B: `GET /workspace/files` скачивает файлы» после записи офисом A у клиента без общей ФС невозможна. Общая ФС/NFS-допущение нигде не заявлено.
- **Предложение:** либо заявить и задокументировать shared-FS-допущение (что противоречит «basePath информативно»), либо вывести download из роуминг-приёмки и оставить его для SERVER_DIR-сессий; определить, чьи файлы отдаёт эндпоинт для CLIENT_EXEC.

### M-8. Canonical-гвард: TOCTOU между realpath и open; streaming-обрыв «413» невыразим в HTTP
- **Пункт:** specs/workspace-download/spec.md:35,68; tasks.md:3,17–18; design.md D-72:56–62.
- **Проблема:**
  1. **TOCTOU (symlink)**: «realpath каждого сегмента … затем streaming» — классический разрыв check→open. Между резолвом канонического пути и `Files.newInputStream` в workspace (запись в который контролирует агент/клиент через bash в контейнере) symlink можно подменить на `/etc/passwd`. Спека не задаёт открытие без следования по symlink на уровне дескриптора (посегментный `O_NOFOLLOW`/`openat` + проверка `realpath` уже **открытого** fd, либо проверка по inode/fileKey внутри корня).
  2. **413 после старта потока**: «отдаётся 10 МБ, затем обрыв с 413-маркером» — HTTP-статус после отправки заголовков `200` невозможен; task 1.1/OpenAPI при этом перечисляют `413` как ответ. Либо предварительный `Files.size` и честный `413` до стрима, либо отказ от 413-контракта и документирование усечения (Content-Length/обрыв).
- **Предложение:** описать безопасное открытие fd-first/`O_NOFOLLOW`; развести «лимит до стрима → 413» и «усечение потока» как взаимоисключающие варианты, оставить один.

### M-9. ArchUnit-план `relay` даёт цикл `execution ↔ relay` и ссылается на несуществующий модуль `workspace`
- **Пункт:** tasks.md:10,12,24; `ArchitectureRulesTest.java:80–106`.
- **Проблема:** «`relay` → `{session, task, workspace, common}`» — (а) модуля `workspace` в проекте нет (есть `WorkspaceTools` в `execution`); (б) `relay/ClientToolAdapter` по задаче 4.2 должен отдавать `ToolResult` и использовать `TurnCancellation` (оба — `execution`) → `relay → execution`; одновременно `AgentTurnEngine` (`execution`) резолвит клиентский оверлей и зовёт адаптер → `execution → relay`. Получается цикл, запрещённый `noCyclesBetweenModules`; в заявленных правилах слоёв ни одно из направлений не разрешено.
- **Предложение:** выбрать одно направление (например, релей — часть `execution`-слоя, либо узкий SPI-интерфейс в `execution`, реализуемый `relay`), обновить `MODULE_LAYERING`/`noCycles`/`noDomainModuleDependsOnApi`/neg-фикстуры, убрать фантомный `workspace`.

---

## MINOR / NIT

### N-1. `agent-turn` delta: два блока `## MODIFIED Requirements` с `RENAMED` между ними
- **Пункт:** specs/agent-turn/spec.md:24,50,55.
- **Проблема:** `openspec validate --strict` проходит, но требование, которое одновременно `RENAMED` и `MODIFIED` (Рестарт-скан), при `archive` может дать двойного владельца/расхождение. Разбиение MODIFIED на два блока нестандартно.
- **Предложение:** сверить семантику RENAMED+MODIFIED в OpenSpec; при возможности — один MODIFIED-блок с новым именем.

### N-2. `agent-turn` MODIFIED «Рестарт-скан…» дублирует/переопределяет целиком, а не дельту
- **Пункт:** specs/agent-turn/spec.md:57–74 против `openspec/specs/agent-turn/spec.md:123–135`.
- **Проблема:** ок; просто отметить, что добавленные сценарии — только клиентский LOST, остальное копия. Не блокер.

### N-3. `client-cli.md`/`agent-tools.md` остаются нерассинхронизированными
- **Пункт:** tasks.md:42; proposal.md:35.
- **Проблема:** proposal говорит «execution-model: источники два → три», но `agent-tools.md:7` (ClientRelayWorkspaceTools), `client-cli.md` (отменённый CLI), `architecture.md:16,28` не упомянуты в задачах/Impact; направление «native-через-релей» vs «клиентский оверлей» — как раз суть H-1/H-3.
- **Предложение:** задачи на синк этих доков (или явный supersede).

### N-4. Расхождение имён инструментов/namespace
- **Пункт:** api-contracts.md:109 (`tool: BASH|READ|WRITE|EDIT|GLOB|GREP`) vs native `read_file/bash/...` (`NativeAgentTools.java:29`); design D-82:48–54 (`client.mcp:<server>`) vs MCP-namespace `server.tool` (M3).
- **Предложение:** зафиксировать канонический нейминг клиентских инструментов в §5 (нижний регистр native-имён или произвольные имена декларации — не смешивать).

### N-5. Мелкие неточности
- **Пункт:** design.md:94 — mojibake `操作上nеудобства` («операционные неудобства»).
- workspace-download spec:54 — расширение `gitignore` без точки; регистр расширений (`.PNG`) не оговорён.
- Ошибки `file-not-found`/`path-invalid`/`extension-not-allowed`/`task-not-client-exec`/`duplicate-tool-name`/`binding-required` отсутствуют в каталоге §6 (tasks 1.1 частично закрывает).

### N-6. Heartbeat-направление
- **Пункт:** specs/client-relay/spec.md:73–77 (сервер шлёт `ping`, клиент `pong`) vs api-contracts.md:112 (`ping`/`pong` ←→).
- **Предложение:** свести к одному (кто инициатор, кто закрывает по 2×) в финальной §5.

---

## Проверка по чек-листу промпта

| # | Пункт | Статус |
|---|---|---|
| 1 | Race в реестре (register/disconnect/tool.call/stop) | ❌ M-2 (unregister/identity, reconnect-4409), M-3 (concurrent send), M-4 (first-final-wins вне sess-лока) |
| 2 | Grace-период и ERROR-переход | ❌ M-5 (нет durable-таймера, нет stop Turn'а, старт только с disconnect), H-4 |
| 3 | tool-not-available тупик агента | ❌ H-3 (stale-манифест внутри Turn'а + native-фолбэк), принятый риск усилен |
| 4 | Canonical-гвард (symlink TOCTOU) | ❌ M-8 (TOCTOU check→open; «413 после стрима») |
| 5 | Совместимость M1–M3 (runtimeStatus/restart-scan/SubtreeCanceller) | ❌ H-2 (R-1 регресс), H-4 (runtimeStatus), M-4/M-9 (restart-scan LOST, SubtreeCanceller не шлёт `tool.cancel`) |
| 6 | Контракты/спека заморожены до кода | ⚠️ M-6 (OpenAPI binding), M-10 ниже — WS §5 не в артефактах |
| 7 | Внутренняя непротиворечивость | ❌ H-1 (D-79 vs D-11/D-12/register/6.2) |

### M-10 (дополнение к чек-листу 6). Ревизия WS-протокола не входит в артефакты ревью
- **Пункт:** tasks.md:3–5; api-contracts.md:101–115 (по-прежнему M1-редакция: `tool: BASH|READ|...`, нет формы `error`-фрейма, нет полей `welcome`, направление heartbeat).
- **Проблема:** delta-спеки задают семантику, но конкретный wire-контракт (`error { code, message }`, `welcome { protocol }`, close-коды 4401/4403/4409 и когда какой, порядок handshake) в ревьюируемых артефактах не зафиксирован — для contract-first это «код раньше заморозки». Задача 1.3 лишь ссылается на будущую правку §5.
- **Предложение:** включить проект §5 в change (или вынести отдельным артефактом) до заморозки.

---

## Вердикт

**REJECT — 19 находок (4 HIGH: H-1 D-79 vs workspace-binding/приёмка, H-2 регресс R-1 `cancel_requested`, H-3 manifest≠resolver/native-фолбэк, H-4 `PARKED_CLIENT` перетирается и теряется; 10 MEDIUM: M-1…M-10; 6 MINOR/NIT).**

Блокеры заморозки: **H-1** (архитектурная модель маршрутизации — D-79 или D-12, не оба), **H-2** (совместимость отмены M3), **H-3** (резолв инструментов CLIENT), **H-4** (жизненный цикл `PARKED_CLIENT`), **M-2/M-4** (гонки реестра и финалов), **M-5** (grace/ERROR), **M-6** (binding/OpenAPI), **M-8** (symlink-гвард). После правок требуется повторный проход и re-approve.
