# Ревью D.2 (regen + wiring) — GLM-5.3-Flash

Дата: 2026-09-18. Объект: pom.xml, 6 контроллеров-стабов + SessionsController.getSessionTree, SecurityConfig. Сверка с openapi.yaml (заморожен в D.1), планом (tasks D.2), генератами в `target/generated-sources/openapi-server` (артефакты прогона dev — чтение, не сборка).

**Вердикт: REJECT** — 3 находки (1 minor / 2 nit). Wiring в целом корректен; блокирует половинчатость webhook-безопасности (M1-находка ниже) — она ровно про то, что D.2 сам и заводит.

---

## Проверка по пунктам постановки

### (1) pom apis — ✅
`apis = Agents,Sessions,SessionMessages,SessionCommands,Tasks,TaskCommands,Workflows,Triggers,Webhooks` — в обоих executions (server interfaceOnly + java native test-client). Теги спеки минус SSE (`SessionEvents`, `TaskEvents` — ручные контроллеры) дают ровно этот набор; `openApiNullable=false`, `useSpringBoot3`, `interfaceOnly`, `requestMappingMode=api_interface`, `useTags=true` — на месте; комментарий плагина обновлён (SessionEvents + TaskEvents).

### (2) jackson-databind provided — ✅ обоснование верно, проверено по генератам
Комментарий pom: provided (не test) — «(1) сгенерированные модели сервера с uniqueItems тянут @JsonDeserialize; (2) тест-клиент сериализует Jackson 2». Подтверждено: единственный сгенерированный класс с импортом `com.fasterxml.jackson.databind.*` — `AddTaskDependenciesRequest` (`@JsonDeserialize(as = LinkedHashSet.class)` на `Set<UUID> blockedBy`). Без provided main-компиляция пала бы на этом импорте. Classpath: provided = compile+test, в упаковку (BOOT-INF/lib) не попадает — main-runtime остаётся на Jackson 3. Функциональной дыры нет вдвойне: даже хотя Jackson 3 игнорирует 2.x-databind-аннотацию, поле типизировано `Set<UUID>` — дедупликация обеспечивается типом коллекции, а не аннотацией. Соответствует AGENTS («компиляционные нужды генератора — provided/test-scope»).

### (3) Контроллеры реализуют сгенерированные интерфейсы — ✅
TasksController → TasksApi (11 операций), TaskCommandsController → TaskCommandsApi (suspend/resume/stop), WorkflowsController → WorkflowsApi (5), TriggersController → TriggersApi (3), WebhooksController → WebhooksApi (2), SessionsController.getSessionTree → реализация интерфейса (SessionTreePage). Все — `se.rocketscien.harness.api`, стабы кидают `UnsupportedOperationException` с пометкой пачки; сигнатуры соответствуют генератам (blockerId после F-7, source опционален и т.д.).

### (4) WebhooksController @RequestMapping("/api") — ✅ корректен
Генерат `WebhooksApi` несёт class-level `@RequestMapping("${openapi...base-path:/api/v1}")` + method-level `PATH_HANDLE_TASK_WEBHOOK = "/webhooks/tasks/{taskId}/{token}"`. Локальная class-level `@RequestMapping("/api")` на контроллере приоритетнее интерфейсной (Spring: локальное объявление побеждает аннотацию интерфейса) → итоговый путь `/api/webhooks/tasks/{taskId}/{token}` — без `/v1`, как в §0.5/§4.4. Обход задокументирован в Javadoc. Генератор path-item `servers` действительно не поддерживает — обоснованно.

### (5) SecurityConfig — ⚠️ permitAll на месте, но не до конца (см. M-1)
`requestMatchers("/api/webhooks/**").permitAll()` стоит **перед** `/api/v1/**`.authenticated(), `anyRequest().denyAll()` — порядок авторизации верный; остальное — механика M1 без изменений (дифф подтверждает: добавлены 3 строки).

### (6) SessionsController.tree — ✅
`getSessionTree(UUID id, Integer depth)` реализует сгенерированный метод, возвращает `SessionTreePage` — схема с `taskId`/`stateCode` для STATE-узлов (см. D.1, SessionTreeNode); реализация — честный стаб под пачку J/K.

---

## Находки

### M-1 (minor). permitAll не спасает вебхуки от BearerTokenAuthenticationFilter: чужой `Authorization: Bearer` даст 401 unauthenticated вместо HMAC-семантики
- **Цитата**: SecurityConfig: `.requestMatchers("/api/webhooks/**").permitAll()` + `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` — одна цепочка фильтров.
- **Проблема**: `BearerTokenAuthenticationFilter` выполняется **до** авторизации на всех путях цепочки. Если внешний отправитель вебхука прислал любой Bearer-заголовок (свой токен внешней системы, прокси-авторизация, ошибочный реюз), резолвер его подхватит, валидация против Keycloak упадёт → entry point → `401 code=unauthenticated` — контроллер с HMAC-проверкой не вызовется. Контракт же требует для вебхуков только capability-токен: чужой Bearer не должен менять исход (должно быть 202/409/401 signature-invalid по HMAC). То же касается GroupsGateFilter — он в той же цепочке.
- **Предложение**: выделить вебхуки в отдельную `SecurityFilterChain` с `securityMatcher("/api/webhooks/**")` (`@Order` ниже основной): без oauth2ResourceServer и группового фильтра, всё permitAll; основная цепочка — `securityMatcher("/api/v1/**")` как раньше. Альтернатива дешевле: кастомный `BearerTokenResolver`, игнорирующий пути `/api/webhooks/**`. Плюс интеграционный тест в пачке L: вебхук с мусорным `Authorization: Bearer x` → НЕ 401 unauthenticated (ожидаемо 401 signature-invalid).

### N-1 (nit). Маппинг «класс побеждает интерфейс» стоит закрепить тестом
- **Цитата**: WebhooksController Javadoc: «Class-level @RequestMapping("/api") — осознанный обход ограничения openapi-generator (path-item servers не поддержан)».
- **Проблема**: корректность держится на поведении Spring (локальная аннотация класса важнее интерфейсной) — не формализовано; смена версии Spring/generator'а или случайное удаление `@RequestMapping("/api")` молча сдвинет вебхуки на `/api/v1/webhooks/**`, и SecurityConfig их перестанет пропускать (denyAll).
- **Предложение**: в пачке L добавить MVC/mockmvc-тест целевых путей вебхуков (`/api/webhooks/tasks/{taskId}/{token}` — 401 signature-invalid, не 404/401 unauthenticated) — он же закроет M-1 регрессией.

### N-2 (nit). TaskEventsController дублирует путь строкой — нет связи с замороженной спекой
- **Цитата**: `@GetMapping(path = "/api/v1/tasks/{id}/events", produces = TEXT_EVENT_STREAM_VALUE)` — путь/параметры набраны вручную (унаследованный паттерн SessionEventsController).
- **Проблема**: при изменении пути/курсора в openapi.yaml ручной контроллер молча разъедется со спекой и тест-клиентом (генерация исключает тег TaskEvents).
- **Предложение**: пачка J: сослаться на константы из генератов (если будут) или добавить acceptance-тест совпадения пути/параметров со спекой; в комментарии контроллера уже есть якорь — достаточно теста.

---

## Что сделано хорошо

- Обход path-item `servers` задокументирован с указанием причины (ограничение генератора), а не «как получилось».
- Единый шаблон стаба (`UnsupportedOperationException("D.2: stub — ...")`) — упадёт громко и понятно при забытой реализации.
- jackson-databind: смена scope обоснована в pom **и** проверяема по генератам (ровно один класс с databind-импортом) — комментарий не врёт.
- Порядок `permitAll(webhooks) → authenticated(/api/v1) → denyAll` соответствует §4.4/§7 (всё вне явного — закрыто).

## Вердикт

**REJECT** — 3 находки (1 minor / 2 nit). M-1 — однострочная перестройка SecurityConfig на две цепочки (или резолвер) + тест в L; после неё и nit-фиксов — approve.

---

# Re-approval D.2 (2026-09-18)

## Статус моих находок

- **M-1 (Bearer-фильтр на вебхуках)** — **закрыто**: SecurityConfig развёрнут в две цепочки — `webhooksFilterChain` `@Order(1)` с `securityMatcher("/api/webhooks/**")`, без oauth2ResourceServer и без GroupsGate/UserSync (permitAll; контроль — HMAC в контроллере), основная цепочка — `securityMatcher("/api/v1/**")` с прежней механикой. Закреплено тестом `foreignBearerDoesNotTriggerJwtGate` (мусорный Bearer на вебхуке → 401 от HMAC-гейта, не от JWT).
- **N-1 (маппинг вебхуков без теста)** — **закрыто**: `WebhooksRoutingTest` — 5 тестов: bad token → 401 signature-invalid; foreign Bearer → 401 (не JWT-гейт); valid token → 501 stub; `/api/v1/webhooks/**` → 401 unauthenticated (пути без /v1 не зарегистрированы); basePath сгенерированного клиента (F-1).
- **N-2 (ручной путь TaskEventsController)** — **закрыто отложкой** (моё же предложение «в пачке J»): Javadoc уточнён («ручной SseEmitter по api-contracts §3.2; сгенерированный интерфейс исключён из генерации»), тест дрейфа — в пачке J вместе с реализацией.

## Проверка фикс-набора dev

- **F-4 (501 not-implemented)** — согласовано в трёх местах: api-contracts §6:132 («метод ещё не реализован в текущем apply-проходе (stubs); не ошибка контракта»), ProblemCode-enum openapi.yaml:1464, ProblemCodes.java. Формального конфликта с §0.2 больше нет; код транзишнл — к приёмке (M.2/M.3) все 501 обязаны исчезнуть (стабы заменяются в H/I/J/K/L — отслеживать в apply-notes пачек).
- **F-1 (basePath клиента)** — закреплён тестом `generatedClientResolvesApiBasePath`.
- **Ранний HMAC-гейт в WebhooksController** — wiring-качество: constant-time сравнение (`MessageDigest.isEqual`), секрет из `WebhookProperties` (конфиг), рефактор в чистый `WebhookSignatureVerifier` (L.2) задокументирован в Javadoc. Замечаний нет.
- `SignatureInvalidException` → 401 signature-invalid, без challenge — соответствует спеке.

## Вердикт re-approval

**APPROVE** — незакрытых: 0. M-1/N-1/N-2 закрыты, F-набор согласован с каталогом ошибок; wiring готов к пачкам H/I/J/K/L. Напоминание в план: исчезновение всех 501 — контрольный пункт пачки M.
