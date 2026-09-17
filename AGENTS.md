# AGENTS.md

## Документация

- Конечные артефакты (глоссарий, дизайн, спеки) — в `docs/`.
- Промежуточные артефакты (research, ревью, черновики) — в `docs/temp/`.

## Правила владельца (зафиксировано в диалоге 2026-09-16)

- Уровень проекта: закрытая внутренняя разработка, один инстанс на VM, без прода; «сломалось — пофиксили — передеплоили». Никакого энтерпрайз-раздутия.
- Все числовые параметры — конфиг (`application.yml` / `@ConfigurationProperties`); хардкод чисел запрещён.
- Каждая новая сущность обязана иметь сценарий необходимости у владельца.
- Любое сущностное дизайн-решение — строкой в `docs/design/decisions.md` (решение → отвергнутые альтернативы → почему).
- Дизайн-правки проходят ревью-цикл (ниже) до вливания.

## Ревью-конвейер (проверенная схема)

1. Свежие сессии на каждую задачу: GLM-5.3-Flash + DeepSeek-V4.1-Flash + Mercury-2.5 (MiniMax исключён — нестабилен у провайдера; Qwen недоступен).
2. Фазы обязательны: ревью → кросс-чек находок коллег → судейские фиксы (судья — оркестратор) → **аппрув всех ревьюеров темы** (reject → фикс → re-approve). 100% консенсус.
3. Не предписывать субагентам активацию навыков — решают сами.
4. Находки — в `docs/temp/review/`; сводки — короткие, большие выкладки в файлы.

## Текущее состояние

- Дизайн-базис завершён (D-01…D-46: MVP-уровень).
- M1 «Ядро сессий»: чендж `m1-session-core` спроектирован и ревью-закрыт; идёт apply.
- **Закоммичено**: A — фундамент+БД (`8d6c92d`), B — sso-gate+session-store (`e22409a`), C — llm-gateway+workspace-tools (`87e48c6`), D — turn-движок (`203316b`), D-2 — конвенции/тест-инфраструктура (`06eab98`). Задачи 1.1–7.6 отмечены в `tasks.md` (26/37).
- **Осталось**: пачка E — API (`8.1–8.5`) + компакция (`9.1–9.2`); пачка F — приёмка (`10.1–10.4`), затем `/opsx:archive`.
- **Открытый долг (решить владельцу)**: Contract-first не заведён в `tasks.md` (нет `openapi.yaml` + генерации серверных интерфейсов/DTO/тест-клиента, хотя `api-contracts.md`/`architecture.md` это декларируют); Spring AI `@Tool` не используется — инструменты объявляются вручную (`NativeAgentTools`/`TurnPayloads`).
- Конвенции (обязательны): импорты вместо FQDN; `@RequiredArgsConstructor` + final-инъекция на всех Spring-компонентах (`lombok.config`); e2e-тесты — от единого `BaseApplicationTest` (Postgres+Keycloak+WireMock, без профилей-заглушек SSO); тесты в пакете `tests/`; `*Impl` — в `.impl`; никаких обходных SQL-хаков в джобах.

## Ключевые документы

- [docs/glossary.md](docs/glossary.md) — глоссарий: термины, типы, инварианты.
- [docs/design/architecture.md](docs/design/architecture.md) — модули монолита, контракты, стек.
- [docs/design/data-model.md](docs/design/data-model.md) — схема БД: таблицы, поля, инварианты.
- [docs/design/execution-model.md](docs/design/execution-model.md) — wake, Turn, цикл агента, инструменты, отмена.
- [docs/design/workflow-domain.md](docs/design/workflow-domain.md) — шаблоны/ревизии, типы состояний, вебхуки, оркестратор.
- [docs/design/api-contracts.md](docs/design/api-contracts.md) — публичные контракты: REST/SSE/WS (прошёл трёхстороннее ревью, 3×approve).
- [docs/design/security-multitenancy.md](docs/design/security-multitenancy.md) — аутентификация, матрица AccessPolicy, секреты, аудит.
- [docs/design/operations.md](docs/design/operations.md) — логи, метрики, health, деплой, бэкапы, алерты.
- [docs/design/agent-tools.md](docs/design/agent-tools.md) — каталог инструментов агента: нативные, мета-, MCP.
- [docs/design/client-cli.md](docs/design/client-cli.md) — attach-CLI: команды, UX-минимум.
- [docs/design/roadmap.md](docs/design/roadmap.md) — фазы реализации M1–M5 (каждая = openspec-change).
- [docs/design/decisions.md](docs/design/decisions.md) — журнал решений (ADR).
