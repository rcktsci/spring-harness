# Review: m1-session-core Batch B (sso-gate + session-store)
**Reviewer:** Mercury-2.5 | **Date:** 2026-09-17

## Findings

### MEDIUM: Test hygiene - extractAccessToken uses fragile string parsing
**File:** `src/test/java/.../identity/AppUserSyncTest.java:140-145`
**Defect:** `extractAccessToken` parses JWT response using string indices (`indexOf("\"access_token\":\"")`), which breaks with escaped quotes or different JSON formatting.
**Proposal:** Use a proper JSON parser (Jackson `ObjectMapper` or `JsonNode`) to extract `access_token`.

### MEDIUM: Architecture - `compactCovers` query lacks pagination
**File:** `src/main/java/.../session/SessionStoreImpl.java:129-149`
**Defect:** `entityManager.createQuery(...).getResultList()` in `compactCovers` has no `setMaxResults`. If a session has thousands of COMPACT events (non-typical), this could exhaust memory.
**Proposal:** Add `setMaxResults(1000)` or similar limit; log warning if exceeded.

### LOW: VisibilityRenderer - edge case when `point == interval.fromSeq` or `point == interval.toSeq`
**File:** `src/main/java/.../session/VisibilityRenderer.java:97-124`
**Defect:** `insertPoint` has 4 branches handling `point+1==from`, `point-1==to`, `point<from`, else. If `point==from` or `point==to`, the point is silently added in the else branch and may create adjacent intervals that aren't merged.
**Proposal:** Add explicit branches for `point == interval.fromSeq` and `point == interval.toSeq` to merge correctly.

### INFO: Test hygiene - static WireMock may have resource leak across parallel test runs
**File:** `src/test/java/.../WireMockJwksInitializer.java:33-44`
**Defect:** `WireMockServer` is initialized in a static block, not explicitly stopped between test runs. If multiple `BaseApplicationTest` subclasses run in parallel (Surefire), each classloader's static init could leave orphaned servers.
**Proposal:** Add `@AfterAll` to stop WireMockServer.

### VALID: Race conditions - `@Transactional` ensures atomic seq reservation + INSERT
**Files:** `SessionStoreImpl.java:64-80`, `SessionStoreImpl.java:109-124`
**Notes:** Row-lock UPDATE via `JdbcTemplate` and `EntityManager.persist()` both use `JpaTransactionManager`, which coordinates a single JDBC connection. Transaction rollback rolls back both seq reservation and INSERT atomically.

### VALID: Idempotency - all changeSets have `preConditions` with `onFail="MARK_RAN"`
**File:** `src/main/resources/db/changelog/migrations/2026/2026-09-17__create_schema__m1_core.xml`
**Notes:** All createTable/uniqueConstraint/index changeset have proper preConditions. Migration can be re-run safely.

### VALID: Security - `GroupsGateFilter` throwing `AuthenticationException` is handled by entry point
**Files:** `GroupsGateFilter.java:41`, `SecurityConfig.java:55-58`, `GroupsNotAllowedException.java:9`
**Notes:** Spring Security's `ExceptionTranslationFilter` catches `AuthenticationException` and delegates to `authenticationEntryPoint`, which returns `401 ProblemDetails` with `code: unauthenticated`.

## Summary

| Severity | Count |
|----------|-------|
| MEDIUM | 2 |
| LOW | 1 |
| INFO | 1 |
| VALID | 3 |

**Top 3 findings:**
1. MEDIUM: `extractAccessToken` uses fragile string parsing (AppUserSyncTest.java:140-145)
2. MEDIUM: `compactCovers` query lacks pagination (SessionStoreImpl.java:129-149)
3. LOW: `VisibilityRenderer.insertPoint` has edge case at interval boundaries (VisibilityRenderer.java:97-124)

**Verdict:** Requires minor fixes before approval.
