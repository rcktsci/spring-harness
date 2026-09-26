# Review Findings: ci-backend-image Release Commit v0.1.0

**Reviewer:** Mercury-2.5  
**Date:** 2026-09-25  
**Scope:** Release commit v0.1.0 and delivery model change (compose pins versioned image tag)

## Severity Counts
- CRITICAL: 0
- MAJOR: 0
- MINOR: 1

## Findings

### MINOR | README.md section 4 | Tag change instruction for rollback
**Issue:** The rollback procedure states "change the tag in the `image:` line, then `up -d` again" but doesn't explicitly mention pulling the new image first. While `docker compose up -d` will pull if `pull_policy` is set or if the image is missing, the safest practice is to explicitly pull before upgrading.

**Why:** On the VM where compose is manually maintained (not a git clone), there's no guarantee the new image tag exists locally. If the image is not present locally and compose's pull policy is not configured, the service may fail to start.

**Fix:** Update the instruction to explicitly include a pull step: "1. `docker pull ghcr.io/rcktsci/spring-harness-orchestrator:<new-tag>`; 2. change the tag in the `image:` line; 3. `docker compose up -d`"

## Consistency Check

| Aspect | Status | Verification |
|---|---|---|
| pom.xml project version | ✅ | `0.1.0` (line 16, not parent version 4.1.1) |
| web-desktop/package.json version | ✅ | `0.1.0` |
| CHANGELOG.md entry | ✅ | `0.1.0 - 2026-09-25` with M1-M5, deployment, CI |
| docker-compose.yml image pin | ✅ | `ghcr.io/rcktsci/spring-harness-orchestrator:v0.1.0` |
| build: removed from compose | ✅ | No build: present in orchestrator service |
| AGENTS.md update | ✅ | Mentions CI, SemVer policy D-96, and delivery change |
| D-96 in decisions.md | ✅ | Contains full ADR with alternatives |
| Spec compliance | ✅ | release-policy spec documents immutability and SemVer rules |
| No enterprise bloat | ✅ | Single compose file, no extra infrastructure |

## Delivery Model Consistency

| Artifact | Consistent? | Notes |
|---|---|---|
| README.md section 1 | ✅ | States compose pins published version, no docker login needed |
| README.md section 4 | ✅ | States tag push publishes vX.Y.Z/X.Y.Z/sha-<short>, doesn't move latest/main |
| design.md D-CI10 | ✅ | Updated to reflect pinned image in compose |
| design.md D-CI13 | ✅ | Version gate and immutability gate documented |
| tasks.md 3.1 | ✅ | Reflects new model (no docker tag, no build) |
| tasks.md 4.3 | ✅ | Documents OCI label checking for VM version |
| release-policy spec | ✅ | Documents immutable release tags |

## No Leftover Old-Model Artifacts
- No `docker tag ... spring-harness:local` references in README ✅
- No `pull_policy` in compose ✅
- No `build:` in compose ✅
- No obfuscated `registry.rocketscien.se` in public artifacts ✅

## VM Realism Check
- VM compose is manually maintained (not git clone) ✅
- Manual copy + single `image:` line change ✅
- Pull + `docker compose up -d` ✅
- No login required (public package) ✅
- OCI label checking via `docker inspect` ✅
- Rollback = tag change + restart ✅

## Owner Rules Compliance
- No hardcoded numbers in code ✅
- No comments in YAML/code files ✅
- English language in README/CHANGELOG ✅
- Russian language in decisions.md/AGENTS.md ✅
- No corporate identifiers in public docs ✅

## Risks Documented
- Risk: forgot to change tag on live VM → app continues old version ✅ (implicit in documentation)

## Final Status
- All release commit requirements met
- One MINOR finding (explicit pull before upgrade)
- No blockers

**FINAL VERDICT: APPROVE**

## Re-approval

**Date:** 2026-09-26

**All fixes verified:**

| Finding | Fix Location | Verification |
|---|---|---|
| MINOR: explicit pre-pull step | README.md:211-213 | `docker compose pull` with explanatory comment before `docker compose up -d` |
| MINOR: design Goals/Non-Goals | design.md:16-24 | Delivery described as `docker compose up -d` with pinned tag; only rejected alternative mentions `docker pull` |
| MINOR: task 4.4 | tasks.md:28 | Release commit checklist includes `image:` line in `docker-compose.yml` |
| MINOR: CHANGELOG date | CHANGELOG.md:9 | Date corrected to `2026-09-26` |

**All artifacts consistent:**
- `pom.xml` = `0.1.0` (project version)
- `web-desktop/package.json` = `0.1.0`
- `docker-compose.yml` = `ghcr.io/rcktsci/spring-harness-orchestrator:v0.1.0` (pinned, no `build:`)
- `CHANGELOG.md` = `0.1.0 - 2026-09-26`
- `README.md` = explicit `docker compose pull` before `up -d`
- `design.md` = delivery via `up -d` with pinned tag
- `AGENTS.md` = CI + D-96 documented
- `openspec validate ci-backend-image --strict` = green

**No blockers remain.**

**FINAL VERDICT: APPROVE**
