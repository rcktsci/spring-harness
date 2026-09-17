# Review: Batch B (tasks 3.1–3.3 identity/sso-gate, 4.1–4.3 session-store)

**Reviewer**: GLM-5.3-Flash (MiniMax-M2.7)
**Date**: 2026-09-17
**Change**: m1-session-core batch B (visibility rewrite)

---

## Findings (severity, файл:строка, дефект, предложение)

### 🔴 CRITICAL

1. **Broken reference: D-44 does not exist**
   - `SessionStore.java:360` — Javadoc references `D-44` for the `covers` format description
   - `docs/design/decisions.md` contains only D-01 through D-43
   - **Defect**: Any developer relying on the Javadoc to understand `covers` semantics will fail to find D-44. This is a documentation debt that will cause confusion during implementation of COMPACT logic downstream.
   - **Fix**: Either remove the D-44 reference, or create the ADR explaining the covers format. Since the spec already describes covers semantics (`spec.md` session-store §"Видимость как производная COMPACT-покрытий"), the D-44 reference appears stale.

---

### 🟡 MEDIUM

2. **`insertPoint` edge case: empty result with adjacent intervals**
   - `VisibilityRenderer.java:1006–1060` — when inserting a self-hidden point that is adjacent to an interval, the code checks `!result.isEmpty() && result.getLast().toSeq() == point - 1` before merging. If `result` is empty (e.g., `lastSeq=2`, compact at seq=1 covers [1,1], compact at seq=2 covers [2,2] — both self-hidden but visible to each other), the `!result.isEmpty()` guard prevents a logical merge, but the interval is still added correctly as `[point, interval.toSeq()]`. The result is correct but the guard is misleading — it suggests a merge was attempted but skipped, when in fact no merge was possible. No functional defect found after tracing all paths.

3. **`IS DISTINCT FROM` — correct PostgreSQL idiom**
   - `UserSyncFilter.java:590–600` — the upsert uses `IS DISTINCT FROM` for both `username` and `display_name` conditional update. This is the correct PostgreSQL idiom for "update if changed, no-op if same" and handles NULL semantics correctly. However, the `display_name` fallback (`displayName != null ? displayName : username`) means `username` is never NULL when passed to the query — so the `IS DISTINCT FROM` for `display_name` only triggers when Keycloak sends a different non-null value.

---

### 🟢 LOW / Info

4. **`complement` cursor clamp to lastSeq**
   - `VisibilityRenderer.java:601` — `Math.min(interval[0] - 1, lastSeq)` correctly clamps visible intervals to `[1..lastSeq]`. Combined with `cursor = Math.max(cursor, interval[1] + 1)` on line 622, the logic is sound. No overflow risk with `interval[0] - 1` since `interval[0] >= 1`.

5. **No hardcoded numbers per D-39**
   - `SessionStoreImpl.java` uses only config-bound values (`SecurityProperties.allowedGroups` from `application.yml`). No magic numbers detected in SQL or Java code.
   - `application.yml:161` — `threshold: 0.8` for COMPACT threshold is config-bound.

6. **Test isolation — profiles correctly separated**
   - `BaseApplicationTest` uses `@ActiveProfiles("test")` — random port, no Keycloak
   - `JwtSecurityTest` uses `@ActiveProfiles("jwtmock")` — WireMock JWKS
   - `AppUserSyncTest` uses `@ActiveProfiles("keycloak")` — Testcontainer Keycloak
   - No Keycloak/testcontainer artifacts leak into prod code

7. **No test bypass of Keycloak in prod**
   - `UserSyncFilter.java` is pure production code. All Keycloak/testcontainer imports are in test-only initializers.
   - `GroupsGateFilter` reads `SecurityProperties` which is `@ConfigurationProperties` — fully prod.

8. **`failedAppendRollsBackSeqReservation` — correct rollback verification**
   - `SessionAppendTest.java:1011–1331` — the test verifies that after a RuntimeException is thrown inside a transaction, `last_seq` remains 0 and no `session_message` rows exist. The subsequent successful `appendEvent` correctly gets `seq=1`. This confirms atomicity of row-lock UPDATE + INSERT within a single `@Transactional` boundary.

---

## Проверено и валидно (без замечаний)

### SQL/JPA-корректность
- **`reserveSeqByRowLock`** (`SessionStoreImpl.java:1091–1231`): `JdbcTemplate` with native `UPDATE ... RETURNING` executes correctly under `JpaTransactionManager`. The `@Transactional` on the method ensures atomicity of `last_seq` increment and subsequent INSERT.
- **Row-lock UPDATE**: `UPDATE session SET last_seq = last_seq + 1, last_activity_at = now() WHERE id = ? RETURNING last_seq, last_activity_at` — correct PostgreSQL row-level lock with RETURNING.
- **`dbNow()`**: `SELECT now()` via `JdbcTemplate` — works in same transaction as entity manager operations.

### Миграция vs Entity mapping
- **`session` table** (changeset 6): `last_seq`, `last_consumed_seq`, `last_activity_at`, `created_at`, `kind`, `cancel_requested`, `owner_user_id` — matches `SessionEntity.java` fields exactly.
- **`session_message` table** (changeset 11): composite PK `(session_id, seq)` with `id` (ULID) unique — matches `SessionMessageId.java` embeddable + `SessionMessageEntity.ulid`.
- **`app_user` table** (changeset 1): `keycloak_subject` unique constraint — correct for upsert target.
- **`agent` table** (changeset 4): maps to `AgentRevisionEntity` — correct.
- **CHECK constraints**: `ck_session__kind`, `ck_session__last_turn_outcome`, `ck_session_message__kind` — all present and restrictive.

### Upsert `app_user` (IS DISTINCT FROM)
- `UserSyncFilter.java:530–660` — conditional update only when `username IS DISTINCT FROM EXCLUDED.username OR display_name IS DISTINCT FROM EXCLUDED.display_name`. Correct PostgreSQL idiom. No N+1 risk — single statement.

### Security filters
- **Order** (`SecurityConfig.java:571–590`): `BearerTokenAuthenticationFilter` → `GroupsGateFilter` → `UserSyncFilter` → `AuthorizationFilter`. Correct.
- **401 without challenge** (`SecurityConfig.java:710–740`): `unauthenticatedEntryPoint` writes Problem Details with `code: unauthenticated`, no `WWW-Authenticate` header. Matches spec requirement.
- **Groups gate** (`GroupsGateFilter.java:341–430`): `passesGroupsGate` uses `Collections.disjoint` — correct intersection check. Throws `GroupsNotAllowedException` (extends `AuthenticationException`) which is handled by entry point.
- **User sync only for authenticated** (`UserSyncFilter.java:390–410`): guard `jwtAuthentication.isAuthenticated()` — sync only runs for successfully authenticated users. Users failing the groups gate are not synced (correct per spec scenario "user outside groups gets 401 and is not synced").

### VisibilityRenderer deep analysis

| Aspect | Status | Comment |
|--------|--------|---------|
| Inclusive bounds `[from..to]` | ✅ | All comparisons use `<=` / `>=` |
| Merges adjacent/overlapping | ✅ | `cover.fromSeq() <= merged.getLast()[1] + 1` — adjacency handled |
| COMPACT doesn't hide itself | ✅ | `selfHiddenOnlyPoints` with `hiddenBySelf && !hiddenByOther` logic |
| Later COMPACT hides earlier | ✅ | All covers merged before complement — later compacts contribute to union first |
| Nested/edge cases | ✅ | Verified with `singleCompactHidesExactlyCoveredRange`, `overlappingCoversMergeIntoSingleHiddenInterval`, `disjointCoverRangesAccumulate` |
| `selfHiddenOnlyPoints` holes | ✅ | The `!hiddenByOther` condition correctly excludes points covered by ANY other COMPACT |
| `insertPoint` adjacency logic | ✅ | Four branches: before-first, after-last, point+1==from, point-1==to. Merges with previous when applicable. |
| `lastSeq=0` | ✅ | Returns `List.of()` |
| `lastSeq=1` | ✅ | Returns `[1,1]` if no covers |
| COMPACT with empty `covers` | ✅ | `compactWithoutCoversHidesNothing` test confirms |
| COMPACT with `covers` beyond lastSeq | ✅ | `complement` clamps with `Math.min(interval[0] - 1, lastSeq)` |
| `to=Long.MAX_VALUE` | ✅ | `cursor = Math.max(cursor, interval[1] + 1)` — no overflow; `long` can represent `Long.MAX_VALUE` safely |

### `selectByIntervals` dynamic JPQL
- `SessionStoreImpl.java:1551–1741`: StringJoiner with `BETWEEN :from{i} AND :to{i}` — dynamic parameter count. Parameter binding loops match the conditions. JPQL keyword uppercase. No SQL injection risk.
- Example output: `sm.id.seq BETWEEN :from0 AND :to0 OR sm.id.seq BETWEEN :from1 AND :to1`

### Test coverage (required scenarios)
| Test | File:Line | Status |
|------|-----------|--------|
| `failedAppendRollsBackSeqReservation` | `SessionAppendTest.java:1011` | ✅ |
| `restoreCarolName` | `AppUserSyncTest.java:42` | ✅ (AfterEach) |
| `tokenSignedByUnknownKey` | `JwtSecurityTest.java:129` | ✅ |
| `wideCoverRangeRendersWithoutSeqMaterialization` | `SessionRenderVisibilityTest.java:78` | ✅ |

### Session record
- `Session.java:241–251`: contains `lastActivityAt` and `createdAt` — both present and populated from `SessionEntity`.

### D-41 SSO-gate implementation
- Bearer JWT required on `/api/v1/**` ✅
- 401 without WWW-Authenticate challenge ✅
- Groups claim intersection check ✅
- User sync only for allowed groups ✅

---

## Summary

| Severity | Count |
|----------|-------|
| 🔴 CRITICAL | 1 |
| 🟡 MEDIUM | 2 |
| 🟢 LOW/Info | 5 |

**Top-3 findings**:
1. **D-44 reference is broken** — Javadoc in `SessionStore.java:360` references a non-existent ADR. Either create the ADR or remove the reference. This is the only blocking issue.
2. **`insertPoint` empty-result guard** — misleading but not functionally wrong; recommend clarifying comment or extracting helper method.
3. **`IS DISTINCT FROM` semantics** — correct but verify the `display_name` fallback doesn't silently mask a null-propagation edge case (already handled: `username` is guaranteed non-null from JWT, but `displayName` can be null and falls back to `username`).

**Вердикт**: ✅ **APPROVE with one required fix**

D-44 reference needs resolution before merge. All other aspects are correct, well-tested, and consistent with specs. The visibility logic is mathematically sound. SQL/JPA, security filters, test isolation, and migration/entity mapping all pass review.
