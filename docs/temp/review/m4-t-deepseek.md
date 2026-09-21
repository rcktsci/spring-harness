# Ревью M4 batch T (relay infra), commit 14ce35c

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-22.
> Объект: `pom.xml`, `src/main/resources/application.yml`, `config/{RelayProperties,WorkspaceDownloadProperties}.java`, `execution/{ClientToolBridge,ToolDescriptor}.java`, `identity/SecurityConfig.java`, `relay/*` (7 классов), тесты (`ArchitectureRulesTest`, `ConfigPropertiesBindingTest`, `RelayConnectionRegistryTest`, фикстуры ArchUnit).
> Контекст: спека `openspec/changes/m4-clients-relay/specs/client-relay/spec.md`, дизайн D-78/D-83/D-85, `docs/design/api-contracts.md §5`, M1–M3-канон (`TurnManagerImpl`, `SessionLockManager` D-J-1, `SessionStore`, `GroupsGateFilter`).
> Сборки не запускались; verify заявлен зелёным (502 теста).
> Severity: **HIGH** — ломает контракт/безопасность; **MEDIUM** — гонка/баг/пробел покрытия; **MINOR/NIT** — косметика.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 0 |
| MEDIUM | 3 |
| MINOR/NIT | 12 |
| **Итого** | **15** |

Проверяемые пункты промпта: (1) takeover/CAS — ✅ корректно (T-8, T-9 — остаточное); (2) handshake 4401/4403 — ✅ соответствует спеке (T-10 — edge); (3) потокобезопасность — ⚠️ T-2/T-9; (4) Jackson 3 — ✅; (5) ArchUnit — ✅ правило корректно, D-85 расходится (T-5); (6) SecurityConfig `permitAll @Order(0)` — ✅ SSO для REST не пробит; (7) AGENTS — ✅ (T-11/T-12 — нюансы).

---

## MEDIUM

### T-1. `register` принимает любую FREE-сессию, включая `spawn`-подсессию; спека — только FREE **root**
- **Пункт:** `RelayWebSocketHandler.java:151–154`; спека client-relay:30 («релею доступны только FREE root-сессии»).
- **Цитата:** `if (target.get().kind() != SessionKind.FREE) { reject(... "wrong-session-kind" ...) }`.
- **Проблема:** `spawn_subagent` создаёт дочернюю сессию с `SessionKind.FREE` и `parent_session_id != null` (`SessionStoreImpl.java:82–104`). Проверки «root» нет, поэтому клиент может зарегистрироваться напрямую на подсессии, сделав её собственным корнем: туда придёт собственный оверлей/соединение, а parent-chain-семантика D-84 («оверлей виден по цепочке») будет обходиться. Не дыра в правах (D-41 — всё видно всем), но отклонение от спеки и от модели видимости.
- **Предложение:** отклонять не-root FREE: `target.get().parentSessionId() != null` → `wrong-session-kind` (или отдельный код); `parentSessionId == null` — валидный root.

### T-2. Heartbeat-задача без защиты от `Throwable`: runtime-исключение отправки молча глушит периодический heartbeat
- **Пункт:** `RelayWebSocketHandler.java:169–178` (`heartbeat`), `:80–81` (`scheduleAtFixedRate`), `WebSocketRelayConnection.java:34–40` (`sendText` ловит только `IOException`).
- **Проблема:** `ScheduledExecutorService.scheduleAtFixedRate` **подавляет все последующие запуски задачи, если один запуск бросил исключение**. `ConcurrentWebSocketSessionDecorator.sendMessage` может бросить runtime-исключение (превышение буфера/лимита отправки, `IllegalStateException` на закрытом delegate) — оно не ловится (`catch (IOException)` в `sendText`). Тогда heartbeat конкретного соединения тихо умирает: `ping` не шлётся, `2×interval`-таймаут не срабатывает, `unregister` не происходит. Это ровно баг D-J-1, уже исправленный в `SessionLockManager.HeldLock.heartbeat()` (гасит `Throwable`, тики продолжаются).
- **Предложение:** обернуть тело `heartbeat`/`sendText` в `try { ... } catch (Throwable t) { log.warn(...); }` (по образцу D-J-1); на закрытом/переполненном соединении — инициировать `close`+`cleanup`, а не молча терять задачу.

### T-3. Нет тестов handshake-стейт-машины, register-отказов, interceptor и relay-цепочки Security
- **Пункт:** тесты батча — только `RelayConnectionRegistryTest` (5 кейсов), `ConfigPropertiesBindingTest` (+2), ArchUnit. `RelayWebSocketHandler`/`RelayHandshakeInterceptor`/`SecurityConfig.relayFilterChain` не покрыты.
- **Проблема:** правила ревью требуют покрытия happy/error-path. Непокрытыми остались все WS-отказы: 4401 (нет/кривой токен/нет групп), 4403 (фрейм до `hello`, `protocol-mismatch`, невалидный JSON), `session-not-found`/`wrong-session-kind`/`duplicate-tool-name`/`workspace-occupied`, takeover-закрытие, heartbeat-таймаут. Приёмочный тест X может не трогать 4401/4403, поэтому дыра рискует «уехать» в архив.
- **Предложение:** unit-тесты `RelayWebSocketHandler` с mock-`WebSocketSession`/`RelayConnection` на все переходы стейт-машины и отказы; тест `RelayHandshakeInterceptor` на группы/JWT; тест relay-цепочки (401 для REST сохраняется, апгрейд проходит). Интеграционный WS-e2e — на X.

---

## MINOR / NIT

### T-4. Таймаут heartbeat фактически ~3×interval, не 2×
- **Пункт:** `RelayWebSocketHandler.java:171` — `now - lastPong > 2 * interval` при тиках с `interval`.
- **Проблема:** первый тик на `1×`, второй на `2×` (условие `>` ещё ложно), закрытие — на `3×`. Спека client-relay:92 — «по превышению `2 × heartbeat-interval`».
- **Предложение:** `>= 2 * interval` либо сравнивать с временем последнего отправленного `ping`.

### T-5. D-85 (дизайн) расходится с реализованным ArchUnit-правилом
- **Пункт:** `design.md:87` («`api` и `execution` **могут зависеть** от `relay` на уровне конфигурации/Wiring») против `ArchitectureRulesTest.java:104,112,212–225` (`execution.mayOnlyAccessLayers(...)` без relay; `relayViolationIsCaught` ловит любую `execution → relay`).
- **Проблема:** код строгий и корректный (`execution ↛ relay` любой формы), а design допускает wiring-зависимость `execution → relay`. Расхождение док↔код.
- **Предложение:** убрать из D-85 оговорку про wiring `execution → relay`; зафиксировать: вся связка — через интерфейс `ClientToolBridge` в `execution`, реализация в `relay` — Spring.

### T-6. Naming-drift SPI: `resolve` (tasks/design) vs `resolveTool` (код)
- **Пункт:** `tasks.md` 2.2 / `design.md:87` — `resolve(sessionId, toolName)`; `ClientToolBridge.java:27` — `resolveTool`.
- **Предложение:** привести к одному имени (в batch V спека/манифест тоже должны совпасть).

### T-7. Лог отключения без MDC (D-77 требует register/disconnect с MDC)
- **Пункт:** `RelayWebSocketHandler.java:121–122` (`afterConnectionClosed`) vs `:198–207` (`logRegistration` c MDC `sessionId`/`principal`).
- **Предложение:** обернуть disconnect-лог в тот же MDC-контекст (sessionId, principal, число инструментов).

### T-8. Повторный `register` того же соединения на **другую** сессию оставляет старый ключ в реестре
- **Пункт:** `RelayWebSocketHandler.java:160–166` (`state.registeredSessionId = sessionId` перезаписывается без `unregister` прежней).
- **Проблема:** реестр хранит `sessionId→connection`; при смене сессии на том же сокете старая запись живёт до `afterConnectionClosed`, а `cleanup` снимет только последнюю.
- **Предложение:** запретить повторную регистрацию на другую сессию (protocol error) либо снимать прежнюю CAS-ом перед новой.

### T-9. `RelaySession.phase`/`registeredSessionId` — non-volatile, читаются из heartbeat-потока
- **Пункт:** `RelayWebSocketHandler.java:264–277`; чтение `registeredSessionId` в `:172–173`.
- **Проблема:** запись идёт из WS-контейнерного потока, чтение — из heartbeat-потока; видимость не гарантирована (влияет лишь на лог/диагностику). Не критично, но стоит либо `volatile`, либо не читать из планировщика.
- **Предложение:** пометить `volatile` или вынести нужное в атомики.

### T-10. `RelayHandshakeInterceptor` ловит только `JwtException`
- **Пункт:** `RelayHandshakeInterceptor.java:59` — `catch (JwtException e)`.
- **Проблема:** заголовок `"Bearer "` (пустой токен) или иная аномалия декодера может дать не-`JwtException` (напр. `IllegalArgumentException`) → исключение наружу → HTTP 500 на handshake вместо WS-close 4401.
- **Предложение:** ловить `Exception` (или `RuntimeException`) и в любом случае ставить `AUTH_FAILED_ATTRIBUTE`.

### T-11. `setAllowedOriginPatterns("*")` — широко
- **Пункт:** `RelayWebSocketConfig.java:29`.
- **Проблема:** для Bearer-авторизованного не-браузерного клиента риска почти нет (браузер не поставит `Authorization`), но паттерн «любой origin» стоит сузить/задокументировать до появления билетной auth (WebUI-фаза).
- **Предложение:** ограничить конфигом/списком доверенных origin либо явно отметить как временный для M4-тест-клиента.

### T-12. WS-путь не проходит `UserSyncFilter` — пропуск синка `app_user`
- **Пункт:** `SecurityConfig.java:52–62` (relay-цепочка без `UserSyncFilter`) против `:119` (REST-цепочка).
- **Проблема:** пользователь, работающий только через релей, не попадёт в `app_user`; если дальнейшие пачки (инструменты/аудит, owner-резолв) на это положатся — рассинхрон. Похоже, релею достаточно `principal`; тогда — зафиксировать явно.
- **Предложение:** подтвердить, что relay не нуждается в `app_user`, либо вызвать синк из interceptor.

### T-13. Прочее (косметика/консистентность)
- `WorkspaceDownloadProperties.java:10` — опечатка «случайное сравнение» → «сравнение».
- Wire-контракт §5 (форма `error`-фрейма, коды закрытия, `welcome`) реализован в коде, но в `api-contracts.md` ещё не перенесён (task 1.2) — держать в одном коммите с пачкой, чтобы спека и код не разъехались (ранее R-8).
- `relayViolationIsCaught` использует `resideInAPackage(EXECUTION)` (паттерн с `..`) — ок; фикстуры живут в test-classpath и в позитивные правила не попадают (проверено).

### T-14 (положительные подтверждения, не находки)
- **Jackson 3**: во всех новых файлах — `tools.jackson.databind.*`; `com.fasterxml` не встречается.
- **SecurityConfig**: relay-цепочка `securityMatcher("/api/v1/relay")` + `@Order(0)` не пробивает SSO REST — `/api/v1/**` по-прежнему идёт в цепочку `@Order(2)` с JWT+groups; relay-гейт вынесен в interceptor и использует **тот же** `JwtDecoder` и test `groups`, что `GroupsGateFilter` (паритет claim).
- **Реестр**: `compute`-атомарность решения takeover/occupied, закрытие вытесненного **вне** `compute`, `remove(key, expected)` CAS, идемпотентный повтор — соответствуют D-78 и сценариям спеки; unit-тесты покрывают все пять веток.
- **Конфиг**: heartbeat/tool-call/send/buffer, max-bytes, allow-extensions — в `application.yml`, `ConfigPropertiesBindingTest` проверяет; `@ConfigurationPropertiesScan` подхватит оба record-а.
- **AGENTS**: числа в конфиге; Lombok `@RequiredArgsConstructor`/`@Slf4j`; ws-starter Boot-managed; новых Jackson 2 нет.

---

## Чек-лист по пунктам промпта

| # | Пункт | Вердикт |
|---|---|---|
| 1 | takeover / idempotent / CAS по спеке | ✅ (T-8, T-9 — краевые) |
| 2 | handshake 4401 vs HTTP 401 | ✅ обосновано и спеке-конформно (T-10 edge) |
| 3 | потокобезопасность (decorator, CHM) | ⚠️ T-2 (heartbeat Throwable), T-9 (visibility) |
| 4 | Jackson 3 (не Jackson 2) | ✅ |
| 5 | ArchUnit канон не противоречив | ✅ правило; ⚠️ D-85 расходится (T-5) |
| 6 | `permitAll @Order(0)` не пробивает SSO REST | ✅ |
| 7 | AGENTS (числа-конфиг, импорты, Lombok) | ✅ (T-11/T-12 — нюансы) |

## Вердикт

**REJECT — 3 MEDIUM (T-1 root-проверка сессии, T-2 защита heartbeat-задачи от `Throwable`, T-3 покрытие error-path тестами) + 11 MINOR/NIT.**

Ядро пачки корректно: реестр (takeover/CAS/идемпотентность) по D-78, handshake-стейт-машина и 4401-семантика по спеке, Jackson 3, ArchUnit-направления (`api→relay`, `relay→{execution,session}`, `execution↛relay` через SPI), relay-цепочка Security безопасна для REST, параметры — в конфиге. Блокеры приёмки: **T-1** (спека «только FREE root»), **T-2** (тихая смерть heartbeat при runtime-исключении отправки — тот же класс, что D-J-1), **T-3** (непокрытые WS-отказы). После фиксов — re-approve.

---

# Re-approval M4 batch T (2026-09-22, рабочее дерево поверх 14ce35c)

> Проверены незакоммиченные фиксы T-1…T-13 (`git diff` по `relay/*`, `config/*`, `execution/ClientToolBridge`, `identity/SecurityConfig`, `application.yml`, `design.md`) + новые тесты `tests/relay/{RelayWebSocketHandlerTest, RelayHandshakeInterceptorTest}` и `ConfigPropertiesBindingTest`. Сборки не запускались; dev verify — 519 тестов, ArchUnit 18/18.
> Все 3 MEDIUM и 11 MINOR/NIT round-1 закрыты. Новых MEDIUM не появилось.

## Статусы находок round-1

| # | Sev | Статус | Проверка |
|---|---|---|---|
| T-1 | MEDIUM | **закрыто** | `RelayWebSocketHandler.java:159–163`: `kind != FREE \|\| parentSessionId != null → wrong-session-kind`; тест `rejectsChildSessionWithoutRegisteredParent` (+ `rejectsStateSession`) |
| T-2 | MEDIUM | **закрыто** | `heartbeat()` обёрнут `try/catch (Throwable)`, при таймауте `close`+`cleanup` (`:181–204`); `WebSocketRelayConnection.sendText/close` ловят `Exception`; тест `heartbeatTimeoutClosesAndUnregisters` |
| T-3 | MEDIUM | **закрыто** | `RelayWebSocketHandlerTest` (12 кейсов: 4401, 4403 pre-hello, protocol-mismatch, welcome, session-not-found, STATE, child, duplicate, occupied, успех, re-register, heartbeat) + `RelayHandshakeInterceptorTest` (5: нет заголовка/пустой/битый/нет групп/валидный) |
| T-4 | MINOR | **закрыто** | `>= 2 * interval` (`:185`) |
| T-5 | MINOR | **закрыто** | `design.md:87`: «`api` может зависеть от `relay`, но **`execution ↛ relay` строго** (любая форма)» — совпадает с ArchUnit |
| T-6 | MINOR | **закрыто** | SPI переименован `resolveTool → resolve` (интерфейс + javadoc); старых ссылок нет |
| T-7 | MINOR | **закрыто** | `withMdc(...)` на disconnect (`:128–131`) |
| T-8 | MINOR | **закрыто** | Смена сессии снимает прежний ключ CAS-ом (`:175–180`); тест `reRegisterOnAnotherSessionDropsStaleKey` |
| T-9 | MINOR | **закрыто** | `RelaySession.phase/registeredSessionId/heartbeat` → `volatile` |
| T-10 | MINOR | **закрыто** | `catch (Exception e)` в interceptor (`:58`); тест `rejectsEmptyBearerToken` |
| T-11 | MINOR | **закрыто** | `harness.relay.allowed-origin-patterns` (RelayProperties + yml + `RelayWebSocketConfig`), дефолт `["*"]` задокументирован для M4-тест-клиента |
| T-12 | MINOR | **закрыто** | Решение «UserSyncFilter не нужен релею (только principal)» зафиксировано Javadoc-ом `SecurityConfig:52–55` |
| T-13 | NIT | **закрыто** | Опечатка WorkspaceDownloadProperties исправлена; wire §5 вынесен в batch 1 (`114352e`) |

## Новые / остаточные замечания (NIT, не блокеры)

- **N-1.** `RelayWebSocketHandler.close(WebSocketSession,…)` по-прежнему ловит только `IOException`; runtime-исключение `session.close` в heartbeat-тике прервёт его **до** `cleanup` (следующий тик самоизлечится: `isOpen()==false → cleanup`). Для симметрии — `catch (Exception)`.
- **N-2.** Тестовый `TestHandler` создаёт heartbeat-планировщик и не вызывает `shutdown()` → десяток daemon-потоков живёт до конца JVM (гигиена тестов; на результат не влияет).
- **N-3.** `RelayProperties.allowedOriginPatterns` без null-guard: при удалении свойства из yml `toArray` даст NPE на старте (у прочих record-ов та же модель — консистентно, но стоит `@DefaultValue`/валидация).
- **N-4.** Остаётся непокрытым интеграционный кейс «relay-цепочка `@Order(0)` не пробивает SSO REST» (webhook-цепочка тоже без такого теста) — приемлемо для unit-пачки, желательно в X.
- **N-5.** Дефолт `allowed-origin-patterns: ["*"]` — осознанно для тест-клиента; не забыть сузить к Web Desktop-фазе (уже в задаче).

## Чек-лист промпта (повторно)

| # | Пункт | Вердикт |
|---|---|---|
| 1 | takeover/idempotent/CAS по спеке | ✅ + тесты |
| 2 | handshake 4401/4403 | ✅ + 5 тестов interceptor + 4 теста стейт-машины |
| 3 | потокобезопасность | ✅ T-2/T-9 закрыты |
| 4 | Jackson 3 | ✅ |
| 5 | ArchUnit канон | ✅ D-85 приведён в соответствие |
| 6 | `permitAll @Order(0)` не пробивает SSO REST | ✅ |
| 7 | AGENTS | ✅ |

## Вердикт re-approval

**APPROVE — 0 блокеров.** T-1/T-2/T-3 и все 11 MINOR/NIT round-1 закрыты материально и подкреплены тестами (519 зелёных, ArchUnit 18/18). Остаточные N-1…N-5 — косметика/гигиена/интеграционное покрытие, не препятствуют закрытию пачки T.
