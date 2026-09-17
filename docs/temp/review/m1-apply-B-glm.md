# Ревью пачки B (задачи 3.1–3.3, 4.1–4.3) m1-session-core — GLM-5.3-Flash

Дата: 2026-09-17. Объект: изменения с 8d6c92d — `identity/` (SecurityConfig, GroupsGateFilter, UserSyncFilter, GroupsNotAllowedException), расширение `session/` (SessionStore/Impl, SessionEntity/Session, AgentRevision*, VisibilityRenderer), тесты (JwtSecurityTest, AppUserSyncTest, SessionStoreCreateTest, SessionAppendTest, SessionRenderVisibilityTest, VisibilityRendererTest), инфраструктура (KeycloakContextInitializer, WireMockJwksInitializer, harness-realm.json, TestPingController), application.yml, application-test.yml, SecurityProperties.
Эталоны: specs/sso-gate, specs/session-store (по сценариям), data-model §1/§5, api-contracts §0/§2, design.md D-M1-4.
Сборки/тесты не запускались (запрещено); все выводы — статический анализ.

## Findings

### blocker

**B-1. GroupsGateFilter стоит в цепочке ДО ExceptionTranslationFilter — отказ гейта не превращается в `401 ProblemDetails`** — `src/main/java/se/rocketscien/harness/identity/SecurityConfig.java:55`.
`addFilterAfter(GroupsGateFilter, BearerTokenAuthenticationFilter.class)` ставит гейт на позицию сразу после Bearer-фильтра (~1200), а `exceptionHandling()`-entry point работает только для исключений, брошенных НИЖЕ ExceptionTranslationFilter (~2000). `GroupsNotAllowedException` (AuthenticationException, GroupsGateFilter.java:41) бросается выше ET — перехватывается не entry point-ом, а контейнером: ожидаемо **500** со стандартной Boot-ошибкой вместо `401 unauthenticated` (Problem Details, без challenge).
Затронуты: сценарий спеки sso-gate «пользователь вне разрешённых групп» (spec.md:27-30) и контракт api-contracts §0.2/§6 (`401`/`unauthenticated`); тесты `JwtSecurityTest.tokenWithoutGroupsClaimReturns401` (:81-88), `tokenOutsideAllowedGroupsReturns401` (:91-98), `AppUserSyncTest.userOutsideGroupsGets401AndIsNotSynced` (:56-69) по этому анализу падают. Сценарии без/кривого/просроченного/чужого-issuer токена НЕ задеты — они идут через штатный failure-handler Bearer-фильтра → entry point → 401.
Оговорка: анализ статический; если у разработчика прогон был зелёным — воспроизвести curl-ом валидный JWT с чужой группой и посмотреть фактический статус. Фикс в обоих мирах один: `addFilterAfter(GroupsGateFilter, ExceptionTranslationFilter.class)` (каноничная позиция кастомного гейт-фильтра, бросающего AuthenticationException); порядок Bearer → ET → Gate → UserSync → Authorization сохраняется, UserSync остаётся строго после гейта.

### minor

**B-2. UPSERT пользователя выполняется на КАЖДЫЙ запрос, безусловно** — `src/main/java/se/rocketscien/harness/identity/UserSyncFilter.java:53-64`.
Спека формулирует «при изменении username/display_name … SHALL обновлять» (sso-gate spec.md:39); реализация пишет UPDATE на каждый запрос даже без изменений — write amplification, HOT-раздувание app_user, лишняя запись на каждый API-вызов (при M1-объёмах терпимо, но это фильтр на горячем пути всех запросов).
Предложение: условный UPSERT — `DO UPDATE … WHERE app_user.username IS DISTINCT FROM EXCLUDED.username OR app_user.display_name IS DISTINCT FROM EXCLUDED.display_name` (один оператор, изменений в контракте нет).

**B-3. NPE в security-цепочке при незаданном `allowed-groups`** — `src/main/java/se/rocketscien/harness/identity/GroupsGateFilter.java:48` + `config/SecurityProperties.java`.
`Collections.disjoint(groups, securityProperties.allowedGroups())` падает NPE, если ключ не сконфигурирован (record без валидации) — а это фильтр допуска: NPE → 500 на каждый запрос. Сегодня ключ есть в yml, но security-цепочка должна быть fail-closed по построению.
Предложение: `@NotEmpty List<String> allowedGroups` + `@Validated` на record (биндинг упадёт на старте с внятной ошибкой).

### nit

**B-4. `exception.getMessage()` уходит в detail ProblemDetail** — `SecurityConfig.java:76`. Для декодер-ошибок это внутренние сообщения Nimbus («An error occurred while attempting to decode the Jwt: …») — лишняя информация наружу; фиксировать detail константой (например, «unauthenticated») или неспецифичным текстом.

**B-5. Кривые элементы `covers` молча отбрасываются (fail-open)** — `SessionStoreImpl.parseCovers` (:126-129): битый payload COMPACT делает события видимыми без следа в логах. Данные пишет только наш код, но семантику стоит зафиксировать в Javadoc (lenient) либо логировать отброшенное.

**B-6. `Cover(from, to=Long.MAX_VALUE)` → квази-бесконечный цикл** — `VisibilityRenderer.java:24`. Защита от мусорных диапазонов отсутствует; сейчас данные только свои, при появлении LLM-генерённых covers (9.1) добавить clamp/санити-чек (например, `to - from <= cap` из конфига).

**B-7. `createSession()`-хелпер скопирован в три тест-класса** — `SessionAppendTest.java:98-124`, `SessionRenderVisibilityTest.java:78-104`, частично `SessionStoreCreateTest.java:85-137`. Корпоративные правила рекомендуют общие фикстуры; к пачке 7.x/10.x консолидировать (заодно и DatabaseCleaner из N-4 пачки A).

### Сценарии спек — покрытие (требование 1)

| Спека / сценарий | Тест в пачке B | Статус |
|---|---|---|
| sso-gate «запрос без токена» | JwtSecurityTest.noTokenReturns401ProblemDetailsWithoutChallenge | ✓ (идёт через Bearer-failure → entry point) |
| sso-gate «токен с чужого issuer» | JwtSecurityTest.foreignIssuerTokenReturns401 | ✓ |
| sso-gate «вне разрешённых групп» | JwtSecurityTest.tokenOutsideAllowedGroupsReturns401 + AppUserSyncTest.userOutsideGroupsGets401AndIsNotSynced | Тест есть; поведение под вопросом — B-1 (ожидаемо красный) |
| sso-gate «в разрешённой группе» | JwtSecurityTest.tokenInAllowedGroupGetsFullAccess + alice-тесты | ✓ |
| sso-gate «первый запрос нового пользователя» | AppUserSyncTest.firstRequestOfNewUserCreatesAppUser | ✓ |
| sso-gate «смена display_name» | AppUserSyncTest.displayNameChangeInTokenUpdatesAppUser (реальная смена firstName в Keycloak) | ✓ |
| session-store «создание с последней ревизией» | SessionStoreCreateTest.createFreeSessionPinsLatestAgentRevision (201/Location — уровень API, пачка 8.x) | ✓ на уровне стора |
| session-store «неизвестный агент» | SessionStoreCreateTest.unknownAgentKeyFails / unknownAgentRevisionFails (404-код — API, 8.x) | ✓ на уровне стора |
| session-store «нумерация событий» | SessionAppendTest.concurrentAppendsHaveNoGapsAndNoDuplicates (50 событий = seq 1..50 без дыр/дублей) | ✓ |
| session-store «после компакции виден пересказ» | SessionRenderVisibilityTest.visibleEventsAreJournalMinusCovered (дословно сценарий) + laterCompactCanHideEarlierCompact + empty | ✓ |
| session-store «мутация запрещена» (405) | — | Вне пачки B: HTTP-методов ещё нет (8.3); app-уровень закрыт reflection-guard пачки A |
| session-store «порог достигнут» / «явный compact» | — | 9.1/9.2 — вне пачки B |
| session-store «рестарт между сообщениями» | — | Прямой тест = 10.3; механизмом покрыто (журнал в БД) |

## Summary

Пачка B по существу крепкая: security-цепочка собрана fail-closed (`anyRequest().denyAll()`, STATELESS, кастомный entry point без WWW-Authenticate), UserSync не пускается для отклонённых гейтом, row-lock-допись — ровно `UPDATE…RETURNING` + INSERT в одной транзакции без sess-лока (D-M1-4), конкурентный тест 2×25 без дыр/дублей, создание FREE пинит точную/последнюю ревизию с 404, рендер видимости — лёгкая проекция (seq+payload только COMPACT) → SET → payload видимых (D-M1-6) с полным набором unit/интеграционных тестов, Keycloak-обходы (sub=username, groups user-attribute mapper) изолированы в тестовом harness-realm.json и не протекли в прод-код, хардкода чисел нет. Единственный blocker — B-1: гейт-фильтр зарегистрирован до ExceptionTranslationFilter, поэтому «валидный JWT вне группы» статически даёт 500 вместо 401 ProblemDetails (три теста под риском); фикс однострочный — пере-якорить `addFilterAfter(GroupsGateFilter, ExceptionTranslationFilter.class)`. Остальное — два minor (безусловный UPSERT на каждый запрос, NPE-риск при пустом allowed-groups) и четыре nit. Вердикт: после фикса B-1 (+ желательно B-3) — к аппруву; сценарное покрытие спек в границах пачки B полное.
