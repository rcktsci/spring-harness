# Review: ci-backend-image planning artifacts
Reviewer: Kimi (subagent)
Date: 2026-09-24
Scope: proposal.md, design.md, tasks.md, specs/backend-image-ci/spec.md

---

## Вердикт: REJECT

Причина: 2 находки severity MAJOR, обе блокируют корректное выполнение задач или вносят неоднозначность в артефакты проекта. После исправлений — готов аппрувить.

---

## Сводка

| Severity | Count |
|---|---|
| CRITICAL | 0 |
| MAJOR | 2 |
| MINOR | 0 |

---

## Находки

### MAJOR-1: tasks.md:3.5 — верификация ссылается на отсутствующий инструмент `openspec validate`

**Место:** `tasks.md`, строка 20: "проверка: `openspec validate ci-backend-image --strict` зелёный".

**Почему это дефект:** В репозитории нет исполняемого файла, скрипта, Maven-плагина, npm-пакета или иного артефакта под именем `openspec`. Ни в корне, ни в `pom.xml`, ни в `package.json` web-desktop, ни среди установленных навыков `.opencode/skills/` нет CLI `openspec validate`. Верификационный шаг задачи невыполним для разработчика, что нарушает требование ревью-чеклиста «нет задач, требующих того, чего нет в репозитории».

**Как исправить:** Заменить проверку на выполнимую в рамках проекта. Варианты:
- (a) Ручная проверка: убедиться, что `docs/design/decisions.md` содержит записи D-CI1…D-CI11 с решениями и отвергнутыми альтернативами, а `AGENTS.md` обновлён разделом «Текущее состояние».
- (b) Если `openspec` — внешний инструмент оркестратора, явно указать в задаче, что проверка выполняется оркестратором, а не разработчиком в сессии.

---

### MAJOR-2: tasks.md:3.5 — «D-94…» конфликтует с существующим ADR D-94

**Место:** `tasks.md`, строка 20: "ADR (D-94…): CI-сборка образа в GHCR".

**Почему это дефект:** В `docs/design/decisions.md` запись **D-94** уже занята решением «Jackson 2 в рантайме — только как изолированный набор openai-java» (дата 2026-09-24). Отсылка «D-94…» вводит разработчика в заблуждение: либо он попытается перезаписать существующий ADR, либо запутается в нумерации. Журнал решений проекта использует сквозную нумерацию D-01…D-94, а design этого change использует собственную схему D-CI1…D-CI11. В tasks следует либо ссылаться на D-CI1…D-CI11, либо указать следующий свободный номер (D-95).

**Как исправить:** Переформулировать задачу: «Зафиксировать в `docs/design/decisions.md` новый ADR (например, **D-95**) с решениями D-CI1…D-CI11 и отвергнутыми альтернативами; обновить `AGENTS.md`».

---

## Проверка по чек-листу (для протокола)

| Пункт | Результат | Примечание |
|---|---|---|
| 1. Непротиворечивость proposal ↔ spec ↔ design ↔ tasks | ✅ | Все требования спеки покрыты задачами; решения design отражены в tasks. |
| 2. Полнота с точки зрения эксплуатации | ✅ | GHCR-логин, приватность, откат по sha-тегу, кэш-промах, отмена прогонов, права токена — всё учтено. |
| 3. Техническая корректность | ✅ | Версии actions совпадают с зафиксированными фактами; metadata-action формирует заявленные теги; кэш GHA описан верно; .dockerignore сохраняет `api/` и `src/`; `failOnNoGitDirectory=false` подтверждён (pom.xml:300); платформа `linux/amd64` обоснована в D-CI4. |
| 4. Реализуемость задач | ✅ | Задачи малы и выполнимы; каждая имеет проверку. |
| 5. Правила владельца | ✅ | Чисел в workflow нет; решения содержат отвергнутые альтернативы; риски с митигациями присутствуют в design. |
| 6. Факты, верифицированные владельцем | ✅ | Ни один из зафиксированных фактов не искажён артефактами. |

---

## Re-approval

**Date:** 2026-09-24 (same session, post-fix)

**Fixed items reviewed:**
- `tasks.md:20` — верификация переформулирована: `openspec validate` помечен как внешний CLI оркестратора (не артефакт репозитория); ручная проверка ADR D-95 и `AGENTS.md` вынесена отдельно.
- `tasks.md:20` — нумерация исправлена: новый ADR — **D-95** (с примечанием, что D-94 занят решением про Jackson 2).
- `tasks.md:2.2` — проверка теперь явно требует `push: true`, `platforms: linux/amd64` и оба ключа GHA-кэша.
- `tasks.md:3.1` — проверка теперь требует в README явно оба варианта доступа к пакету.

**Outcome:** Обе MAJOR-находки устранены. Нет новых дефектов. Все пункты исходного чек-листа остаются в силе.

## Re-approval 2

**Date:** 2026-09-24 (same session, post-owner-fixes)

**Owner decisions applied:**
- **Package visibility**: public (PAT not needed on VM).
- **VM architecture**: `x86_64` confirmed.

**Files re-reviewed:** `design.md`, `proposal.md`, `specs/backend-image-ci/spec.md`, `tasks.md`

**Verification of fixes:**

| Original issue | Fixed in | Verification |
|---|---|---|
| D-CI10 wording ambiguous about package visibility | `design.md:104` | Explicit: "пакет GHCR — **публичный** … владелец один раз переключает видимость" |
| Risks — architecture VM and private-package risk | `design.md:120`, `design.md:124` | Architecture confirmed x86_64; private-package risk reformulated as "первый push приватный → один раз переключить → публичный" |
| Migration Plan step 2 | `design.md:131` | Updated to one-time public switch |
| Open Questions both open | `design.md:138` | Both closed: "Закрыты владельцем 2026-09-24" |
| Impact — package visibility ambiguous | `proposal.md:28` | Explicit public package, no PAT needed |
| Spec — scenario required "authorized creds" | `spec.md:33` | Changed to "пакет публичный, логин не требуется" |
| Task 3.1 required PAT or public options | `tasks.md:16` | Now: public package, `docker pull` without login, check: "в README нет шага с PAT" |
| Task 3.2 pending architecture check | `tasks.md:17` | Marked `[x]` done, owner-confirmed x86_64 |

**Cross-check:**
- `spec.md` scenario «владелец тянет образ на VM» (line 33) now correctly says no login required — consistent with `design.md:104` and `tasks.md:16`.
- `proposal.md:28` and `design.md:104` both state: first push private, owner switches once to public — consistent with GHCR behavior and `spec.md`.
- `tasks.md:17` marked done — no technical contradiction with `design.md:60` (D-CI4 still references `uname -m` check in general terms, but task explicitly closed by owner confirmation).

**Outcome:** No new defects. All owner-driven changes are consistently applied across all four planning artifacts. The change remains narrow, non-intrusive, and aligned with owner rules.

## Вердикт: APPROVE
