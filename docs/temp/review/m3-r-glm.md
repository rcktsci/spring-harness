# Ревью пачки R (ACL + рестарт-скан + FQDN-аудит) — GLM-5.3-Flash

Дата: 2026-09-21. Объект: FQDN-аудит (53 вхождения → 26 файлов), R-фиксы (tasks R.1–R.4 [x]), интеграционный тест RestartScanTest (orphan subagent container), 487 тестов. Сборки не запускались.

**Вердикт: REJECT** — 1 находка (minor, процесс). Техническое содержимое пачки чистое; блокирует только отсутствие apply-notes-секции R — четвёртый случай паттерна H-9.

---

## (1) R.1 D-69 (subagent pinned к своей ревизии) — ✅
Проверено в пачке O и подтверждено тестом R.1: `createChildSession` пиннит дочернюю сессию на ревизию агента по `agentKey` субагента; гейты (манифест + executeToolCall) читают флаг из **запинненной** ревизии — наследования `metaTools` нет. Verify-сценарий «orchestrator spawn'ит sub-coder → orchestrator-tools недоступны» покрыт.

## (2) R.2 owner inheritance — ✅
`createChildSession`: `owner = parent.getOwnerUserId()`, `parent_session_id = parent.id`, `depth = parent.depth + 1` (колонка 074). Интеграционный тест «owner через parent.session.owner» — в пачке.

## (3) R.3 рестарт-скан/orphan subagent containers — ✅
Очистка контейнеров — по факту существования сессии в БД (generic по session-namespace), субагентские контейнеры `harness-<subSessionId>` покрываются ею без специального кода. Интеграционный тест `orphanSubagentContainerRemovedWhileParentAndSiblingsLive`: осиротевший sub-контейнер удалён, родитель и сиблинги живы — ровно сценарий R.3. Оговорка «orphan sub-sessions с живым parent — no-op до stop parent» — соблюдена (скан сессию в БД видит — контейнер не трогает; очистка — SubtreeCanceller при stop).

## (4) R.4 cross-session read_compacted — ✅
Same-session only (пачка O): `ref.sessionId != sessionId → not-found`; cross-session — вне M3 (R8, точка эволюции с условиями). Подтверждено повторным чтением ReadCompactedTool.

## (5) FQDN-аудит — ✅
Остаток: ровно **2** inline-вхождения, оба структурно неустранимы (simple-name занят сгенерированным DTO):
- `ApiMappers.java:244` — `se.rocketscien.harness.task.TaskTreeNode` против `api.gen.model.TaskTreeNode`;
- `SessionsController.java:78` — `se.rocketscien.harness.session.SessionKind` против `api.gen.model.SessionKind`.
Обе коллизии домен↔генерация; альтернатива — переименование доменных классов (хуже). Остальные 51 вхождение переведены на import + short name. ✅

## (6) ArchUnit — ✅
Пачка R не трогала сло-граф (только FQDN-косметика + тесты) — `ArchitectureRulesTest` в актуальном состоянии (слои N-пачки + violation-фикстуры). Напоминание из пачки N в силе: формализация слоёв `agent`/`mcp` — S.1.

## (7) 487 тестов — ✅ принимается по отчёту dev (без повторного прогона; дельта +34 к 453 соответствует объёму O+Q+R).

---

## Находки

### M-1 (minor, повторяющийся процесс). apply-notes: нет секции «Пачка R»
- **Проверено**: заголовки apply-notes — N/O/P/Q; секции R нет. Фикс-лист (R.1–R.4), аудит FQDN (53→2, перечень оставшихся), счёт тестов 487 — нигде не зафиксированы. Четвёртый случай паттерна (H-9 → I → J → R).
- **Предложение**: дописать секцию R тем же форматом (решения/аудит/тесты) до коммита пачки.

## Позитив

- FQDN-аудит проведён до конца с честным остатком «2 неустранимых» вместо декларативного «всё почищено».
- R.3-тест проверяет нетривиальный инвариант (осиротевший субагент + живые родитель/сиблинги) — именно тот случай, ради которого затевался рестарт-скан.

## Вердикт

**REJECT** — 1 находка (minor: apply-notes R-секция). Технически пачка готова; после дописывания секции — approve.

---

# Re-approval пачки R (2026-09-21)

- **M-1 (apply-notes R-секция)** — **закрыто**: секция «Пачка R. ACL/owner + рестарт-скан + FQDN-аудит» дописана: R.1–R.4 с указанием покрывающих тестов (OrchestratorMetaToolsTest, SubagentSpawnTest, RestartScanTest, ReadCompactedToolTest), честное отклонение от строки verify R.3 («stop не удаляет контейнеры — этого не требует замороженная спека; снимает только рестарт-скан», API удаления сессий отсутствует) — обоснованно и зафиксировано; фикс-лист ревью (включая R-1 DS по McpAgentConfigAuditor) на месте.

## Вердикт re-approval

**APPROVE** — незакрытых: 0. Пачка R закрыта; M3 остаётся с S (ArchUnit-формализация + acceptance) до архивирования.
