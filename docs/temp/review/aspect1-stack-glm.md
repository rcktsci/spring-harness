# Стек-валидация дизайн-базиса spring-harness (aspect1, GLM)

> Дата: 2026-09-16. Сверка поведенческих обещаний дизайна с реальными API актуальных версий.
> Версии из `pom.xml`: Boot 4.1.1 (parent), Java 25, Spring AI 2.0.1 (BOM, starter-model-openai), ShedLock 7.9.0,
> preliquibase-spring-boot-starter 2.0.0, Boot-стартеры liquibase/data-jpa/web, PostgreSQL, testcontainers 2.0.5 (test).
> Источники: Javadoc Spring AI 2.0.0, документация Spring Boot 4.1 (v4.1.0 в git), GitHub (Pre-Liquibase, ShedLock, docker-java, hibernate-orm),
> OpenJDK JEP 491, MCP Java SDK docs, spring.io blog. Ссылки приведены по тексту.
> Формат находки: **обещано (док+раздел) → что реально в API (источник) → вердикт → альтернатива/действие**.

## Сводка

| Вердикт | Кол-во | № находок |
|---|---|---|
| OK | 12 | 1–5, 7, 9–13, 15 |
| RISK | 3 | 6 (MCP OAuth), 8 (PreLiquibase/ручной порядок), 14 (jsonb×Jackson 3) |
| BLOCKER | 0 | — |

---

## A. Spring AI 2.0 — ChatClient, tool-calling, память, structured output

### 1. Ручная сборка ChatModel из LlmModel; 6 отключённых автоконфигураций OpenAI — OK

**Обещано:** `architecture.md` §4 («автоконфигурации отключены… клиенты собираются из LlmModel вручную»), `glossary.md` §2.

**Реально:** Spring AI 2.0 GA официально «designed to be used with Spring Boot 4.0/4.1 and Spring Framework 7.0» (spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now). Пакет `org.springframework.ai.model.openai.autoconfigure` существует именно в таком виде, и в нём ровно 6 автоконфигураций: `OpenAiChatAutoConfiguration`, `OpenAiAudioSpeechAutoConfiguration`, `OpenAiAudioTranscriptionAutoConfiguration`, `OpenAiEmbeddingAutoConfiguration`, `OpenAiImageAutoConfiguration`, `OpenAiModerationAutoConfiguration` (javadoc 2.0.1: docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/model/openai/autoconfigure/package-summary.html). `application.yml` проекта перечисляет все 6 с корректными FQCN — ничего не пропущено, при запуске не останется «висящих» автоконфигов OpenAI.

**Вердикт: OK.**

### 2. Кастомные tool-объекты с поздним результатом (ASYNC_ACCEPTED → второй TOOL_RESULT, late=true) — OK

**Обещано:** `glossary.md` §4 «Асинхронный инструмент» (окно ~30 с → плейсхолдер `ASYNC_ACCEPTED(call_id)` → поздний второй `TOOL_RESULT`), `execution-model.md` §4, `agent-tools.md` §1 (bash async-capable) и §5 (единый контракт результата, идемпотентность по callId).

**Реально:** Spring AI 2.0 даёт полный ручной контроль над tool-циклом:
- `ToolCallingChatOptions` / `DefaultToolCallingChatOptions.Builder.toolCallbacks(List<ToolCallback>)` — регистрация своих тулов (Javadoc 2.0.0, DefaultToolCallingChatOptions);
- `internalToolExecutionEnabled(false)` — модель возвращает tool-calls, исполнение берёт на себя приложение;
- `ToolCallingManager.resolveToolDefinitions(...) / executeToolCalls(prompt, chatResponse)` и `ToolExecutionResult.conversationHistory()/returnDirect()` (Javadoc 2.0.0, ToolCallingManager, ToolExecutionResult).

Паттерн дизайна ложится без трений: цикл агента сам пишет `TOOL_CALL` в `session_message`, возвращает модели `ASYNC_ACCEPTED` как результат тул-вызова (или завершает раунд — случай «только async»), паркует Turn; поздний результат дописывается движком и попадает в следующий раунд через собственный рендер контекста. Spring AI не обязан знать про «late» — идемпотентность по callId живёт на уровне SessionStore.

**Вердикт: OK.** Примечание: держать tool-результаты как `ToolResponseMessage` с равным числом ToolResponse нужно на границе раунда — смешанный ответ «sync+async» (таблица §3.1 в execution-model) собирается вручную, это ровно то, что делает `ToolCallingManager` под капотом.

### 3. Отмена стрима при cancel — OK

**Обещано:** `architecture.md` §3 контракт `LlmGateway` («стриминг, отмена, счёт токенов»), `execution-model.md` §2/§6 (проверка `cancel_requested` между вызовами инструментов, CancellationToken-дерево).

**Реально:** `ChatClient.stream()` возвращает `Flux<ChatClientResponse>`, проходящий через advisor-цепочку `ChatModelStreamAdvisor.adviseStream(...)` (Javadoc 2.0.0). Отмена = `subscription.cancel()`/`dispose()` на Flux — реактор пробрасывает отмену до WebClient (reactor-netty), закрывая HTTP-соединение SSE-потока. Кооперативная схема дизайна (флаг `cancel_requested` + граница между tool-call'ами) с этим полностью совместима: для «мягкой» отмены достаточно перестать дренировать Flux и закоммитить CANCELLED; для «жёсткой» — cancel() закрывает соединение к LLM.

**Вердикт: OK.** Действие: в реализации `LlmGateway` зафиксировать, что отмена in-flight стрима = cancel подписки (закрытие соединения), а частично полученный контент не коммитится как ASSISTANT (или коммитится как усечённый — решить по контракту компакции).

### 4. Подмена ChatMemory/Advisor на SessionStore — OK

**Обещано:** `execution-model.md` §5 (рендер видимых событий из собственного хранилища, COMPACT.covers, кэш «шапки»), что фактически означает отказ от встроенной памяти Spring AI.

**Реально:** ChatClient в 2.0 допускает передачу произвольного списка сообщений на каждый запрос (`prompt().messages(...)`, либо ниже уровнем — `ChatModel.call(Prompt)/stream(Prompt)` с `List<Message>`). ChatMemory/Advisor'ы (`ChatMemoryRepository.saveAll(conversationId, messages)` и пр.) — опциональные декорации; их можно не подключать вовсе. Дизайну не требуется ни адаптер `ChatMemory` поверх SessionStore, ни кастомный Advisor — рендер-проход по событиям сессии и есть источник промпта. Бонус: API advisor'ов в 2.0 переименовано (`ChatModelStreamAdvisor` и т.д.) — не используем, не страдаем.

**Вердикт: OK** (схема «без ChatMemory» проще и точнее соответствует обещанному рендеру; «подмена» не нужна).

### 5. Structured output при необходимости — OK

**Обещано:** `docs/design/architecture.md` §4 (manual clients), косвенно — `execution-model.md` §5 (маленький LLM-вызов компакции с пересказом).

**Реально:** `StructuredOutputConverter<T>` (метод `getJsonSchema()` — since 2.0.0) + `ChatClient.CallResponseSpec.entity(converter)` (Javadoc 2.0.0). Prompt-based конвертеры (BeanOutputConverter и др.) работают с любым OpenAI-совместимым бэкендом; для компакции (судья → COMPACT.covers) достаточно entity-вызова.

**Вердикт: OK.**

---

## B. MCP-клиент Spring AI — bearer, автообновление OAuth

### 6. Bearer-токены и автообновление OAuth для 12+ серверов за SSO-прокси — RISK

**Обещано:** `agent-tools.md` §3 («клиент Spring AI; серверы из конфигурации (корпоративные 12+ за SSO-прокси), токены обновляет сервер — агент про OAuth не знает»), `glossary.md` §6 («токены обновляются автоматически»), `architecture.md` §1 (integration: MCP-клиенты SSO).

**Реально:**
- Транспорт: Spring AI 2.0 starter даёт `spring.ai.mcp.client.streamable-http.connections.<name>.url` (`McpStreamableHttpClientProperties`), кастомизацию через `McpClientCustomizer<AsyncSpec/SyncSpec>` (`McpAsyncClientConfigurer`/`McpSyncClientConfigurer`, Javadoc 2.0.0). WebFlux-транспорты MCP переехали в координаты `org.springframework.ai` с Spring AI 2.0 (java.sdk.modelcontextprotocol.io/latest/client).
- Заголовки: SDK-транспорт `HttpClientStreamableHttpTransport` поддерживает «Custom HTTP request customization» (MCP Java SDK docs, client page; см. также issue modelcontextprotocol/java-sdk#458 про `httpRequestCustomizer.customize`) — заголовок Authorization можно вычислять **на каждый запрос**, что и нужно для ротирующихся токенов.
- OAuth-обновление: **готового «включил и забыл» нет ни в starter'е, ни в SDK**. Официальный рецепт Spring — статья «MCP Authorization in practice with Spring AI and OAuth2» (spring.io/blog/2025/05/19/spring-ai-mcp-client-oauth2): своя интеграция `spring-security-oauth2-client` + `ExchangeFilterFunction`, подставляющая токен `OAuth2AuthorizedClientManager`'а. Для JDK HttpClient-транспорта эквивалент — `httpRequestCustomizer` с чтением из кэша токенов.
- Массовость: 12+ серверов × 24-часовые SSO-сессии = per-server реестр клиентов авторизации, обработка 401 → refresh → retry, синхронизация при одновременном протухании. Это реальная инженерная работа, а не конфигурация.

**Вердикт: RISK** (не блокер: все крючки API есть; риск — недооценка объёма работы и отсутствие готовой автоподстановки).

**Альтернатива/действие:** (1) в `integration`-модуле заложить `McpTokenProvider` (per server) + `httpRequestCustomizer`, читающий актуальный bearer из кэша `OAuth2AuthorizedClientManager` (client_credentials/refresh_token против Keycloak); (2) при 401 от MCP-сервера — инвалидировать кэш и повторить один раз; (3) в дизайн-док уточнить формулировку «токены обновляет сервер» → «обновляет harness (integration-модуль), прозрачно для агента».

---

## C. Spring Boot 4.1 — ручной datasource, порядок инициализации, стартеры

### 7. FQCN исключённых автоконфигураций — OK

**Обещано:** `application.yml` (exclude `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`), `architecture.md` §4.

**Реально:** в Boot 4 кодовая база модуляризована; DataSource-автоконфиг живёт в модуле `spring-boot-jdbc` под новым пакетом `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` (файл META-INF/spring/...AutoConfiguration.imports в spring-projects/spring-boot). Liquibase-автоконфиг — `org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration` в модуле `spring-boot-liquibase`. Указанный в yml FQCN корректен.

**Вердикт: OK.**

### 8. Порядок preliquibase → liquibase → пул при выключенном auto-DS — RISK (нюансы, не блокер)

**Обещано:** `architecture.md` §4 («datasource собирается вручную — порядок preliquibase → liquibase → пул»).

**Реально:**
- Pre-Liquibase 2.0.x — «Current version for Spring Boot 4.x» (таблица совместимости README, lbruun-net/Pre-Liquibase; артефакт 2.0.0 выпущен 2025-11-25). Версия pom (2.0.0) — актуальная линия.
- Регрессия Boot: README предупреждает, что Boot **3.5.8 и 4.0.0** содержат регрессию, ломающую Pre-Liquibase (issue lbruun-net/Pre-Liquibase#45); рекомендовано 4.0.1+. Проект на **4.1.1** — не задет.
- **Ловушка №1:** «Pre-Liquibase assumes that you are using auto-configuration for Liquibase. If you are manually configuring a bean of type `SpringLiquibase` then Pre-Liquibase will not fire» (README, Additional notes; issue #5). Дизайн отключает только `DataSourceAutoConfiguration`, Liquibase-автоконфиг остаётся и по документации Boot подхватит ручной бин `DataSource` («Spring Boot reuses your javax.sql.DataSource anywhere one is required, including database initialization»; как-to: data-access) — значит, PreLiquibase сработает **пока** команда не определит `SpringLiquibase` вручную. Стоит определить `spring.liquibase.url/user` нет, а просто бин DataSource — этого достаточно.
- **Терминологическая неточность:** «пул последний» — бин пула (HikariDataSource) создаётся первым; реальная последовательность: бин пула (ленивые соединения) → PreLiquibase SQL → Liquibase changelog → Hibernate/JPA. Поведение дизайна (схема существует до миграций, миграции до JPA) достигается, но формулировка «порядок сборки бинов» не соответствует механике Spring.
- Пул вручную: рецепт из Boot 4.1 how-to — `DataSourceProperties` + `DataSourceBuilder.initializeDataSourceBuilder()` (документация data-access, v4.1.0).

**Вердикт: RISK** (работает при соблюдении двух условий; оба легко нарушить по незнанию).

**Альтернатива/действие:** (1) запретить ручной `SpringLiquibase` (ArchUnit/ревью-правило): Liquibase остаётся на автоконфигурации с ручным DataSource; (2) либо, если `SpringLiquibase` понадобится ручной, — создавать бины PreLiquibase вручную по образцу example2 из Pre-Liquibase; (3) поправить формулировку в `architecture.md` §4 на «пул (ленивые коннекты) → preliquibase → liquibase → JPA».

### 9. Изменения стартеров/пакетов Boot 4, влияющие на план — OK

**Обещано:** `architecture.md` §4 (стек), `pom.xml` (spring-boot-starter-liquibase, starter-data-jpa, starter-web, starter-validation).

**Реально:** Boot 4.0 провёл полную модуляризацию кодовой базы («A complete modularization of the Spring Boot codebase», spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now); технологии без стартеров получили их в 4.x (Liquibase/Flyway — `spring-boot-starter-liquibase`/`-flyway`); Jackson 3 — дефолт (см. находку 14). Все стартеры из pom существуют в 4.1; Boot 4.1.1 — патч-релиз от 2026-08-20 (98 багфиксов; spring.io/blog/2026/08/20).

**Вердикт: OK.**

---

## D. ShedLock 7.9 × Boot 4

### 10. Совместимость, lockAtMostFor, Mode — OK

**Обещано:** `architecture.md` §4 (ShedLock 7.9 jdbc-template, singleton-джобы), `execution-model.md` §1 (POLL-джоба раз в ~5 сек, `lockAtMostFor` ~10 сек, «отпускает сама»), §8.

**Реально:** официальная матрица совместимости README (lukas-krecan/ShedLock): «7.x.x — Minimal JVM 17 — Tested with Spring 7.0, 6.2, **Spring Boot 4.x**, 3.5, 3.4». То есть ShedLock 7.9.0 официально протестирован с Boot 4.x/Spring Framework 7. Семантика подтверждена: лок держится на время исполнения и освобождается по завершении; `lockAtMostFor` — страховка на падение JVM (должен быть заметно больше максимального времени джобы); `lockAtLeastFor` — против гонок коротких задач; режим по умолчанию PROXY_METHOD (AOP вокруг метода), PROXY_SCHEDULER deprecated. `JdbcTemplateLockProvider` + `usingDbTime()` (UTC по часам БД, «prevents INSERT conflicts») — рекомендуемый провайдер для PostgreSQL; таблица `shedlock(name PK, lock_until, locked_at, locked_by)`. 5с/10с из дизайна — согласованы (джоба сканирования заведомо быстрее 10 с).

**Вердикт: OK.** Действие: рассмотреть бамп до актуальной 7.10.1 (текущая линия); `usingDbTime()` — обязательно; `lockAtLeastFor` не нужен (CAS-лок сессий — отдельный механизм).

---

## E. Java 25 — виртуальные потоки

### 11. Виртуальные потоки × блокирующий spawn_subagent — OK

**Обещано:** `glossary.md` §5, `execution-model.md` §4/§8 (родительский виртуальный поток ждёт дочерний Turn; все Turn'ы — виртуальные потоки процесса).

**Реально:** блокирующее ожидание на виртуальном потоке — штатный сценарий (parking/unparking, дешёвые тысячи параллельных ожиданий). Дочерний Turn в своём ВП, родитель блокируется на ожидании его завершения — масштабируется линейно по числу сессий, ограничение — только пул соединений к БД (нужен разумный максимум Hikari).

**Вердикт: OK.**

### 12. Pinning-риски (synchronized в драйверах/JDBC) — OK (снято JEP 491)

**Обещано:** `architecture.md` §4 («виртуальные потоки для Turn'ов»), риск-контекст задачи.

**Реально:** **JEP 491 «Synchronize Virtual Threads without Pinning» доставлен в JDK 24** (openjdk.org/jeps/491; обзор: mikemybytes.com/2025/04/09/java24-thread-pinning-revisited): блокировка в `synchronized` и `Object.wait()` больше не пинит carrier-поток. На Java 25 рекомендации «замените synchronized на ReentrantLock» больше не действуют — `synchronized` в PgJDBC и внутри библиотек не является риском масштабирования. Остаточный пиннинг — только нативные фреймы (JNI) и некоторые файловые операции; PgJDBC — чистая Java, docker-java — сетевой I/O (сокеты не пинят).

**Вердикт: OK.** Действие: в дизайн-док можно снять/пометить неактуальным пункты о pinning-рисках synchronized (если такие формулировки появятся в M1-спеках).

---

## F. docker-java

### 13. Актуальность API под JDK 25; контейнеры с лимитами; монтирование тома — OK

**Обещано:** `architecture.md` §2/§4 (`ContainerWorkspaceTools`, per-session контейнер `harness-<sessionId>`, том, docker.sock), `execution-model.md` §4/§8 (лимиты `cpus=2`, `memory=2g`, `pids-limit=512`; события смерти контейнера), `agent-tools.md` §1, `glossary.md` §6 (D-30).

**Реально:** проект жив и активен: последний релиз **3.7.1** (2026-03-18), до него 3.7.0 (2025-11), 3.6.0 (2025-08) — github.com/docker-java/docker-java/releases. В 3.7.0 default Docker API поднят до 1.44; в 3.7.0/3.6.0 добавлены propagation-режимы монтирования (`Bind.parse`: rslave/rshared/rprivate) и wait-условия контейнера; в 3.5.x — аннотации HostConfig, исправлены типы int64 в InspectContainerResponse. Требуемые дизайном лимиты покрываются `HostConfig` API: `withNanoCpus(2_000_000_000)` (эквивалент `--cpus=2`), `withMemory(2 * 1024**3)`, `withPidsLimit(512)`; том — `withBinds(new Bind(hostPath, new Volume(containerPath)))`; события смерти — `eventsCmd` + проверка статуса при попытке досылки результата (что и предписывает D-30). JDK 25 ограничений нет (библиотека на современном стеке: httpclient5, netty 4.2, japicmp-контроль совместимости).

**Вердикт: OK.** Действие: в pom зафиксировать явно версию docker-java (3.7.1) — в pom его ещё нет; учитывать, что docker-java тянет собственный Jackson 2 (управляет версиями независимо с 3.7.0) — с Boot BOM конфликтов не ожидается, но следить за convergence.

---

## G. Hibernate/Jackson — jsonb и UUID v7

### 14. jsonb-маппинг (graph_jsonb, params/payload/permissions) — RISK (известная ловушка Boot 4 + Hibernate 7.2 + Jackson 3)

**Обещано:** `glossary.md` §3 (`graph_jsonb`), §4 (`payload_jsonb`), §2 (`params_jsonb`), `architecture.md` §4 (PostgreSQL + JPA).

**Реально:**
- Механика маппинга: `@JdbcTypeCode(SqlTypes.JSON)` — канонический способ для PostgreSQL jsonb в Hibernate 6.2+ и в 7.x (thorben-janssen.com/persist-postgresqls-jsonb-data-type-hibernate; docs Hibernate).
- **Ловушка:** Spring Boot 4.x по умолчанию использует **Jackson 3** (`tools.jackson`, бин `JsonMapper`, `spring-boot-starter-json` — docs Boot 4.1, features/json.adoc), а Boot **4.1** управляет **Hibernate 7.2.x** (релиз-ноты spring-boot: «Upgrade to Hibernate 7.2.19.Final»). Встроенная поддержка Jackson 3 FormatMapper появилась только в Hibernate **7.3** («What's New in 7.3»: «Support for Jackson 3 JSON and XML FormatMappers... alongside the existing Jackson 2 support»). На Hibernate ≤7.2 без Jackson 2 в classpath JSON-поля падают с `Could not find a FormatMapper for the JSON format` (discourse.hibernate.org/t/11819; разбор «Spring Boot 4 + Jackson 3 + Hibernate JSON» — известный trap, linkedin.com/pulse/...aleman-rojas).
- Спасение для проекта уже в pom: `jackson-datatype-jsr310` (2.x) транзитивно тянет `jackson-databind` 2.x, а Boot 4.1 BOM по-прежнему управляет Jackson 2 (2.21.4) — значит Hibernate найдёт Jackson2-FormatMapper и jsonb заработает из коробки. Но зависимость «случайная» (jsr310 нужен для дат), и её removal сломает jsonb молча и неочевидно.

**Вердикт: RISK.**

**Альтернатива/действие:** (1) явная зависимость `com.fasterxml.jackson.core:jackson-databind` (версия — из Boot BOM) с комментарием «нужен Hibernate JSON FormatMapper до Hibernate 7.3»; (2) либо явный `hibernate.json_format_mapper` + собственный FormatMapper на Jackson 3 (`tools.jackson`); (3) после перехода на Boot 4.2+/Hibernate 7.3+ — мигрировать на встроенный Jackson 3 mapper и убрать Jackson 2; (4) в тесты — smoke-тест round-trip jsonb-поля (graph_jsonb), чтобы падение поймалось на CI, а не в проде. Плюс помнить: dirty-checking JSON-полей — сравнение значения целиком (для append-only `payload_jsonb` неактуально; для мутируемых `graph_jsonb`/`params_jsonb` — ожидаемое поведение).

### 15. Генератор UUID v7 как PK — OK

**Обещано:** брифинг («самописный UUID v7 генератор»); `api-contracts.md` §3.2 (UUIDv7-идентификаторы в курсорах задач).

**Реально:** Hibernate (аннотация `org.hibernate.annotations.UuidGenerator`, файл в master hibernate-orm) поддерживает `style = Style.VERSION_7` (`UuidVersion7Strategy`, помечен `@Incubating`) и — что важнее для «самописного» генератора — **официальный крюк `algorithm()`: Class<? extends UuidValueGenerator>`** — кастомный алгоритм подключается без reimplementing `IdentifierGenerator`. То есть самописный генератор встраивается декларативно: `@UuidGenerator(algorithm = OwnUuidV7Generator.class)` (при style=AUTO), либо можно вовсе взять встроенный VERSION_7. UUID как PK в PostgreSQL (`uuid`-тип) — нативно; time-ordered v7 хорош для индексов. Замечание: у встроенных time-based стратегий внутренний счётчик синхронизирован (`synchronized`) — на Java 25 пиннинга нет (JEP 491), при экстремальных TPS — свой `UuidValueGenerator` закрывает и это.

**Вердикт: OK.** Действие: решить в спеке M1 — встроенный `Style.VERSION_7` (достаточно почти наверняка) или свой генератор через `algorithm()`; учесть `@Incubating`-статус (возможны мелкие изменения API между 7.2→7.3).

---

## Итог

- **12 OK** — обещания дизайна воспроизводимы на актуальных API без отклонений: Spring AI 2.0 (ручные ChatModel/тулы/стрим/структурированный вывод/отказ от ChatMemory), Boot 4.1 (FQCN, стартеры), ShedLock 7.9×Boot 4.0, виртуальные потоки (включая снятый JEP 491 pinning), docker-java 3.7.1 (лимиты/тома/события), UUID v7 (встроенный стиль + крюк для самописного).
- **3 RISK, 0 BLOCKER** — все три риска имеют дешёвые компенсации, но требуют **явных действий до/в начале M1**:
  1. **jsonb×Jackson 3 (находка 14):** Boot 4.1 = Hibernate 7.2 = нет Jackson 3 FormatMapper. Явно держать Jackson 2 databind + smoke-тест jsonb. 
  2. **MCP OAuth (находка 6):** автообновление токенов — самописная прослойка (customizer/ExchangeFilterFunction + OAuth2AuthorizedClientManager) на 12+ серверов; заложить в план integration-модуля.
  3. **PreLiquibase (находка 8):** молча не сработает при ручном `SpringLiquibase`; правило ревью + уточнение формулировки порядка инициализации в architecture.md §4.

---

## Cross-check (ретро-тема 1)

Сверка с отчётами коллег: `aspect1-stack-deepseek.md` (нумерация F1–F25) и `aspect1-stack-mercury.md`.
К моменту кросса уже сняты/закрыты судьёй и владельцем: UUID v7 (генератор владельца), пиннинг (JEP 491 — в JDK 25 отсутствует), ShedLock → 7.10.1 (в pom), существование preliquibase 2.0.0 (repo1), Jackson3-ловушка и таймауты #6915 (записаны в architecture.md), callId-маппинг (в agent-tools.md).

Критерии: CRITICAL=BLOCKER, MAJOR=RISK из отчётов коллег.

### DeepSeek (13 CRITICAL/MAJOR: F2, F3, F6, F8, F10, F11, F13, F15, F19, F21, F22, F23, F24-часть)

| # | Пункт | Суть находки | Вердикт | Комментарий |
|---|---|---|---|---|
| F2 | RISK | Дубликат `tool_call_id` (плейсхолдер + поздний результат) расходится с append-only историей OpenAI API | **agree-was-valid** | Закрыто: callId-маппинг зафиксирован в agent-tools.md — рендер не обязан подавать провайдеру два `tool`-сообщения с одним id (поздний результат уходит вне tool-роли / с маппингом id). Опасность снята контрактом рендера. |
| F3 | RISK | Транспорт OpenAI в 2.0 — `openai-java` SDK; гарантии, что dispose Flux останавливает генерацию провайдера, нет (#6441) | **agree-was-valid** | Признаю: мой исходный тезис исходил из WebClient-транспорта — в 2.0 модуль действительно переехал на openai-java. Правильность отмены обеспечивает кооперативная схема (cancel_requested между раундами); прекращение потока токенов на стороне провайдера — интеграционный тест «cancel → нет новых токенов» (альтернатива DeepSeek). Остаётся открытым как действие, не блокер. |
| F6 | RISK (пот. BLOCKER) | #6915: per-prompt `OpenAiChatOptions` навязывает 60 с таймаут / 3 ретрая в 2.0.1 | **agree-was-valid** | Закрыто: обход (`.timeout()`+`.maxRetries()` в каждом per-prompt options + тест «ход > 60 с») записан в architecture.md. |
| F8 | BLOCKER | MCP+OAuth: зависимостей и модели токенов нет в сборке | **agree-was-valid** (severity оспорена) | Факт верен (pom не содержит `spring-ai-starter-mcp-client`/oauth2-client — подтверждаю). Но хуки API существуют (MCP request-customizer, OAuth2-кастомайзеры поверх `OAuth2AuthorizedClientManager` — docs mcp-security), а pom — скелет: зависимости появляются в фазе integration. Мой вердикт RISK остаётся; BLOCKER завышен. |
| F10 | RISK | Исключение DS-автоконфига избыточно (`@ConditionalOnMissingBean`); порядок «preliquibase → liquibase → пул» неточен; ручной `SpringLiquibase` глушит PreLiquibase | **agree-was-valid** | Совпадает с моей находкой 8. Тезис «исключение не обязательно» технически справедлив, но дизайн сознательно требует ручной сборки — принимаем фиксацию порядка «пул (ленивый) → preliquibase → liquibase → JPA» + правило «не объявлять SpringLiquibase вручную». Открыто. |
| F11 | RISK | preliquibase 2.0.x в матрице — для Boot 4.0.x; 4.1.x явно не заявлен | **agree-was-valid** | Существование артефакта подтверждено (repo1, снято), но комбинация ×4.1.1 остаётся непроверенной — smoke-тест «schema до Liquibase» на 4.1.1 в план M1. Открыто. |
| F13 | BLOCKER | Keycloak OIDC ничем не обеспечен в сборке | **agree-was-valid** (severity оспорена) | Факт верен (в pom нет security/resource-server стартеров — подтверждаю). Pom — скелет; auth — более поздняя фаза по roadmap (API уже спроектирован: ticket §1.4). RISK/задача фазы, не блокер дизайн-базиса. |
| F15 | RISK (minor) | `lockAtMostFor` — safety net, не лимит времени; 10 с мало при длинном скане | **agree-was-valid** | Разумная подстройка: `lockAtMostFor` 20–30 с, запуск `fixedDelay`, `LockAssert.assertLocked()`. Minor, открыт. |
| F19 | BLOCKER | docker-java отсутствует в pom | **agree-was-valid** (severity оспорена) | Факт подтверждён (в pom нет docker-java — я это отмечал и сам). Задача фазы D-30: `docker-java-core` + `docker-java-transport-httpclient5`, 3.7.x. Не противоречие дизайна — незаполненный скелет. |
| F21 | RISK (minor) | docker-java тащит собственный Jackson 2 на Boot 4/Jackson 3 | **agree-was-valid** | Совпадает с моим примечанием к находке 13 (независимое управление версиями Jackson с 3.7.0; следить за convergence). Minor. |
| F22 | RISK | jsonb не работает «из коробки» на Jackson 3 (Hibernate ≤7.2 ищет Jackson 2 FormatMapper) | **agree-was-valid** | Закрыто: Jackson3-ловушка записана в architecture.md. Совпадает с моей находкой 14 (Boot 4.1 = Hibernate 7.2.x). |
| F23 | RISK | `jackson-datatype-jsr310` — легаси, но транзитивно «спасает» jsonb — скрытая хрупкая связь | **agree-was-valid** | Закрыто вместе с F22: явная зависимость Jackson 2 databind (до Hibernate 7.3) + smoke-тест round-trip jsonb. |
| F24 | RISK-часть (курсор) | UUID v7 time-ordered, но не строго монотонен внутри мс — «монотонный курсор» в api-contracts §3.2 неточен | **agree-was-valid** | Закрыто владельцем (собственный генератор). Нюанс монотонности внутри мс покрыт генератором владельца. |

Итог DeepSeek: 13/13 agree-was-valid; 4 severity-пометки (F8, F13, F19 — BLOCKER→RISK/фазовая задача; F6 — BLOCKER-квалификация снята записью обхода).

### Mercury (3 CRITICAL/MAJOR)

| # | Пункт | Суть находки | Вердикт | Комментарий |
|---|---|---|---|---|
| §1 | RISK | Отмена стрима: «в Spring AI нет встроенной отмены; нужна ручная агрегация `ChatClientMessageAggregator` + `StreamedChatResponse` + `Disposable`» | **disagree** | `Flux`, возвращаемый `stream()`, и есть штатный механизм отмены (cancel/dispose — реактивный контракт Spring AI); агрегатор к отмене отношения не имеет (он собирает полный ответ из стрима). Класса `StreamedChatResponse` в Spring AI не существует. Обоснованная доля (провалидировать обрыв на стороне провайдера) корректно покрыта у DeepSeek (F3). |
| §5 | RISK | Pinning: «VT пинается на synchronized; pgjdbc может использовать synchronized; заменить на ReentrantLock» | **stale** | Воспроизведена рекомендация эпохи JDK 21: JEP 491 доставлен в JDK 24 — synchronized/Object.wait больше не пинят carrier (в JDK 25 пиннинга нет), замена на ReentrantLock официально более не рекомендуется. Снято владельцем; остаточный пиннинг — только JNI/часть файлового I/O. |
| §7 | RISK | UUID v7: «Hibernate не имеет встроенной поддержки v7; нужен кастомный IdentifierGenerator или com.fasterxml.uuid» | **stale** | Устарело: `@UuidGenerator` имеет `Style.VERSION_7` (+ официальный крюк `algorithm()` для самописного `UuidValueGenerator`) — проверено по исходнику hibernate-orm (см. мою находку 15). Вопрос закрыт генератором владельца. |

### Попутные наблюдения (вердикты OK у Mercury — вне CRITICAL/MAJOR, но перекос фиксирую)

- Mercury «MCP OAuth: OK» игнорирует отсутствие зависимостей в pom и per-server wiring на 12+ серверов — см. F8/моя находка 6 (RISK).
- Mercury «datasource: OK» с альтернативой `@AutoConfigureBefore/@AutoConfigureAfter` — неверный механизм: эти аннотации упорядочивают автоконфигурации, не ручные бины (для ручных — граф зависимостей/`@DependsOn`); ловушка ручного `SpringLiquibase` не замечена (см. F10/моя находка 8).
- Альтернатива Mercury `@Type(JsonBinaryType)` — класс не из Hibernate core (это hypersistence-utils/vladmihalcea-линейка) — не применять.

### Счёт кросса

16 CRITICAL/MAJOR-пунктов коллег (DeepSeek 13, Mercury 3): **agree-was-valid — 13, disagree — 1, stale — 2.**
