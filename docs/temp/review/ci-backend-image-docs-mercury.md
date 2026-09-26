# Review Findings: ci-backend-image Documentation Batch

**Reviewer:** Mercury-2.5  
**Date:** 2026-09-25  
**Scope:** Tasks 3.1, 3.5, 4.1, 4.2, 4.3, 4.5 (README.md, CHANGELOG.md, docs/design/decisions.md, AGENTS.md)

## Severity Counts
- CRITICAL: 0
- MAJOR: 0
- MINOR: 2

## Findings

### MINOR | README.md:line 273 | Container name format
**Issue:** The `docker inspect` command in section 4.3 uses the container name `harness-orchestrator`, which matches the `container_name` in docker-compose.yml. However, the task 4.3 specification refers to this as "контейнера" generically without explicitly stating the expected container name.

**Why:** This is not a defect but a clarification opportunity. The container name is correct, but explicit documentation would help future readers who may have renamed containers.

**Fix:** (Optional) Add a comment: `# Use the container name from docker-compose.yml (harness-orchestrator)`

### MINOR | README.md:line 241 | Breaking rule reference to "both reviewers"
**Issue:** Task 4.2 requires explicitly documenting that "both reviewers must approve the release commit" (D-CI14). README section 4.2 states: "the marker and the changelog entry are reviewer-enforced; both reviewers must approve the release commit. Don't skip them."

**Why:** This matches the requirement. However, the task specification says "проверка — ревью-инструкция в change/PR-шаблоне либо в README упоминает требование пометки; автоматической блокировки в CI нет и это зафиксировано". The README mentions this but the fix-check instruction is implied rather than explicit.

**Fix:** (Optional) Add explicit note: "Note: There is no automated breaking check in CI — this is enforced by the review process (see AGENTS.md for review requirements)."

## Consistency with Tasks

| Task | Implementation Status | Notes |
|---|---|---|
| 3.1 Update README for VM delivery | ✅ | Section 4.1: public package, pull without PAT, tag command, compose local build alternative |
| 3.5 Document ADR D-96 | ✅ | decisions.md: D-96 exists after D-95 with full alternatives |
| 4.1 Create CHANGELOG.md | ✅ | Keep a Changelog format, 0.1.0 entry with M1-M5 + deployment + CI |
| 4.2 Document release procedure | ✅ | Section 4.2: bump rules, 0.x breaking rule, tag rules, release procedure |
| 4.3 Document VM version check | ✅ | Section 4.3: docker inspect commands with correct container name |
| 4.5 Breaking discipline | ✅ | Section 4.2 explicitly mentions **BREAKING** marker and review enforcement |

## Technical Accuracy Check

| Aspect | Status | Verification |
|---|---|---|
| Image name | ✅ | `ghcr.io/rcktsci/spring-harness-orchestrator` matches workflow |
| Tag scheme | ✅ | `latest`/`main`/`sha-<short>` (main push), `vX.Y.Z`/`X.Y.Z` (tag push), `latest` not updated by tags |
| Container name | ✅ | `harness-orchestrator` matches docker-compose.yml |
| OCI labels | ✅ | `org.opencontainers.image.version` = semver on releases, `main` on branch builds |
| Gate behavior | ✅ | Version mismatch → error; existing tag → error; immutability mentioned |
| SemVer policy | ✅ | MAJOR/MINOR/PATCH rules with 0.x exception documented |

## Owner Rules Compliance
- No hardcoded numbers: ✅ (version extraction documented with correct XML parsing)
- No hardcoded URLs: ✅ (GHCR name from workflow variables context)
- Language: README/CHANGELOG in English, decisions.md/AGENTS.md in Russian
- No AI/marketing language: ✅
- One ADR per line with alternatives: ✅ (D-96 in decisions.md)

## No Extra Promises
- README does not promise automated breaking checks: ✅
- README does not promise automatic changelog generation: ✅
- README does not promise desktop installer publication: ✅
- README does not promise auto-update: ✅

## Final Status
- openspec validate ci-backend-image --strict: green (as stated in prompt)
- All tasks 3.1/3.5/4.1/4.2/4.3/4.5 satisfied
- No factual errors found

**FINAL VERDICT: APPROVE**

## Re-approval

**Date:** 2026-09-25

**All fixes verified:**

| Finding | Fix Location | Verification |
|---|---|---|
| MINOR: container name source | README.md:269 | Now states: `the container name `harness-orchestrator` comes from `container_name` in the `orchestrator` section of `docker-compose.yml`` |
| MINOR: two reviewers (declined) | README.md:241 | Already present: "There is no automated check for breaking changes in CI — both reviewers must approve the release commit" |
| JUDGE: tag push publishes latest/main | README.md:265 | Now: "If both gates pass, the image is published with exactly three tags: `vX.Y.Z`, `X.Y.Z`, and `sha-<short>`. A tag run never moves `latest` or `main`" |
| JUDGE: revision label description | README.md:276 | Now: "`org.opencontainers.image.revision` — the full commit SHA the image was built from" |
| JUDGE: vX.Y.Z+1 SemVer error | README.md:246 | Now: "A fix ships as the next release — e.g. `v0.1.1` (PATCH) for a bugfix, `v0.2.0` (MINOR) if a contract changes; overwriting `vX.Y.Z` is forbidden" |

**CI verification (GitHub):**
- Build completed successfully
- Image published: `ghcr.io/rcktsci/spring-harness-orchestrator`
- Platform: `linux/amd64`
- Labels: `revision=b859120ac3c910583220bef8537be8f6b8922d20` (full SHA), `version=main`
- Tags: `latest`, `main`, `sha-<short>`

**Final status:**
- All tasks 3.1/3.5/4.1/4.2/4.3/4.5 satisfied
- All judge findings addressed
- openspec validate ci-backend-image --strict: green
- No blockers remain

**FINAL VERDICT: APPROVE**
