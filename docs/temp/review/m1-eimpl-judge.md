# Судейские вердикты: пачка E-impl (оркестратор)

## Принято (гейтит коммит)

| # | Фикс |
|---|---|
| E-J-1 | **Mojibake-регрессия (GLM E1/DS F1, блокер)**: `git restore` трёх docker-тестов (`ContainerWorkspaceToolsDockerTest`, `WorkspaceContainerFailureDockerTest`, `WorkspaceContainerManagerDockerTest`), заново — только конструктор `LimitsProperties` (100). Проверить юникод-литерал roundtrip-теста |
| E-J-2 | **Пустой text финального ASSISTANT (GLM E2/DS F2/Mercury, консенсус)**: `MessageAggregator.aggregate(stream, responseRef::set)` + no-op в subscribe; ассерты текста в тестах 7.2/8.3. Починить в ЭТОЙ пачке — приёмка 10.2 без него невалидна |
| E-J-3 | **Курсор (GLM E3/DS F3)**: полная точность Instant (toString/parse, не toEpochMilli) + битый курсор → 422 validation-failed (не 500) + тест «сиды в одну миллисекунду» |
| E-J-4 | **Правка замороженной спеки (GLM/DS-вариант)**: в 202 compact/stop добавить `content: {application/json: {schema: {}}}` → регенерация (`ResponseEntity<Void>` сохраняется) → убрать костыль `Accept: */*` из `SessionCommandsApiTest`. Mercury-вариант (SendMessageAccepted) отклонён — compact/stop не возвращают тело. Спека: минимальная аддитивная правка, отметить в apply-notes |
| E-J-5 | **Коррекция комментариев (GLM/DS F4)**: apply-notes/pom — неверное утверждение «J3 игнорирует J2-аннотации» заменить: Jackson 3 использует тот же jackson-annotations 2.x и читает их; функционального дефекта нет (provided-статус сохраняет) |
| E-J-6 | **Явные null'ы J3 (GLM E4)**: сериализация absent-полей как `"late":null` — принять как поведение, зафиксировать в apply-notes (внутренний MVP, клиенты толерантны); спеку не трогать |

## Отклонено

| # | Обоснование |
|---|---|
| E-R-1 | Mercury «SendMessageAccepted в 202 compact/stop» — misread: операции Void (202 без тела) |
| E-R-2 | Mercury «фикс пустого ASSISTANT в M3» — приёмка фазы требует видимых ответов; чинится одной строкой здесь |

## Условия коммита

E-J-1…E-J-6 закрыты + `mvn clean verify` зелёный + re-approve трёх ревьюеров (по своим находкам).
