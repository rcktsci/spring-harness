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

## Организация разработки (замечания владельца, сессия 2026-09-17)

**Модели и роли субагентов:**
- Разработчик — `subagent-glm-5-3-flash`; full GLM-5.3 на длинных пачках не использовать (сжигает 5ч окно z.ai).
- Mercury-2.5 — **только ревью**, на разработку не сажать.
- Сущностные пачки — полный конвейер ревьюеров; косметика/конвенции — достаточно одного ревьюера.
- Субагенту в промпте явно заявлять: «ты субагент, своих субагентов/ревью-команд не заводи» (MiniMax-M3 дважды строил собственные ревью-команды и «судейские» файлы).
- Не активировать навыки ни за субагентов, ни вместо них; допустимо дать субагенту путь к файлам навыка для самостоятельного чтения.
- Исчерпание окна провайдера или переполнение контекста сессии — не пересоздавать с нуля: переключить модель в той же сессии (`task_id` + другой `subagent_type`); непосильная сессия — новая сессия с кратким брифом состояния.
- **Чистота сессий**: самодостаточная пачка (косметика, конвенции, новая область) — свежая сессия с кратким брифом; переиспользование контекста — только когда он реально работает на задачу (доработки той же пачки, ревью только что просмотренного кода). Разросшаяся сессия = деньги за кэш-токены и расфокус.

**Параллельная работа:**
- Параллельным сессиям в одном каталоге явно запрещать сборки/тесты/docker build. Прогон — только одна сессия (разработчик); ревьюеры верифицируют по артефактам его прогона.

**Коммиты и автономия оркестратора:**
- Субагенты не коммят; коммиты — чекпоинты оркестратора после закрытия ревью-цикла пачки.
- Владелец может уйти — оркестратор продолжает сам по согласованному плану; после завершения этапа — пауза и промежуточный отчёт с положением в общем плане.
- Директивы владельца приоритетнее плана; применяются немедленно, если не указано «после этапа». Собственное ревью владельца обязательное к исполнению.

**Оценки и угрозы:**
- Никогда не давать оценок сроков.
- Прежде чем писать «защиту», назвать угрозу и её реального потребителя; не признал владелец — не делать. Изоляция исполнения = docker-контейнер; слои безопасности в глубине не плодить (path-guard удалён `70e5260`; canonical-гвард — в эндпоинте скачивания api-contracts §8, когда он появится).
- Фоновые джобы — максимально простые («нашёл — запустился»); никаких SQL-хаков над внутренностями библиотек (D-46).

**Contract-first (директива владельца, 2026-09-18):**
- Два разделённых шага: (1) проектирование машиночитаемой спеки (`api/openapi.yaml`) → полный ревью-цикл → заморозка; (2) только затем генерация из зафиксированной спеки и склейка с кодом (контроллеры на сгенерированных интерфейсах, DTO, тест-клиент).
- Зависимости — самые последние версии (openapi-generator — прежде всего).
- Jackson 2 в проект не тащить: компиляционные нужды генератора (jackson-databind-nullable и пр.) — `provided`/test-scope, никогда main-runtime; HTTP-слой проекта — Jackson 3 (Boot 4).

**Рантайм и тесты:**
- Целевой рантайм один: выделенная VM, docker-compose (Linux). Windows — машина разработки; под неё ничего не подстраивать.
- Тесты максимально воспроизводят рантайм: живой Keycloak в контексте **всегда** (профили-заглушки SSO запрещены); негативные токены — самоподписанные допустимы.

## Текущее состояние

- Дизайн-базис завершён (D-01…D-85). **M1 «Ядро сессий» завершён и заархивирован** (2026-09-18-m1-session-core). **M2 «Workflow-движок» завершён** (openspec/changes/archive/2026-09-20-m2-workflow-engine): реестры workflow/task, движок состояний, STATE-сессии, `transition` (D-59), REST/SSE/вебхуки/триггеры, приёмочный e2e; D-47…D-59.
- **M3 «Агентский слой» завершён** (openspec/changes/archive/…-m3-agent-layer, пачки N/O/P/Q/R/S): async-инструменты (окно → `ASYNC_ACCEPTED` → поздний `TOOL_RESULT late`, рестарт-скан + `AsyncTimeoutWatcher`), `spawn_subagent` + `read_compacted` + каскадная отмена поддерева, оркестратор-metaTools (`permissions_jsonb.metaTools`, гейт D-70), MCP-клиент (SDK 2.0.0, namespace `server.tool`), приёмочный e2e «Сделай биллинг»; D-60…D-70 — в docs/design/decisions.md. Архитектурные слои ArchUnit расширены `agent`/`mcp`.
- **M4 «Клиенты и релей» завершён** (openspec/changes/archive/2026-09-22-m4-clients-relay, пачки 1/T/U/V/W/X): WS-релей `/api/v1/relay` (handshake/heartbeat/takeover, close 4401/4403/4409), клиентский toolset как runtime-оверлей (parent-chain, гейт нативных файловых в CLIENT, `tool.cancel`/LOST, рестарт-скан), скачивание серверного workspace (§8, canonical-гвард), приёмка «Роуминг» (`AcceptanceWorkspaceRoamingTest`); D-72, D-77, D-78, D-80…D-85. Слой ArchUnit `relay`, SPI `ClientToolBridge`. **560 тестов** зелёных (`mvn clean verify`, 3 symlink-skip на Windows).
- Contract-first отработан на M1+M2+M3+M4: спека заморожена (ревью-цикл) → генерация 7.25 → контроллеры на сгенерированных интерфейсах → e2e через сгенерированный клиент.
- **M5 «Web Desktop» (Electron + VueJS) завершён и заархивирован** (openspec/changes/archive/2026-09-24-m5-web-desktop, пачки A/B/C/D/E/F): scaffold (Electron + Vue 3 + Vite + TS strict), SSO PKCE + safeStorage + silent refresh, `confirmCommands=always` (D-93), WS-релей-клиент `RelayClient` (full §5: handshake, ping-watchdog, 4401→silent refresh, 4403→fatal, 4409→`superseded`-takeover, parent-chain toolset-оверлей, cancel во время spawn) + локальные инструменты `bash`/`read_file`/`write_file`/`edit`/`glob`/`grep`, список сессий, лента всех `MessageKind` (markdown-it + DOMPurify), сворачиваемые tool-блоки, плейсхолдер, late-маркер, `since=0`-пейджинг, черновик per-session, SSE через `fetch`+`ReadableStream` (`SessionSseClient`/`TaskSseClient` поверх общего `SseStream`), дерево сессий, проваливание в STATE-субагентов + breadcrumb, панель задачи (statusProjection + история + комментарии), артефакты (UX-валидация + save-as + open-in-OS через temp-кэш `<sha256[:16]>-<basename>` + обработка 404/413/422; D-72 серверный canonical-гвард — security boundary), Playwright-electron e2e против in-test stub-сервера (REST + WS §5 + SSE §3.1/§3.2). **D-86…D-93** — в docs/design/decisions.md; D-88 — owner-risk-apprув отдельной строкой (аппрув получен в ревью-цикле пачки D).
- 5 новых спек в `openspec/specs/`: `desktop-shell`, `desktop-chat`, `desktop-relay-client`, `desktop-session-tree`, `desktop-artifacts`. Архитектурные слои ArchUnit: новые слои не нужны (Electron + Vue 3 — отдельный npm-пакет в monorepo, вне Maven; см. D-86/D-90).
- **Пост-M5 харденинг по живому стенду завершён** (3 заархивированных change + D-94/D-95): `2026-09-25-relay-connect-consent-confirm` (авто-подключение и регистрация реле при открытии FREE-сессии, UI per-session consent, UI подтверждения каждой команды D-93, переписанный electron-e2e), `2026-09-25-desktop-ui-robustness` (disconnect терминирует регистрацию, at-most-once на ответы диалогов без буферизации в main, ошибка каталога агентов больше не выглядит как «агентов нет»), `2026-09-25-deployment-readme` (деплой с нуля: host-гранты, D-95). Live-подтверждено на VM владельца: локальный `bash` исполняется на машине пользователя после подтверждения.
- **Следующий шаг — эволюция** (см. §"Эволюция после M5" ниже): раннеры (control/execution split), селективная компакция (агентский инструмент поверх COMPACT), MCP-сервер наружу (operatorский UI для `harness.mcp.servers`), ротация capability-секретов (cron-джоба), адаптеры задач в Jira/Trello/GitLab, multi-instance состояний (ShedLock-реестр → Redis), браузерный клиент (потребует `auth/ticket` capability по D-42), auto-update (electron-updater).
## Эволюция после M5 (за рамками архивированного change)

Каждая строчка — отдельный openspec-change с собственным ревью-циклом. Триггеры — за владельцем.

| Направление | Где зафиксировано | Триггер |
|---|---|---|
| Раннеры (control/execution split) | roadmap.md → эволюция; `TurnManager` уже изолирован в `execution`, вынос = новая реализация | Рост нагрузки (горизонтальное масштабирование Turn'ов) |
| Селективная компакция (агентский инструмент) | roadmap.md; `read_compacted` уже есть в `agent` layer | Длинные сессии — снижение качества модели от объёма контекста |
| MCP-сервер наружу (operatorский UI) | `agent-tools.md` §3; D-63 уже в ядре | Корпоративные интеграции через MCP |
| Ротация capability-секретов (cron) | api-contracts §4.4 + D-05/D-25/D-26 | Заявленный срок жизни HMAC-секрета |
| Адаптеры Jira/Trello/GitLab | `integration` слой + api-contracts | Корпоративный onboarding |
| Multi-instance состояний | ShedLock-реестр → Redis | Горизонтальное масштабирование |
| Браузерный клиент | D-42 + `auth/ticket` | Запрос пользователя на веб-без-Electron |
| Auto-update (electron-updater) | D-90 + ops-раздел | Переход от внутреннего MVP к распространению |

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
- [docs/design/roadmap.md](docs/design/roadmap.md) — фазы реализации M1–M5 (каждая = openspec-change); M5 done.
- [docs/design/decisions.md](docs/design/decisions.md) — журнал решений (ADR); включает D-86…D-93 (Web Desktop) и D-88 owner-risk-строкой.
- [docs/design/web-desktop-client.md](docs/design/web-desktop-client.md) — Web Desktop: архитектура (D-91), сценарии A–F, security (D-88/D-92/D-93), параметры конфига.
- [web-desktop/docs/smoke.md](web-desktop/docs/smoke.md) — Playwright-electron e2e против docker-compose + ручной smoke.
- [openspec/specs/](openspec/specs/) — заархивированные спеки (`desktop-shell`, `desktop-chat`, `desktop-relay-client`, `desktop-session-tree`, `desktop-artifacts`).
