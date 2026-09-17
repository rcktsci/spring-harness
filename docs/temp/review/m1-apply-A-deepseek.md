# Review: m1-session-core — apply, пачка A (задачи 1.1–2.2) — DeepSeek-V4.1-Flash

Объект: `git diff` + новые каталоги `src/main/java/.../common|config|session`, `src/main/resources/db/`, `src/test/**`, `pom.xml`, `application.yml`, отметки `[x]` в `tasks.md`.
Эталоны: `design.md` (D-M1-1…4, 9), `specs/session-store/spec.md`, `docs/design/data-model.md` (§1–2, §5, §7), `docs/design/architecture.md` §4, `AGENTS.md`.
Прогон: `mvn clean test` на JDK 25 (corretto-25.0.2) — **29/29 зелёных, BUILD SUCCESS**. Дефолтный `java` на машине — 17, без `JAVA_HOME` surefire форкается на 17 и падает на class version 69 (среда, не код; для воспроизведения задавать `JAVA_HOME` = JDK 25).
Docker/Testcontainers: postgres:17-alpine, миграции накатываются с нуля, `ddl-auto: validate` проходит (косвенно доказывает порядок пул → preliquibase → liquibase → JPA).

## Findings

1. **major** — `pom.xml:100-103`, `config/JpaConfig.java:17-20`, `docs/design/architecture.md:48` (D-M1-2).
   Дефект: в заявлении дева «docker-java тянет Jackson 2 в classpath» источник указан неверно. По `dependency:tree` `docker-java:3.7.1` НЕ тянет `com.fasterxml.jackson` (его дети — core/api, transport-jersey, transport-netty, guava/bouncycastle; под ними Jackson 2 нет). Реальные источники Jackson 2: (а) **прямая compile-зависимость** `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.5` (`pom.xml:100-103`) — именно она держит Jackson 2 в продуктовом classpath; (б) `com.openai:openai-java-core:4.49.0` → `jackson-module-kotlin:2.21.5` (runtime). При этом в `hibernate-core:7.4.5` лежат ОБА маппера (`Jackson3JsonFormatMapper` и `JacksonJsonFormatMapper`), поэтому пин действительно обязателен — но не из-за docker-java. Ошибка в обосновании опасна: при будущей «чистке classpath» причину снимут не ту, и пин останется без страховки.
   Предложение: удалить унаследованную Jackson-2-зависимость `jackson-datatype-jsr310` (Boot 4 отдаёт JavaTime через `tools.jackson`), зафиксировать в apply-заметке верный источник J2 (OpenAI SDK) и то, что пин `hibernate.type.json_format_mapper` — единственная защита от J2-фолбэка.

2. **major** — `config/DataSourceConfig.java:19-22`, `AGENTS.md` («все числовые параметры — конфиг»), `architecture.md:46`.
   Дефект: `dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build()` биндит только `spring.datasource.{url,username,password,driver-class-name,type}`. Любые `spring.datasource.hikari.*` (pool size, connection/idle timeout, leak detection) молча игнорируются — вручную собранный Hikari остаётся на библиотечных дефолтах, и задать их из конфига невозможно. Это ломает правило «числа только в конфиге» ровно там, где появится первая эксплуатационная настройка (нагрузка, health, SRE-тюнинг).
   Предложение: собирать пул через `HikariConfig`/`HikariDataSource` с `@ConfigurationProperties("spring.datasource.hikari")` (или явно документировать, что hikari-настройки не поддерживаются до отдельного решения), и добавить тест, что заданный `spring.datasource.hikari.maximum-pool-size` реально применяется.

3. **major** — `openspec/changes/m1-session-core/tasks.md:15` (2.1, «preliquibase-хук») vs `src/main/resources/db/**` (нет `preliquibase`-скриптов/конфига), `src/test/.../config/DataSourceBootstrapTest.java:24`.
   Дефект: `preliquibase-spring-boot-starter` в pom есть и бин `PreLiquibase` создаётся, но ни одного pre-DDL-скрипта и ни одной строки `spring.preliquibase.*` в репозитории нет — в логе preliquibase не выполняет ничего. Пункт 2.1 отмечен `[x]`, но его часть «preliquibase-хук» фактически не материализована; smoke-тест проверяет лишь наличие бина, а не эффект.
   Предложение: либо добавить осмысленный pre-DDL (например, `CREATE EXTENSION`/настройка схемы) и тест его применения, либо явно записать в задаче/apply-заметке, что в M1 pre-DDL не требуется и «хук» = только wiring стартера (тогда убрать его из списка результатов 2.1).

4. **minor** — `src/test/.../config/JsonbRoundtripTest.java:40-47`, `config/JpaConfig.java:18-20`.
   Дефект: тест `jsonFormatMapperIsJackson3` читает `sessionFactory.getProperties().get(MappingSettings.JSON_FORMAT_MAPPER)`, т.е. проверяет значение, которое сам же и положил customizer — тавтологично; не подтверждает, что Hibernate реально резолвит этот маппер (roundtrip `jsonb` одинаково проходит и на Jackson 2).
   Предложение: ассертить эффективный маппер: `sessionFactory.getSessionFactoryOptions().getJsonFormatMapper()` (в Hibernate 7.4 метод есть — `org.hibernate.boot.spi.SessionFactoryOptions#getJsonFormatMapper`) `isInstanceOf(Jackson3JsonFormatMapper.class)`.

5. **minor** — `pom.xml:148-152`, `openspec/.../tasks.md:7` (1.1 «docker-java (+транспорт httpclient5)»).
   Дефект: используется агрегатный `com.github.docker-java:docker-java`, который тянет `docker-java-transport-jersey` и `docker-java-transport-netty` вдобавок к отдельно добавленному `docker-java-transport-httpclient5`. Три транспорта на classpath — лишний вес и риск неоднозначного выбора транспорта в будущем коде `ContainerWorkspaceTools`.
   Предложение: заменить агрегат на `com.github.docker-java:docker-java-core` + `docker-java-transport-httpclient5`.

6. **minor** — `db/changelog/migrations/2026/2026-09-17__create_schema__m1_core.xml:125-132,211-218,266-273,289-295,458-464` vs `docs/design/data-model.md:4` («удаления каскадные от владельца, если не указано иное»).
   Дефект: каскад выставлен только на `session.parent_session_id` (297-304) и `session_message.session_id` (425-435). Все остальные FK идут без `deleteCascade`: `session.owner_user_id`, `session.agent_revision_id`, `session_message.author_user_id`, `llm_model.credentials_id`, `agent.llm_model_id`. Для продукта без удалений это латентно, но схема заявлена как основа на будущее и отклоняется от преамбулы data-model.
   Предложение: привести политику каскадов в соответствие с data-model явно (owner-FK — cascade), либо зафиксировать в data-model исключения списком.

7. **minor** — `.../2026-09-17__create_schema__m1_core.xml:281-287` vs `docs/design/data-model.md:141`.
   Дефект: `session.task_id` создан как «голый» UUID без FK на `task`, потому что таблицы `task` в M1 нет. Это осознанный срез фазы, но нигде не зафиксировано, что FK добавит миграция M2 — расхождение со схемой data-model остаётся «висящим».
   Предложение: отметить в apply-заметке/design явный follow-up M2 «add FK `session.task_id → task.id`» (и `state_code`-инварианты), чтобы при добавлении `task` это не потерялось.

8. **minor** — `src/main/resources/application.yml:45-47` (нет `spring.jpa.open-in-view`).
   Дефект: в логе `spring.jpa.open-in-view is enabled by default` (WARN). Для API+SSE-сервиса с ручными транзакциями открытая сессия на время рендера — известная анти-практика (ленивые догрузки вне транзакции, удержание коннектов).
   Предложение: `spring.jpa.open-in-view: false`.

9. **minor** — `src/test/.../session/AppendOnlySessionMessageTest.java:75-94` vs `tasks.md:16` (2.2 «попытка UPDATE `session_message` отклоняется на уровне приложения»).
   Дефект: guard проверяется reflection-ом (нет методов-мутаторов в репозитории, нет `set*` у сущности). Это структурный, а не поведенческий тест: он не фиксирует, что UPDATE журнала недоступен ни из какого прикладного пути, и не выполняется никакой «попытки UPDATE».
   Предложение: либо принять и переформулировать задачу («append-only обеспечен отсутствием мутирующего API», тест reflection-ом), либо добавить поведенческий тест-инвариант (например, ArchUnit-правило «только `SessionMessageRepository.append` пишет в `session_message`») — увязать с задачей 10.1.

10. **nit** — `.../2026-09-17__create_schema__m1_core.xml:129,215,270,292,300,431,461` vs `rcktsci-sql` (конвенция FK `fk_<column>__<ref_table>_<ref_column>`).
    Дефект: имена FK включают таблицу (`fk_llm_model__credentials_id__llm_credentials_id` и т.п.) — расходится с корпоративным шаблоном (там имя таблицы не префиксуется). data-model имён не задаёт, поэтому это стилистика, но она разъедется с остальными миграциями проекта.
    Предложение: привести к `fk_<column>__<ref_table>_<ref_column>` (или зафиксировать отступление в конвенции).

11. **nit** — `.../2026-09-17__create_schema__m1_core.xml:444-450` vs `data-model.md:161`.
    Дефект: колонка `session_message.id` (ULID) — `VARCHAR(26)`, тогда как data-model объявляет `text UNIQUE`; индекс назван `uidx_session_message__id`, хотя семантика — ULID.
    Предложение: либо оставить `VARCHAR(26)` (строже — ок) и назвать `uidx_session_message__ulid`, либо синхронизировать data-model с фактическим типом.

12. **nit** — `src/main/resources/db/changelog/changeset-master.xml:7-8` vs `rcktsci-sql` (структура `includeAll` по годам + `run_always`).
    Дефект: один рекурсивный `<includeAll path="migrations"/>` вместо явных годовых агрегаторов. Работает (2026 подхвачен, лог подтверждает), но при появлении `run_always`/компонентных миграций порядок станет неочевидным.
    Предложение: подключить `migrations/2026` и (заранее) `run_always` явными `includeAll`, как в конвенции.

13. **nit** — `src/main/java/se/rocketscien/harness/HarnessApplication.java:7-8`.
    Дефект: `@EnableConfigurationProperties` (без аргументов) и `@ConfigurationPropertiesScan` вместе; первый при скане избыточен.
    Предложение: убрать `@EnableConfigurationProperties` либо оставить один механизм.

## Summary

Проверено 13 позиций: блокеров нет; 3 major, 6 minor, 4 nit. Прогон `mvn clean test` на JDK 25 — 29/29 зелёных, порядок инициализации и Jackson 3-пин фактически подтверждены (в `hibernate-core:7.4.5` есть и Jackson2-маппер, так что пин обязателен). Топ-3: (1) ручная сборка Hikari не биндит `spring.datasource.hikari.*` — нарушает «числа только в конфиге» (major); (2) обоснование про Jackson 2 неверно — источник не docker-java, а прямая `jackson-datatype-jsr310` + OpenAI SDK (major); (3) «preliquibase-хук» из 2.1 не материализован — скриптов/конфига нет, тест проверяет только бин (major). Mitigated: dasniko `testcontainers-keycloak:4.3.1` собран против testcontainers 2.0.5 — версии совпадают, watch-item закрыт.

## Cross-check

Сверка с `docs/temp/review/m1-apply-A-glm.md` (GLM-5.3-Flash) и кодом. Сборки не запускались (параллельные сессии); использован ранее снятый прогон 29/29.

| GLM | Вердикт | Факт / позиция |
|---|---|---|
| **A-1** behavioral append-only test (major) | Частично; severity — **minor** | Задача 2.2 прямо оговаривает «на уровне приложения»; reflection-тесты (`AppendOnlySessionMessageTest.java:76-94`) guard-ят API репозитория и отсутствие сеттеров. Регрессия при снятии `@Immutable` (`SessionMessageEntity.java:23`) действительно не ловится — принимаю как отдельный gap, но не major: в M1 нет ни одного пути записи, а merge-тест фиксирует семантику Hibernate, а не приложения. |
| **A-2** UUIDv7 sortability (major) | Частично; severity — **minor** | «Теста сортируемости нет» неточно: `IdGeneratorTest.java:38-47` фиксирует невозрастание timestamp. Теста сравнения самих UUID — верно, нет. Смешение counter\|random подтверждаю: `UUIDv7Generator.java:192-200` OR-ит два сдвига на 56, маска на `:203` срезает биты 62-63 (counter[6..7]) → в пределах мс значения немонотонны, хотя Javadoc `:56` обещает «монотонное возрастание». Код владельца (D-M1-9) не трогаем; добавить тест фактического контракта + довести до владельца. |
| **M-1** Hikari не биндит `spring.datasource.hikari.*` (minor) | **Согласен** по факту; severity — **major** | AGENTS: «числа только в конфиг» — контрактное правило; необвязываемый пул делает его невыполнимым при первом же тюнинге (`operations.md`). |
| **M-2** J2 `jackson-datatype-jsr310` (minor) | **Согласен**; severity — **major** | Функционально митигировано пином (`JpaConfig.java:19`, `JsonbRoundtripTest.java:46`), но остаток J2 в compile-скоупе + неверная атрибуция источника (не docker-java, а прямая зависимость и OpenAI SDK) → риск, что пин снимут «не по той причине». |
| **M-3** незафиксированный отложенный FK `session.task_id` | Согласен | Дубль моего #7. |
| **M-4** CHECK-констрейнты не в `decisions.md` | Принято (пропустил) | Валидно по правилу владельца; CHECK усиливает enum'ы из `data-model.md`. |
| **M-5** нет CHECK `kind='STATE' ⇒ task_id IS NOT NULL` | Принято (пропустил) | Подтверждаю: NULL-ключи не конфликтуют, partial-unique `(task_id,state_code) WHERE kind='STATE'` (`m1_core.xml:355-359`) допускает дубли STATE с NULL. |
| **N-1** дублирование аннотаций | Согласен | Дубль моего #13. |
| **N-2** FQN в `extends` | Принято (пропустил) | `DataSourceBootstrapTest.java:13` — верно. |
| **N-3** `harness.docker.cpu-nanos` требует пересчёта/переименования | **Не согласен** частично | В `docker-java-api:3.7.1` есть `HostConfig.nanoCPUs` + `withNanoCPUs(Long)` (наносекунды) наряду с `cpuQuota`/`cpuPeriod` (микросекунды) — ключ валиден без пересчёта при реализации через `NanoCpus`. Фиксацию имён до 6.2 поддерживаю. |
| **N-4** тесты не чистят данные | Принято (пропустил) | Безвредно сейчас, ок как долг в пачку B. |
| **N-5** `includeAll` vs per-year | Согласен | Дубль моего #12. |
| GLM «FK-и и каскады от владельца ✓» | **Не согласен** | `m1_core.xml:266-273` `session.owner_user_id` без `deleteCascade` (также `:289-295` agent_revision_id, `:458-464` author_user_id) при `data-model.md:4` «удаления каскадные от владельца, если не указано иное». Мой #6 в силе. |
| GLM «тест ассертит класс маппера в SessionFactory» | Уточнение | Ассертится значение, которое этот же customizer и положил (`sessionFactory.getProperties()`) — тавтологично (мой #4); нужен `sessionFactory.getSessionFactoryOptions().getJsonFormatMapper()`. |
| GLM «pom: docker-java (+httpclient5) — ровно как согласовано» | Уточнение | Агрегатный `docker-java` тянет transport-jersey + transport-netty (мой #5). |

**Итог сверки:** согласие по 6 пунктам (M-3/N-1/N-5 — дубли; M-4/M-5/N-2/N-4 — приняты, пропущены мной), расхождение по severity (A-1, A-2, M-1, M-2), фактическое опровержение — по каскадам FK и `cpu-nanos`. Оба ревью не нашли блокеров; общий список дефектов дополнен (детерминированность UUIDv7-контракта, CHECK'и, `NanoCpus`).

## Fixes approval

Проверено фактическое состояние файлов после судейских фиксов (`docs/temp/review/m1-apply-A-judge.md`, A-J-1…A-J-6). Сборки не запускались (запрет для ревьюеров; принят прогон разработчика `mvn clean verify` — 29/29).

| Моя находка | Вердикт | Факт |
|---|---|---|
| **#1** Jackson 2: прямая `jackson-datatype-jsr310` + неверный мотив (docker-java) | **approve** | Зависимость удалена (`pom.xml` — блока jsr310 больше нет, diff −7 строк). Мотив исправлен: `JpaConfig.java:10-13` (Jackson 2 транзитивно Spring AI → openai-java-core) и `apply-notes.md:28-30`. Пин `Jackson3JsonFormatMapper` на месте (`JpaConfig.java:20`). |
| **#2** Hikari: `spring.datasource.hikari.*` не биндится | **approve** (директивой владельца) | Ручная сборка удалена: `DataSourceConfig.java` отсутствует, `DataSourceAutoConfiguration` снят из exclude (`application.yml`, grep — не найден), DataSource+Hikari собирает Boot → hikari-свойства биндятся штатно. Замечание: явного теста «применяется key» нет — `DataSourceBootstrapTest.java:22-26` по-прежнему проверяет только типы бинов (и имя метода осталось `...ManualPool...`). |
| **#3** preliquibase-хук не материализован | **reject** (принято решение судьи A-R-3) | Фикса нет; `apply-notes.md:32-33` фиксирует «pre-DDL в M1 не нужен». Фактическая поправка к мотиву отказа: утверждение «starter несёт встроенные PG-скрипты» неверно — `PreLiquibaseProperties.DEFAULT_SCRIPT_LOCATION = classpath:preliquibase/`, скриптов `preliquibase/{postgresql|default}.sql` в репо нет, т.е. хук — ожидаемый no-op (не гейтит). |
| **#4** тавтологичный тест маппера | **approve** | Усилено: `JsonbRoundtripTest.java:41-47` — `SessionFactoryImplementor.getSessionFactoryOptions().getJsonFormatMapper()` `isInstanceOf(Jackson3JsonFormatMapper.class)`. |
| **#5** агрегатный `docker-java` тянет jersey+netty | **reject** (не адресовано) | `pom.xml:147-152` без изменений; в A-J-списке нет. Остаётся minor, не гейтит. |
| **#6** каскады owner-FK | **approve** | `m1_core.xml:266-273` `session.owner_user_id` → `deleteCascade="true"`; политика для остальных FK задокументирована (`apply-notes.md:17-24`). |
| **#7** отложенный FK `session.task_id` не зафиксирован | **approve** | `apply-notes.md:11-13`: follow-up M2 + частичное покрытие partial-unique. |
| **#8** OSIV не отключён | **approve** | `application.yml:47` `open-in-view: false`. |
| **#9** нет поведенческого append-only теста (minor) | **reject** (A-R-1) | Исправления нет; решение судьи принято. Оговорка: regression-hole (снятие `@Immutable` (`SessionMessageEntity.java:23`) не ловится) остаётся, но не гейтит. |
| **#10** FK-нейминг (nit) | **reject** (A-R-5, косметика) | Без изменений. |
| **#11** `session_message.id` VARCHAR(26) vs `text` | **approve** (тип) | Выровнено: миграция `:445-451` `type="TEXT"`, сущность `SessionMessageEntity.java:30` без `length`. Имя индекса `uidx_session_message__id` не менялось (nit, см. A-R-5). |
| **#12** `includeAll` vs per-year (nit) | **reject** (косметика) | `changeset-master.xml:7-8` без изменений. |
| **#13** дублирование `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` (nit) | **reject** | `HarnessApplication.java` без изменений. |
| Кросс-чек **M-4** (CHECK-констрейнты → decisions.md) | **reject** (не адресовано) | Процессный долг владельца, не гейтит. |
| Кросс-чек **M-5** (`CHECK kind='STATE' ⇒ task_id IS NOT NULL`) | **reject** (не адресовано) | NULL-дубли STATE остаются возможны через partial-unique; не гейтит. |
| Кросс-чек **N-3** (`cpu-nanos` пересчёт) | n/a | Снят в кросс-чеке (docker-java `nanoCPUs`). |
| Новое (остаточное) | фикс до архива | `tasks.md:11` (1.5) и `architecture.md:46` всё ещё описывают «ручную сборку DataSource, автоконфиг отключён» — расходится с A-J-1. Привести текст задач/дизайна в соответствие (иначе apply-артефакты противоречат коду). |

**Итог:** все гейтирующие пункты A-J-1…A-J-6 закрыты фактически; **пачка A — approve**. Остаточные не-гейтирующие: #5 (агрегат docker-java), #3 (нет pre-DDL-скрипта — решение принято), отсутствие явного теста на hikari-ключ, устаревшая формулировка 1.5/`architecture.md:46`, неадресованные M-4/M-5.
