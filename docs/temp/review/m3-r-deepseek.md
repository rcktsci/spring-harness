# Ревью M3 batch R — DeepSeek-V4.1-Flash

Дата: 2026-09-21. Объект: FQDN-чистка (26 файлов / 53 вхождения), `GenEnums`, `McpAgentConfigAuditor` (R-round после Q-1), R.1 D-69, R.2 owner/depth, R.3 рестарт-скан, `RestartScanTest`. Сборки не запускались. PgJDBC-семантика проверена отдельно (см. R-1).

**Вердикт: REJECT** — 4 находки (R-1 HIGH; R-2/R-3/R-4 MINOR).

---

## Фокус-проверки

### FQDN-чистка — ✅
Inline-FQDN `se.rocketscien.harness.api.gen.model.*` в `src/main/java` не осталось (только обычные `import`). `GenEnums` (package-private, `api`) централизует 4 enum-конверсии (`SessionKind`, `MessageKind`, `SessionRuntimeStatus`, `TurnOutcome`) через `valueOf(name)`; `ApiMappers` использует `GenEnums.*` (9 вызовов). Остаточные 2 коллизии domain↔gen структурно неустранимы и обоснованы javadoc'ом `GenEnums`:
- `ApiMappers` — доменный `task.TaskTreeNode` vs `gen.model.TaskTreeNode`;
- `SessionsController` — доменный `session.SessionKind` vs `gen.model.SessionKind`.
Переименование доменных классов — хуже. Принято.

### R.1 D-69 (non-inheritance) — ✅
Наследования нет: дочерняя сессия пиннится на **свою** ревизию агента (`createChildSession` по `agentKey` субагента), гейты манифеста и `executeToolCall` читают `metaTools` из запинненной ревизии. Покрыто тестом «orchestrator spawn'ит sub-coder → orchestrator-tools недоступны».

### R.2 owner/depth — ✅
`owner = parent.owner`, `parent_session_id = parent.id`, `depth = parent.depth + 1` (колонка 074). Покрыто integration-тестом.

### R.3 рестарт-скан / orphan subagent-контейнеры — ✅ (по существу)
`WorkspaceContainerManager.removeOrphanContainers(liveSessionIds)` — generic по session-namespace: суффикс парсится как UUID, `harness-task-*` отбрасываются. Субагентские `harness-<subSessionId>` покрываются без спец-кода; осиротевший sub-контейнер при живых parent/siblings удаляется (тест `RestartScanTest`). Форма `tools_jsonb.mcp` в `AgentTurnEngine` (List per-server `{server, include?, exclude?}`) и в `McpAgentConfigAuditor` совпадает — Q-1 закрыт.

---

## Находки

### R-1 (HIGH). `McpAgentConfigAuditor` — `?` в SQL не эскейплен → падение старта
`McpAgentConfigAuditor.java:34-35`:
```java
List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT key, tools_jsonb FROM agent WHERE tools_jsonb ? 'mcp'");
```
- В PgJDBC одиночный `?` — **placeholder**; JSONB-оператор `?` требует эскейпа `??`. Проверено прямым вызовом `org.postgresql.core.Parser.parseJdbcSql` (42.7.13):
  - `... WHERE tools_jsonb ? 'mcp'` → `nativeSql="... tools_jsonb $1 'mcp'"`, `binds=1`;
  - `... WHERE tools_jsonb ?? 'mcp'` → оператор `?` сохранён, `binds=0`;
  - `... ? 'mcp' AND key = ?` → `binds=2`.
  (`??`-escape есть с pgjdbc 42.2.11 → поведение одинаково на 42.4/42.6/42.7.)
- `queryForList` вызывается **вне** try (per-row catch ниже), аргументов 0 → `PSQLException` (syntax error at "$1" / no value for parameter 1). Исключение из `@EventListener(ApplicationReadyEvent)` всплывает в `SpringApplication.run` → **контекст не поднимается**; аудитор — `@Component` без условий, т.е. на любом профиле.
- Теста на аудитор нет (`grep` по `src/test` — 0 ссылок на `McpAgentConfigAuditor`), и `McpToolsTest` его путь не покрывает → прогон не защищает.
- Фикс: `jsonb_exists(tools_jsonb, 'mcp')` либо `tools_jsonb ?? 'mcp'`; плюс context-тест на `audit()` (известный/неизвестный сервер).

### R-2 (MINOR). Аудитор даёт сигнал только на старте и молча пропускает неверную форму
- `audit()` — только `ApplicationReadyEvent`; по D-39 агенты заводятся в БД **вручную после** старта → на первом старте таблица пуста и аудит ничего не видит, повторного аудита при появлении/загрузке агента нет.
- `if (!(mcp instanceof List<?> configs)) continue;` — конфиг старой/иной формы (map `{servers[], …}`) не даёт ни ERROR, ни предупреждения; при этом `AgentTurnEngine` на такой форме роняет Turn (`IllegalStateException`). Сигнал о неверной форме теряется именно там, где аудитор и задумывался (Q-1).
- Предложение: аудит при загрузке агента/ревизии (или по запросу) + WARN на не-List `mcp`.

### R-3 (MINOR). Q-3 (`mcp-name-collision`) не покрыт тестом
`AgentTurnEngine` строки 528-534 реализуют fail-fast дублей (`seen`), но в `McpToolsTest` кейса «повтор сервера в `tools_jsonb.mcp` → `MCP-name-collision`» нет (7 тестов: lazy/filters/exclude-wins/call/async/unknown-server/401-ветки). Заявленное в apply-notes покрытие Q-3 не подтверждается тестом.
Предложение: тест `duplicateMcpServerEntryFailsTurn`.

### R-4 (MINOR, согласие с GLM M-1). Нет секции «Пачка R» в apply-notes
Заголовки apply-notes: N/O/P/Q — секции R нет. Не зафиксированы: FQDN-аудит (53→2 и перечень остатка), `GenEnums`, R.1–R.3, фактический дизайн R.3. Отдельно: скобка tasks R.3 «cleanup через `SubtreeCanceller` при stop parent» **не соответствует** реализации (контейнеры поддерева на stop не удаляются; очистка — только рестарт-сканом по факту живости сессии), что согласуется со спекой `subagent-lifecycle` (контейнеры там не упомянуты), но противоречит `proposal.md:13`. Нужна либо запись отклонения в apply-notes/D-журнал, либо правка текста в `proposal.md`/`tasks.md`.

---

## Позитив

- FQDN-чистка доведена до конца с честным остатком «2 неустранимых» в javadoc, а не декларативным «всё почищено».
- R.3-тест проверяет нетривиальный инвариант (осиротевший субагент при живых parent/siblings) — ровно сценарий рестарт-скана.
- List-форма `tools_jsonb.mcp` синхронна в движке и аудиторе (Q-1 закрыт консистентно).

---

## Re-approval (2026-09-21)

Все 4 находки закрыты; сборка не запускалась (по указанию), сверка по исходникам и отчёту `mvn clean verify` — 489 тестов, BUILD SUCCESS.

- **R-1 (HIGH) — ✅ закрыто.** `McpAgentConfigAuditor.java:36-37` → `WHERE jsonb_exists(tools_jsonb, 'mcp')`; JSONB-колонка подтверждена (`2026/004_create_table_agent.xml`: `tools_jsonb type=JSONB`) → функция валидна и index-friendly, `?`-плейсхолдер больше не участвует. Добавлен тест `McpToolsTest#auditorLogsUnknownServerAndWarnsOnNonCanonicalMcpForm`, вызывающий `auditor.audit()` на реальном контексте (SQL исполняется) — регрессия R-1 была бы поймана.
- **R-2 (MINOR) — ✅ закрыто.** Не-`List` форма `mcp` → `log.warn` (строки 43-49), не silent `continue`; assertion на WARN с `agentKey` в тесте присутствует.
- **R-3 (MINOR) — ✅ закрыто по существу.** `McpToolsTest#mcpNameCollisionFailsFastOnDuplicateServerNames` (дубликаты `name` в `harness.mcp.servers` → `IllegalStateException` `MCP-name-collision`, fail-fast в конструкторе реестра) + существующий `unknownMcpServerInAgentConfigFailsTurn` для runtime-пути.
- **R-4 (MINOR) — ✅ закрыто.** `apply-notes.md §«Пачка R»` (строки 469-530): R.1–R.4, фиксы R-1/R-2/R-3, FQDN-аудит (53→2, перечень 25 файлов + 2 неустранимые коллизии, `GenEnums`), verify 489. Отклонение R.3 («stop НЕ удаляет контейнеры поддерева; замороженная `subagent-lifecycle` этого не требует; снимает только рестарт-скан») зафиксировано явно — согласованность tasks/proposal/спеки восстановлена.

**Новых блокеров нет.** Остаётся NIT (не блокирует): ветка `AgentTurnEngine` `seen.add(namespaced)` → `MCP-name-collision` (строки 502, 528-534) по-прежнему без теста — её триггер теперь только повтор `server` внутри одного `tools_jsonb.mcp[]` (конфиг-уровневые дубли ловит реестр раньше); либо покрыть кейсом, либо считать defensive-кодом с явной пометкой.
