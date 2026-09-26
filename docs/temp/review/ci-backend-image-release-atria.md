# Review Findings: ci-backend-image — Release Commit v0.1.0 + Delivery Model Change

**Reviewer:** Atria-Dawn
**Date:** 2026-09-26
**Scope:** Release commit for `v0.1.0` and the delivery-model change (owner decision 2026-09-25, supersedes the old D-CI10 "pull + retag"): compose pins a versioned image tag, `build:` removed, local build is a separate command.
**Files in the batch:** `docker-compose.yml`, `pom.xml`, `CHANGELOG.md`, `README.md`, `AGENTS.md`, `docs/design/decisions.md`, `openspec/changes/ci-backend-image/{design.md, tasks.md, specs/backend-image-ci/spec.md, specs/release-policy/spec.md}`.

## Verdict: APPROVE

| Severity | Count |
|---|---|
| CRITICAL | 0 |
| MAJOR | 0 |
| MINOR | 5 |

## 1. Release consistency

- `pom.xml:16` — project `<version>` is `0.1.0` (top-level, not the `<parent>` `4.1.1`); `web-desktop/package.json:3` — `0.1.0`. Both match the planned tag; the CI version gate reads exactly these two.
- `CHANGELOG.md:9` — `## [0.1.0] - 2026-09-25` with the release summary and per-milestone Added entries; comparison link `[0.1.0]: https://github.com/rcktsci/spring-harness/releases/tag/v0.1.0` present (CHANGELOG.md:22).
- `docker-compose.yml:49` — `image: ghcr.io/rcktsci/spring-harness-orchestrator:v0.1.0`; the `build:` block is gone. Nothing else in compose was touched: env block, ports 8080/8081, healthchecks, volumes, `/var/run/docker.sock` mount, `group_add` are all unchanged (verified by diff — the only removed lines were the `build:`/`context:`/`dockerfile:` trio).
- The compose header comment still documents the helper-image build (`docker build -f docker/Dockerfile -t harness-helper:local .`) — that is the per-session helper, correctly still local; no comment was added or changed in the YAML.

## 2. Single delivery model across artifacts

No leftover of the old model anywhere in the batch:

- **README** — the old "build the jar on your dev machine and replace the `build:` block" paragraph is replaced by a pin statement (README:68); section 4 is rewritten around the pin (`README:203` "does not build anything — it pins one published version", `README:228` "Compose never builds the orchestrator image"); the `docker pull` + `docker tag ... spring-harness:local` sequence is gone, replaced by tag edit + `up -d`; local build is a deliberate `docker build -f docker/Dockerfile.orchestrator -t ghcr.io/...:local .` with an explicit warning not to point compose at `:local`.
- **D-CI10** (`design.md:112-118`) — retitled to "пин версионного тега в compose, `build:` удалён"; the old variant is now rejected-alternative (1), `build:` + `pull_policy` is (2), override-file is (3); the "Почему" explains the one-step deploy and single-file state.
- **Risks** (`design.md:175`) — the old risk "publish succeeds but compose keeps building locally" is replaced by the real new one: "forgot to change the tag in the live compose copy" → mitigation is the release-commit pin (mandatory step) + OCI label check. Adequate and honest.
- **Migration Plan** (`design.md:186-190`) — step 3 is the switch to the release image, step 4 includes the `image:` line in the release commit, step 5 is rollback by tag edit. Coherent.
- **backend-image-ci spec** — the requirement text (spec.md:53) and both scenarios (spec.md:57, 62) now describe the compose pin; no `pull_policy` or "compose builds" promise remains.
- **release-policy spec** — release-commit requirement (spec.md:24) and rollback scenario (spec.md:80) updated to the tag-edit flow; traceability requirement (spec.md:70) matches.
- **D-96** (`decisions.md:97`) — still exactly one row after D-95; updated in place to describe the pin model and to carry the two new rejected alternatives. No second D-96 row.
- **AGENTS.md** — the CI paragraph now states the pin decision and the supersedes note; nothing else changed (diff is one line).
- **tasks.md** — 3.1/3.3/3.4 rewritten to the pin flow; 3.3/3.4 and 4.4 remain unchecked, which is correct (acceptance run and tag push happen after this review).

All spec requirements are implementable and are implemented by this batch.

## 3. VM implementability

- The VM runs a hand-maintained compose copy (not a clone). README:210 states this explicitly and separates the two edits: the live copy on the VM before `up -d`, the repo line by the release commit. A version bump is a one-line edit — no clone, no `docker login` (package is public), no `docker tag`.
- Rollback = set the previous tag (`vX.Y.Z`/`X.Y.Z`/`sha-<short>`) + `up -d` (README:285). Immutable tags make it byte-exact.
- Version control via `docker inspect` on container `harness-orchestrator` (README:279-283) — the container name still matches `container_name` in compose.
- No instruction in the batch requires a repository clone for deployment; the clone remains only for the from-zero path (helper image build), which is unchanged and correct.

## 4. Risks

- The "forgot to bump the tag in the live compose" risk is stated with the right mitigation: the release commit ships the pin as a mandatory step, and the OCI label is the post-check. No overstated promise — the doc says plainly that `up -d` keeps the old version if the line did not change.
- No promise of auto-block on breaking changes, changelog auto-generation, installer publishing, or auto-deploy — the Non-Goals (design.md:22-28) and README:262 ("There is no automated check for breaking changes in CI") keep these out.

## 5. Owner rules

- No hardcoded numbers in code: `pom.xml` carries the version string `0.1.0` (a version, not a numeric parameter); no numeric config was introduced.
- No comments added to code or YAML — the compose diff is purely the `build:` → `image:` swap; the pre-existing header comments are untouched.
- No corporate identifiers (e.g. `registry.rocketscien.se`) in any changed line of this batch — grep over all eight files is clean; only `ghcr.io/rcktsci/...` (the public org path) and `keycloak.example.com` placeholders appear.
- Tone: README/CHANGELOG in lively English, no AI-lexicon; decisions.md/AGENTS.md in Russian in the files' style; D-96 is a single row after D-95.

## MINOR findings (non-blocking, cosmetic consistency)

1. **`openspec/changes/ci-backend-image/design.md:20`** — Goals still read "Владелец на VM получает образ через `docker pull`". The delivery path is now `docker compose up -d` on the pinned tag. Fix: reword to the pin flow ("получает образом через `docker compose up -d` по закреплённому в compose тегу").
2. **`design.md:24`** — Non-Goals still read "сейчас доставка = `docker pull` руками". Same staleness. Fix: "сейчас доставка = правка строки `image:` в compose + `docker compose up -d`".
3. **`specs/backend-image-ci/spec.md:4`** — Purpose still says "забирает VM через `docker pull`". The requirement body (line 53) and scenarios already describe compose. Fix: align the Purpose sentence with the pin model.
4. **`tasks.md:28` (task 4.4)** — The release-commit description lists pom + package.json + CHANGELOG, but not the compose `image:` pin, which `specs/release-policy/spec.md:24` and README 4.2 step 3 now make mandatory. Fix: add "+ строка `image:` в `docker-compose.yml`" to the task text.
5. **`CHANGELOG.md:9`** — The entry is dated `2026-09-25`, and the file's own note says the release date is the date of the release commit; the release commit has not been made yet and the last commit is from 2026-09-26. Fix: set the entry date to the actual commit date when the release commit lands (or state 2026-09-25 explicitly as the intended release date).

None of these affect correctness of the release or of the delivery model — they are wording leftovers in internal change artifacts.

## Re-approval (2026-09-26)

All five MINOR findings were fixed, verified by diff:

1. `design.md:20` — Goal reworded to "`docker compose up -d` по версионному тегу, закреплённому в `docker-compose.yml`" — done.
2. `design.md:24` — Non-Goal reworded to "правка строки `image:` в compose + `docker compose up -d`" — done.
3. `specs/backend-image-ci/spec.md:4` — Purpose aligned with the pin model; the two remaining `docker pull` mentions (spec lines 43 and 63) are intentional — one is the low-level public-package/tag-identity check, the other states that no separate pull/re-tag is required. Both read correctly in context.
4. `tasks.md:28` (4.4) — the compose `image:` line is now part of the release-commit description; checkbox stays `[ ]`, which is correct (the tag push happens after owner approval).
5. `CHANGELOG.md:9` — entry date is now `2026-09-26`, matching the actual release-commit date; the note no longer pins a fixed date.

Plus the Mercury finding was adopted: `README` 4.1 now shows `docker compose pull` before `up -d` with the rationale ("fails fast with a clear error if the tag is missing or mistyped … surfaces a wrong tag immediately, not at container start"). The deploy path and the rollback path stay consistent — `up -d` pulls a changed tag on its own, the explicit `pull` is an optional fail-fast check.

Verified: `design.md` mentions `docker pull` only inside the rejected alternative D-CI10(1) (line 116). No other file in the batch describes the old pull+retag flow. `openspec validate ci-backend-image --strict` is green (reported by the orchestrator).

## Final verdict: APPROVE

The release commit `v0.1.0` is ready: `pom.xml` = `0.1.0`, `web-desktop/package.json` = `0.1.0`, `CHANGELOG.md` entry `## [0.1.0] - 2026-09-26` with tag link, `docker-compose.yml` pins `ghcr.io/rcktsci/spring-harness-orchestrator:v0.1.0` with no `build:` block and no other changes, and the delivery model is stated consistently across README, both specs, D-CI10/D-CI15/D-96, AGENTS.md, and tasks.md. The tag `v0.1.0` may be pushed after the owner approves the commit.
