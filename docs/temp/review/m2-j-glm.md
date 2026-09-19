# Ревью пачки J (STATE-сессии, мета-инструмент, SSE задач) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: session/{StateSessionService(+impl)}, execution/{TransitionMetaTool, AgentStateBootstrapper, TaskWakeBroadcaster, TaskEventBroadcaster, AgentTurnEngine(гейт)}, task/{TaskEvent, TaskEventListener}, api/TaskEventsController (реальный SSE), Caller (instructionSource/sessionOwner), тесты. Сборки не запускались.

**Вердикт: REJECT** — 3 находки (1 minor / 2 nit). Ядро пачки сильное (атомарный findOrCreate, гейт/лимит на Turn, SSE без пропусков-дублей); блокирует только отсутствующая apply-notes-секция J с 4 отклонениями dev — третий случай паттерна H-9.

---

## (1) StateSessionService.findOrCreate (J.1) — ✅
Атомарность §7.2: `INSERT … ON CONFLICT (task_id, state_code) WHERE kind='STATE' DO NOTHING RETURNING id` — конфликт-таргет точно по PARTIAL UNIQUE 005; seed-SYSTEM (seq=1, тот же `now()`) — в той же транзакции (классовая `@Transactional`) → «сессия есть, seed не записан» невозможно; проигравший гонку резюмирует (SELECT по паре; блокировка на конфликте ждёт исхода победителя — ни потерянного seed, ни гонки). `owner_user_id` — из строки task (SQL, без импорта task-классов — прецедент TaskRegistryImpl→workflow_revision; session → identity по ArchUnit выдержан). `last_seq=1/last_consumed_seq=0` согласованы с seed. `agent_revision_id` — пин при создании, резюм не трогает (D-53). Seed несёт предыдущий переход задачи (контекст возврата) и напоминание про transition+reason — приятный штрих для резюма.

## (2) TransitionMetaTool + гейт (J.2) — ✅ c nit
Гейт в `AgentTurnEngine.executeToolCall`: `instructionSource != USER` → ошибка инструмента (переход не происходит; источник резолвится из батча, поднявшего Turn — USER/TOOL_RESULT/seed); `session.kind() != STATE` → отказ; лимит `harness.task.transition.max-per-turn` — AtomicInteger на Turn, конфиг. `TransitionMetaTool.execute`: STATE-сессия обязательна; `taskId` резолвится из сессии, явно переданный чужой — ошибка; `reason` обязателен непуст (`rule=reason-required`); kind — NEXT (дефолт)/ERROR; применение — транзакционно в момент tool-call через `TaskEngine.processTaskTransition` (CAS+история+seq атомарны), CAS-промах и отсутствующее ребро — ошибки инструмента (не исключения наружу). Применение в момент tool-call — как зафиксировано в M5/D-52.

## (3) AgentStateBootstrapper (J.3) — ✅
`bootstrap(taskId, stateCode)`: `findOrCreate` (идемпотентно: победитель создаёт+seed, повторный — резюм) → `turnManager.tryStart(session.id())` (лок `sess-{id}`; без незапрошенного батча — no-op). `agent_revision_id` — последняя ревизия `agent_key` состояния на момент входа, пин при создании; отсутствующий agent_key — защитный отказ (валидатор графа такой граф не сохраняет). POLL подстраховывает повторным wake.

## (4) TaskWakeBroadcaster + TaskEventsController (J.4) — ✅ c nit
Broadcaster: per-task stream, буфер переупорядочивания по `task_event_seq` (бездырочность сериализована row-lock'ом строки task), ограниченный in-memory backlog для бэкфилла (`harness.sse.task-backlog`); пары кадров (transition + статус) делят seq — доставляются вместе, порядок эмиссии сохранён; опоздавшие — warn+skip. Это НЕ wake-шина (разведение задокументировано в обоих Javadoc). Контроллер: `retry: 5000` первым кадром; снапшот `task.status` из строки task — с `taskEventSeq` (точка входа live, без SSE id — задокументировано); подписка до бэкфилла + pending-очередь + drain под sendLock — без пропусков и дублей; `Last-Event-ID` приоритетен над `?since=`; ping — комментарий по конфиг-таймеру; неизвестная задача → 404 task-not-found (через TaskRegistry). `TaskEvent` — sealed-иерархия в task-модуле (эмиттеры — движок и реестр), доставка afterCommit.

## (5) D-58/D-59 — ✅
D-59: plumbing введён ровно в границах решения — `Caller.instructionSource()` возвращает USER (M2 — только HTTP-точки входа; агентский контекст — M3, задокументировано), `sessionOwner()` = preferred_username; гейт metaTools — USER-only; вне STATE/не-USER переход невозможен. Следствие по дизайну: bootstrap-turn (seed-SYSTEM) не может transition — пользователь стартует сценарий сообщением; это соответствует «единственная точка входа — USER-сообщение» (спека agent-turn). D-58: профиль (LimitedJsonSchemaValidator) переиспользуется без изменений.

## (6) ArchUnit — ✅
execution → session (StateSessionService, Session, AgentRevisionRepository — контракты, без .impl); api → execution (`TaskEventBroadcaster`) + session + task (разрешено с H); task → identity; встречных импортов нет (task определяет TaskEvent/TaskEventListener, execution/api — потребители).

## (7) Отклонения dev — по коду подтверждаются, НО не документированы (M-1)
`task_event_seq` расходуется комментарием/stop-событиями (TaskEngineImpl:241 — отдельный UPDATE … RETURNING для безпереходных событий), парные кадры делят seq, backlog in-memory (источник истины — строка task), K.2 suspend-emit отложен — всё видно в коде и разумно, однако:

### M-1 (minor, повторяющийся процесс). apply-notes: нет секции «Пачка J» — 4 отклонения dev не зафиксированы
- **Проверено**: заголовки apply-notes — D.2/D/H(+round 2)/I; секции J нет. Это третий случай паттерна (H-9 → судья потребовал; I — M-4 → дописано; J — снова пусто).
- **Что именно теряется**: (1) расход `task_event_seq` безпереходными событиями (комментарий/stop) и семантика парных кадров; (2) in-memory backlog как не-источник-истины; (3) отложенный task.status-emit suspend'а (K.2 — проконтролировать в ревью K, иначе потеряется); (4) снапшот без SSE id.
- **Предложение**: дописать секцию J тем же форматом (решения + фикс-лист + тесты: StateSessionServiceTest, AgentTransitionToolTest, AgentStateBootstrapperTest, TaskEventsSseTest) до коммита пачки.

### N-1 (nit). Метка правила в ошибке toState
- **Цитата**: TransitionMetaTool:72 — «toState обязателен **(rule=reason-required)**».
- **Предложение**: отдельная метка (например `rule=to-state-required`) — сейчас две разные ошибки неразличимы для парсера.

### N-2 (nit). Кодовые fallback-дефолты чисел
- **Цитата**: TaskWakeBroadcaster:42 — `… ? 512 : configured`; TaskEventsController:248 — `… ? 15000L : …` (ping), RETRY_MILLIS=5000 — контрактная константа (ок, M1-прецедент).
- **Проблема**: правило «числа — конфиг»: fallback в коде при null-конфиге. Задать `harness.sse.task-backlog`/`ping-interval` в application.yml явно и упростить ветки (или дефолты в SseProperties).

---

## Позитив

- findOrCreate через ON CONFLICT по частичному индексу — ровно та «дверь к резюму без race», которую требовал D-53; seed в одной транзакции.
- Гейт/лимит резолвятся на уровне Turn-движка (батч-источник), а не инструмента — обход невозможен через прямые аргументы.
- SSE-конвейер «subscribe → snapshot → backlog → drain» без пропусков/дублей; парные кадры и безкурсорные кадры (seq<0) аккуратно разведены.
- Разведение wake-шины и SSE-бродкастера продублировано в Javadoc обоих классов — путаница из tasks 3.5/4.4 исключена.

## Вердикт

**REJECT** — 3 находки (1 minor: M-1 apply-notes J + 4 отклонения; 2 nit: метка правила, кодовые fallback-числа). Функционально пачка готова к K (REST-поверхность задач); после apply-notes и двух однострочников — approve.

---

# Re-approval пачки J (2026-09-19)

## Статус моих находок

- **M-1 (apply-notes J)** — **закрыто**: секция «Пачка J — AGENT-состояния + STATE-сессии + metaTools + SSE задач» с «Отклонениями dev (4)» (контракт StateSessionService в корне session-модуля + SQL-чтение owner из task; резолв instructionSource в execution из батча Turn'а — SecurityContext недоступен на виртуальном потоке, api не импортируется) — те самые 4 девиации, плюс фикс-лист и тесты.
- **N-1 (метка правила toState)** — **закрыто**: TransitionMetaTool:79 — `rule=to-state-required`.
- **N-2 (кодовые fallback-числа)** — **закрыто**: application.yml задал `ping-interval: 15s` и `task-backlog: 512`; ветки 512/15000 из кода ушли.

## Попутно проверено (фиксы судьи в моей зоне)

- **J-4**: отписка удаляет пустой stream из карты (`streams.remove(taskId, stream)`); переполнение backlog доставляет подписчикам служебный кадр `TaskEvent.BacklogOverflow` (клиент знает о потере и обязан перечитать снапшот) — честная семантика вместо молчаливой потери.
- **J-1 (rewake)**: механизм USER-source rewake присутствует в TurnManagerImpl, сценарий покрыт AgentTransitionToolTest (подвешенный bash + USER → rewake → transition применён) — закрывает дедловую ситуацию «Turn в bash + пользователь пишет в сессию состояния».

## Вердикт re-approval

**APPROVE** — незакрытых: 0. Все находки закрыты; пачка J готова к K (REST-поверхность задач) и L (вебхуки).
