# Review Findings: ci-backend-image

**Reviewer:** Mercury-2.5  
**Date:** 2026-09-24  
**Verdict:** APPROVE

## Severity Counts
- CRITICAL: 0
- MAJOR: 0
- MINOR: 2

## Key Findings

1. **MINOR | tasks.md:2.2 | Missing push= true verification**  
   Task 2.2 specifies `docker/build-push-action@v7` with `push: true` in configuration, but the verification step only checks action versions and not that publishing actually occurs. Acceptance criteria should explicitly verify `docker buildx imagetools inspect` shows published tags in GHCR. Fix: Add explicit verification that the action's push parameter is true and results in a published image.

2. **MINOR | tasks.md:3.1 | Partial visibility/PAT coverage**  
   Task 3.1 (README update) verification says "инструкции воспроизводимы по README" but does not explicitly verify that both visibility options are documented: (a) public package with direct pull, and (b) private package with PAT read:packages login. Design D-CI9/D-CI10 address this. Fix: Add verification that README contains both options with explicit instructions for each path.

## Consistency Check
- proposal ↔ spec ↔ design ↔ tasks: All requirements trace cleanly. No mutually exclusive claims found.
- All spec scenarios covered by tasks: Yes.
- Design decisions D-CI1–D-CI11 documented with alternatives and rationales: Yes.

## Technical Correctness Check
- Actions versions (checkout@v7, login@v4, setup-buildx@v4, build-push@v7, metadata@v6): Match verified facts.
- GHA cache config (type=gha, mode=max): Matches D-CI3.
- Platform (linux/amd64): Matches D-CI4.
- Tags (main, sha-<short>, latest): Matches D-CI5.
- .dockerignore excludes (.git/, target/, web-desktop/...): Matches D-CI6; api/ and src/ not excluded.
- Concurrency + cancel-in-progress: Present in 2.1, matches D-CI8.
- Permissions (contents: read, packages: write): Present in 2.1, matches spec.

## Risks / Trade-offs Covered
- VM architecture check (uname -m): Covered in task 3.2.
- Cache miss (first run, eviction): Documented in design.
- Private package access: Documented in design and addressed in task 3.1.
- No .git in context: Design D-CI7 explains failOnNoGitDirectory=false.

## Owner Rules Compliance
- No hardcoded numbers in workflow: Yes (versions pinned, params configurable).
- Design decisions documented with alternatives and "why": Yes (D-CI1–D-CI11).
- Non-enterprise bloat: One workflow, one .dockerignore file.

## Notes
- .dockerignore not present in repo yet: Task 1.1 creates it.
- .github/workflows not present yet: Task 2.1 creates it.
- Task 3.5 references docs/design/decisions.md for ADR D-94—external artifact, change design.md already contains all ADR content.

## Re-approval

**Fixes verified against updated tasks.md:**

| Original MINOR | Fix location | Verification |
|---|---|---|
| 2.2 missing push/platforms/cache keys | tasks.md:2.2 | Проверка теперь требует явно проверить наличие `push: true`, `platforms: linux/amd64`, `cache-from: type=gha`, `cache-to: type=gha,mode=max` в шаге build-push-action. |
| 3.1 partial README visibility coverage | tasks.md:3.1 | Проверка теперь требует явного наличия обоих вариантов логина (публичный пакет / PAT read:packages) и воспроизводимости инструкций без внутренних доков. |

| Original MINOR | Fix location | Verification |
|---|---|---|
| 3.5 ADR D-94 reference conflict | tasks.md:3.5 | Переформулирована: ручная проверка D-95 и AGENTS.md, `openspec validate` отмечен как внешний CLI оркестратора. |

**Итог:** Все замечания устранены. Спека ↔ дизайн ↔ задачи согласованы. Никаких новых дефектов не найдено.

**FINAL VERDICT: APPROVE**

## Re-approval 2

**Owner changes verified (2026-09-24):**

| Area | Change | Verification |
|---|---|---|
| GHCR package visibility | Private → Public (owner decision 2026-09-24) | design.md:D-CI10: «пакет GHCR — публичный (решение владельца 2026-09-24): первый push создаёт пакет приватным, владелец один раз переключает видимость в настройках пакета, после чего docker pull на VM идёт без логина и без PAT» |
| VM architecture | Confirmed x86_64 | design.md:Risks: «Архитектура VM — x86_64, подтверждено владельцем 2026-09-24» |
| Migration Plan | Updated to reflect one-time public switch | design.md:Migration Plan step 2: «после первого пуша переключает видимость пакета GHCR на публичную» |
| Open Questions | Closed | design.md:Open Questions: «Закрыты владельцем 2026-09-24: пакет GHCR публичный, архитектура VM x86_64» |
| Spec scenario | Credentials requirement removed | spec.md:33: «пакет публичный, логин не требуется» |
| Task 3.1 | Updated for public package | tasks.md:3.1: «после первого пуша владелец переключает пакет GHCR на публичный, дальше docker pull без логина»; проверка: «в README нет шага с PAT» |
| Task 3.2 | Marked complete | tasks.md:3.2: [x] «подтверждено владельцем 2026-09-24» |

**Consistency check:**
- proposal.md:28: «владелец один раз переключает его на публичный... после этого docker pull на VM идёт без логина и без PAT»
- spec.md:33: «пакет публичный, логин не требуется»
- design.md:D-CI10: «пакет GHCR — публичный»
- tasks.md:3.1: «после первого пуша владелец переключает пакет GHCR на публичный, дальше docker pull без логина»

All artifacts now consistently reflect: (a) one-time owner action to make package public after first push, (b) no PAT/login required afterward for VM pulls.

**Risk update:**
- design.md:Risks: «Первый push в GHCR создаёт приватный пакет, а владелец рассчитывает на публичный» → решено как «разовая ручная настройка владельцем... README фиксирует этот шаг»

**Final status:**
- openspec validate ci-backend-image --strict: green
- All owner concerns addressed
- No blockers remain

**FINAL VERDICT: APPROVE**
