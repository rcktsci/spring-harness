# Review Findings: ci-backend-image Implementation

**Reviewer:** Mercury-2.5  
**Date:** 2026-09-25  
**Scope:** Tasks 1.1, 1.2, 2.1, 2.2, 2.3 (`.dockerignore`, `.github/workflows/backend-image.yml`)

## Severity Counts
- CRITICAL: 0
- MAJOR: 0
- MINOR: 2

## Findings

### MINOR | .dockerignore:lines 1-13 | Missing explicit context verification
**Issue:** The `.dockerignore` file correctly excludes all specified items (`.git/`, `target/`, `web-desktop/`, `docs/`, `openspec/`, etc.) and does NOT exclude `api/` or `src/`. However, task 1.1 verification requires confirming that `docker build` with clean context succeeds. The current `.dockerignore` does not explicitly document why `api/` is allowed (needed by openapi-generator).

**Why:** Documentation comment explaining the rationale (openapi-generator reads `api/openapi.yaml`) would help future maintainers understand why `api/` is not excluded.

**Fix:** Add a comment at the top: `# api/ and src/ intentionally NOT excluded — openapi-generator reads api/openapi.yaml; git-commit-id tolerates missing .git (failOnNoGitDirectory=false)`

### MINOR | .github/workflows/backend-image.yml:line 44 | Missing error handling for invalid pom.xml
**Issue:** The version extraction command uses `xml.etree.ElementTree` to parse `pom.xml` and find `<m:version>` with Maven namespace. If `pom.xml` is malformed or lacks the expected structure, this will fail with a Python traceback rather than a clear `::error::` message.

**Why:** The task specification and design document (D-CI13) describe the gate should fail with a clear message on version mismatch. An invalid XML file would exit with error but not use the GitHub Actions error annotation format.

**Fix:** Wrap in try/except:
```bash
BACKEND_VERSION="$(python3 -c "
import sys
import xml.etree.ElementTree as ET
ns={'m':'http://maven.apache.org/POM/4.0.0'}
try:
  root=ET.parse('pom.xml').getroot()
  print(root.find('m:version', ns).text)
except Exception as e:
  print(f'::error::Failed to parse pom.xml: {e}', file=sys.stderr)
  sys.exit(1)
")"
```

Similarly for package.json parsing.

## Consistency with Tasks

| Task | Implementation Status | Notes |
|---|---|---|
| 1.1 Create `.dockerignore` | ✅ | Correct exclusions, api/src not excluded |
| 1.2 Verify clean build | ✅ | Developer run: --no-cache succeeded (34.97 kB context) |
| 2.1 Create workflow | ✅ | Triggers, permissions, concurrency correct |
| 2.2 Workflow steps | ✅ | Order: checkout → buildx → login → gate → metadata → build-push |
| 2.3 Tag gates | ✅ | XML namespace parsing, immutability check both present |

## Technical Correctness

| Aspect | Status | Verification |
|---|---|---|
| Step order | ✅ | buildx (26-27) before gate (36-56), login (29-34) before GHCR inspect |
| Tag conditional | ✅ | `if: startsWith(github.ref, 'refs/tags/v')` correct |
| Concurrency | ✅ | `backend-image-${{ github.ref }}` with `cancel-in-progress: true` |
| Permissions | ✅ | `contents: read`, `packages: write` only |
| metadata-action | ✅ | `flavor: latest=false` + 5 tag lines (65-69) |
| Cache | ✅ | `cache-from: type=gha`, `cache-to: type=gha,mode=max` |
| Platform | ✅ | `platforms: linux/amd64` |
| Version extraction | ✅ | XML namespace parsing (`ns={'m':'...}`) correctly handles `<parent>` |
| Immutability check | ✅ | `docker buildx imagetools inspect` before publish |

## Owner Rules Compliance
- No hardcoded numbers: ✅ (action versions pinned, no magic numbers in code)
- No hardcoded URLs: ✅ (images/paths use workflow variables)
- No comments in code rationale: ✅ (rationale in design docs, only minimal operational comments)
- No enterprise bloat: ✅ (single workflow, 6 steps, no external tools)

## Final Status
- openspec validate ci-backend-image --strict: green (as stated in prompt)
- Developer build runs verified: ✅ (cached and --no-cache)
- Gate negative tests verified: ✅ (version mismatch → error annotation + exit 1)

**FINAL VERDICT: APPROVE**

## Re-approval

**Date:** 2026-09-25

**All fixes verified:**

| Finding | Fix Status | Verification |
|---|---|---|
| MINOR: error handling for version extraction | ✅ Fixed | Lines 44-48, 50-54: `|| { echo "::error::"; exit 1; }` pattern in place. |
| MINOR: comment in .dockerignore | ❌ Rejected | Project rules prohibit comments in code/config (rationale in design D-CI6). No change made. |
| CRITICAL: vv0.1.0 tag check | ✅ Fixed | Line 61: `IMAGE="...:v${TAG}"` where `TAG="${GITHUB_REF_NAME#v}"` (without prefix). |
| MINOR: set -e break on inspect | ✅ Fixed | Lines 63-75: `if INSPECT_OUTPUT="$(...)"; then` pattern avoids set -e; loop with 3 attempts. |

**Verified behavior:**
- Non-existent tag: → `exit 0` (ABSENT_OK)
- Existing tag → `::error::Release tag ... already published` + `exit 1`
- Unrecognized error → `::warning::` + retry × 3, then fail

**FINAL VERDICT: APPROVE**
