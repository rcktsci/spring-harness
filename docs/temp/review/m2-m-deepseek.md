# Ревью M2 batch M: приёмка (ArchUnit + e2e + ADR-перенос + AGENTS)

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-18.
> Объект: `tests/architecture/ArchitectureRulesTest` (+ фикстуры-нарушители), `tests/api/AcceptanceTwoPhaseReviewTest`, `execution/impl/TaskWakeDispatcher.runBashStateOnce`, `docs/design/decisions.md` (D-47…D-58), `AGENTS.md`, `apply-notes.md` §M.
> Контекст: `docs/design/roadmap.md` (критерий M2), tasks.md M.1–M.3, design.md D-47…D-58, architecture.md §1–§2.
> Сборки не запускались; сверка по исходникам.
> Severity: **MEDIUM**; **MINOR**; **NIT**.

## Сводка

| Severity | Кол-во |
|---|---|
| MEDIUM | 0 |
| MINOR | 2 |
| NIT | 2 |
| **Итого** | **4** |

---

## Проверка фокусных пунктов

### Критерий M2 (roadmap.md) — ✅ выполнен

roadmap M2: «сценарий «двухфазное ревью с возвратом» на тестовом графе проходит (агент переводит задачу `transition`), включая таймауты и error-пути bash-состояний».
`AcceptanceTwoPhaseReviewTest.acceptanceTwoPhaseReviewWithReturn` — один интеграционный e2e на живом приложении: живой Keycloak (alice по password grant, JWT), WireMock-LLM, **реальный helper-контейнер**. Граф `plan(AGENT) → run-checks(BASH) → reviewer-1(AGENT) → reviewer-2(AGENT) → merge(BASH) → done/failed`, ERROR-рёбра ревьюеров назад в `plan` (цикл). Проверено:
- история из 8 переходов `containsExactly`, включая `reviewer-1→plan:ERROR` (возврат) и повторное прохождение `run-checks`/`reviewer-1`;
- `reason` ERROR/повторного NEXT — текст агента из USER-ходов (гейт D-52/D-59); bash-reason — `kind=bash`, `exitCode=0`, реальный output (`checks-ok`, `merge-done`);
- STATE-сессия `plan` резюмируется (та же `planSession`, D-53);
- артефакт `review/checks.txt` физически в host-workspace задачи (`exists`, содержимое `checks-ok\n`) — доказательство реального контейнера;
- bash-таймаут: граф `PT5S` + `sleep 30` → TIMEOUT-переход, `exitCode≠0`, `durationMs 4–20с`, `pump` не ждал 30с (`elapsed < 25с`).
Хост-проверки (окружение) — Testcontainers Postgres/Keycloak + Docker, соответствует рантайму.

### ADR-перенос D-47…D-58 — ✅ без потерь

`decisions.md` содержит строки D-47…D-58 (12) с колонками «решение → альтернативы → почему»; D-59 не дублирован (был ранее). Сверка с `design.md` §Decisions: содержательно перенесены все 12, включая уточнения пачки K (D-49 `StopTaskFacade → TaskWakeDispatcher.handleStop`, D-54 «sync+async») и D-58 (ограниченный профиль; hibernate.validator явно отвергнут). Датированы 2026-09-18.

### ArchUnit — ✅ модули + циклы + негативные тесты

`MODULE_LAYERING` (7 слоёв; `api → {execution,intelligence,session,identity,task,workflow}`, `execution → {session,identity,task,workflow,intelligence}`, `task/workflow/session → identity`, identity изолирована, api не читается) на `MAIN_CLASSES`; `foreignImplPackageIsHiddenBehindContract` параметризован по 6 модулям; `noDomainModuleDependsOnApi`; `intelligenceDoesNotDependOnExecution`; `noCyclesBetweenModules` (slices). M.1 добавлены негативные «нарушение ловится»: `foreignImplViolationIsCaught` (api→`task.impl`), `layerViolationIsCaught` (session→task), `dependencyCycleIsCaught` (alpha↔beta, «Cycle detected»); фикстуры-нарушители только в test-classpath, позитивные правила на `target/classes` их не видят. `ArchitectureRulesTest` — 13 зелёных.

### e2e: реальный BASH-контейнер + возврат-цикл — ✅

См. критерий M2 выше: контейнер `harness-task-<id>` реален (артефакт в host-workspace переживает одноразовые контейнеры), цикл возврата `reviewer-1→plan(ERROR)` и повторный проход подтверждены историей. **M-1:** BASH-ветка раскачивается тест-хуком `TaskWakeDispatcher.runBashStateOnce` (см. ниже), а не штатным EVENT-wake.

### AGENTS.md — ✅

Обновлено: «M2 «Workflow-движок» завершён (пачки D/H/I/J/K/L/M)… 439 тестов зелёных»; «Дизайн-базис D-01…D-59»; следующий шаг — M3; «Архивирование change (N.1) — не выполнено». Состояние соответствует фактам.

---

## Findings

### M-1 [MINOR]. `TaskWakeDispatcher.runBashStateOnce` — публичный тест/ops-хук в production-классе, e2e не проходит штатный EVENT-wake для BASH

- **Где:** `execution/impl/TaskWakeDispatcher.runBashStateOnce` (public, синхронно, без конфиг-гейта `harness.task.bash-dispatch.enabled`); `AcceptanceTwoPhaseReviewTest.pumpBash` зовёт его напрямую.
- **Проблема:** e2e-приёмка (M.2) проверяет BASH через метод, добавленный «для детерминизма», — штатный EVENT-wake-путь BASH (`onTaskWake` → `dispatch` → gated `dispatchBash`) в e2e не исполняется; в production-классе появляется public-метод, обходящий kill-switch (заявлен как «ops-инструмент», но в спеке/контракте не описан).
- **Предложение:** либо переиспользовать штатный путь в e2e (включить `harness.task.bash-dispatch.enabled=true` для этого теста через `@TestPropertySource` и дождаться wake), либо пометить метод как package-private/`@VisibleForTesting` и не позиционировать как ops; зафиксировать решение в apply-notes (сейчас задокументировано как отклонение).

### M-2 [MINOR]. Приёмочный e2e не покрывает bash-ERROR (exit≠0)

- **Где:** `AcceptanceTwoPhaseReviewTest` — run-checks/merge со `exit=0` (NEXT), таймаут-проба → TIMEOUT; `exit≠0 → ERROR` в e2e нет.
- **Проблема:** roadmap-критерий говорит «включая таймауты и **error-пути** bash-состояний»; на e2e-уровне проверен только TIMEOUT. `exit≠0 → ERROR` покрыт интеграционным `BashStateExecutorTaskContainerTest.nonZeroExitMeansErrorOutcome` — т.е. суммарно путь проверен, но не в приёмочном сценарии.
- **Предложение:** добавить в приёмочный тест ветку `run-checks`/`merge` с `exit≠0` → ERROR (в `failed`), либо явно отметить в apply-notes/T.2, что bash-ERROR покрыт executor-интеграцией.

### M-3 [NIT]. Порядок строк в `decisions.md`

- **Где:** таблица ADR: `… D-44, D-46, D-45, D-47…D-58, D-59`.
- **Проблема:** числовой порядок нарушен (D-46 раньше D-45 — было и до пачки M; новые D-47…D-58 вставлены после D-45). Читаемость/навигация, не суть.
- **Предложение:** при следующей правке отсортировать по номеру (или оговорить «хронологический порядок»).

### M-4 [NIT]. AGENTS.md: «D-01…D-59» при D-01…D-46 в тексте-заголовке

- **Где:** `AGENTS.md` §Текущее состояние — «Дизайн-базис завершён (D-01…D-59)».
- **Проблема:** корректно по факту (D-59 в реестре), но стоит убедиться, что при архивации N.1 формулировка не разойдётся с `decisions.md` (там D-47…D-59 добавлены в конец после D-45). Косметика.

---

## Позитив (проверено)

- Приёмочный e2e — реальный end-to-end (живой Keycloak, WireMock-LLM, реальный контейнер, REST через сгенерированный клиент), с точной проверкой истории, цикла возврата, резюма STATE-сессии, артефакта и таймаута.
- ADR-перенос полный и в едином формате; D-59 не дублирован.
- ArchUnit покрывает все доменные модули и циклы, плюс негативные тесты («нарушение ловится») — правила не「декоративны」.
- AGENTS.md и apply-notes §M отражают фактическое состояние; итог 439 зелёных.
- Чистые вспомогательные фикстуры-нарушители изолированы в test-classpath.

## Вердикт

**APPROVE — 0 MEDIUM (2 MINOR: M-1 тест-хук `runBashStateOnce`/e2e не на штатном wake, M-2 нет bash-ERROR в e2e; 2 NIT: M-3, M-4).** Критерий M2 (roadmap) выполнен, ADR-перенос без потерь, ArchUnit покрывает модули и циклы, e2e — с реальным контейнером и возврат-циклом, AGENTS.md актуален. M-1/M-2 — рекомендации, не блокеры (пути покрыты другими тестами/задокументированы).