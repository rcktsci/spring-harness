# Review m1-session-core — apply, пачка B, прогон m3 (после переписывания видимости) — судейский вердикт

> Прогон после правок по итогам B-m2: фиксы B-J-1…B-J-7 закрыты, диапазонная логика (`selectByIntervals`, переработанный `VisibilityRenderer`) переписана, добавлены `failedAppendRollsBackSeqReservation`, `restoreCarolName` (@AfterEach), `tokenSignedByUnknownKeyReturns401`, `wideCoverRangeRendersWithoutSeqMaterialization`.
>
> Ревью: GLM-5.3-Flash → MiniMax-M2.7 (subagent_glm_5_3_flash rate-limited; фоллбек на эту модель) · DeepSeek-V4.1-Flash · Mercury-2.5. Сборки/тесты не запускались (запрет; принят прогон разработчика 62/62).

## Findings (severity, файл:строка, дефект, предложение)

### critical — документационная

1. **D-44** — `src/main/java/se/rocketscien/harness/session/SessionStore.java:36` Javadoc ссылается на «(D-44; оригиналы по seq, не по ULID)», но `docs/design/decisions.md` заканчивается **D-43** (`decisions.md:49`). Нарушено правило владельца (AGENTS.md): «любое сущностное дизайн-решение — строкой в `docs/design/decisions.md`». Консенсус трёх ревьюеров (DS, M2.7, явная отсылка в B-J-4 первого прогона).
   **Предложение:** внести D-44 в `decisions.md` (решение → отвергнутые альтернативы → почему: covers = seq-диапазоны в пределах сессии; id/диапазоны vs seq-диапазоны; резолв `read_compacted` по seq, не по ULID) и сослаться в `SessionStore.renderVisible` Javadoc. **Действие оркестратора.**

### minor — корректность (не блокирует SQL/поведение, но инвариант сломан)

2. **`VisibilityRenderer.insertPoint` даёт дубликаты/перекрытия/нарушение порядка при смежной самовставке** — `src/main/java/se/rocketscien/harness/session/VisibilityRenderer.java:97-124`.
   - DS нашёл три контрпримера: `visible=[(1,5),(9,10)]`, `point=6` → `[(1,6),(6,6),(9,10)]`; `visible=[(1,3),(5,8),(10,12)]`, `point=4` → `[(1,4),(4,8),(4,4),(10,12)]`.
   - Mercury зафиксировал граничный кейс `point==interval.fromSeq/toSeq`.
   - M2.7 отметил misleading guard `!result.isEmpty()` (функционально корректно, вводит в заблуждение).
   - **Влияние:** объединение интервалов остаётся корректным ⇒ `selectByIntervals` с `BETWEEN…OR BETWEEN…` через СУБД даёт правильные строки (диапазоны схлопываются на выполнении). Теряется fast-path `renderVisible:94` (`size()==1`) и инвариант «дизъюнктные отсортированные интервалы». Достижимо только для COMPACT с `covers`, покрывающим ровно свой `compactSeq` — наш компоновщик M1 таких не пишет, но payload не валидируется (см. ниже).
   **Предложение:** канонизация — единый пост-проход «добавить точку и merge/дедуп»; добавить unit-кейсы `pointBetweenTwoAdjacentIntervals`, `point==interval.fromSeq`, `point==interval.toSeq`.

3. **`renderVisible` fast-path проверяет только `fromSeq()==1`, но не `toSeq()==lastSeq`** — `src/main/java/se/rocketscien/harness/session/SessionStoreImpl.java:94`.
   - DS нашёл контрпример: `compacts=[{compactSeq:2, covers:[{from:4,to:10}]}]`, `lastSeq=10` → `mergedCovers=[[4,10]]`, `selfHiddenOnlyPoints` пуст → `complement=[(1,3)]` → fast-path → возвращается **весь** журнал (`findAllBySessionId`), включая скрытые события 4–10.
   - Достижимо только при `covers` со ссылками на seq > `compactSeq` (наш компоновщик таких не пишет), но payload не валидируется.
   **Предложение:** `… && visibleIntervals.getFirst().toSeq() == session.getLastSeq()` (или fast-path только при `compacts.isEmpty()`); добавить интеграционный кейс «скрыт хвост, единственный видимый интервал [1..X]».

4. **`parseCovers`/`complement` не клампят `covers` к `[1..lastSeq]`, нет защиты от `to=Long.MAX_VALUE`** — `src/main/java/se/rocketscien/harness/session/SessionStoreImpl.java:181-201` + `VisibilityRenderer.java:47,56-68`.
   - DS: `compacts=[{1,[{1,Long.MAX_VALUE}]}]`, `lastSeq=10` → `interval[1]+1` переполняется в `Long.MIN_VALUE`, `Math.max(cursor,…)` оставляет `cursor=1` → возвращается `[(1,10)]`, **покрытие игнорируется (fail-open)**.
   - Аналогично `covers` за `lastSeq`: `compacts=[{1,[{1,5}]},{10,[{10,20}]}]`, `lastSeq=5` → добавляется `Cover(6,5)` (инвариант `from>to` сломан, в SQL безвредно).
   - DS также: «другим» в `selfHiddenOnlyPoints` считается любой COMPACT, а не только позднейший (`compactSeq > point`); спека: «покрытые `covers` **позднейших** COMPACT-событий». Контрпример: `compacts=[{2,[{1,7}]},{5,[{1,5}]}]` — компакт 5 покрыт компактом 2 (раньше), по спеке это OK, но по реализации `hiddenByOther=true` — поведение совпадает по выводу, но семантика не выдержана.
   **Предложение:** передавать `lastSeq` в `parseCovers`, клампить/дропать `from/to` вне `[1..lastSeq]`, явно обрабатывать `Long.MAX_VALUE` (либо запретить в Javadoc); в `selfHiddenOnlyPoints` фильтровать `other.compactSeq() > point` (по спеке «позднейший»).

### minor — архитектура

5. **`compactCovers` загружает все COMPACT-события сессии без `setMaxResults`** — `src/main/java/se/rocketscien/harness/session/SessionStoreImpl.java:129-149`. Mercury: если в сессии тысячи COMPACT (вне M1, но не исключено при долгоживущих STATE-сессиях), это OOM.
   **Предложение:** `setMaxResults(1000)` с WARN-логом при превышении (лимит — конфиг, D-39).

6. **`tasks.md` 4.3 описывает устаревший подход** — `openspec/changes/m1-session-core/tasks.md:28` говорит «проекция → SET покрытий → payload видимых», но реализация диапазонная (`VisibilityRenderer.visibleIntervals` + `selectByIntervals`), без материализации SET. DS nit, не код, но документационная несогласованность.
   **Предложение:** синхронизировать формулировку.

### nit

7. **`exception.getMessage()` уходит в Problem Detail** — `src/main/java/se/rocketscien/harness/identity/SecurityConfig.java:79`. Для JWT-ошибок это внутренние сообщения Nimbus/декодера — лишняя информация наружу + нестабильный контракт. DS nit, консенсус с прошлым прогоном. Константный `detail` (например, `"authentication required"`).

8. **`extractAccessToken` парсит JWT-ответ строковыми индексами** — `src/test/java/se/rocketscien/harness/identity/AppUserSyncTest.java:140-145`. Mercury: неустойчиво к экранированию кавычек. Тестовая утилита, не прод. Использовать Jackson `ObjectMapper`.

9. **Статический WireMock/Keycloak никогда не закрываются** — `src/test/java/se/rocketscien/harness/WireMockJwksInitializer.java:31-50`, `KeycloakContextInitializer.java:18-30`. DS/Mercury: тестовая гигиена, утечка при параллельных Surefire-прогонах. `@AfterAll`/shutdown-hook.

## Проверено и валидно (консенсус 3/3)

- **Row-lock ↔ INSERT атомарность:** `SessionStoreImpl.reserveSeqByRowLock:109-124` (`UPDATE session SET last_seq=last_seq+1 … RETURNING`) под class-level `@Transactional`; `renderVisible` — `@Transactional(readOnly=true)` (`:83`). Атомарность доказана `SessionAppendTest.failedAppendRollsBackSeqReservation:101-134` (после отката `last_seq=0`, `count=0`, след. seq=1). Конкурентный тест 2×25: `last_seq=50` без дыр/дублей. `last_consumed_seq` дописью не двигается. Контракт D-M1-4 (row-lock, без sess-лока) выдержан, зафиксирован в `SessionStore` Javadoc.
- **Entity ↔ миграция:** `SessionEntity`/`SessionMessageEntity`/`AgentRevisionEntity` совпадают по колонкам/типам с `2026-09-17__create_schema__m1_core.xml`; PK `(session_id, seq)` через `@EmbeddedId`, ULID `id TEXT UNIQUE`, `TIMESTAMP WITH TIME ZONE`↔`Instant`, `JSONB`↔`@JdbcTypeCode(SqlTypes.JSON)`+`columnDefinition="jsonb"`, `VARCHAR(16)`↔`@Enumerated(STRING) length=16`; `preConditions onFail="MARK_RAN"` на всех changeSet; индексы и CHECK'и (`ck_session__kind`, `ck_session__last_turn_outcome`, `ck_session_message__kind`) на месте.
- **Upsert app_user:** `UserSyncFilter.java:53-66` — условный `ON CONFLICT … DO UPDATE … WHERE … IS DISTINCT FROM EXCLUDED.*` корректный PG-идиом (M2.7 подтверждает), гонка первого запроса безопасна, `created_at` не перетирается.
- **Security filters:** `BearerTokenAuthenticationFilter → GroupsGateFilter → UserSyncFilter → AuthorizationFilter`; `@Validated @NotEmpty allowedGroups` (SecurityProperties:17) — fail-closed на старте, NPE в `GroupsGateFilter:48` исключён; `UserSyncFilter` строго после гейта (sync не запускается для отклонённых — `AppUserSyncTest.userOutsideGroupsGets401AndIsNotSynced`); `GroupsNotAllowedException` — `AuthenticationException`, обрабатывается entry point-ом без `WWW-Authenticate` (подтверждено 7/7 `JwtSecurityTest` + `tokenSignedByUnknownKeyReturns401`).
- **Видимость — корректные сценарии:** все 10 unit-тестов `VisibilityRendererTest` мысленно проходят; включительные границы, COMPACT не скрывает себя, позднейший COMPACT скрывает предыдущий, широкий cover не материализует seq (`wideCoverRangeIsIntervalNotPerSeqMaterialization`), `complement`/`mergedCovers` корректны при штатных данных.
- **Изоляция тестов от прода:** `TestPingController`, `KeycloakContextInitializer`, `WireMockJwksInitializer`, `harness-realm.json`, `application-test.yml` — только `src/test`; прод `application.yml` не содержит тестовых URL/секретов; профили `jwtmock`/`keycloak` активируются лишь в тестах.
- **D-39 (числа — только конфиг):** в прод-коде пачки B хардкода tunable-чисел нет (только доменные границы 0/1).
- **Прошлые находки закрыты:** B-J-1 (условный UPSERT), B-J-2 (`@NotEmpty` валидация), B-J-3 (`selectByIntervals`/`visibleIntervals`), B-J-4 (D-44 — висячая ссылка, но не внесён), B-J-5 (`failedAppendRollsBackSeqReservation`), B-J-6 (`restoreCarolName` `@AfterEach`), B-J-7 (прочее).

## Консенсус ревьюеров

| Тема | DS | M2.7 | Mercury | Консенсус |
|---|---|---|---|---|
| D-44 отсутствует | ✗ | ✗ | (не отметил, но косвенно через «запись за оркестратором») | **3/3** → оркестратор |
| `insertPoint` дыры | ✗ | ✗ (misleading guard) | ✗ (граничные кейсы) | **3/3** → minor |
| `fast-path` renderVisible | ✗ | — | — | 1/3 → minor |
| `parseCovers` clamp | ✗ | — | — | 1/3 → minor |
| `compactCovers` без пагинации | — | — | ✗ | 1/3 → minor |
| `extractAccessToken` хрупкое | — | — | ✗ | 1/3 → nit |
| `tasks.md` 4.3 устарел | ✗ | — | — | 1/3 → minor |
| `exception.getMessage()` в detail | ✗ | — | — | 1/3 → nit |
| WireMock/Keycloak статика | ✗ | — | ✗ | 2/3 → nit |
| Row-lock-атомарность | ✓ | ✓ | ✓ | 3/3 |
| Миграция ↔ entity | ✓ | ✓ | ✓ | 3/3 |
| Upsert `IS DISTINCT FROM` | ✓ | ✓ | ✓ | 3/3 |
| Security filters | ✓ | ✓ | ✓ | 3/3 |
| Видимость (штатная) | ✓ | ✓ | ✓ | 3/3 |
| Тестовое покрытие спек | ✓ | ✓ | ✓ | 3/3 |

## Summary

**Находки:** 1 critical (документационная, за оркестратором) · 4 minor (корректность/архитектура) · 4 nit. Блокеров и мажоров в прод-коде нет.

**Консенсус 3/3:**
1. **D-44** — Javadoc `SessionStore.java:36` ссылается на несуществующий ADR; правило владельца AGENTS.md → **оркестратор** вносит решение в `decisions.md` (покрывает B-J-4 из B-m2).
2. **`VisibilityRenderer.insertPoint` логические дыры** — функционально SQL через `BETWEEN…OR BETWEEN…` возвращает верные строки, но инвариант дизъюнктности/сортировки сломан, теряется fast-path. Достижимо только для вырожденных `covers` (свой `compactSeq` в своём cover) — наш компоновщик M1 таких не пишет, но payload не валидируется. Желательная канонизация + unit-кейсы.
3. **`parseCovers`/`renderVisible` fast-path без клампа `covers`** — fail-open при `to=Long.MAX_VALUE` или covers за `[1..lastSeq]`. Достижимо только при нештатных payload'ах. Желательно передать `lastSeq` в `parseCovers` и добавить гвард в fast-path.

**Вердикт:** APPROVE с одним условием — оркестратор вносит D-44 в `decisions.md` (административное). Технические minor/nit (insertPoint, fast-path, parseCovers clamp, compactCovers пагинация, extractAccessToken) — на усмотрение разработчика как канонизация/гигиена; ни одна из них не меняет фактическое поведение при корректных данных нашего компоновщика M1 и не задевает сценариев спек sso-gate/session-store. Прогон разработчика 62/62 зелёных сохраняется.

## Final approval

Прогон m4 после правок фикс-цикла. Проверено по `VisibilityRenderer.java`, `SessionStoreImpl.java`, `VisibilityRendererTest.java`, `docs/design/decisions.md`:

- **D-44 внесён** в `decisions.md:50` — есть решение / альтернативы / обоснование; Javadoc `SessionStore.java:36` более не висячая ссылка.
- **Клампинг `covers` по `lastSeq` без overflow** — `VisibilityRenderer.visibleIntervals:30-41`: `Math.max(1, cover.fromSeq())` + `Math.min(cover.toSeq(), lastSeq)` + фильтр `from <= to` после клампа. `Long.MAX_VALUE` безопасно становится `lastSeq`, оверлоупа нет; хардкода чисел нет (D-39).
- **Fast-path усилен** — `SessionStoreImpl.renderVisible:94-96`: условие `size==1 && fromSeq==1 && toSeq==lastSeq` (был только `fromSeq==1`); fail-open сценарий из ревью закрыт.
- **`insertPoint` канонизирован** — `VisibilityRenderer.java:108-143`: проверка `result.getLast().toSeq()+1 >= interval.fromSeq` в обеих смежных ветках, явная ветка `point внутри interval` отбрасывает дубль; дизъюнктность/сортировка сохраняются.
- **4 новых теста в `VisibilityRendererTest.java:127-176`**: `selfInsertionAdjacentIntervalsMergedDisjoint`, `coversWithLongMaxValueClampedWithoutOverflow`, `coversBeyondJournalClampedNoFailOpen`, `mergedIntervalsStrictlyDisjointAndSorted`. Покрывают все три топ-находки m3.

Условие аппрува выполнено, технические minor/nit (insertPoint, fast-path, parseCovers clamp) закрыты в коде. Прогон разработчика 66/66 зелёных.

**Вердикт: APPROVE.** Пачка B готова к вливанию.
