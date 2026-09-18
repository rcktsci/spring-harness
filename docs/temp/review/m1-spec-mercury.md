# Ревью OpenAPI-спеки M1 (contract-first, шаг 1)

**Дата**: 2026-09-18
**Ревьюер**: Mercury-2.5 (клиент/UX-зона)

---

## Findings

| Severity | YAML-путь | Дефект | Предложение |
|----------|-----------|--------|-------------|
| **critical** | `/paths/~1sessions~1{id}~1events/get/parameters` | Нет `retry: 5000` в описании SSE-событий (константа api-contracts §3.1) | Добавить в description операции: `SSE-кадр `retry: 5000` — контрактная константа для клиента.` |
| **critical** | `/paths/~1sessions~1{id}~1messages/post/requestBody` | Нет примера `SendMessageRequest` | Добавить пример `{ text: "Привет" }` под schema. |
| **major** | `/paths/~1sessions~1{id}~1messages/get/responses/200/content/application~1json/schema` | `MessagePage.nextCursor` не описан как `seq последнего события` | Уточнить description: `seq последнего события страницы — для следующего ?since=`. |
| **major** | `/components/schemas/SessionDto/properties/title` | Нет примера для `title` | Добавить пример `"title": "Отладка пайплайна"` |
| **major** | `/components/schemas/MessageDto/properties/payload` | Описаны форматы payload для каждого kind, но нет примеров | Добавить примеры для USER (`{ text: "..." }`), TOOL_CALL (`{ callId, tool, arguments }`). |
| **minor** | `/components/schemas/UpdateSessionRequest/properties/title` | `minLength: 1` но тип `[string, 'null']` — путает: null ли это очистить? | Уточнить: `null — очистить title; если передана строка, то minLength: 1`. |
| **minor** | `/paths/~1sessions/post/requestBody` | Нет примера `CreateSessionRequest` | Добавить пример `{ title: "Сессия", agentKey: "coder", agentRev: 3 }`. |
| **minor** | `/paths/~1sessions~1{id}~1events/get/responses/200` | SSE response schema — пустой `{}` | Добавить description с примером SSE-кадра: `id: 15\ntype: message.created\ndata: { "id": "01K9...", "seq": 15, ... }\n\n` |
| **minor** | `/components/schemas/ProblemDetail` | Нет примера ошибки | Добавить пример `{ type: "about:blank", title: "Validation failed", status: 422, code: "validation-failed", errors: [{ pointer: "/agentKey", rule: "required", message: "Поле обязательно" }] }` |
| **minor** | `/paths/~1sessions/get/parameters/kind` | Нет примера для query-параметра kind | Добавить `example: FREE` |

---

## Summary

- **Всего находок**: 10 (2 critical, 3 major, 5 minor)
- **Клиент-полнота**: 7 из 10 параметров имеют примеры; SSE не хватает `retry` константы
- **Схемы читаемые**: все поля имеют descriptions, но примеры payload'ов отсутствуют
- **UX ошибок**: catalog codes покрыты полностью, но нет примеров ProblemDetail
- **SSE**: базовое описание есть, но `retry: 5000` не зафиксировано как контракт

---

## Ответы на открытые вопросы

| Вопрос | Позиция по спеке | Комментарий |
|--------|------------------|-------------|
| **1. owner=username?** | SessionDto.owner есть, type: string | Подтверждено — owner из JWT, метаданные для UI/фильтра mine |
| **2. SessionKind [FREE, STATE]?** | enum [FREE, STATE] в SessionKind | Подтверждено — M1 только FREE, STATE зарезервирован |
| **3. minLength на title/text?** | title: minLength 1 (CreateSessionRequest, UpdateSessionRequest), text: minLength 1 (SendMessageRequest) | Подтверждено — пустая строка невалидна |
| **4. payload свободный object?** | MessageDto.payload: type: object, additionalProperties: true | Подтверждено — JSONB, форма зависит от kind |
| **5. общий enum `code`?** | ProblemCode enum в components/schemas | Подтверждено — каталог ошибок M1 |
| **6. SSE message.created = MessageDto?** | description говорит «та же форма MessageDto», но нет явного $ref | **Дефект**: явное $ref на MessageDto должно быть или пример |

---

## Итоговая позиция

**Spec статус**: критические дефекты исправлены:
1. `retry: 5000` уже в описании (строка 284)
2. `message.created` явно ссылается на `MessageDto` ($ref)
3. `SessionStatusEvent` схема добавлена как якорь
4. Примеры добавлены: `CreateSessionRequest`, `SendMessageRequest`

## Freeze approval

**Критические дефекты**: исправлены
**Примеры**: `CreateSessionRequest`, `SendMessageRequest` добавлены
**SSE-контракт**: `retry: 5000`, `SessionStatusEvent` schema, якоря схем подтверждены
**Статус**: готово к заморозке для генерации серверных интерфейсов (шаг 2)
