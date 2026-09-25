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

## Release policy review

**Date:** 2026-09-25

### Consistency check
- proposal ↔ spec (backend-image-ci) ↔ spec (release-policy) ↔ design (D-CI12–D-CI15) ↔ tasks: 6 requirements in release-policy spec are covered by design decisions D-CI12–D-CI15 and tasks 4.1–4.5.
- Two specs (backend-image-ci, release-policy) do not contradict; release-policy adds tag-trigger scenarios already referenced in backend-image-ci spec.

### Findings

| Severity | Location | Issue | Why | Fix |
|---|---|---|---|---|
| MAJOR | design.md:Risks (missing) | No semver tag overwrite protection | If owner accidentally pushes same tag v0.1.0 twice, GHCR will allow overwrite (build-push-action push: true doesn't guard). Design mentions tag as source of truth but doesn't warn about tag immutability. | Add risk: «Повторный пуш того же semver-тега перезапишет образ в GHCR. Решение: владелец проверяет `docker buildx imagetools inspect` перед пушем; при необходимости — тег с датой/комментарием» |
| MAJOR | tasks.md:4.4 | First release alignment not explicitly verified | Task assumes orchestrator prepares commit with pom=0.1.0 and package.json=0.1.0, but doesn't require explicit diff verification of both files before tagging. | Add check: «diff pom.xml и web-desktop/package.json перед коммитом — версия должна быть 0.1.0 в обоих файлах» |
| MINOR | tasks.md:2.3 | Version extraction fragility | Design D-CI13 acknowledges "первый <version> в pom.xml" assumption but tasks should document that this breaks if pom gets <parent>. | Add note: «Шаг извлекает версию из первого <version> в pom.xml; при появлении <parent> в пом шаблон сломается — чинится одной строкой» |
| MINOR | spec.md:67 (release-policy) | Tag-branch rule enforcement | Spec requires "тег только после merge в main" but tasks 4.5 only references README/PR-template review instruction. | Add to task 4.5: «Проверка: в README.md есть инструкция "тег только с коммита в main" и пример команды git tag + git push» |
| MINOR | tasks.md:4.2 | SemVer bump rules for 0.x not fully explicit | Task asks to document bump rules but spec requires explicit 0.x breaking=MINOR+**BREAKING** rule. | Add to README check: «В правилах bump явно указано: в 0.x breaking = MINOR с пометкой **BREAKING**» |
| MINOR | design.md:D-CI15 | OCI label version format on tag run | metadata-action with type=semver may produce vX.Y.Z or X.Y.Z; design says org.opencontainers.image.version (semver) but doesn't clarify format. | Add note: «metadata-action type=semver на теге v0.1.0 выдаст теги 0.1.0 и vX.Y.Z; label org.opencontainers.image.version будет X.Y.Z (без v)» |

### SemVer policy adequacy
- Bump rules for MAJOR/MINOR/PATCH with 0.x exception documented in design D-CI14, spec release-policy, and tasks 4.2.
- Breaking-change discipline (manual mark **BREAKING** + changelog entry + two-reviewer approval) documented in D-CI14.
- Traceability goal: OCI labels (org.opencontainers.image.version, org.opencontainers.image.revision) provide version lookup via docker inspect (design D-CI15, tasks 4.3).
- Changelog requirement: CHANGELOG.md creation and manual maintenance documented (tasks 4.1, D-CI15).

### First release v0.1.0 feasibility
- Current state: pom.xml=1.0.0-SNAPSHOT, package.json=0.1.0 (version mismatch exists).
- Task 4.4: orchestrator prepares release commit aligning both to 0.1.0, owner approves diff, then tag v0.1.0 pushed.
- No blockers: task 4.4 explicitly handles alignment. Post-release, version mismatch cannot recur due to CI check on tag run (task 2.3).

### Owner rules compliance
- No hardcoded numbers in workflow: versions pinned in actions, policy parameters in design/docs.
- Design decisions documented with alternatives and rationales: D-CI12–D-CI15 have full ADR treatment.
- Non-enterprise bloat: one workflow, manual changelog, manual tag, CI check only for version alignment.

### Risks covered
- Version mismatch: CI fails on tag run before publish (design D-CI13).
- Tag on non-main commit: documented as discipline check (design Risks).
- Changelog missing entry: caught by release-review (design D-CI14).
- SemVer tag overwrite: **not documented** (see MAJOR finding above).

### Final status
- All owner requirements addressed.
- One MAJOR (tag overwrite risk), five MINOR (documentation/verification gaps) — no blockers.
- openspec validate ci-backend-image --strict: green.

**FINAL VERDICT: APPROVE**

## Re-approval 3

**Date:** 2026-09-25

**All fix requests verified:**

| Original Finding | Fix Location | Verification |
|---|---|---|
| MAJOR-1: Semver tag overwrite protection | design.md:D-CI16, release-policy/spec.md:82-94, tasks.md:2.3 | D-CI16 added «Релизные теги неизменяемы». Release-policy spec has 2 scenarios (lines 86-94). Task 2.3 checks if tag exists in GHCR via `docker buildx imagetools inspect` → fail if found. |
| MAJOR-2: First release alignment verification | tasks.md:2.3, tasks.md:4.4 | Task 2.3 explicitly checks current pom.xml returns `1.0.0-SNAPSHOT` not parent version. Task 4.4 requires logs showing both versions checked and tag not yet in GHCR. |
| MINOR-3: First `<version>` fragility | design.md:D-CI13, tasks.md:2.3 | XML tree parsing via `python3` (`<version>` top-level element, not first in file). D-CI13 documents that pom has `<parent>spring-boot-starter-parent:4.1.1` at lines 7-12. |
| MINOR-4: Tag must be on main | tasks.md:4.2 | Rule «тег ставится только на коммите, уже в `main`» explicitly in task 4.2. |
| MINOR-5: 0.x breaking rule | tasks.md:4.2 | Rule «пока версия `0.x`, breaking = MINOR с пометкой `**BREAKING**` и миграцией в changelog» explicitly in task 4.2. |
| ADR number conflict | tasks.md:3.5 | Changed to **D-96** (D-95 occupied by deployment-readme host-grants). |
| metadata-action behavior | design.md:D-CI5 | Explicitly documents `flavor: latest=false`, `type=semver,pattern={{raw}}` + `type=semver,pattern={{version}}`, no `{{major}}`/`{{minor}}`. |
| Tracing scenario | release-policy/spec.md:75 | Clarified: `.version` = semver on release images, `main` on images from push to branch. |

**Consistency check:**
- proposal.md, design.md (D-CI1–D-CI16), tasks.md (1.1–4.5), specs (backend-image-ci, release-policy) all aligned.
- Two specs do not contradict; release-policy adds tag-unchangeable requirement referenced in design D-CI13/D-CI16.
- All owner rules satisfied: no hardcoded numbers in workflow, design decisions have alternatives and rationale, one workflow file, manual changelog/tag.

**Final status:**
- openspec validate ci-backend-image --strict: green
- All fixes applied and verified
- No blockers remain
- No new findings

**FINAL VERDICT: APPROVE**
