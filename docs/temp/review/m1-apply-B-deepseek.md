# Review: m1-session-core — apply, пачка B (задачи 3.1–3.3, 4.1–4.3) — DeepSeek-V4.1-Flash

Объект: изменения после коммита `8d6c92d` — `identity/` (SecurityConfig, GroupsGateFilter, UserSyncFilter, GroupsNotAllowedException), расширение `session/` (SessionStore/Impl, Session/SessionEntity/SessionKind/TurnOutcome, AgentRevision*, VisibilityRenderer, исключения), тесты `identity/`, `session/`, `api/TestPingController`, `application-test.yml`, `harness-realm.json`, `SecurityProperties`, `application.yml`.
Эталоны: `specs/sso-gate/spec.md`, `specs/session-store/spec.md`, `data-model.md` §1/§5, `api-contracts.md` §0, `design.md` D-M1-4.
Сборки/тесты не запускались (запрет для ревьюеров). Использованы артефакты прогона разработчика: `target/surefire-reports` — JwtSecurityTest 7/7, AppUserSyncTest 3/3, SessionAppendTest 2/2, SessionStoreCreateTest 4/4, SessionRenderVisibilityTest, VisibilityRendererTest, MigrationFromScratchTest — без fail/error.

## Findings

1. **major** — `session/SessionStoreImpl.java:95-138` (`coveredSeqs`) + `session/VisibilityRenderer.java:20-32`, вызов `SessionStoreImpl.java:81-92`.
   Дефект: множество покрытых реализуется поэлементно (`for seq = from..to → add`) и целиком передаётся в JPQL `sm.id.seq NOT IN :covered` (`:86`). Для COMPACT, покрывающего большое окно (сессии бессрочны, retention не чистится — data-model §5), это O(range) heap и `IN`-список, который упрётся в лимит bind-параметров PostgreSQL (~65535) → рантайм-падение `renderVisible`, а не деградация. Дизайн (execution-model §5) действительно предполагает «SET покрытий», но хранить его как множество всех seq — самая дорогая интерпретация.
   Предложение: не материализовать диапазон — либо anti-join по диапазонам (`NOT EXISTS`/`NOT (seq BETWEEN ...)` для каждого cover), либо брать журнал одним запросом и фильтровать в памяти по диапазонам; тест на COMPACT с широким cover.

2. **minor** — `identity/UserSyncFilter.java:53-64`.
   Дефект: upsert выполняется на **каждом** аутентифицированном запросе безусловно — `ON CONFLICT ... DO UPDATE SET username, display_name` без сравнения. Даже при неизменённых данных Postgres пишет новую версию строки `app_user` (dead tuples, WAL, row-lock) на каждый `/api/v1/**`-запрос. Спека sso-gate требует обновление лишь «при изменении».
   Предложение: добавить хвост `WHERE app_user.username IS DISTINCT FROM EXCLUDED.username OR app_user.display_name IS DISTINCT FROM EXCLUDED.display_name` (тогда no-op запросы не пишут).

3. **minor** — `session/SessionStore.java:32-38` и `session/SessionStoreImpl.java:121-138` vs `data-model.md:164`, `glossary.md:58`, `api-contracts.md` (`read_compacted`).
   Дефект: формат `covers` сужен до seq-диапазонов `{"from","to"}`. data-model/glossary разрешают «id/диапазоны», а `read_compacted` в agent-tools/api оперирует `compact_message_id` и id сообщений (у `session_message` есть отдельный ULID `id`). Javadoc контракта формат фиксирует (плюс), но id↔seq-развилка не записана как дизайн-решение (правило владельца) и создаст трения в M3.
   Предложение: строка в `decisions.md` «covers = seq-диапазоны в пределах сессии» + явно оговорить, как `read_compacted` резолвит оригиналы (по seq, не по ULID).

4. **minor** — `session/SessionStoreImpl.java:57-73`, `src/test/.../session/SessionAppendTest.java:38-86`.
   Дефект: конкурентный тест доказывает бездырочность/отсутствие дублей `seq`, но **не** доказывает атомарность связки «резерв `last_seq` (row-lock UPDATE) ↔ INSERT события»: при откате после UPDATE ни один тест не проверяет, что `last_seq` не уехал (появилась бы дыра). Смешение `JdbcTemplate` + `EntityManager` держится на том, что `JpaTransactionManager`/`HibernateJpaDialect` отдаёт тот же JDBC-коннект (это так, иначе тесты бы разошлись на блокировках), но контракт «транзакционно» не зафиксирован тестом.
   Предложение: тест на откат (в одной транзакции выполнить допись и намеренно бросить исключение до коммита → во второй транзакции `last_seq` не изменился, события нет).

5. **minor** — `session/SessionStoreImpl.java:16-17,76-93`.
   Дефект: класса-level `@Transactional` делает `renderVisible` (чистое чтение) записывающей транзакцией; на PostgreSQL это лишний read-write tx + удержание snapshot.
   Предложение: `@Transactional(readOnly = true)` на читающих методах (или разнести write/read).

6. **minor** — `src/test/.../identity/AppUserSyncTest.java:72-91` vs `:38-53`.
   Дефект: тест мутирует **общий** Keycloak-контейнер (Alice `firstName` → "Alicia") и не восстанавливает состояние; `firstRequestOfNewUserCreatesAppUser` ассертит `display_name = "Alice Smith"`. Порядок методов не гарантирован JUnit-ом — в прогоне `displayNameChange...` шёл последним (подтверждено surefire-XML), поэтому прошло; при переупорядочивании/перезапуске в том же контейнере — падение.
   Предложение: восстанавливать данные в `finally`/`@AfterEach` либо использовать отдельного пользователя под тест смены имени.

7. **minor** — `src/test/.../identity/JwtSecurityTest.java:38-108`, `AppUserSyncTest.java`.
   Дефект: неполное покрытие требований sso-gate — нет кейса «валидный формат, но подпись не сходится с JWKS» (есть только garbage/expired/foreign-issuer), и в синхронизации пользователей проверяется лишь смена `display_name`, тогда как спека требует обновление и `username`.
   Предложение: добавить токен, подписанный другим ключом (401), и тест смены `username`.

8. **nit** — `session/AgentRevisionRepository.java:11`.
   Дефект: наследование `JpaRepository` на `@Immutable`-сущности (`agent`, data-model §2 «UPDATE запрещён») экспонирует `save`/`delete`, т.е. возможность мутировать ревизии из кода.
   Предложение: узкий read-only-интерфейс (`Repository` + методы поиска) вместо полного `JpaRepository`.

9. **nit** — `session/Session.java`.
   Дефект: проекция не содержит `lastActivityAt`/`createdAt`, которые обязательны в `SessionDto` (api-contracts §2); при сборке API (пачка 8) контракт `SessionStore` придётся расширять.
   Предложение: либо добавить поля в `Session` сейчас (дешево), либо зафиксировать это как известную точку роста.

10. **nit** — `session/SessionStoreImpl.java:48-49` vs `:148`.
    Дефект: `created_at/last_activity_at` при создании берутся из Java `Instant.now()`, а при дописи — из БД `now()`; расхождение источника времени (часы приложения vs сервера БД).
    Предложение: единый источник (оба — БД).

11. **nit** — `identity/SecurityConfig.java:76`.
    Дефект: в `detail` Problem Details кладётся `exception.getMessage()` (для JWT-ошибок это текст парсера/декодера) — потенциальная утечка деталей и нестабильный контракт.
    Предложение: статичный текст `detail` («authentication required» и т.п.), `code` — уже есть.

12. **nit** — `session/SessionStoreImpl.java:121-138` (`parseCovers`).
    Дефект: malformed/отсутствующий `covers` молча трактуется как пустой (fail-open) — при порче payload скрытое «разворачивается» наружу без следа.
    Предложение: логировать WARN/кидать ошибку на невалидный payload COMPACT.

13. **nit** — `src/test/.../WireMockJwksInitializer.java:31-50`, `KeycloakContextInitializer.java:18-30`.
    Дефект: статический WireMock-сервер стартует в static-инициализаторе и никогда не останавливается; Keycloak-контейнер статичен (полагается на Ryuk). Тестовая гигиена, не влияет на прод.
    Предложение: `@AfterAll`/shutdown-hook для WireMock.

### Проверено и валидно (без замечаний)

- **Row-lock / монотонный seq (зона 1)**: `UPDATE session SET last_seq = last_seq + 1, last_activity_at = now() WHERE id = ? RETURNING last_seq` (`SessionStoreImpl.java:144-159`) под `@Transactional`: вторая конкурентная допись блокируется на строке и после коммита первой на READ COMMITTED перечитывает `last_seq` (EvalPlanQual) → без дыр/дублей (подтверждено `SessionAppendTest`: 2×25, `last_seq=50`). `last_consumed_seq` дописью не трогается — верно. Несуществующая сессия → `SessionNotFound` до INSERT. Атомарность UPDATE+INSERT в одной tx обеспечивается `JpaTransactionManager`/`HibernateJpaDialect` (тот же коннект) — см. находку 4 (нужен тест на откат).
- **JPA-маппинг против миграций пачки A (зона 2)**: `session_message.id` = `TEXT` ↔ `String` без `length`; `payload_jsonb`/`*_jsonb` = `@JdbcTypeCode(SqlTypes.JSON)`; `INSTANT` ↔ `TIMESTAMP WITH TIME ZONE`; enum'ы (`MessageKind`, `SessionKind`, `TurnOutcome`) — `@Enumerated(STRING)` + `VARCHAR(16)` (совпадает с CHECK'ами миграции); `SessionMessageEntity`/`AgentRevisionEntity` — `@Immutable`, `SessionEntity` — нет (её строки мутируются) — корректно; PK `(session_id, seq)` через `@EmbeddedId`.
- **Upsert app_user (зона 3)**: гонка двух первых запросов безопасна (`ON CONFLICT (keycloak_subject) DO UPDATE`, loser'ский `id` отбрасывается), `created_at` не перетирается; `sub`/`username`/`name` из JWT. Замечания — только по churn (находка 2).
- **Параметризация (зона 4)**: весь SQL — `?`/named-параметры (`UserSyncFilter`, row-lock, `renderVisible`, `coveredSeqs`); конкатенации пользовательских данных нет — инъекций нет.
- **Фильтры (зона 5)**: оба фильтра — не бины (`new`), добавлены в security-цепочку (двойной servlet-регистрации нет); порядок Bearer → GroupsGate → UserSync (reflection тест `tokenOutsideAllowedGroups`/`withoutGroups` = 401, `bob` не синхронизируется; `UserSyncFilter` до `AuthorizationFilter`). Валидный токен без группы → 401 (групповой гейт), без токена/битый/просроченный/чужой issuer → 401 без `WWW-Authenticate` (проверено тестами).
- **covers/видимость (зона 6)**: включительные границы, COMPACT не скрывает себя, более поздний COMPACT скрывает предыдущий, пустой кандидат — покрыты unit+интеграционными тестами (`VisibilityRendererTest`, `SessionRenderVisibilityTest`). Формат зафиксирован в Javadoc (см. находку 3).
- **Тестовая изоляция от прода (зона 7)**: `KeycloakContextInitializer`/`WireMockJwksInitializer`/`TestPingController`/`harness-realm.json`/`application-test.yml` — только `src/test`; профильные значения (`jwtmock`/`keycloak`) активируются лишь в тестах. Прод-`application.yml` не содержит тестовых URL/секретов. JwtDecoder ленив (JWKS-фетч при первой валидации), поэтому старт без Keycloak возможен.

## Summary

Пачка B — **без блокеров**; 1 major, 6 minor, 6 nit. Все заявленные сценарии sso-gate и session-store (создание с пином ревизии, `404 agent-not-found`, append-only + монотонный seq, гейт и синхронизация пользователей, видимость через COMPACT) реализованы и покрыты тестами; прогон разработчика зелёный (JwtSecurityTest 7/7, AppUserSyncTest 3/3, Session* 6/6). Топ-3: (1) `coveredSeqs` материализует диапазон покрытий и передаёт его в `NOT IN` — O(range) heap и упирание в лимит bind-параметров PG при широком COMPACT (major); (2) безусловный `ON CONFLICT DO UPDATE` в `UserSyncFilter` пишет `app_user` на каждый запрос — churn/локи на горячей строке (minor); (3) формат `covers` сужен до seq-диапазонов против «id/диапазоны» в data-model/glossary и id-ориентированного `read_compacted` — не записано в decisions.md (minor). Особо отмечу как корректное: row-lock + `RETURNING` под `@Transactional` даёт бездырочный seq при конкуренции; полная параметризация SQL; изоляция Keycloak/WireMock от прод-конфига. Уязвимость тестов: `AppUserSyncTest` мутирует общий Keycloak без восстановления (порядкозависимость).

## Re-review (fixes)

Объект: пачка B с судейскими фиксами (`docs/temp/review/m1-apply-B-judge.md`). Сборки не запускались (принят `mvn clean verify` разработчика — 62/62).

### Вердикты по условиям аппрува B-J

| Условие | Вердикт | Факт |
|---|---|---|
| **B-J-1** upsert без write-amplification | **approve** | `UserSyncFilter.java:57-60` добавлен `WHERE app_user.username IS DISTINCT FROM EXCLUDED.username OR app_user.display_name IS DISTINCT FROM EXCLUDED.display_name` — no-op запросы строку не трогают, гонка первого запроса по-прежнему безопасна. |
| **B-J-2** `allowedGroups=null` → NPE в запросе | **approve** | `SecurityProperties.java:14-17` — `@Validated` + `@NotEmpty List<String> allowedGroups`: пусто/отсутствие → падение бина на старте (fail-closed), а не NPE в `GroupsGateFilter`. |
| **B-J-3** материализация Set всех seq + `NOT IN` | **approve** | `VisibilityRenderer.visibleIntervals` (диапазонный комплемент + merge, `VisibilityRenderer.java:26-69`) + `SessionStoreImpl.selectByIntervals` (`:155-175`) строят `seq BETWEEN :fromN AND :toN`-предикаты: **ни `Set` всех seq, ни `NOT IN`-списка больше нет**; число параметров — 2 на интервал. `coveredSeqs` удалён. Тест `wideCoverRangeRendersWithoutSeqMaterialization` (cover 1..1_000_000 → виден только seq 3) и unit `wideCoverRangeIsIntervalNotPerSeqMaterialization`. Замечание (не гейтит): см. minor-1 ниже. |
| **B-J-4** формат `covers` (seq-диапазоны) | **approve с условием** | Код/Javadoc готовы: `SessionStore.java:34-37` ссылается на D-44. **Но `D-44` в `docs/design/decisions.md` отсутствует** (файл заканчивается D-43) — запись обещана оркестратору; до её внесения ссылка в Javadoc висячая. Не блокер (текст решения — за оркестратором). |
| **B-J-5** тест атомарности «резерв seq ↔ INSERT» | **approve** | `SessionAppendTest.failedAppendRollsBackSeqReservation:101-134` в одной `TransactionTemplate`-транзакции делает `appendEvent` и бросает исключение → ассертит `last_seq=0`, `count=0` и следующий seq=1. Тест содержателен: если бы `JdbcTemplate`-UPDATE шёл по другому коннекту (autocommit), `last_seq` остался бы 1 и ассерт упал бы — т.е. проверяется именно участие обоих в одной транзакции `JpaTransactionManager`. Откат-резерв подтверждён. |
| **B-J-6** изоляция `AppUserSyncTest` | **approve** | Смена имени вынесена на отдельного `carol` (`harness-realm.json:106-119`), `@AfterEach restoreCarolName():41-57` возвращает `Caroline`→`Carol`; `firstRequest...` работает с `alice` и больше не зависит от порядка. `alice`/`bob` не мутируются. |
| **B-J-7** прочие миноры | частично | #9 (SessionDto-поля), #10 (единый источник времени), #12 (fail-open covers) закрыты; #7, #8, #11, #13 остаются (см. таблицу ниже). |

### Статус моих прежних находок

| # | Было | Статус |
|---|---|---|
| 1 | major: `coveredSeqs` Set + `NOT IN` | **fixed** (B-J-3) |
| 2 | minor: безусловный upsert | **fixed** (B-J-1) |
| 3 | minor: сужение `covers` не записано решением | **частично** — Javadoc есть, `D-44` в decisions.md нет |
| 4 | minor: нет теста атомарности резерва seq | **fixed** (B-J-5) |
| 5 | minor: `renderVisible` в write-tx | **fixed** — `@Transactional(readOnly = true)` (`SessionStoreImpl.java:83`) |
| 6 | minor: `AppUserSyncTest` мутирует общий Keycloak | **fixed** (B-J-6) |
| 7 | minor: нет тестов invalid-signature JWT и смены `username` | **открыто** (кейсов не добавилось) |
| 8 | nit: `AgentRevisionRepository extends JpaRepository` на `@Immutable` | **открыто** |
| 9 | nit: `Session` без `lastActivityAt/createdAt` | **fixed** (`Session.java:24-25`) |
| 10 | nit: разный источник времени (Java vs БД) | **fixed** — `dbNow()` (`SessionStoreImpl.java:48,203-205`) |
| 11 | nit: `detail = exception.getMessage()` | **открыто** (`SecurityConfig.java:79`) |
| 12 | nit: `parseCovers` fail-open | **fixed** — WARN + проверка `from<=to` (`SessionStoreImpl.java:181-201`) |
| 13 | nit: WireMock static server не останавливается | **открыто** |

### Оставшиеся major/blocker

**Нет.** Ни одного блокера/майора в текущем состоянии кода не осталось; ключевые условия B-J-1…B-J-6 закрыты (B-J-4 — кроме записи D-44, которая за оркестратором).

### Замечено при верификации B-J-3 (minor, не гейтит)

- **minor-1** `VisibilityRenderer.insertPoint` (`VisibilityRenderer.java:97-124`) при вставке self-точки, соседней с видимыми интервалами **с двух сторон**, отдаёт перекрывающиеся интервалы: для одного COMPACT с `covers=[{3,3}]` и `lastSeq=5` → `visibleIntervals = [(1,3),(3,5)]` вместо `[(1,5)]`. На результат `renderVisible` это не влияет (SQL `BETWEEN 1 AND 3 OR BETWEEN 3 AND 5` даёт то же множество без дублей), но нарушает инвариант «дизъюнктные отсортированные интервалы» и ломает fast-path `size()==1`; если интервалы позже пойдут в счётчики/SSE — риск двойного учёта. Тесты случай не покрывают. Фикс: условие слияния `result.getLast().toSeq() >= point` (или слияние/дедуп после вставки).
- **minor-2** `renderVisible` fast-path (`SessionStoreImpl.java:94`) проверяет только `size()==1 && fromSeq()==1`. Если всё скрыто, а COMPACT стоит на seq 1 (`visibleIntervals=[(1,1)]`, `lastSeq>1`), fast-path вернёт **весь** журнал `findAllBySessionId`, включая скрытые. Сценарий экзотический (COMPACT первым событием с широким cover), но условие стоит усилить до `toSeq()==session.getLastSeq()` (или fast-path при пустых `compacts`).

## Final approval

Объект: финальное состояние пачки B после закрытия minor-1/minor-2 и B-J-4+nits. Сборки не запускались (принят `mvn clean verify` разработчика — 66/66).

| Пункт | Вердикт | Факт |
|---|---|---|
| **minor-1** `insertPoint` / смежные интервалы | **approve** | `VisibilityRenderer.java:108-143` переписан; кейс «self-точка рядом с интервалами» — `selfInsertionAdjacentIntervalsMergedDisjoint` (`covers=[{3,3}], lastSeq=5 → [(1,5)]`), ранее дававший `[(1,3),(3,5)]`. `mergedIntervalsStrictlyDisjointAndSorted` фиксирует канонический вид. |
| **minor-2** fast-path `renderVisible` | **approve** | `SessionStoreImpl.java:94-96` — `size()==1 && fromSeq()==1 && toSeq()==lastSeq`; ложный fast-path «всё скрыто + COMPACT на seq 1» исключён. |
| Клампинг covers | **approve** | `VisibilityRenderer.java:30-41`: `from=max(1,from)`, `to=min(to,lastSeq)`, пустые (`from>to`) отбрасываются **до** `mergedCovers` — `Long.MAX_VALUE` не переполняет (`+1` в merge/complement безопасен), fail-open исключён. Тесты `coversWithLongMaxValueClampedWithoutOverflow`, `coversBeyondJournalClampedNoFailOpen`. Хардкода `Long.MAX_VALUE - 100` в коде не осталось (grep чист). |
| **B-J-4** D-44 + согласованность Javadoc | **approve** | `docs/design/decisions.md` содержит запись **D-44** (covers = seq-диапазоны `[{from,to}]` в пределах сессии; id↔seq явно, оригиналы по seq), `SessionStore.java:34-37` ссылается на D-44 и формулирует то же — расхождений нет. |

**Итог: approve.** Все условия (B-J-1…B-J-6, minor-1/2, D-44) закрыты фактически, блокеров/майоров нет.

Остаточное (не гейтит, не функционально): `selfHiddenOnlyPoints` (`VisibilityRenderer.java:86-106`) возвращает `HashSet`, поэтому при **нескольких** self-точках порядок вставки недетерминирован, и `insertPoint` в редком случае (две self-точки, вставка соседней с интервалами с двух сторон) может выдать неканонические (`(1,3),(2,2),(5,5)`) интервалы: множество видимых seq при этом сохраняется (SQL `OR` BETWEEN — множество), дублей строк нет; но инвариант строгой дизъюнктности для multi-self гарантирован не полностью. Рекомендация (на будущее, вне пачки B): детерминированный порядок (sorted) или пост-нормализация списка интервалов.
