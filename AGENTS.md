# AGENTS.md

## Документация

- Конечные артефакты (глоссарий, дизайн, спеки) — в `docs/`.
- Промежуточные артефакты (research, ревью, черновики) — в `docs/temp/`.

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
