# Ревью ченджа m3-agent-layer (плановые артефакты) — GLM-5.3-Flash

Дата: 2026-09-19. Ревьюер: GLM-5.3-Flash (субагент). Объект: proposal/design/tasks + 6 дельт (async-instruments, subagent-lifecycle, orchestrator-metaTools, mcp-client, agent-turn, session-api). Контекст: D-01…D-59, M1/M2-спеки, execution-model/agent-tools (фрагменты), roadmap M3.

**Вердикт: REJECT** — 11 находок (2 major / 5 minor / 4 nit). Скелет M3 здрав (async через session-канал, spawn с owner-наследованием, MCP только клиент — всё в русле D-09/D-21/D-38/D-44), но два major-разрыва (взаимоисключающая семантика spawn; недоопределённый cross-session read_compacted на негарантированном резолве) обязаны быть закрыты до заморозки.

---

## MAJOR

### MJ-1. spawn_subagent: две взаимоисключающие семантики в одной дельте agent-turn
- **Цитата**: specs/agent-turn: «Дочерняя сессия стартует сразу (**синхронно в дочернем потоке, без отдельного wake**)… **родительский виртуальный поток блокируется**; после финала spawn возвращает TOOL_RESULT» — и тут же Scenario: «**родительский Turn завершился раньше субагента** — WHEN spawn отрабатывает **в фоне**, родительский Turn завершил цикл без `spawn_pending` — THEN субагент завершается независимо; результат → message.created → новый Turn родителя».
- **Проблема**: если spawn синхронно блокирует родительский поток (proposal: «синхронный metaTool»; D-10: завершение = «ход окончен И pending==0»), сценарий «Turn завершился раньше субагента» невозможен. Два сценария описывают разные фичи (strict-sync vs async-spawn), имплементатор обязан выбрать один — а тесты пачки 2 пишутся под непонятно какой.
- **Предложение**: зафиксировать strict-sync (как в proposal/D-10): родительский Turn не завершается при живом spawn (pending учитывает spawn), сценарий «раньше субагента» удалить; async-spawn (окно) — отдельное осознанное решение/точка эволюции. Заодно уточнить связь с `harness.async.window` (распространяется ли окно на spawn).

### MJ-2. `read_compacted`: оркестраторский cross-session путь недоопределён и опирается на негарантированную уникальность
- **Цитата**: specs/subagent-lifecycle: «Гейт: … доступно только оркестратору и тому же агенту, чья сессия видела компакцию»; tasks 5.4: «проверяет `session_id == currentSession.id` или caller — оркестратор с metaTools=true И **orchestrator.targetSession в manifest**»; design Migration: «Опциональная миграция 073_add_unique_session_message_id_per_session.xml (**если ULID не уникален глобально** — проверим в apply-фазе)».
- **Проблемы**: (а) сигнатура инструмента `read_compacted(compact_message_id)` не содержит целевую сессию — cross-session чтение без параметра сессии возможно только через «магический» резолв id; (б) «orchestrator.targetSession в manifest» — неопределённый механизм (что такое targetSession и кто его проставляет?); (в) резолв `id → (session_id, seq)` для чужой сессии требует **глобальной** уникальности ULID, которая в Migration Plan лишь «опциональна/проверим потом» — т.е. фича опирается на гипотезу, а не на инвариант; (г) сценарий agent-turn «id вне сессии → not-found (cross-session)» конфликтует с правом оркестратора читать чужое (когда not-found, а когда разрешено?).
- **Предложение**: для M3 сузить до same-session only (сценарий «возврат к сути своей компакции» — он и есть мотивация); оркестраторский cross-session — вынести в точку эволюции/отдельное решение (потребует обязательного unique-индекса + явного параметра сессии + сценария). Либо — если cross-session нужен — сделать миграцию 073 обязательной и определить targetSession-механизм формально.

---

## MINOR

### m-1. Путаница счётчиков: `task_event_seq` (task SSE, M2) vs session `seq` (session_message, M1)
- **Цитата**: proposal: «поздний TOOL_RESULT … через существующий канал **task_event_seq** сессии»; design D-60: «**task_event_seq** (M2) инкрементируется»; specs/async-instruments: «existing M2 task_event_seq-механизм **для сессии** … Это и есть "wake по task_event_seq **сессии**"».
- **Проблема**: `task_event_seq` — колонка `task` (курсор SSE задач); у **сессии** счётчик — `session_message.seq`/`session.last_seq` (M1). Late TOOL_RESULT — это session_message → инкрементируется **session seq**, task_event_seq здесь ни при чём. Терминологическая каша в трёх артефактах — имплементатор может инкрементировать не тот счётчик или строить wake не на том канале.
- **Предложение**: унифицировать на «session seq (`session_message.seq` + `last_seq`) через InMemorySessionEventBroadcaster»; упоминания task_event_seq убрать/поправить.

### m-2. D-64 «первый финальный выигрывает»: «lock строки session_message по callId» нереализуем как описано
- **Цитата**: design D-64: «публикуется … транзакционно с проверкой „уже есть TOOL_RESULT(callId) с terminal status“ (**lock строки session_message по callId**, идемпотентно)».
- **Проблема**: у `session_message` нет колонки callId (callId живёт в payload_jsonb) — «lock строки по callId» не существует; а гонка «реальный результат vs рестарт-LOST» — это гонка двух **вставок**, которые нельзя закрыть блокировкой несуществующей строки. Без механизма правило не обеспечивается.
- **Предложение**: записать в D-64 конкретный механизм: публикация late-результата и LOST-синтетика — под программным локом сессии `sess-{sessionId}` (D-40 ShedLock; сериализует обе вставки), проверка «уже есть финальный TOOL_RESULT по callId в payload» внутри лока; либо (дороже) частичный уникальный индекс по `(session_id, payload->>'callId')` — но это миграция, против тезиса «0 миграций».

### m-3. Дельты agent-turn/session-api помечены ADDED, но модифицируют требования M1
- **Цитата**: specs/agent-turn — `## ADDED Requirements` («Асинхронный цикл Turn'а», write-ahead с плейсхолдером); specs/session-api — ADDED («MessageDto.late реализовано»).
- **Проблема**: async-цикл меняет семантику существующего требования M1 «Агентный цикл с синхронными инструментами» (новый исход PARKED_ASYNC, условие завершения), write-ahead — существующего «Write-ahead раунда» (TOOL_CALL+ASYNC_ACCEPTED атомарно), late — существующего «Чтения сообщений» (MessageDto.late). При архиве ADDED-дельты **не обновят** M1-требования — спека останется противоречивой (урок ревью M2 C-1).
- **Предложение**: переоформить как `## MODIFIED Requirements` (Агентный цикл, Write-ahead раунда, Чтение сообщений) + ADDED только для новых требований (spawn/read_compacted в agent-turn можно оставить ADDED).

### m-4. Рестарт-скан: ASYNC_ACCEPTED перечислен среди «финальных» результатов — противоречит собственному сценарию
- **Цитата**: specs/async-instruments: «найти TOOL_CALL без парного финального TOOL_RESULT (`OK|ERROR|`**`ASYNC_ACCEPTED`**`|CANCELLED|LOST`)» — и тут же Scenario: «сессия имеет **ASYNC_ACCEPTED(callId) без результата** — THEN рестарт-скан записывает синтетический TOOL_RESULT LOST».
- **Проблема**: если ASYNC_ACCEPTED считается «парным финальным», вызов никогда не получит LOST — сценарий невыполним.
- **Предложение**: финальные статусы — `OK|ERROR|CANCELLED|LOST`; ASYNC_ACCEPTED — признак «в полёте» (подлежит закрытию LOST на рестарте).

### m-5. Верхний лимит late-result (15 мин): «рестарт-скан (или фоновый watcher)» — watcher в tasks отсутствует
- **Цитата**: specs/async-instruments: «SHALL закрыть вызов синтетическим LOST… рестарт-скан (**или фоновый watcher**)»; tasks 1.3 — только рестарт-скан.
- **Проблема**: между ASYNC_ACCEPTED и рестартом лимит 15 минут никем не обслуживается: потерянный late-result (баг фонового доведения) подвесит PARKED_ASYNC-сессию навсегда (до рестарта). Требование SHALL без исполнителя.
- **Предложение**: назначить механизм — либо джоба-скан (ShedLock, интервал-конфиг: «нашёл ASYNC_ACCEPTED старше лимита → LOST», стиль task-timeout-scanner), либо честно ограничить требование рестартом и зафиксировать риск «висим до рестарта» в Risks.

---

## NITS

- **n-1**: имя capability `orchestrator-metaTools` — camelCase-фрагмент в kebab-case-идентификаторе (M2-конвенция: paths kebab-case; → `orchestrator-meta-tools`).
- **n-2**: miscitation/расхождения номеров: proposal — «`spawn.max-depth: 2` (D-26-ish)» (D-26 — capability-URL, не лимиты); proposal Impact — опциональная миграция `072_create_index_session_parent.xml`, design — `073_add_unique_session_message_id_per_session.xml` (две разные «опциональные» миграции, не сведены); design R1 — «6 пачек (N..S: async/spawn+orchestrator/MCP/revisions+wiring/acceptance)» не совпадает с tasks 1–6 (spawn и orchestrator в разных пачках; «revisions+wiring» в tasks отсутствует).
- **n-3**: proposal п.14 «Тримминг сессии» смешивает два механизма: spawn-сессии (parent_session_id) и STATE-сессии подзадач (create_subtask → AGENT-state → findOrCreate M2) — это разные сущности с разными lifecycle; пункт переформулировать (второе — не «субагентская сессия» в смысле subagent-lifecycle).
- **n-4**: Risks заканчиваются R8 (запрос предполагал R1–R9); kind создаваемой spawn'ом сессии не указан (подразумевается FREE — зафиксировать; spawn из STATE-сессии — разрешить/запретить явно).

---

## Что проверено и чисто

- **D-09/D-10/D-23**: окно → ASYNC_ACCEPTED → парковка → поздний результат; «первый финальный выигрывает» = D-36; завершение субагента = «ход окончен И pending==0» (D-10) — согласовано.
- **D-21/D-63**: MCP только клиент, сервер наружу — non-goal; Spring AI client, без кастомного JSON-RPC ✓.
- **D-38/D-41/D-59**: metaTools boolean без градаций (D-41-стиль); оркестраторский обход USER-гейта только для orchestrator-tools, `transition` остаётся под D-59 для всех; 3 остальных множителя D-38 = 1 — явно в Non-Goals; D-69 (no-inheritance) закрывает горизонтальную эскалацию ✓.
- **D-44/D-17**: read_compacted — read-only поверх covers, COMPACT не мутируется ✓; D-31: правка permissions_jsonb = новая ревизия ✓.
- **D-08**: stop-поддерево по parent_session_id ✓; owner-наследование (alice-сценарий) ✓; D-69-гейт против суб-спавна без флага ✓.
- **D-22**: contract-first — metaTools через существующие контракты M2 (TaskRegistry/WorkflowRegistry/TriggerRegistry), 0 новых REST-операций✓; MessageDto.late — аддитивно ✓.
- **Покрытие M3-scope**: async/spawn/read_compacted/subtree-cancel/orchestrator-tools/MCP — все 6 блоков в дельтах; llm-профили — осознанно вынесены в Open Question 1/R6 (владельцу до apply — иначе расхождение с roadmap M3 остаётся).
- **Пачки**: 6 пачек с Verify в каждой; acceptance 6.2 = критерий roadmap («Сделай биллинг») с Keycloak+WireMock+контейнером.
- **Open Questions** (LLM-профили first-class; список MCP-серверов; configure_trigger — URL отдаётся, регистрацию делает агент) — конкретны, адресаты названы.

## Вердикт

**REJECT** — 11 находок: 2 major (MJ-1 spawn-семантика, MJ-2 cross-session read_compacted) / 5 minor (m-1 счётчики, m-2 D-64-механизм, m-3 ADDED-vs-MODIFIED, m-4 ASYNC_ACCEPTED-в-финальных, m-5 watcher без исполнителя) / 4 nit. Ядро согласовано с ADR-базой; после фиксов (оба major решаются выбором одной из двух честных опций) — re-approve.

---

# Re-approval m3-agent-layer (2026-09-19)

## Статус моих находок

- **MJ-1 (spawn-семантика)** — **закрыто**: specs/agent-turn — «Вызов строго синхронный — блокирующий: родительский виртуальный поток ждёт завершения субагента… окно harness.async.window на spawn НЕ распространяется»; сценарий переписан в «родительский Turn завершён до возврата spawn (отмена/рестарт)» — единственный легальный путь позднего TOOL_RESULT (message.created). Согласовано с proposal/D-10.
- **MJ-2 (cross-session read_compacted)** — **закрыто сужением**: same-session only; «id чужой сессии → not-found»; cross-session — явно вне M3 с перечнем условий эволюции (глобальная уникальность ULID + параметр сессии + отдельное решение). Tasks O.4 синхронно.
- **m-1 (счётчики)** — **закрыто**: D-60/spec — «message.created / session_message.seq … инкрементирует last_seq … M2 task_event_seq на сессионном канале НЕ используется»; бонус: PARKED_ASYNC = `session.runtimeStatus` (значение из api-contracts §2), TurnOutcome не расширяется; ASYNC_ACCEPTED = новое MessageKind (миграция 075 ALTER CHECK) — заодно снимает неоднозначность «callId в payload vs отдельная сущность».
- **m-2 (D-64 механизм)** — **закрыто**: «Финальные статусы — OK|ERROR|CANCELLED|LOST… публикация late-результата и LOST-синтетика выполняются под программным локом сессии sess-{sessionId} (ShedLock, D-40)… внутри лока — проверка по payload_jsonb» — реализуемый механизм вместо несуществующего «lock строки по callId».
- **m-3 (ADDED vs MODIFIED)** — **НЕТ (не закрыто)**: обе дельты по-прежнему `## ADDED Requirements` (agent-turn:3, session-api:3). «Асинхронный цикл Turn'а» изменяет M1-требование «Агентный цикл с синхронными инструментами» (новое условие завершения: pending_tool_calls>0 + runtimeStatus PARKED_ASYNC против M1-«модель закончила ⟺ нет tool-calls → COMPLETED»); плейсхолдер уточняет M1 «Write-ahead раунда»; `MessageDto.late` изменяет M1 «Чтение сообщений». При архиве ADDED-дельты лягут РЯДОМ с M1-требованиями → противоречивая спека agent-turn/session-api (openspec validate это не ловит — проверено: valid). В судейском списке фиксов m-3 отсутствовал — видимо, потерян при консолидации.
- **m-4 (ASYNC_ACCEPTED в финальных)** — **закрыто**: «финальные статусы — OK|ERROR|CANCELLED|LOST; ASYNC_ACCEPTED — placeholder "в полёте", финальным НЕ считается» (design D-64 + async-instruments).
- **m-5 (watcher)** — **закрыто**: tasks N.6 `AsyncTimeoutWatcher` (ShedLock, расписание — конфиг, лок sess-{id}) + требование в spec назначает его исполнителем; рестарт-скан — вторая линия.
- **n-1** — **закрыто**: capability переименована в `orchestrator-meta-tools`. **n-2** — **закрыто**: «D-26-ish» убрано; миграции сведены к 074/075 одинаково в proposal и design; пачки N..S в tasks, R1 ссылается на них. **n-3** — **закрыто**: «Тримминг сессии» удалён. **n-4** — **закрыто**: R9 (supersession) заведён; D-70 в tasks S.3 + синхронизация agent-tools.md (тем самым закрыт и старый класс X-1).
- **M-6/M-7** — подтверждены: D-70 (частичный supersede D-41 — metaTools-гейт возвращается для оркестраторов, обычные остаются под D-41) регистрируется при apply; llm-профили — resolved как revision-as-profile (D-62, без нового поля), roadmap-пункт закрыт.

## Вердикт re-approval

**REJECT** — 1 незакрытая: m-3 — переоформить дельты agent-turn и session-api в `## MODIFIED Requirements` (Агентный цикл с синхронными инструментами → асинхронный; Write-ahead раунда; Чтение сообщений), ADDED оставить только для новых требований (spawn/read_compacted). Правка заголовков + связка текстов; после неё — approve без полного цикла.

---

# Re-approval 2 m3-agent-layer (final) (2026-09-19)

- **m-3 (ADDED vs MODIFIED)** — **закрыто**: specs/agent-turn — `## RENAMED Requirements` (FROM «Агентный цикл с синхронными инструментами») + `## MODIFIED Requirements` («Агентный цикл Turn'а (sync + async-capable)» — вобрал и уточнение write-ahead плейсхолдером) + `## ADDED` только для действительно новых (spawn_subagent, read_compacted). Архив теперь корректно заменит M1-требование, противоречивой спеки не будет.
- **session-api остаётся ADDED** — **принимаю**: M1-спека прямо декларировала «late? (поле M3; в M1 всегда отсутствует)» — отдельное ADDED-требование о реализации зарезервированного поля не создаёт конфликта с «Чтением сообщений» (семантика чтения не меняется).

## Вердикт re-approval 2 (final)

**APPROVE** — незакрытых: 0. Все 11 находок (2 major / 5 minor / 4 nit) закрыты; D-60…D-70 согласованы с ADR-базой; чендж m3-agent-layer готов к заморозке и apply (пачки N…S).
