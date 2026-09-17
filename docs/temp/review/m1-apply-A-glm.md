# Ревью пачки A (задачи 1.1–2.2) m1-session-core — GLM-5.3-Flash

Дата: 2026-09-17. Объект: `git diff` (pom.xml, application.yml, HarnessApplication, BaseApplicationTest, tasks.md) + новые `src/main/java/.../common|config|session`, `src/main/resources/db/`, `src/test/**`.
Эталоны: tasks.md (гр. 1–2), design.md (D-M1-1…4, 9), specs/session-store, data-model.md §1–2/§5/§7, architecture.md §4, AGENTS.md.
Верификация: `mvn test` (JDK 25 corretto) — **BUILD SUCCESS, 29 тестов, 0 fail/err**; Testcontainers-Postgres поднимался реально (DataSourceBootstrapTest ~12s).

## Findings

### major

**A-1. Тест «попытка UPDATE отклоняется на уровне приложения» отсутствует как поведенческий** — `src/test/java/se/rocketscien/harness/session/AppendOnlySessionMessageTest.java:76-94`.
Задача 2.2 требует «append-only-guard (попытка UPDATE `session_message` отклоняется на уровне приложения)». Реализованы только статические проверки: рефлексия по именам методов репозитория и запрет `set*` у сущности. Ни один тест не выполняет фактическую попытку мутации (например, `entityManager.merge` изменённого экземпляра) и не ассертит, что запись в БД не изменилась. Регрессионная дыра: если снять `@Immutable` с `SessionMessageEntity` (SessionMessageEntity.java:23), все тесты останутся зелёными.
Предложение: добавить тест — в одной транзакции загрузить событие, сформировать изменённую копию, попытаться merge/persist-обновление, во второй транзакции прочитать и ассертить неизменность payload (поведение Hibernate `@Immutable` — апдейт игнорируется; фиксируем это как контракт).

**A-2. Для UUIDv7 нет теста сортируемости значений; строгая монотонность значений генератором не гарантируется** — `src/test/java/se/rocketscien/harness/common/IdGeneratorTest.java:37-47`, `src/main/java/se/rocketscien/harness/common/UUIDv7Generator.java:192-200`.
Задача 1.2: «unit-тесты на формат/сортируемость». Для ULID сортируемость покрыта (строгое возрастание в/между мс); для UUIDv7 — только невозрастание декодированного timestamp, сравнения самих UUID нет. При этом в `buildOrderedUuid` младшие 6 бит счётчика в lsb смешиваются OR со случайными битами (`(counter & 0xFF) << 56 | (randomBytes[0] & 0x3F) << 56`), а биты счётчика 6–7 срезаются маской варианта — при равном миллисекундном штампе значение UUID может убывать, несмотря на режим STRICT_ORDERING (заявленный в Javadoc «монотонное возрастание»).
Код генератора владельца — не трогаем (D-M1-9, проверка только «не переписан»). Но: (а) теста, фиксирующего контракт сортируемости UUIDv7, нет; (б) для PK-индексов важна локальность по времени (она сохраняется — старшие биты msb чистые), полная монотонность значений, вероятно, не требуется.
Предложение: не менять генератор; добавить тест, фиксирующий фактический контракт (невозрастание timestamp + опционально наблюдение о немонотонности значений в одной мс), и/или зафиксировать у владельца, что строгая сортируемость UUIDv7-значений контрактом не является. Информационно довести до владельца наблюдение о смешении counter|random.

### minor

**M-1. `spring.datasource.hikari.*` не биндится в ручной пул** — `src/main/java/se/rocketscien/harness/config/DataSourceConfig.java:19-23`.
`DataSourceProperties.initializeDataSourceBuilder()` переносит только url/username/password/driver; пулоспецифичные ключи (`maximum-pool-size`, `connection-timeout`…) при таком ручном бине молча игнорируются (в автоконфиге Boot их биндит отдельный Hikari-байндинг). Сейчас таких ключей в application.yml нет, но при тюнинге (operations.md) настройка «применится» только внешне. Нарушает дух правила «числа — в конфиг».
Предложение: либо биндить `@ConfigurationProperties("spring.datasource.hikari")` на HikariDataSource через `DataSourceBuilder` + отдельный binder, либо явно задокументировать в классе, что пул-ключи не поддерживаются, до появления потребности.

**M-2. Jackson 2-артефакт `jackson-datatype-jsr310` остаётся в compile-скоупе** — `pom.xml:100-103`.
Это ровно та «J2-приманка», из-за которой Hibernate мог выбрать Jackson2-маппер (скелетная зависимость, не из этой пачки). Ловушка закрыта явным `Jackson3JsonFormatMapper` (JpaConfig.java:19) + тестом на тип маппера (JsonbRoundtripTest.java:41-47) — само по себе корректно. Но зависимость — кандидат на удаление после проверки, что никому (Spring AI, docker-java) J2 не нужен; иначе дублирующий JSON-стек тянется в рантайм.
Предложение: завести задачу «аудит jackson2-зависимостей» (можно в пачку B/финализацию M1), немедленного фикса не требует.

**M-3. Отклонение `session.task_id` без FK не зафиксировано** — `src/main/resources/db/changelog/migrations/2026/2026-09-17__create_schema__m1_core.xml:281-283`.
data-model §5: `task_id | uuid FK task`. Таблицы task до M2 нет — FK объективно невозможен, решение верное, но нигде не записано (ни remarks колонки, ни decisions.md, ни apply-заметки). При M2-миграции FK легко забыть.
Предложение: дополнить remarks колонки («FK добавляется миграцией task-домена в M2») или строку в decisions.md/apply-заметках.

**M-4. CHECK-констрейнты enum-ов — сущностное решение без записи в decisions.md** — `m1_core.xml:390-411,496-499` (`ck_session__kind`, `ck_session__last_turn_outcome`, `ck_session_message__kind`).
data-model enum-ы описывает, но CHECK на уровне БД — добавленное решение (усиление валидное, соответствует §5). Правило владельца: любое сущностное дизайн-решение — строкой в decisions.md.
Предложение: строка в decisions.md («enum-ы сессий/сообщений закреплены CHECK-констрейнтами БД»).

**M-5. Нет инварианта `kind='STATE' → task_id IS NOT NULL`** — `m1_core.xml:355-359`.
При `kind='STATE'` и NULL `task_id` partial-уникальный индекс `(task_id, state_code) WHERE kind='STATE'` допускает сколько угодно дублей (NULL-ключи не конфликтуют). data-model помечает task_id/state_code «только STATE»; обратное (STATE ⇒ task_id NOT NULL) не зафиксировано нигде.
Предложение: `CHECK (kind <> 'STATE' OR task_id IS NOT NULL)` тем же changeset-паттерном + строка в M-4.

### nit

**N-1. Дублирование `@EnableConfigurationProperties` + `@ConfigurationPropertiesScan`** — `HarnessApplication.java:9-10`. Достаточно `@ConfigurationPropertiesScan`. Работает, но вводит в заблуждение.

**N-2. FQN в extends вместо import** — `DataSourceBootstrapTest.java:13` (`extends se.rocketscien.harness.BaseApplicationTest`). Стиль; в остальных тестах import.

**N-3. `harness.docker.cpu-nanos`: семантика единиц** — `application.yml:19`. У docker-java CPU-квоты в микросекундах (cpu-quota/period); ключ в «нано» при реализации 6.2 придётся пересчитывать/переименовывать. Design.md Open Questions допускает финализацию имён при написании конфигов — предлагаю зафиксировать окончательное имя/единицы до 6.2 (например, `cpu-quota-micros`).

**N-4. Интеграционные тесты не чистят данные после себя** — `AppendOnlySessionMessageTest.java:96-119`. Вставки app_user/session/session_message накапливаются в контейнере между тестами. Сейчас безвредно (уникальные UUID, тесты независимы), но по корпоративным тестовым правилам очистка (DatabaseCleaner-подход) должна появиться, как только данных станет больше; заложить в пачку B (4.1).

**N-5. includeAll вместо per-year include** — `changeset-master.xml:7-8`. Корпоративный шаблон — `includeAll` на каждый год; текущий одиночный `includeAll migrations` (рекурсивный) работает, порядок внутри — алфавитный по пути. При росте числа лет разница несущественна. Допустимо, отмечено для единообразия.

### Проверено и валидно (без замечаний)

1. **Миграции vs data-model.md §1–2/§5/§7**: имена/типы всех колонок `app_user`, `llm_credentials` (включая `key_version INT DEFAULT 1`), `llm_model`, `agent` (UNIQUE `(key, rev)` отдельным changeset), `session`, `session_message` — совпадают; PK `(session_id, seq)` ✓; UNIQUE ULID `VARCHAR(26)` ✓; partial unique `(task_id, state_code) WHERE kind='STATE'` ✓; partial `(last_seq) WHERE last_seq > last_consumed_seq` ✓; FK-и и каскады от владельца ✓; ShedLock-таблица точно по DDL провайдера jdbc-template (`name VARCHAR(64) PK, lock_until/locked_at TIMESTAMP, locked_by VARCHAR(255)`) ✓; ключ `sess-{uuid}` (41 символ) помещается в VARCHAR(64) ✓. Preconditions + MARK_RAN, пустые строки, перенос атрибутов, UPPER CASE SQL, нейминг `idx_/uidx_/pk_/fk_` — по конвенции.
2. **Append-only контракт**: интерфейс репозитория — только `append/find/findAllBySessionId`; сущность `@Immutable`, без сеттеров; UPDATE/DELETE в коде отсутствуют (поведенческий тест — см. A-1).
3. **Конфиг-ключи**: полный состав task 1.3 присутствует и биндится (`harness.security.allowed-groups`, `lock.{session-ttl,heartbeat-interval,job-ttl}`, `turn.{poll-interval,llm-retries,backoff-base}`, `llm.timeout`, `compact.threshold`, `docker.{helper-image,workspace-root,cpu-nanos,memory}`, `sse.ping-interval`, `limits.{body,tool-output,bash-timeout-cap}`); тест на дефолты из application.yml + override-тест есть (ConfigPropertiesBindingTest — 9 тестов).
4. **Порядок пул→preliquibase→liquibase→JPA**: `DataSourceAutoConfiguration` исключён (application.yml:32); ручной HikariCP-бин; smoke-тест ассертит HikariDataSource + PreLiquibase + EMF и старт на чистом контейнере (JPA validate пройден → liquibase отработал до JPA). `ddl-auto: validate` в основном yml ✓.
5. **UUIDv7Generator/ULID**: генератор владельца перенесён только в пакет `common`; признаков переписывания при визуальной сверке нет (полную побайтовую идентичность внутри рабочего каталога подтвердить нечем — оригинал вне репо). ULID — `UlidCreator.getMonotonicUlid()` (монотонный) через единую точку `IdGenerator` ✓; тесты формата/алфавита/монотонности ULID есть.
6. **Jackson 3**: `Jackson3JsonFormatMapper` — единственная точка (JpaConfig), тест ассертит класс маппера в SessionFactory + реальный roundtrip jsonb (вложенность, юникод) + тип колонки jsonb. J2-фоллбэк закрыт (остаточная J2-зависимость — M-2).
7. **Тесты не заглушки**: 29 тестов, интеграционные реально поднимают Testcontainers-Postgres и проверяют структуру по pg_catalog/pg_indexes (составной PK по attname, partial-индексы по indexdef), BindingTest гоняет реальный биндинг. TDD-следы по артефактам не верифицируются — на результат не влияет.
8. **Хардкод чисел**: в новом основном коде отсутствуют; константы генератора — алгоритмические, длины VARCHAR — из data-model.
9. **Утечка скоупа**: нет — только задачи 1.1–2.2 (session-пакет — минимум, необходимый для guard-теста 2.2; jsonb_probe — тестовый, в тестовом changelog). Никакого bootstrap-кода.
10. **pom**: docker-java 3.7.1 (+httpclient5), archunit-junit5 1.5.0, dasniko testcontainers-keycloak 4.3.1, ulid-creator 5.2.4 — ровно как согласовано; Boot 4.1.1 / Java 25 / Spring AI 2.0.1 — соответствие architecture §4.

## Cross-check

Сверка с `docs/temp/review/m1-apply-A-deepseek.md` (DeepSeek-V4.1-Flash, 3 major / 6 minor / 4 nit). Прогресс-пометка: сборки/тесты по требованию оркестратора не запускались; факты сверялись по файлам.

| DS# | Суть DS | Мой вердикт | Позиция (факт файл:строка) |
|---|---|---|---|
| 1 | Источник Jackson 2 — не docker-java, а прямая `jackson-datatype-jsr310` + OpenAI SDK | Согласен, спора нет | Моя M-2 никогда не атрибутировала J2 к docker-java — источником названа прямая jsr310 (pom.xml:100-103), что совпадает с пунктом (а) DS. Dep-tree-факты DS (docker-java без J2; openai-java-core → jackson-module-kotlin) без сборки/выхода за каталог не проверяемы — принять к сведению и проверить при следующем прогоне. Существенно одно: пин `JSON_FORMAT_MAPPER` обязателен при любом источнике J2 (в hibernate-core оба маппера — принято). Severity major у DS обоснован неверным обоснованием в apply-материалах дева, не кодом; к моему ревью претензия не относится. |
| 2 | Ручной Hikari не биндит `spring.datasource.hikari.*` | Согласен по существу (дубль моей M-1); спор о severity | Нарушение «числа в конфиг» — потенциальное, не фактическое: в application.yml нет ни одного ключа `spring.datasource.hikari.*` (application.yml:39-47), сегодня ничего не игнорируется молча. Держу minor; согласен с DS-фиксом (биндинг HikariConfig + тест применения). |
| 3 | «preliquibase-хук» из 2.1 не материализован (нет скриптов/`spring.preliquibase.*`) | Спор — снижение до minor | Starter preliquibase 2.0.0 несёт встроенные дефолт-скрипты платформы PostgreSQL (создание БД, если отсутствует) — кастомные pre-DDL для M1 не требуются, «хук» = wiring стартера + `spring.liquibase.change-log`. В тестах хук ожидаемо no-op: контейнер сам создаёт БД «harness» (PostgresContextInitializer.java:16-19), поэтому «в логе не выполняет ничего» — не дефект. Бин PreLiquibase ассертится (DataSourceBootstrapTest.java:24). Согласен с DS только в части фиксации трактовки: формулировку 2.1/apply-заметку уточнить. |
| 4 | Тест `jsonFormatMapperIsJackson3` тавтологичен | Согласен, принято | Тест читает значение, положенное самим кастомизатором (JsonbRoundtripTest.java:41-47, JpaConfig.java:18-20); roundtrip прошёл бы и на J2-маппере. Мой вывод «ловушка закрыта» ослабить: предложить ассерт эффективного маппера через `SessionFactoryOptions#getJsonFormatMapper` (если доступен в Hibernate 7.4) — усиление, не блокер. |
| 5 | Агрегат `docker-java` тянет jersey+netty вдобавок к httpclient5 | Согласен по существу; причина — в формулировке задачи | tasks.md:7 буквально требует «`docker-java` (+транспорт httpclient5)» — дев исполнил букву задачи. Замена на `docker-java-core` + transport-httpclient5 технически верна; править надо tasks.md/design, не код. Для M1 некритично. |
| 6 | Каскады отсутствуют на owner-FK (`session.owner_user_id`, `llm_model.credentials_id`, `agent.llm_model_id` и др.) | Отклоняю по существу | «Удаления каскадные от владельца» (data-model:4) — владелец агрегата, а не любая ссылаемая строка: агрегатные каскады есть (session→session_message :425-435; session→sub-session :297-304). Каскады от app_user/llm_model/agent противоречили бы «Retention: бессрочно — сессии пинят ревизии; чистка ревизий осознанно отсутствует» (data-model:55) и отсутствию API удалений (D-41): удаление credentials снесло бы модели, удаление user — весь журнал. Схему не менять; зафиксировать трактовку «от владельца агрегата» строкой в data-model — принимаю как действие. |
| 7 | `session.task_id` без FK — follow-up M2 не зафиксирован | Согласен (дубль моей M-3) | См. M-3; резолюция одна — remarks/decisions/apply-заметка. |
| 8 | `spring.jpa.open-in-view` не отключён | Согласен, принято (мой пропуск) | Ключа в yml нет (application.yml:45-47) → OSIV включён по умолчанию со стандартным WARN. Для MVC+SSE-сервиса с виртуальными потоками отключить: `spring.jpa.open-in-view: false`. |
| 9 | Append-only guard — reflection, не поведение | Согласен по существу (дубль моей A-1); спор о severity | Держу major: отметка `[x]` стоит на формулировке «попытка UPDATE … отклоняется» (tasks.md:16), которой в тестах (AppendOnlySessionMessageTest.java:75-94) нет поведенчески. Предложение DS «переформулировать задачу под тест» — равнодопустимая резолюция, но в текущем виде задача и тесты расходятся. |
| 10 | FK-имена с префиксом таблицы偏离 шаблона `fk_<column>__<ref_table>_<ref_column>` | Согласен (nit) | Подтверждаю по rcktsci-sql: шаблон без префикса своей таблицы; моя сверка была мягкой («близко к конвенции»). Отклонение стилистическое; при переименовании следить за глобальной уникальностью имён FK. |
| 11 | `session_message.id` VARCHAR(26) vs text; индекс назвать `__ulid` | Частично отклоняю | VARCHAR(26) — легальное уточнение: 26 — длина ULID, заявленная в самом data-model:161, строже ≠ противоречие. Имя `uidx_session_message__id` конвентно: индекс именуется по колонкам (`uidx_<table>__<columns>`), а колонка называется `id` — переименование в `__ulid` нарушило бы правило нейминга. Не менять. |
| 12 | includeAll без годовых агрегаторов | Согласен (дубль моей N-5) | — |
| 13 | `@EnableConfigurationProperties` + `@ConfigurationPropertiesScan` вместе | Согласен (дубль моей N-1) | HarnessApplication.java:9-10. |

Найдки моего отчёта, отсутствующие у DS (остаются в силе): A-2 (нет теста сортируемости UUIDv7; counter|random-смешение в `buildOrderedUuid`, UUIDv7Generator.java:192-200), M-4 (CHECK-констрейнты — сущностное решение без записи в decisions.md), M-5 (нет инварианта `kind='STATE' → task_id IS NOT NULL`), N-2/N-3/N-4.

Итог кросс-чека: по существу расхождений мало — 2 спора о severity (DS#2, DS#9), 1 снижение (DS#3), 2 отклонения с фактами (DS#6, DS#11-частично); принято от DS новое: DS#4 (усиление теста маппера), DS#8 (OSIV — мой пропуск), DS#10 (нейминг FK). Блокеров ни у кого нет.

## Fixes approval

Ре-аппрув по `docs/temp/review/m1-apply-A-judge.md`. Сборки не запускались (запрещены); все проверки — по факту файлов. Прогон разработчика `mvn clean verify` 29/29 — принят как единственный верифицирующий.

| Моя находка | Вердикт судьи | Статус по файлам | Итог |
|---|---|---|---|
| A-1 (major) поведенческий UPDATE-тест | A-R-1: отклонено — «на уровне приложения» = контракт API, reflection-guard достаточен | — | **Закрыто (reject судьи принят)**: трактовка «контракт API» согласована и зафиксирована в судейском файле; расхождения задача↔тест больше нет. |
| A-2 (major) UUIDv7 сортируемость/counter-mixing | A-R-2: отклонено (код владельца — директива), условие — зафиксировать контракт в Javadoc `IdGenerator` | IdGenerator.java:11-17 — контракт зафиксирован: timestamp-сортируемость да, строгая монотонность в 1 мс нет, для UUID не требуется | **Закрыто**: условие судьи выполнено буквально. |
| M-1 (minor) Hikari-биндинг | A-J-1, закрыт директивой владельца: ручная сборка убрана, DataSource — автоконфиг Boot | DataSourceConfig.java удалён (каталог config — 9 Properties-файлов + JpaConfig); exclude `DataSourceAutoConfiguration` снят (application.yml:34); `spring.datasource.hikari.*` теперь биндится нативно автоконфигом | **Approve (корневая причина устранена)**. Остаточные хвосты директивы, не блокирующие пачку A, но требующие одной правки до пачки B: (а) architecture.md:46 и design.md:7 (openspec) всё ещё утверждают «DataSourceAutoConfiguration отключён, ручная сборка» — stale-доктрину синхронизировать с директивой владельца (по AGENTS.md решения — в decisions.md); (б) DataSourceBootstrapTest.java:22 имя метода `contextStartsWithManualPool…` устарело. |
| M-2 (minor) J2 `jackson-datatype-jsr310` | A-J-2: убрать + исправить мотив | pom.xml — зависимость удалена (диф минус-блок :100-103); JpaConfig.java:11-13 — мотив исправлен («Spring AI → openai-java-core», оба маппера в hibernate-core); apply-notes «Прочее» — источник зафиксирован | **Approve**. |
| M-3 (minor) task_id без FK, не зафиксировано | A-J-6: сверить с data-model, расхождения фиксировать | apply-notes «Отложенные пункты схемы»: follow-up M2 «FK `session.task_id → task.id` + инварианты state_code» | **Approve**. |
| M-4 (minor) CHECK-констрейнты без записи в decisions.md | судьёй не взят | docs/design/decisions.md не менялся (нет в git status) | **Не закрыто** (minor, non-blocking): остаётся backlog-пунктом; предлагаем включить в пачку B или в ту же строку синхронизации доков из M-1. |
| M-5 (minor) нет инварианта `kind='STATE' → task_id IS NOT NULL` | судьёй не взят явно; косвенно — follow-up M2 в apply-notes («инварианты state_code») | схема не менялась | **Не закрыто** (non-blocking): принять как отложенное, но в M2-задаче инвариант назвать прямо (CHECK), а не общим словом. |
| N-1 (nit) двойная аннотация properties | не взят | HarnessApplication.java:8-9 — обе аннотации на месте | **Не закрыто** (nit, не блокирует). |
| N-2 (nit) FQN в extends | не взят | DataSourceBootstrapTest.java:13 — FQN на месте | **Не закрыто** (nit, не блокирует). |
| N-3 (nit) cpu-nanos единицы | A-R-4: отклонено — в docker-java есть `nanoCPUs` | application.yml:19 без изменений | **Закрыто (reject принят)**: замечание снимаю — ключ маппится на `CreateContainerConfig.nanoCPUs` 1:1. |
| N-4 (nit) чистка тестовых данных | не взят | очистки нет | **Не закрыто** — согласованное отложенное до 4.1 (DatabaseCleaner); в backlog. |
| N-5 (nit) includeAll без годовых агрегаторов | A-R-5: отклонено (косметика) | — | **Закрыто (reject принят)**. |

### Проверка судейских фиксов A-J-1…A-J-6 по файлам

- **A-J-1** ✓ — DataSourceConfig удалён, exclude снят, автоконфиг Boot; тест контекста зелёный (прогон разработчика).
- **A-J-2** ✓ — pom без J2; мотив в JpaConfig.java:11-13 корректен.
- **A-J-3** ✓ — `open-in-view: false` (application.yml, секция spring.jpa).
- **A-J-4** ✓ — `deleteCascade` на owner-FK `session.owner_user_id` (m1_core.xml:270-273); restrictive сохранены: `agent_revision_id` (:293), `llm_model.credentials_id` (:129), `agent.llm_model_id` (:215), `session_message.author_user_id` (:462); политика задокументирована (apply-notes «Политика каскадов»).
- **A-J-5** ✓ — JsonbRoundtripTest.java:41-47: ассерт эффективного маппера через `SessionFactoryImplementor.getSessionFactoryOptions().getJsonFormatMapper()` — тавтологичность устранена.
- **A-J-6** ✓ — `session_message.id` → TEXT (m1_core.xml:446; у сущности `@Column(name="id", unique=true)` без length — SessionMessageEntity.java:30); task_id-FK — follow-up M2 в apply-notes.

### Итог ре-аппрува

**APPROVE.** Все шесть судейских фиксов закрыты по факту; условия аппрува пачки A (A-J-1…A-J-6 + зелёный verify) выполнены. Отклонения судьи (A-R-1…A-R-5) приняты. Остаточные non-blocking пункты — backlog: (1) синхронизация architecture.md §4/design.md с директивой владельца по DataSource + запись решения в decisions.md [рекомендую до пачки B]; (2) M-4 (CHECK → decisions.md); (3) M-5 (инвариант STATE⇒task_id — прямо назвать в M2); (4) N-1, N-2 (косметика); (5) N-4 (чистка тестов — к 4.1).

## Summary

Пачка A выполнена качественно и по делу: миграция побайтово своряется с data-model §1–2/§5/§7 (включая оба partial-индекса и ShedLock-DDL), порядок пул→preliquibase→liquibase→JPA собран вручную и покрыт smoke-тестом, Jackson3-маппер — единственная точка с тестом на J2-фоллбэк, конфиг-скелет полный и проверен на биндинг, `mvn test` зелёный (29/29). Два major: (A-1) заявленный в 2.2 append-only-guard проверен только статически — поведенческого теста попытки UPDATE нет, снятие `@Immutable` тестами не ловится; (A-2) для UUIDv7 нет теста сортируемости, а генератор владельца в одной мс допускает немонотонность значений (счётчик смешан OR со случайными битами; код не трогаем, но контракт стоит зафиксировать). Мелочи: неподключаемый `spring.datasource.hikari.*`, остаток J2-зависимости, незадокументированные решения (FK task_id в M2, CHECK-констрейнты, инвариант STATE⇒task_id). Вердикт: approve после фикса A-1 (A-2 достаточно зафиксировать контрактом/тестом-наблюдением).
