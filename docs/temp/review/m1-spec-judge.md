# Судейские вердикты: шаг 1 contract-first (спека) — оркестратор

## Принято (до заморозки)

| # | Фикс |
|---|---|
| S-J-1 | `SessionDto.title` — в `required` (nullable, но присутствует всегда); merge-patch: неизвестные поля игнорируются (RFC 7396), `null` → очистить, `""` → 422 — зафиксировать в описании операции (GLM F-1/F-4, DS F3) |
| S-J-2 | JsonNullable-ловушка (DS F1 high): в топ-description спеки — обязательные опции генерации: `openApiNullable=false`, Jackson-2-зависимости запрещены (AGENTS.md) |
| S-J-3 | SSE-эндпоинт: (а) добавить 406/422-ответы (GLM F-2); (б) note «при генерации интерфейса — исключить, реализуется SseEmitter вручную» (DS F2); (в) schema-якорь `SessionStatusEvent {runtimeStatus, lastTurnOutcome?}` + `message.created` = `$ref MessageDto` (GLM F-6, Mercury #3) |
| S-J-4 | COMPACT payload: зафиксировать форму D-44 (`covers: [{from,to}]`, `summary`) вместо «фиксируется пачкой» (GLM F-5) |
| S-J-5 | `owner` = username — синхронизировать эталоны: строка в api-contracts §2 + глоссарий (делает dev, строкой) |
| S-J-6 | ULID: `pattern ^[0-9A-HJKMNP-TV-Z]{26}$`; `agentKey`/`title`/`text`: minLength 1 (DS F6/F7) |
| S-J-7 | Курсоры: SessionPage.nextCursor — opaque string; MessagePage.nextCursor — int64 seq (= since-семантика); описать в descriptions (DS F8) |
| S-J-8 | Примеры: CreateSessionRequest, SendMessageRequest, MessageDto.payload (Mercury #2) |
| S-J-9 | DS F9/F10 (RFC 9457-обработчики 405/406/415/413, кастомные коды) — в apply-notes для шага 2 (генерация/реализация), не в спеке |

## Отклонено

| # | Обоснование |
|---|---|
| S-R-1 | Mercury critical «retry: 5000 отсутствует» — константа есть в description (строка 272), подтверждено кросс-чеком GLM |
| S-R-2 | 6 открытых вопросов — решены консенсусом 3/3: owner=username (S-J-5); SessionKind [FREE,STATE] оставить; minLength 1 подтвердить + agentKey; payload — свободный object (oneOf до стабилизации форм отвергнут, D-44 — единственная фиксация); общий enum `code` (per-endpoint врали бы из-за 405); SSE message.created = MessageDto (плюс якорь S-J-3в) |

## Условия заморозки

S-J-1…S-J-8 применены в `openapi.yaml` (S-J-5 — также строка в api-contracts §2 + glossary; S-J-9 — apply-notes) → re-approve всех трёх ревьюеров → commit замороженной спеки.
