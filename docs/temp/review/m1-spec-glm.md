# Ревью OpenAPI-спеки M1 (contract-first, шаг 1) — GLM-5.3-Flash

**Дата**: 2026-09-18
**Ревьюер**: GLM-5.3-Flash (субагент-ревьюер, без своих субагентов)
**Объект**: `src/main/resources/api/openapi.yaml` (OpenAPI 3.1, 667 строк)
**Эталоны**: `docs/design/api-contracts.md` §0–§3+§6, `openspec/changes/m1-session-core/specs/session-api/spec.md`, `docs/glossary.md`, `docs/design/data-model.md` §5, `docs/design/decisions.md` (D-40…D-46), AGENTS.md.

---

## Findings

| # | Severity | YAML-путь | Дефект | Предложение |
|---|----------|-----------|--------|-------------|
| F-1 | **minor** | `/components/schemas/SessionDto` | `title` не в `required`, хотя description говорит «null, если не задано» (т.е. поле **всегда присутствует**, nullable), и эталон openspec (SessionDto: `id, kind, title, owner, …` — `title` без `?`) подразумевает то же. Как есть — генерация сделает `title` опциональным, сервер вправе omit'ить поле, а контракт обещает `"title": null`. | Добавить `title` в `required` (тип уже `type: [string, 'null']` — семантика «всегда есть, бывает null» сохранится). |
| F-2 | **minor** | `/paths/~1sessions~1{id}~1events/get/responses` | Асимметрия с `listMessages`: там 422 на невалидные `since`/`limit` декларирован, здесь — нет, хотя `since=-5` так же подлежит validation-failed (§6: «тело/параметры»). Нет и `406`: у SSE есть своё представление (`text/event-stream`), а description общего `NotAcceptable` упоминает только `application/json`. | Добавить `'406'` и `'422'` в operation; в description `NotAcceptable` дополнить: «для SSE — Accept без `text/event-stream`». |
| F-3 | **minor** | `/components/schemas/MessageDto/properties/id`, `/components/schemas/SendMessageAccepted/properties/messageId` | ULID ограничен длиной (26/26), но без `pattern` — сгенерированный клиент/серверная валидация не отсекают не-Crockford-строки. | Добавить `pattern: ^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{26}$` (Crockford base32: без I, L, O, U). Опционально, но дёшево до заморозки. |
| F-4 | **minor** | `/components/schemas/UpdateSessionRequest` | Поведение merge-patch с **неизвестными** полями не зафиксировано (`{"foo": 1}` → 422 или молча игнорировать?). RFC 7396 формально добавил бы член к ресурсу — у ресурса его нет. Заморозка оставляет дыру, реализация решит сама — расхождение с «файл замораживается». | Зафиксировать одной строкой в description. Рекомендация: неизвестные поля → `422 validation-failed` c `errors[].pointer` (строже, соответствует духу каталога ошибок); решение — за автором/судьёй. |
| F-5 | **info** | `/components/schemas/MessageDto/properties/payload` (ветка COMPACT) | «Точная JSON-форма covers фиксируется пачкой компакции (D-44)» — устаревшая формулировка: D-44 **уже зафиксировал** форму — последовательность seq-интервалов `[{from, to}]`; ключ `summary` уже читается кодом (`SessionPromptBuilder`). Спека-источник истины может фиксировать прямо сейчас. | Переписать: `COMPACT — {"covers": [{"from": seq, "to": seq}], "summary": string}` (D-44). |
| F-6 | **info** | `/paths/~1sessions~1{id}~1events/get/responses/200` | SSE-кадры не типизированы (осознанно и задокументировано: «генерация тела не производится»). `message.created` = MessageDto и форма снапшота `session.status {runtimeStatus, lastTurnOutcome?}` живут только в прозе description. Не блокирует заморозку (тело и не генерится), но тест-клиент (8.5) и будущий attach-CLI сверяются с прозой, не с машиночитаемым артефактом. | Опционально, до/после заморозки: добавить документирующие схемы кадров (`message.created` → `$ref MessageDto`; `session.status` → `{runtimeStatus, lastTurnOutcome?}`; `ping`/`retry`) с примером кадра — якорь для ручного SSE-парсера. |
| F-7 | **info** | весь файл (codegen-оценка) | Провокаций Jackson-2 нет: ни одного `oneOf`/`allOf`/`discriminator`, payload — свободный `object` (→ `Map<String,Object>`), nullable — идиоматично для 3.1 (`type: [x, 'null']`), форматы только uuid/date-time/int64/int32/uri-reference. Спека codegen-friendly. Риск вне спеки: шаблоны openapi-generator исторически тянут `com.fasterxml`-аннотации в модели — это concern шага 2 (конфиг генератора + provided/test-scope по директиве AGENTS.md), спека тут ни при чём. | Действий по спеке нет. На шаге 2: свежайший openapi-generator, проверить его поддержку Jackson 3 в spring-шаблонах до склейки. |

**Не подтверждается critical-находка Mercury №1**: `retry: 5000` в description SSE-операции **присутствует** (строка 272 спеки), снапшот, `Last-Event-ID`-приоритет и ping-конфиг — тоже. По «нет явного $ref на MessageDto» (Mercury №2/вопрос 6) — см. F-6: следствие осознанной нетипизации SSE-тела, не дефект заморозки. Minor-находки Mercury про примеры (examples) — вкусовщина, заморозке не препятствует.

---

## Summary

- **Всего находок**: 7 — 0 critical, 0 major, **4 minor** (F-1…F-4), 3 info (F-5…F-7).
- **Полнота против api-contracts §0–§3+§6**: все 10 M1-операций на месте (агенты, CRUD сессий, messages GET/POST, compact, stop, SSE); `/tree`, tasks, webhooks, relay, билеты, скачивание — корректно отсутствуют. Заголовки `Location` (201) и `Last-Event-ID` есть; параметры `mine/kind/q/cursor/limit/since` — все; конверты `{items, nextCursor?}` — у трёх списков, у AgentCatalog конверт без пагинации — по §1. Коды: M1-подмножество каталога §6 — ровно 9 значений, ни лишнего, ни недостающего; 405 корректно не перечисляется (задокументирован), 406 — на всех JSON-ответах, 413/415 — на всех body-эндпоинтах, 401 — на всех операциях.
- **M1-границы**: чисто. `task/stateCode/parentSessionId/PARKED_*` отсутствуют (и явно названы исключениями в description); `late`, `read_compacted`, «поддерево» в stop — только с пометкой M3, как разрешено; enum `STATE` — см. вопрос 2 (не утечка).
- **Схемы против data-model §5 / glossary**: типы и обязательность сходятся (uuid id, ULID 26, seq int64 ≥1, lastSeq ≥0, tokens nullable, kind/author/payload/callId/late по §2 MessageDto); enum'ы `FREE|STATE`, `IDLE|TURN_RUNNING`, `COMPLETED|FAILED|CANCELLED`, 6 MessageKind, статусы TOOL_RESULT `OK|ERROR|CANCELLED|LOST` — совпадают с глоссарием и фактическим `TurnPayloads` (включая опциональный `toolCallId`).
- **OpenAPI 3.1**: валидно; все `$ref` разрешаются (проверено по всем 30+ ссылкам, `MethodNotAllowed` — осознанно неиспользуемый компонент-документация); nullable через `type: [x,'null']`; discriminator не нужен; сиблинг-`description` у `$ref` легален в 2020-12.
- **Безопасность**: глобальный `bearerJWT` на всём, исключений нет; вебхуков/билетов в M1 нет — и в спеке их нет. Соответствует §0.1 и D-41.
- **CodeGen-friendly**: да (F-7) — без сложных комбинаций, свободный payload, простые enum'ы; type-array nullable требует свежего openapi-generator — по директиве так и планируется.
- **Вердикт**: спека точная и полная, к заморозке готова после minor-правок. Обязательные до заморозки: **F-1, F-2**; желательные: F-3, F-5, F-4 (решение по семантике merge-patch); F-6 — опционально (можно и после, до шага генерации тест-клиента).

---

## Ответы на открытые вопросы

**1. `owner` = username vs subject?**
Позиция: **username — правильно**. Обоснование: эталон сам отвечает — `MessageDto.author? (username; только USER)` в api-contracts §2, и `owner` стоит в одном ряду; `mine`-фильтр сравнивает owner с JWT — наружу должно светиться то, что есть в токене (`preferred_username`). `keycloak_subject` — внутренняя идентичность `app_user` (data-model §1), в публичный DTO не выносится; конвертация `owner_user_id → username` — забота серверного слоя.

**2. `SessionKind` = [FREE, STATE] vs [FREE]?**
Позиция: **[FREE, STATE] — оставить**. Обоснование: род — глоссарный инвариант домена (Session: «Род FREE или STATE»), а не M2-поле; enum не тянет за собой ни task, ни stateCode. Срезка до [FREE] дала бы в M2 ломающее расширение enum'а для всех сгенерированных клиентов. POST и description честно фиксируют «создаётся только FREE»; фильтр `kind=STATE` в M1 вернёт пустую страницу — безвредно.

**3. `minLength: 1` на title/text?**
Позиция: **да, правильно**. Обоснование: отличает «пусто» от «не задано» — при создании без title остаётся `null` (data-model: `title text NULL`), а `""` — осмысленно невалиден (422). В merge-patch комбинация `type: [string,'null'] + minLength: 1` даёт точную семантику RFC 7396: `null` — очистить, `""` — 422, отсутствие — не менять. maxLength не нужен — размер тела ловит 413/лимит конфигом.

**4. `payload` — свободный object?**
Позиция: **да**. Обоснование: payload_jsonb по data-model; формы по kind — достояние `TurnPayloads` (код, D-M1-1), жёсткая типизация oneOf'ами в спеке создала бы второй источник истины и хрупкий контракт при том, что генерация всё равно свела бы это к Map. Описательные формы в description (сверены с TurnPayloads — совпадают) — правильный баланс; форму COMPACT стоит лишь актуализировать по D-44 (F-5).

**5. Общий enum `code`?**
Позиция: **да, один каталог**. Обоснование: §6 — «каталог ошибок», «code вне каталога — дефект реализации» — по определению единый список; пер-эндпоинтные enum'ы усложнили бы генерацию и всё равно врали бы (405/401 невыразимы per-endpoint честно). Один `ProblemCode` → один сгенерированный класс, точное соответствие код→HTTP зафиксировано в description response-компонентов.

**6. SSE `message.created` = MessageDto?**
Позиция: **да по существу; фиксация прозой допустима, схема — желательна**. Обоснование: тело SSE не типизируется и не генерится (осознанно, задокументировано в ответе 200), поэтому «та же форма MessageDto» в description — достаточная фиксация для заморозки. Но тест-клиент 8.5 и attach-CLI парсят кадры руками — машинный якорь (схема кадров + пример, F-6) снимет единственное место, где контракт живёт только прозой. Блокером это не является.

---

## Freeze approval

**Дата**: 2026-09-18. **Вердикт: approve.**

Проверка закрытия своих находок по `openapi.yaml` после судейских фиксов S-J-1…S-J-9:

| Находка | Статус | Где в спеке |
|---|---|---|
| F-1 `title` вне required | **Закрыта** | `SessionDto.required` содержит `title`; description — «присутствует всегда, null — если не задано» |
| F-2 нет 406/422 на SSE | **Закрыта** | operation `streamSessionEvents` получил `'406'` и `'422'`; `NotAcceptable` дополнен «для SSE-потока — text/event-stream» |
| F-3 ULID без pattern | **Закрыта** | `pattern ^[0-9A-HJKMNP-TV-Z]{26}$` на `MessageDto.id`, `MessageDto.callId`, `SendMessageAccepted.messageId` (набор корректный: без I/L/O/U) |
| F-4 merge-patch неизвестные поля | **Закрыта** | Семантика зафиксирована (неизвестные игнорируются, RFC 7396-lenient): в описании PATCH-операции и `UpdateSessionRequest` (+ `additionalProperties: true`); выбор «ignore» вместо моего «422» — судейское решение, принято, эталону не противоречит |
| F-5 COMPACT covers «фиксируется пачкой» | **Закрыта** | Форма D-44 зафиксирована: `{"covers": [{"from","to"}], "summary": string}` |
| F-6 (info) SSE-якоря | **Добавлено** | Схема `SessionStatusEvent`, `message.created` = `$ref MessageDto` в description, пример SSE-кадра в 200-ответе |
| F-7 (info) codegen/Jackson | **Добавлено** | В info.description: `openApiNullable=false`, запрет Jackson 2 в main-runtime, SSE — вручную на SseEmitter, ссылка на apply-notes |

Попутно проверены остальные правки (примеры S-J-8, `agentKey` minLength, курсорные descriptions S-J-7): дефектов нет, все новые `$ref` резолвятся.

**Замороженную версию спеки — approve к коммиту.**
