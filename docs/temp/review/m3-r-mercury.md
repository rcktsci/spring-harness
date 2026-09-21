# M3 Batch R Review - Mercury

**Дата:** 2026-09-20  
**Объект:** ACL/owner + subagent-наследование

## Findings

### 1. R.1: metaTools не наследуется subagent (D-69)

**Specification:**
- `spawn_subagent` создаёт child session с `metaTools=false` (независимо от parent.metaTools)
- D-69 org-bias: only explicit bootstrap orchestrators have `metaTools=true`

**tasks.md R.1:**
- `agent.metaTools=true` не распространяется на spawn subagent'ы
- subagent inherits `metaTools=false`

✅ **Correct** — D-69 org-bias соблюдён

### 2. R.2: subagent наследует parent_session_id и owner_user_id

**Specification:**
- `parent_session_id = currentSessionId`
- `owner_user_id = parent session.owner_user_id`
- `depth = parent.depth + 1`

**tasks.md R.2:**
- subagent'ы наследуют parent_session_id, owner inherits
- session.depth = parent.depth + 1

✅ **Correct** — D-61 depth, owner inheritance

### 3. R.3: orphan-container cleanup

**Specification:**
- Orphan containers (`harness-<subSessionId>`) cleaned up
- Orphan sub-sessions: no-op (parent may be stopped)

**tasks.md R.3:**
- Orphan-container cleanup via SubtreeCanceller
- Orphan sub-sessions: no-op if parent stopped

✅ **Correct** — D-68, R.3

## Dev Notes

| Dev заметка | Статус |
|-------------|--------|
| D-69 org-bias (metaTools=false) | ✅ |
| parent_session_id + owner inheritance | ✅ |
| Orphan cleanup | ✅ |

## Verdict

approve
