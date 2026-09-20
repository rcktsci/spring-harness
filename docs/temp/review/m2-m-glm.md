# Ревью пачки M (acceptance M2) — GLM-5.3-Flash

Дата: 2026-09-19. Объект: ArchitectureRulesTest (негативные), AcceptanceTwoPhaseReviewTest, decisions.md (D-47…D-59), apply-notes «Пачка M», AGENTS.md. Сборки не запускались; верификация — по коду тестов и артефактам.

**Вердикт: APPROVE** — незакрытых: 0. Критерий M2 из roadmap покрыт тестом целиком; процессные артефакты (ADR, apply-notes, AGENTS.md) синхронны.

---

## (1) ArchUnit: негативные тесты — ✅
Три «намеренных нарушения ловятся», каждое на изолированных фикстурах test-classpath (пакет `violation.*` — main не затронут): `foreignImplViolationIsCaught` (api → task.impl — foreignImpl-правило падает с перечнем зависимости), `layerViolationIsCaught` (session → task — MODULE_LAYERING), `dependencyCycleIsCaught` (CycleFixtureA↔CycleFixtureB — slices free-of-cycles). Позитивные 13/13 (слои, no-api-dependency, intelligence-изоляция, foreignImpl по 6 модулям, циклы) на месте.

## (2) AcceptanceTwoPhaseReviewTest — ✅ критерий roadmap M2 закрыт полностью
Сценарий (через сгенерированный тест-клиент, живой Keycloak + WireMock-LLM + реальный helper-контейнер):
- plan(AGENT): bootstrap → seed-ход (SYSTEM, без перехода) → **USER-сообщение** → агентский `transition` с reason по NEXT — гейт D-59 работает в обоих режимах (seed-ход не переходит, USER-ход переходит);
- run-checks(BASH) — **реальное исполнение в `harness-task-<id>`**: reason.output содержит «checks-ok», артефакт `review/checks.txt` существует на диске и дочитывается merge'ом («merge-done») — сквозная работа bash-состояний с workspace подтверждена;
- reviewer-1: FAILED → **ERROR-ребро → возврат в plan**; plan **резюмирует ту же STATE-сессию** (assert идентичности id — D-53 буквально);
- второй заход: plan → reviewer-1 → reviewer-2 → merge → done SUCCESS;
- история по REST: 8 переходов в точном порядке (`plan->run-checks:NEXT … merge->done:NEXT`), kinds корректны, агентские reasons проверены содержательно, bash-reason: kind=bash + output;
- **таймаут**: отдельный probe-граф, `PT5S` против `sleep 30` → TIMEOUT-переход, `elapsed < 25s` (не дождались sleep), reason kind=bash + durationMs.

Roadmap-критерий «агент переводит задачу инструментом transition, включая таймауты и error-пути bash» — покрыт по всем трём составляющим.

## (3) ADR-перенос — ✅
decisions.md: **13 строк D-47…D-59** (по одной на каждое решение, даты 2026-09-18), **D-59 ровно один раз** (дубля нет); форматы «решение → альтернативы → почему» соблюдены (проверено выборочно: D-47, D-52, D-58, D-59 совпадают с design.md пачки D с учётом фиксов ревью — включая «stop всегда каскаден» в D-54 и H-1-поправки).

## (4) AGENTS.md — ✅
«M2 завершён (пачки D/H/I/J/K/L/M)… 439 тестов зелёных. Архивирование change (N.1) — не выполнено» — честная фиксация остатка; следующий шаг — M3. Соответствует фактическому состоянию (openspec change не архивирован).

## (5) Отклонения dev — ✅ оба подтверждены кодом
- **runBashStateOnce** (TaskWakeDispatcher, public): та же ветка `dispatchBash` с теми же гейтом/исполнителем — e2e синхронно раскачивает bash, не ожидая виртуальные потоки; async-путь EVENT-wake покрыт AgentTransitionToolTest/пачкой J. Детерминизм acceptance без ущерба покрытию.
- **exitCode=124 при TIMEOUT**: `ContainerWorkspaceTools` оборачивает команду в GNU `timeout` (exit 124 → `timedOut=true`, WorkspaceContainerManager:268), `BashStateExecutor.classify` — timedOut → TIMEOUT; unit-тест classify(124, timedOut) закрепляет. Конвенция корректна (124 — стандартный код GNU timeout).

---

## Позитив

- Acceptance-тест проверяет не только порядок состояний, но и **содержательные** вещи: реальные артефакты bash-контейнера на диске, резюм той же сессии (идентичность id), содержимое агентских reasons, факт непродольного ожидания таймаута.
- Негативные ArchUnit-тесты на фикстурах-нарушителях — правильный способ держать правила «живыми» (правило без демонстрации поимки — мёртвое).
- Честная фиксация невыполненного N.1 в AGENTS.md — остаток работы виден владельцу без раскопок.

## Вердикт

**APPROVE** — незакрытых: 0. Критерий M2 закрыт, артефакты приёмки синхронны; остаётся N.1 (архивирование change) — вне этой пачки, учтено в AGENTS.md.
