# M1 batch E-impl — review (DeepSeek-V4.1-Flash, subagent reviewer)

Zona: openapi-generator config + Jackson-3 serialization, SQL/JPA / mapping, merge-patch converter,
SSE backfill race, spec deviation «202 no body → 406», bug «empty final ASSISTANT text».

Material: uncommitted working tree (pom, generated `target/generated-sources|test-sources`, new
`api/**`, `identity/AppUserDirectory*`, session/execution changes, `tests/api/**`).
**Builds/tests not run** (per task; dev run 198/0/0 accepted as given). Verified statically + by
surefire XML already present in `target/`.

## Findings

### F1 [Major] Mojibake corruption in three modified Docker tests (encoding regression)
`ContainerWorkspaceToolsDockerTest.java`, `WorkspaceContainerFailureDockerTest.java`,
`WorkspaceContainerManagerDockerTest.java` were re-saved as UTF-8 → CP1251 double-encoding.
Evidence (`git diff` shows old readable / new corrupt, mojibake-marker counts 43/85/45):
- `ContainerWorkspaceToolsDockerTest.java:32-33` class javadoc `РЅР°С‚РёРІРЅС‹Рµ...`;
- `ContainerWorkspaceToolsDockerTest.java:198` test literal changed
  `"привет-😀-100%-\\t\\n"` → `"РїСЂРёРІРµС‚-рџ-100%-\\t\\n"` (emoji/Cyrillic destroyed);
- `WorkspaceContainerFailureDockerTest.java:95`, `WorkspaceContainerManagerDockerTest.java:26-27` comments.
Test still green (roundtrip compares to the same corrupted string), so 198/0/0 hides it.
Fix: restore the three files from HEAD and re-apply only the intended `LimitsProperties(..., page)`
constructor change (`..., Duration.ofSeconds(5), 100)`) in correct encoding. Blocking for commit.

### F2 [Major] Bug «empty text of final ASSISTANT» — confirmed by code; exact fix
`AgentTurnEngine.callModel` (`src/main/java/se/rocketscien/harness/execution/AgentTurnEngine.java:185-193`):
```java
new MessageAggregator()
    .aggregate(llmInvoker.stream(...), chunk -> {})   // aggregate delivered to no-op
    .doOnCancel(done::countDown)
    .subscribe(responseRef::set, ...);                // captures per-chunk, not the aggregate
```
`MessageAggregator.aggregate(Flux, Consumer)` (spring-ai-model 2.0.1, verified by bytecode of
`org/springframework/ai/chat/model/MessageAggregator.class`) returns the **original per-chunk flux**
with side-effects (`doOnSubscribe/doOnNext/doOnComplete/doOnError`); the aggregated `ChatResponse` is
only passed to the `Consumer` in `doOnComplete`. `OpenAiChatModel.internalStream` itself only uses the
aggregator for observation and returns the per-chunk flux too (verified). For a text-only stream the
last raw chunk is the `usage` chunk (`delta:{}`, `finish_reason:stop`) → `text == ""`; the tool-call
round happens to work only because the tool stream is buffered/merged into one emission. This matches
the comment in `MessagesApiTest.java:130-132` and `apply-notes.md`.
Exact fix (make the aggregate the source of truth, don't capture raw emissions):
```java
Disposable subscription = new MessageAggregator()
        .aggregate(llmInvoker.stream(agent.llmModelId(), prompt, toolCallbacks), responseRef::set)
        .doOnCancel(done::countDown)
        .subscribe(chunk -> { },
                error -> { errorRef.set(error); done.countDown(); },
                done::countDown);
```
(aggregate's `doOnComplete` runs before the subscriber's `onComplete`, so `responseRef` is set before
`done.countDown()`; on cancel/error the consumer doesn't fire → `responseRef` null and the existing
`cancelled()/error()` branches handle it).
Then add assertions and drop the «pre-existing bug» waiver: `TurnEngineWireMockTest`
(`fullCycleWithToolThenFinalAnswer`, `messageDuringTurnIsSeenByExtraRound`) → final ASSISTANT
`payload.text` = stubbed value; `MessagesApiTest.sendMessageWakesTurnFasterThanPollInterval` →
`messages.get(1).payload.text == "готово"`.

### F3 [Major] Opaque page cursor loses sub-millisecond precision → skipped rows
`SessionStoreImpl.encodeCursor/decodeCursor` (`:335-352`) encode `lastActivityAt.toEpochMilli()`.
Column `session.last_activity_at` is `TIMESTAMP WITH TIME ZONE` (Postgres µs; `005_create_table_session.xml:96-100`),
and values come from `now()`/`dbNow()` (µs). The next-page predicate
`(s.lastActivityAt < :cursorActivity OR (s.lastActivityAt = :cursorActivity AND s.id < :cursorId))`
(`:251-252`) compares against a **truncated** instant, so every row sharing the cursor row's millisecond
but with larger µs is neither `<` nor `=` → silently skipped (data loss at page boundary).
Current test `SessionsApiTest.listSessionsPaginatesWithOpaqueCursorWithoutDupesOrLosses` hides it with
`Thread.sleep(10)`. Fix: carry full precision, e.g.
`raw = lastActivityAt.toString() + "|" + sessionId` / `Instant.parse(...)` (keep Base64-URL/opaque);
add a test creating ≥3 sessions inside one millisecond (e.g. direct DB seeds with identical
`last_activity_at`, distinct UUIDv7) and assert no loss/dupes.

### F4 [Minor] Jackson-2 annotation reasoning in pom/apply-notes is factually wrong
`pom.xml:164-172` and `apply-notes.md` claim Jackson-2 annotations «in main-runtime не попадает» and
are «игнорируются» Jackson 3. In fact Boot 4.1.1 uses `tools.jackson.core:jackson-databind:3.1.5`,
which by upstream design depends on `com.fasterxml.jackson.core:jackson-annotations:2.21`
(same `com.fasterxml.jackson.annotation` package) — so annotations **are on the main runtime
classpath** and **are honored** by Jackson 3 (`@JsonProperty`/`@JsonValue`/`@JsonCreator`).
No functional defect (field/enum names coincide), `provided` is harmless as a compile declaration,
but the comment and the «достаточно ли?» rationale must be corrected. The real directive (no Jackson-2
`databind` in main) holds: only `tools.jackson.databind` (main) and Jackson-2 `databind` (test, for the
generated `java/native` client) are present. Generated code uses only `org.springframework.lang.Nullable`
and `org.springframework.format.annotation.DateTimeFormat` (Spring 7 still ships both) — no Jackson-2
databind leak in generated main sources (verified by grep).

### F5 [Minor] No index supports `GET /sessions` ordering/filters
`searchSessions` (`SessionStoreImpl.java:255-256`) orders by `last_activity_at DESC, id DESC` with
optional `owner_user_id`/`kind`/`title LIKE`. No index on `last_activity_at`/`owner_user_id` exists
(migration 005 indexes only `task_id,state_code` and partial `last_seq`). Acceptable for M1 single VM,
but full scan+sort per list call; consider `(owner_user_id, last_activity_at DESC, id DESC)` when the
list volume justifies it (owner decide). `title LIKE '%…%'` is inherently non-indexable.

### F6 [Minor] SSE initial `session.status` uses stale persisted outcome
`SessionEventsController.java:114-117` builds the first frame from
`snapshot.runtimeStatus()` **and** `session.lastTurnOutcome()`, where `session` is the request-time
`findSession`. Broadcaster snapshot already carries `lastTurnOutcome` (`StatusSnapshot`, DS F5). Under
a concurrent Turn completion between `findSession` and the snapshot send the frame can pair
`IDLE` with a stale/null outcome. Use `snapshot.lastTurnOutcome()` for consistency.

### F7 [Minor] SSE emitter complete vs send race; ping scheduled after close
`ClientStream.close()` (`SessionEventsController.java:222-235`) calls `emitter.complete()` outside
`sendLock`, while `ping()`/`drain()` hold `sendLock` around `emitter.send(...)`; concurrent
`complete()`/`send()` is a race (`IllegalStateException`) that can surface as noise. Also `start()`
(`:139-141`) schedules the ping task without re-checking `closed`, so a close during backfill can leave
a scheduled ping until its first failing tick. Low risk (internal, idempotent close), but tighten:
guard schedule on `!closed` and/or call `complete()` under `sendLock`.

### F8 [Minor] `SessionDto.agent` NPE for sessions without pinned revision
`SessionsController.toDto`/`listSessions` dereference `agent.key()` where `agentSummaries` may be
missing (null `agent_revision_id`, e.g. a future STATE row). Spec makes `agent` required and M1 fixtures
always set it, so this is an edge/defensive note only.

### F9 [Info] Jackson-3 wire-format coverage is partial
Covered: enums SCREAMING_SNAKE (raw SSE JSON `IDLE`/`USER` via Jackson 3, `SessionEventsSseTest:98,101`),
nullable `title` (client round-trip, `SessionsApiTest:113-121`), `seq` int64 parsed as number, lenient
`limits.body` boundary. **Not** asserted at wire level: ISO-8601 UTC formatting of `lastActivityAt`/
`createdAt` (`ApiMappers.utc`, `ApiMappers.java:119-121`) and JSON-number type of `lastSeq`/`seq`.
Add one raw-JSON assertion on `GET /sessions` (e.g. `lastActivityAt` matches `…Z`/`+00:00`, `lastSeq`
is a JSON number) to lock the contract.

## Verified OK (no finding)
- Generator config: `interfaceOnly`, `skipDefaultInterface`, `useSpringBoot3`, `openApiNullable=false`,
  `requestMappingMode=api_interface`, `useTags`, `documentationProvider=none`; SSE excluded via
  `globalProperties.apis` (no SessionEvents API generated); test client `java/native`,
  `addCompileSourceRoot=false` + `addTestCompileSourceRoot=true`, test-scope Jackson-2 deps only.
  No `JsonNullable`/`jackson-databind-nullable` in generated main sources.
- Merge-patch: `MergePatchHttpMessageConverter` registered first (`WebMvcConfig.extendsMessageConverters`
  addFirst), only `application/merge-patch+json`; ThreadLocal captured on the request thread and cleared
  in `MergePatchBodyFilter` `finally` (`OncePerRequestFilter`) — safe for synchronous MVC; `absent`/`null`
  distinction preserved and tested (`SessionsApiTest.patchSessionMergePatchSemantics`); plain
  `application/json` on PATCH → 415 (test).
- `limit=0` cannot 500: generated `SessionsApi` has `@Min(1)` on `limit` → `HandlerMethodValidationException`
  → 422 (test `ApiErrorHandlingTest.limitBelowMinimumReturns422`). Both controllers also clamp to
  `harness.limits.page`.
- SQL: `findLatestRevisions` correlated-max JPQL, `agentSummaries` `IN`, `renameSession` 404 on 0 rows,
  LIKE escaping (`escapeLike` + `ESCAPE '\'`), `limit+1` page detection and `(lastActivityAt,id)`
  tie-break direction are correct apart from F3.
- SSE backfill: subscribe **before** store snapshot, buffer-until-backfilled, `lastSentSeq` dedup make
  the broadcaster↔store race safe (no dupes/losses) for the covered paths; `Last-Event-ID` priority,
  `retry: 5000`, ping-by-config, snapshot-first all tested with a real SSE client.
- Broadcaster DS F5: `StatusChanged`/`StatusSnapshot` carry `lastTurnOutcome`; tests updated.

## Summary

Findings: **9** (3 Major — F1, F2, F3; 5 Minor — F4…F8; 1 Info — F9). No Blocker found by this zone.
Top-3:
1. **F1** — encoding corruption in the three Docker tests (must be fixed before commit; invisible to 198/0/0).
2. **F2** — empty final ASSISTANT text: `AgentTurnEngine` must take the response from the
   `MessageAggregator` consumer, not the last raw chunk; add the text assertions.
3. **F3** — millisecond-truncated page cursor drops rows sharing a millisecond; encode full-precision
   `Instant`.

Position p.5 (spec deviation «202 no body → 406»): **конфирм, отклонение реально и принадлежит спеке,
не коду.** `compact`/`stop` генерируются с `produces="application/problem+json"` только потому, что
`202` без `content`, а единственный типизированный ответ — `problem+json`; запрос с
`Accept: application/json` получает 406 до хендлера, хотя контракт 406 для безтелых успехов не
подразумевает. Минимальная правка спеки (`api/openapi.yaml`, обе 202):
```yaml
'202':
  description: Команда принята; COMPACT-событие появится в потоке.
  content:
    application/json: {}      # добавляет application/json в produces, тела не объявляет
```
для `/sessions/{id}/compact` и `/sessions/{id}/stop`. Это добавляет `application/json` в `produces`
генератора и снимает 406, не объявляя тело (схема отсутствует → `ResponseEntity<Void>` и
native-клиент без десериализации сохраняются). После регенерации проверить: (a) сигнатуры методов
остались `ResponseEntity<Void>`; (b) тест-клиент шлёт `Accept` c `application/json`; (c) тест
`SessionCommandsApiTest` можно упростить до обычного клиента (убрать оговорку про `*/*`). Правка —
часть шага 2 contract-first и требует ре-ревью (спека заморожена).

Position p.6 (empty final ASSISTANT): **подтверждён по коду и по поведению Spring AI 2.0.1**, не
«пре-существующий неустранимый»; лечится точечно в `AgentTurnEngine.callModel` (см. F2) плюс
ассерты текста. Откладывать в долг не нужно — фикс в пачке E.

## Fixes approval

Re-approval of judge fixes (verified by files; builds not run):
- **F1 APPROVED** — mojibake marker scan = 0 in all three Docker tests; literals restored
  (`ContainerWorkspaceToolsDockerTest.java:198` = `"привет-😀-100%-\\t\\n"`), constructors carry
  `page=100` (`:244`, `WorkspaceContainerFailureDockerTest:130`, `WorkspaceContainerManagerDockerTest:155`).
- **F2 APPROVED** — `AgentTurnEngine.callModel` now `aggregate(..., responseRef::set)` with no-op
  `subscribe` onNext (`AgentTurnEngine.java:185-193`); text assertions added
  (`TurnEngineWireMockTest.java:96`, `MessagesApiTest.java:131`), waiver comment removed.
- **F3 APPROVED** — cursor now full-precision ISO (`SessionStoreImpl.encodeCursor/decodeCursor`,
  `Instant.toString()`/`Instant.parse()`), invalid cursor → checked `InvalidCursorException`
  (`SessionStore.searchSessions throws`, `SessionStoreImpl:238,346`) converted to 422 with
  pointer `/cursor` in `SessionsController.java:78-82`; tests `paginationSurvivesSameMillisecondTies`
  and `garbageCursorReturns422ValidationFailed` present. Only implementer/caller confirmed.
- **F4 APPROVED** — pom comment corrected (Jackson 3 uses same `com.fasterxml.jackson.annotation`
  annotations; provided only compile declaration).
- **F5 (SSE race) ACCEPTED CLEAN** — no code change required.

Result: **APPROVE**.

## Notes
- Не коммитил; сборки/тесты не запускал.
- Проверял generated-код из `target/generated-sources|test-sources` (7.25.0) и байткод
  spring-ai-model/openai 2.0.1, spring-core 7.0.9 (наличие `org.springframework.lang.Nullable`),
  jackson-bom 3.1.5 / Boot 4.1.1 BOM (annotations 2.21).
