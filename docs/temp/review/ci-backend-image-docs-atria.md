# Review Findings: ci-backend-image Documentation Batch

**Reviewer:** Atria-Dawn
**Date:** 2026-09-26
**Scope:** Tasks 3.1, 3.5, 4.1, 4.2, 4.3, 4.5 — `README.md`, `CHANGELOG.md`, `docs/design/decisions.md`, `AGENTS.md`

## Verdict: APPROVE

| Severity | Count |
|---|---|
| CRITICAL | 0 |
| MAJOR | 0 |
| MINOR | 0 |

## 1. Completeness against tasks 3.1 / 3.5 / 4.1 / 4.2 / 4.3 / 4.5

All six tasks are covered, nothing invented:

- **3.1 (README: public image, no PAT on the VM)** — README:206 announces a public GHCR package as the alternative to the local `build:` path; README:223-229 give the exact pull/tag/up sequence with no `docker login`. README:230 explicitly frames it as the alternative for VMs without a JDK or a clone, keeping section 1's local build valid.
- **3.5 (ADR D-96 + AGENTS.md)** — one new row D-96 at `docs/design/decisions.md:97`, directly after D-95, with rejected alternatives and a link to the change; AGENTS.md gained one bullet in «Текущее состояние» (AGENTS.md:65) and two entries in «Ключевые документы» (AGENTS.md:95 — CHANGELOG.md, AGENTS.md:99 — active change). `git diff` shows additions only, no existing content lost.
- **4.1 (CHANGELOG.md, Keep a Changelog)** — new file, format link in the header, `## [0.1.0] - 2026-09-25` with Added/Changed/Fixed/Removed sections, and a comparison link `[0.1.0]: https://github.com/rcktsci/spring-harness/releases/tag/v0.1.0` (CHANGELOG.md:22).
- **4.2 (release procedure in README)** — README:4.2 covers bump rules, the 0.x `**BREAKING**` convention, tag rules (only from `main`, immutable), and a step-by-step procedure (version → changelog → commit → tag → push), plus the two CI gates.
- **4.3 (docker inspect on the VM)** — README:272-274 give the exact `docker inspect --format` commands for both OCI labels; README:276-277 explain their values; README:282-285 give the rollback sequence by immutable `sha-<short>` (or semver) tag.
- **4.5 (breaking discipline)** — README:241 states plainly that there is no automated breaking detector and that the marker plus the changelog entry are reviewer-enforced by both reviewers.

## 2. Facts vs workflow / compose

Cross-checked line by line; everything matches.

- **Image name** `ghcr.io/rcktsci/spring-harness-orchestrator` — workflow image line, README:208.
- **Tag scheme** — workflow `docker/metadata-action` with `flavor: latest=false` and five tag lines: `type=ref,event=branch`, `type=sha,format=short`, `type=semver,pattern={{raw}}`, `type=semver,pattern={{version}}`, `type=raw,value=latest,enable={{is_default_branch}}`. On a `main` push this yields `latest` / `main` / `sha-<short>` (3 tags, matches the live CI run). On a `v*` tag push only the two semver patterns and the sha pattern fire → exactly `vX.Y.Z` / `X.Y.Z` / `sha-<short>`; `latest` and `main` are not moved, exactly as README:213-217 and README:265 state.
- **`spring-harness:local`** — compose expects `image: spring-harness:local`; README:225 and README:283 re-tag the pulled image to exactly that name so `docker compose up -d` works unmodified.
- **Container `harness-orchestrator`** — compose `container_name:`; README:269 cites it correctly.
- **OCI labels** — `org.opencontainers.image.revision` = full commit SHA on every build (identical across all tags from one commit); `org.opencontainers.image.version` = semver on a release build, branch name on a branch build. Matches the live run (`revision` = full SHA, `version` = `main`) and README:276-277.
- **Two CI gates** — workflow runs a python3 XML parse of `pom.xml` (top-level `<version>`, not the parent's) plus `web-desktop/package.json`, failing on mismatch, and a `docker buildx imagetools inspect` immutability check before publishing. README:260-263 describe both accurately.

## 3. Release-policy compliance

- **MAJOR = breaking** `api/openapi.yaml`, WS relay (§5), SSE (§3.1/§3.2) — README:237.
- **0.x breaking = MINOR** with a `**BREAKING**` marker and a migration note in the changelog — README:241.
- **Tag only from `main`** — README:245.
- **Tags immutable**, fix ships as the next release — README:246.
- **Keep a Changelog** with an explicit migration note — CHANGELOG.md header link + README:241.
- **Traceability and rollback on the VM** — README:267-285: read `.revision`/`.version` on the running container, roll back by pulling the exact `sha-<short>` (or semver) tag and re-tagging to `spring-harness:local`.

## 4. Tone and owner rules

- **README / CHANGELOG** — English, natural, direct. No AI-lexicon ("delve", "leverage", "seamlessly", "robust solution"), no corporate identifiers. Section 4 reads in the same voice as sections 1–3.
- **decisions.md / AGENTS.md** — Russian, in the files' established style.
- **D-96** — exactly one row, placed directly after D-95 (decisions.md:97), listing nine-plus rejected alternatives (`${revision}` + flatten-maven-plugin, semantic-release / release-please, a `VERSION` file, registry cache, multi-arch, SBOM/provenance/cosign, `oasdiff` auto-detect, `setup-java` + `mvn` on the runner, compose pinned to the registry image) and closing with a link to the change (design D-CI1…D-CI16).
- **AGENTS.md** — `git diff` confirms purely additive changes; the existing structure and entries are intact.

## 5. No promises the CI does not keep

- No claim of automated breaking-change blocking — README:241 says the opposite.
- No claim of changelog auto-generation — the procedure is manual (README:251).
- No claim of installer publishing — the changelog entry covers only the backend image and release tooling.
- No claim of auto-deploy to the VM — the pull/tag/up sequence is manual (README:223-229).

## Notes

- The `[0.1.0]` comparison link in CHANGELOG.md:22 points at a tag that does not exist yet. That is by design: the changelog lands in `main` first, the tag follows as a separate step (CHANGELOG.md:5), and the tag-push gate would refuse a re-run anyway. Not a finding.
