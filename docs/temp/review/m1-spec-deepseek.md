# Ревью машиночитаемой спеки шага 1 contract-first (DeepSeek-V4.1-Flash)

Объект: `src/main/resources/api/openapi.yaml` (OpenAPI 3.1.0).
Эталоны: `docs/design/api-contracts.md` §0–§3, §6; `openspec/changes/m1-session-core/specs/session-api/spec.md`;
`docs/glossary.md`; `docs/design/data-model.md` §5; AGENTS.md (Jackson 3, M1-границы).
Зона: точность полей/типов/обязательности, синтаксис 3.1, генератор-риски (openapi-generator 7.x + Jackson 2),
именование/коды, позиции по 6 открытым вопросам. Сборки не запускались.

## Findings

### F1 — High: nullable-тип `[string, 'null']` утянет Jackson 2 через `jackson-databind-nullable`

- **Путь:** `#/components/schemas/SessionDto/properties/title`, `#/components/schemas/UpdateSessionRequest/properties/title`
- **Дефект:** `type: [string, 'null']` — каноничный OpenAPI 3.1-way. openapi-generator 7.x при дефолтном
  `openApiNullable=true` разворачивает такое поле в `org.openapitools.jackson.nullable.JsonNullable<T>` и
  подключает `org.openapitools:jackson-databind-nullable` (Jackson **2** databind). Для merge-patch это
  как раз даёт различение absent/explicit-null, но нарушает директиву AGENTS.md: «Jackson 2 в проект не тащить…
  never main-runtime; HTTP-слой — Jackson 3». Кроме того, `JsonNullable` нужно иметь на runtime, а не только
  в `provided` (DTO реально сериализуются/десериализуются).
- **Предложение:** зафиксировать решение ещё на шаге спеки: в generator-config `openApiNullable: false`
  (nullable → обычный тип; Jackson 3). Если для PATCH нужна семантика absent-vs-null — не полагаться на
  `JsonNullable`, а держать `UpdateSessionRequest` свободным (`additionalProperties: true`/`Map`) либо
  генерировать DTO с `@JsonInclude(NON_NULL)` и разбирать merge-patch вручную. Проверить генерацию
  пробным прогоном до заморозки (это ровно тот риск, который заморозка должна исключить).

### F2 — Medium: SSE-операция даст сгенерированный интерфейсный метод, несовместимый с `SseEmitter`

- **Путь:** `#/paths/~1sessions~1{id}~1events/get/responses/200/content/text~1event-stream`
- **Дефект:** `content: { text/event-stream: {} }` — тело схемой не типизировано. Spring-генератор всё равно
  создаст метод операции с возвращаемым типом вроде `ResponseEntity<Void>`/`void`; контроллер с `SseEmitter`
  не сможет реализовать такой интерфейс. В плане сказано «сервер отдаёт поток напрямую», но машиночитаемого
  признака «не генерировать тело» в спеке нет.
- **Предложение:** осознанно зафиксировать способ обхода в generator-config/`.openapi-generator-ignore` либо
  в описании: SSE-контроллер не реализует сгенерированный интерфейс для этой операции. Внести пункт в задачи
  генерации (шаг 2), иначе первый же `mvn generate-sources` + компиляция контроллера даст конфликт.

### F3 — Medium: `SessionDto.title` не в `required`, хотя эталоны перечисляют его без `?`

- **Путь:** `#/components/schemas/SessionDto/required`
- **Дефект:** api-contracts §2 (`id, kind, title, owner, …`) и spec.md (стр. 27) перечисляют `title` без `?`
  (в отличие от `lastTurnOutcome?`), т.е. поле присутствует всегда (значение может быть `null`). В yaml `title`
  исключён из `required` → генератор сделает его `@JsonProperty(required=false)` и (при `NON_NULL`) может вовсе
  опустить. Это расхождение контракта с источником истины.
- **Предложение:** либо добавить `title` в `required` (тип уже `[string,'null']` — будет «присутствует, но null»),
  либо, если решено «опционально и опускается», синхронизировать api-contracts §2 и spec.md явным `?`.

### F4 — Medium: семантика `owner = username` изобретена в yaml и не подтверждена эталонами

- **Путь:** `#/components/schemas/SessionDto/properties/owner`
- **Дефект:** yaml объявляет `owner` как `username` владельца. В data-model §5 поле — `owner_user_id uuid FK`,
  в api-contracts §2/spec.md — просто `owner` без типа. Контракт фиксирует username (потребует резолва
  `app_user.username`), но нигде в источниках истины это не заявлено. При этом `author` у MessageDto —
  осознанно username, так что выбор согласован, но недокументирован.
- **Предложение:** подтвердить и **внести в источники истины** (`api-contracts.md` §2, `glossary.md`): `owner` —
  username, метаданные для UI/`mine`; иначе отдать `ownerId`. Не оставлять единственным носителем решения
  сам yaml.

### F5 — Medium: у `session.status` нет схемы, а реализация уже теряет `lastTurnOutcome`

- **Путь:** `#/paths/~1sessions~1{id}~1events/get/description` (компонент отсутствует)
- **Дефект:** api-contracts §3.1 и yaml-описание определяют `session.status { runtimeStatus, lastTurnOutcome? }`,
  но машинной схемы нет — только проза. При этом в коде `SessionEvent.StatusChanged` и
  `SessionEventBroadcaster` несут только `runtimeStatus` (lastTurnOutcome не публикуется). Это разрыв
  «контракт ↔ реализация», который всплывёт при склейке (шаг 2).
- **Предложение:** добавить `#/components/schemas/SessionStatusEvent` (`runtimeStatus` required,
  `lastTurnOutcome` optional) — хотя бы для документации/будущего клиентского codegen; и завести пункт на
  доработку broadcaster/снапшота (`lastTurnOutcome`).

### F6 — Low: ULID-поля без машинного ограничения

- **Путь:** `#/components/schemas/MessageDto/properties/id`, `.../properties/callId`,
  `#/components/schemas/SendMessageAccepted/properties/messageId`
- **Дефект:** data-model §5/glossary фиксируют ULID (26 символов, Crockford base32), а для `callId` yaml даже
  не задаёт длину. Сейчас проверяется только длина у `id` и `messageId`; `callId` вообще без ограничений.
- **Предложение:** добавить `pattern: '^[0-9A-HJKMNP-TV-Z]{26}$'` (Crockford, без I/L/O/U) для `id`/`messageId`;
  для `callId` — либо тот же pattern, либо minLength/maxLength 26. `pattern` → `@Pattern` (jakarta.validation),
  Jackson 2 не тянет.

### F7 — Low: `CreateSessionRequest.agentKey` без `minLength`

- **Путь:** `#/components/schemas/CreateSessionRequest/properties/agentKey`
- **Дефект:** `required: [agentKey]` ловит отсутствие, но не пустую строку. `title`/`text` в этой же спеке
  имеют `minLength: 1`; для `agentKey` поведение на `""` (404 `agent-not-found` или 422) не задано.
- **Предложение:** `minLength: 1` для консистентности; в описании явно указать, что пустой ключ — 422
  `validation-failed`.

### F8 — Low: `nextCursor` разного типа в двух конвертах

- **Путь:** `#/components/schemas/SessionPage/properties/nextCursor` (string) vs
  `#/components/schemas/MessagePage/properties/nextCursor` (integer int64)
- **Дефект:** §0.4 говорит о непрозрачном `nextCursor`; для messages это осознанно seq. Решение рабочее, но не
  задокументировано — клиентский генератор даст два разных типа курсора без объяснения.
- **Предложение:** оставить как есть, но в описании `MessagePage.nextCursor` прямо сказать «= seq (не непрозрачный)».

### F9 — Low: `ProblemDetail` и RFC 9457 — обязательность/форматы

- **Путь:** `#/components/schemas/ProblemDetail`
- **Дефект:** required только `[code]`; `type`/`title`/`status` необязательны, `type`/`instance` — без
  `format: uri-reference`, у `type` нет `default: about:blank`. RFC 9457-совместимый ответ обычно содержит
  `type`/`title`/`status`. Плюс общий `ProblemCode` (см. вопрос 5) не даёт per-response точности.
- **Предложение:** как минимум задокументировать; при желании добавить `status` в required и `default` для `type`.
  Формат `uri-reference` — не вводить без проверки генератора (может отобразиться в `URI`).

### F10 — Low: `code` на 405/406/415/413 требует кастомных обработчиков

- **Путь:** response-компоненты `MethodNotAllowed`, `NotAcceptable`, `UnsupportedMediaType`, `PayloadTooLarge`,
  `Unauthorized`
- **Дефект:** контракт требует `code` в problem+json для ошибок, которые в Spring генерируются фреймворком
  (`MethodNotSupportedException`, `HttpMediaTypeNotSupportedException`, `MaxUploadSizeExceededException` и т.п.),
  а `401` — без `WWW-Authenticate`-челленджа (§6: «without challenge»). Это не дефект спеки, но обязательство,
  которое легко забыть при склейке.
- **Предложение:** вынести в шаг 2 явный пункт: маппинг фреймворковых исключений в `ProblemDetail`+`code`,
  отключение challenge на 401.

### F11 — Nit: неиспользуемый компонент и регистр тегов

- **Путь:** `#/components/responses/MethodNotAllowed`, `#/tags`
- **Дефект:** `MethodNotAllowed` намеренно не referenced (задокументировано) — ок; теги `SessionMessages`,
  `SessionCommands`, `SessionEvents` в PascalCase (конвенция OpenAPI обычно kebab/lowerCase). На генерацию
  интерфейсов не влияет.
- **Предложение:** оставить; при желании привести теги к единому стилю.

### F12 — Nit: merge-patch и неизвестные поля

- **Путь:** `#/components/schemas/UpdateSessionRequest`
- **Дефект:** `additionalProperties` не задан; строгий десериализатор может отклонить/невесть как обработать
  неизвестный член патча, хотя RFC 7396 допускает произвольные ключи (игнор/no-op).
- **Предложение:** осознанно выбрать: `additionalProperties: false` (строго) либо `true` (толерантно как в RFC 7396);
  зафиксировать в описании.

### F13 — Nit: M3-поля в замороженном M1-контракте

- **Путь:** `#/components/schemas/MessageDto/properties/late`
- **Дефект:** `late` (M3) в M1 всегда отсутствует; включать его в M1-контракт допустимо (spec.md требует), но
  замораживание M1 с заведомо мёртвым полем — цена, которую стоит подтвердить.
- **Предложение:** оставить (совпадает со spec.md), пометить `description` как M3/always-absent — уже сделано.

## Summary

- Проверено: 10 операций, 20 schema/response-компонентов, 3 параметра, security/servers/tags.
- Итог: **0 Blocker, 1 High, 4 Medium, 5 Low, 3 Nit** (13 находок).
- **Топ-3:**
  1. **F1 (High)** — nullable `[string,'null']` → `JsonNullable`/`jackson-databind-nullable`, прямой конфликт
     с директивой «Jackson 2 не в main-runtime». Требует решения на шаге спеки (`openApiNullable: false`
     или иная модель PATCH).
  2. **F3 + F4 (Medium)** — расхождения с эталонами по `SessionDto`: `title` (обязательность) и `owner`
     (username) заявлены только в yaml, не в api-contracts/glossary. Источник истины разъезжается.
  3. **F2 (Medium)** — SSE `text/event-stream: {}` даст несовместимый генерируемый метод; нужен осознанный
     обход на шаге генерации.
- **Что верно** (подтверждено дословной сверкой): M1-подмножество `SessionDto` (нет task/state/parent,
  `runtimeStatus` без PARKED_*) и `MessageDto` (`author` только USER, `late` M3, `tokens`); конверты
  `items`/`nextCursor`; каталог M1-кодов ровно из 9 значений spec.md; `required` у `MessageDto`
  `[id, seq, kind, payload, createdAt]`; `201`+`Location`, `202`+`{messageId, seq}`, `409 wrong-session-kind`
  только на compact; `Last-Event-ID` приоритетнее `since`; корректный 3.1-синтаксис `'null'`-строки,
  `examples` (массив), `$ref`-sibling description; уникальные `operationId`; enum'ы `SCREAMING_SNAKE`,
  camelCase-поля, kebab-case-пути.
- Замечание вне зоны: код `SessionEvent.StatusChanged`/`SessionEventBroadcaster` не несёт `lastTurnOutcome`,
  хотя контракт §3.1 его требует (см. F5) — учесть при шаге 2.

## Ответы на открытые вопросы

1. **owner = username?** Да, оставить username — согласуется с `author` (username) и фильтром `mine` из JWT;
   но это решение обязано попасть в `api-contracts.md` §2 и `glossary.md`, иначе источник истины врёт.
2. **SessionKind?** Оставить общий enum `[FREE, STATE]` — это стабильный доменный enum, а не новая сущность;
   M1-ограничение (создаётся/возвращается только FREE) — поведенческое и уже задокументировано. Так M2 не
   делает breaking-widening enum'а в сгенерированных клиентах.
3. **minLength?** Да, `minLength: 1` на `title`/`text` оправдан и выражает правило 422; добавить такой же
   `agentKey` и помнить, что пробельная строка им не ловится (сервисное правило).
4. **payload object?** Оставить `type: object, additionalProperties: true` (свободный JSONB) — избегаем
   пустого POJO и `oneOf`-генерации; per-kind формы описаны прозой и ещё не заморожены (`covers` — D-44).
   Типизировать (discriminated `oneOf`) только когда формы устоятся.
5. **Общий enum code?** Оставить единый `ProblemCode`; точность «ответ → код» держать в `description` ответов.
   Per-response `const`/`oneOf` даст зоопарк near-identical схем и плохую эргономику клиента — отвергнуть для M1.
6. **SSE = MessageDto?** Да: `message.created` обязан переиспользовать `MessageDto` (та же форма, что GET /messages;
   spec §3.1); уточнить: добавить схему `SessionStatusEvent` для `session.status`, тело SSE оставить нетипизированным.

## Freeze approval

**APPROVE** (DeepSeek-V4.1-Flash, финальная сверка `src/main/resources/api/openapi.yaml` с судейскими фиксами
`m1-spec-judge.md`).

Проверено по файлу (все мои находки закрыты):

- **F1 (High)** — топ-description спеки (строки 29–34): `openApiNullable=false`, запрет Jackson-2-зависимостей
  в main-runtime, ссылка на apply-notes шага 2. Дублировано в `apply-notes.md` §«Шаг 2 contract-first». OK.
- **F2 (Medium)** — SSE-описание (287–288): «эндпоинт исключается из генерации, SseEmitter вручную»;
  apply-notes содержит `.openapi-generator-ignore`/операционный фильтр. OK.
- **F3 (Medium)** — `SessionDto.title` в `required` (501) + описание «поле присутствует всегда, null…» (510). OK.
- **F4 (Medium)** — `owner=username` закреплён в yaml (513) **и** в эталонах: `api-contracts.md` §2 (стр. 40,
  `preferred_username`, резолв `owner_user_id→username` — серверный слой) + `glossary.md` §4 (стр. 55,
  Session/владелец светится как username). OK.
- **F6 (Low)** — ULID `pattern '^[0-9A-HJKMNP-TV-Z]{26}$'` на `MessageDto.id` (543), `callId` (577),
  `SendMessageAccepted.messageId` (685); алфавит Crockford (без I/L/O/U) проверен посимвольно. OK.
- **F7 (Low)** — `agentKey minLength: 1` (646) + разграничение «пусто — 422 / неизвестный — 404» (647–649). OK.
- **F8 (Low)** — `SessionPage.nextCursor` opaque (611–615) vs `MessagePage.nextCursor` int64 seq (625–631)
  с явным «НЕ непрозрачный». OK.
- **F9/F10** — вынесены в `apply-notes.md` (RFC 9457-хендлеры 405/406/415/413, 401 без challenge). OK.

Дополнительно (S-J-1/3/4/5/8) подтверждено: merge-patch-семантика в описании операции (163–167) и
`UpdateSessionRequest` (657–661); SSE +406 (317) +422 (318); `SessionStatusEvent` (427–439); COMPACT-форма
D-44 (565–566); примеры `CreateSessionRequest`/`SendMessageRequest`/`payload` (635, 670, 567–572); ULID-пример
SSE-кадра (307–313).

Регрессий заморозки не выявлено. Единственный не-блокирующий нит на шаг 2: смешение `example` (singular,
635/670) и `examples` (567/699) — в 3.1 `example` deprecated, но допустим; при генерации учесть, что
`UpdateSessionRequest` c `additionalProperties: true` + declared `title` требует проверки маппинга экстра-полей.
**Вердикт: approve; спека готова к заморозке.**
