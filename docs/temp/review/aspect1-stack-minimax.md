# Stack Review: соответствие обещаний дизайна реальным API актуальных версий

> Дата: 2026-09-17
> Reviewer: MiniMax-M3 (subagent)
> Объект: поведенческие обещания дизайн-документов (`docs/glossary.md`, `docs/design/architecture.md`, `docs/design/execution-model.md`, `docs/design/agent-tools.md`, `docs/design/api-contracts.md`), завязанные на стек.
> Метод: сопоставление каждого обещания с актуальной документацией (Spring Boot 4.1.1 release notes, Spring AI 2.0.1 release notes, OpenJDK 24/25 JEPs, ShedLock support matrix, docker-java wiki, Hibernate 7.4 docs, Maven Central).
> Не входит: ревью контрактов API/REST/WS, контракт-уровень SecurityMultitenancy, DDL/JPQL — это скоупы api-ревизии и sql-ревью.

## Сводка

| Severity | Кол-во |
|---|---|
| OK      | 14 |
| RISK    | 9 |
| BLOCKER | 2 |

Топ-3:

1. **BLOCKER — `ShedLock 7.9` не существует в Maven Central.** Реальная последняя опубликованная версия `shedlock-spring` — `6.6.0` (см. [BLOCKER-1](#blocker-1-shedlock-7-как-артефакт-не-опубликован)).
2. **BLOCKER — `preliquibase-spring-boot-starter 2.0.0` не существует в Maven Central.** Реальная последняя версия — `1.6.1` (см. [BLOCKER-2](#blocker-2-preliquibase-spring-boot-starter-2-0-0-не-опубликован)).
3. **RISK — `synchronized`-пиннинг в PostgreSQL JDBC 42.7.13 и драйверах docker-java.** JEP 491 (JDK 24, перенесён в JDK 25) снимает пиннинг для `synchronized`-методов, но не для JNI/FFM/native-frames (см. JEP 491 «Future Work»); наша страховка — синхронные блокировки переписать на `ReentrantLock` или вынести JNI-блоки (см. [RISK-3](#risk-3-пиннинг-в-jdbcdocker-несмотря-на-jep-491)).

---

## 1. Spring AI ChatClient

### OK-1: ChatClient.structured output / tool callbacks

**Обещано** (`architecture.md` §3 контракт `LlmGateway`, `agent-tools.md` §2 `transition(tool, reason)` — обязательный аргумент; `execution-model.md` §4 «инструмент декларирует `sync | async`», §5 «страховочная компакция — маленький LLM-вызов»): LLM-шлюз должен стримить, отменять стрим, считать токены, вызывать кастомные инструменты с обязательными полями, поддерживать structured output для служебных вызовов (компакция, `transition.reason`).

**API**: `org.springframework.ai.chat.client.ChatClient` имеет `.stream().content() → Flux<String>`, `.stream().chatResponse() → Flux<ChatResponse>`, `.stream().chatClientResponse() → Flux<ChatClientResponse>` ([Spring AI 2.0 reference — ChatClient Streaming](https://docs.spring.io/spring-ai/reference/2.0/api/chatclient.html)). Кастомные инструменты — `FunctionToolCallback.builder(name, fn)...build()` или прямая реализация `ToolCallback.call()`. Параметр `resultConverter` (`DefaultToolCallResultConverter` по умолчанию — Jackson) — расширяется ([Spring AI — Tools Result Conversion](https://docs.spring.io/spring-ai/reference/2.0/api/tools.html)). Structured output — `.entity(Class)`/`.parameterizedType(TypeReference)` (присутствует в ChatClient 2.0). Кастомные поля (`callId`, `late`, `truncated`) серилизуются конвертером; десерилизует их уже **наш** движок, который сам сопоставляет `call_id` с `TOOL_RESULT` строкой.

**Вердикт: OK.**

### OK-2: ChatClient отмена стрима между tool-calls

**Обещано** (`execution-model.md` §2 «если `cancel_requested` → статус CANCELLED, выход»; §6 «проверка между вызовами инструментов»; §6 «отмена поддерева»): при `cancel_requested` стрим должен прерываться между вызовами инструментов и при гонке с активным model-вызовом.

**API**: `Flux<...>` от `chatClient.stream()` поддерживает стандартную отмену через `subscription.dispose()` / `Mono#cancel`. Spring AI 2.0.1 закрыл два известных бага по этой теме:
- `#6656 Close OpenAI stream response on cancellation` (Spring AI 2.0.1 release notes) — ранее стрим лиал сокеты при отмене;
- `#6759 Fix OpenAiAudioTranscriptionModel leaking the stream on cancellation` — для аудио.

В Spring AI 2.0.1 поведение теперь корректное: при отписке от `Flux` HTTP-stream к провайдеру закрывается. Наш код отменяет подписку (`subscription.dispose()`) при `cancel_requested` между раундами; для между-tool-cancel проверяем флаг до вызова `toolCallingManager.executeToolCalls`.

**Вердикт: OK** — поведение достижимо стандартным Reactor API, специфика наша (как именно мы отдаём `Flux` в SSE-стрим и где храним `Subscription`).

### RISK-1: Кастомный `callId` / поздний результат — наш слой, не Spring AI

**Обещано** (`glossary.md` §4 «поздний результат `TOOL_RESULT` с тем же `call_id`, флаг `late=true`»; `agent-tools.md` §5 «единый контракт результата { callId, tool, status, output, exitCode, truncated, late }»): Spring AI должен отдавать `call_id` в нашем формате, чтобы движок мог сопоставить `TOOL_CALL` ↔ `TOOL_RESULT` и корректно подвесить `late=true`.

**API**: В Spring AI `ToolCallback.call(ToolCall)` возвращает `String` (или `ToolExecutionException`). `ToolCall.id` существует и приходит от провайдера (OpenAI возвращает `call_xyz`), но мы **не можем** его переопределить — это идентификатор LLM-провайдера. `call_id` в нашем формате (`UUID v7` из `IdGenerator`) **не** то же самое, что провайдерский id.

**Решение**: наш `IdGenerator` генерирует `call_id` и кладёт его в `ToolContext` (Spring AI 2.0 поддерживает `.toolContext(Map)` на ChatClient — см. [MCP Client Boot Starter docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)). Наш `ToolCallback`:
1. Получает `ToolCall.id` (провайдерский) и `ToolContext` с нашим `call_id`.
2. Возвращает `String` в формате JSON `{ "call_id": "...", "output": "...", "truncated": false }` через кастомный `ToolCallResultConverter`.
3. Движок сам сохраняет `TOOL_CALL` с нашим `call_id` (по `ToolContext`) и сопоставляет `TOOL_RESULT` с тем же `call_id`.

**Вердикт: RISK.** Семантика отличается от наивной — наш `call_id` живёт в `ToolContext`/конвертере, **не** в API провайдера. Требуется явное документирование в `intelligence` модуле: какой идентификатор где.

### RISK-2: Async-инструменты (окно 30 сек → плейсхолдер → поздний TOOL_RESULT) — наш слой

**Обещано** (`execution-model.md` §4 «окно ~30 сек → плейсхолдер `ASYNC_ACCEPTED(call_id)` → ход возвращается агенту → фон дорабатывает → второй TOOL_RESULT с тем же call_id, флаг late=true»; `agent-tools.md` §1 «bash — async-capable»).

**API**: Spring AI 2.0 поддерживает `Mono<String>`/`Flux<String>` возврат из `ToolCallback.call()` (через `ToolCallingManager`). Однако `ASYNC_ACCEPTED` как формальная «заглушка до прихода позднего результата» — **наша абстракция**, не API Spring AI. Реализация:
- `ToolCallback.call()` возвращает либо `String` (синхронно), либо завершение во `Mono`/`Flux` (async).
- Если результат не успевает за 30 сек — **наш** wrapper немедленно возвращает JSON-плейсхолдер `{"status":"ASYNC_ACCEPTED","call_id":"..."}`, а фон продолжает работу и дописывает в `session_message` второе `TOOL_RESULT`.

**Вердикт: RISK.** Реализуемо, но нужно явно зафиксировать: `ASYNC_ACCEPTED` ≠ API Spring AI, это договорённость между нашим движком и моделью. Модель должна «знать» формат плейсхолдера (т.е. он попадает в system prompt через manifest).

### OK-3: ChatMemory / подмена Advisor на SessionStore

**Обещано** (`architecture.md` §3 `SessionStore` «единственная дверь к сессиям/сообщениям (append-only)»; `glossary.md` §2 «Из `LlmModel` собирается per-agent `ChatModel` (автоконфигурации Spring AI отключены намеренно)»): наш `SessionStore` заменяет стандартный `ChatMemory` (JPA/in-memory); автоконфиги Spring AI отключены, ChatClient собирается вручную.

**API**: `MessageChatMemoryAdvisor.builder(chatMemory).build()` — точка кастомизации `ChatMemory` ([Spring AI — MessageChatMemoryAdvisor](https://docs.spring.io/spring-ai/reference/2.0/api/chat-memory.html)). В Spring AI 2.0 `PromptChatMemoryAdvisor` удалён (upgrade notes — «Code that imports or references PromptChatMemoryAdvisor will fail to compile»). Реализация интерфейса `ChatMemory` (методы `add`, `get`, `clear`) — наш адаптер к `SessionStore`.

**Вердикт: OK.** Подмена достигается через `defaultAdvisors(MessageChatMemoryAdvisor.builder(ourChatMemoryAdapter).build())` на нашем `ChatClient`, который мы собираем руками после отключения автоконфигов.

---

## 2. MCP-клиент Spring AI

### OK-4: Bearer-токены / OAuth2

**Обещано** (`agent-tools.md` §3 «серверы из конфигурации (корпоративные 12+ за SSO-прокси), токены обновляет сервер — агент про OAuth не знает»).

**API**: Spring AI 2.0 имеет выделенный security-модуль [spring-ai-mcp-client-security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html). Поддерживаются три OAuth2-флоу:
- Authorization Code Flow (user-context)
- Client Credentials Flow (machine-to-machine)
- Hybrid Flow (initialize/tools/list без юзера, tool-calls — с юзером)

Токены пробрасываются через:
1. `McpSyncHttpClientRequestCustomizer` (HttpClient-based transport) — `OAuth2AuthorizationCodeSyncHttpRequestCustomizer(clientManager, "authserver")`;
2. WebClient-based — `McpOAuth2AuthorizationCodeExchangeFilterFunction`;
3. `spring.security.oauth2.client.*` properties.

**Вердикт: OK.**

### RISK-4: OAuth2 поддержан только для sync-клиента

**Обещано**: «корпоративные 12+ за SSO-прокси» (не уточняет sync/async).

**Реальность**: Spring AI 2.0 explicitly: *"IMPORTANT: This module supports `McpSyncClient` only."* ([MCP Client Security docs](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)).

**Вердикт: RISK.** 12+ MCP-серверов с OAuth2 — это sync транспорт. Если в будущем захотим async-клиент для реактивного исполнения (например, чтобы MCP-вызовы не блокировали virtual threads), нужно либо дописать свой `OAuth2ReactiveAuthorizationCodeExchangeFilterFunction` (нет в Spring AI 2.0), либо ждать Spring AI 2.x расширения. На сейчас sync OK.

### RISK-5: Токен-рефреш — конфигурируемая «session TTL 24ч»

**Обещано** (`agent-tools.md` §3 «токены обновляет сервер»; `decisions.md` D-32 имплицитно — права = права владельца): 24-часовая SSO-сессия должна рефрешиться автоматически.

**Реальность**: Spring AI полагается на Spring Security `OAuth2AuthorizedClientManager` — стандартный механизм с `RefreshToken` (если он есть) или повторным authorization-code / client-credentials. Refresh TTL зависит от `clientRegistration.refresh-token-validity` или от `provider.user-info-uri`/конфигурации. **Нет** встроенного «24-часового» SLA — это проставляется на стороне Keycloak client config.

**Вердикт: RISK.** Сервер-сайд настройка Keycloak (`access-token-lifespan`, `refresh-token-max-reuse`), не код приложения. Нужно явно зафиксировать в `integration` модуле или в ops-runbook: «Keycloak client config: access-token-lifespan=1h, refresh-token-max-reuse=24h, sso-session-max-lifespan=24h».

---

## 3. Spring Boot 4.1

### OK-5: Spring Boot 4.1.1 — релиз существует

**Обещано**: parent `spring-boot-starter-parent:4.1.1`.

**Реальность**: [Spring Boot 4.1.1 release](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1) опубликован (содержит список фиксов: bug fixes, dependency upgrades). Hibernate 7.4.5.Final, PostgreSQL JDBC 42.7.13 — на борту.

**Вердикт: OK.**

### OK-6: `DataSourceAutoConfiguration` отключение — работает как обещано

**Обещано** (`architecture.md` §4 «`DataSourceAutoConfiguration` datasource собирается вручную — порядок preliquibase → liquibase → пул»).

**Реальность**: `DataSourceAutoConfiguration` остаётся в `org.springframework.boot.jdbc.autoconfigure` ([Boot 4.1 source](https://github.com/spring-projects/spring-boot/blob/main/module/spring-boot-jdbc/src/main/java/org/springframework/boot/jdbc/autoconfigure/DataSourceAutoConfiguration.java)). Исключение — `spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` в `application.yml` (или `@SpringBootApplication(exclude=...)`). Документация: [Disabling Specific Auto-configuration Classes](https://github.com/spring-projects/spring-boot/blob/main/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/using/auto-configuration.adoc) — `excludeName` поддерживается и для случаев, когда класс не на classpath.

**Вердикт: OK.**

### OK-7: Сборка datasource вручную с порядком preliquibase → liquibase → pool

**Обещано** (`architecture.md` §4 «порядок preliquibase → liquibase → пул»).

**Реальность**: Spring Boot 4.1 не предоставляет автоконфига pre-liquibase; это делается через bean-порядок (`@DependsOn`, `@Order` на `BeanPostProcessor`) и явное объявление бинов `DataSource` → `JdbcTemplate` → `SpringLiquibase`. Схема стандартная, описана в Spring Boot issue-tracker.

**Вердикт: OK** в части архитектуры, но см. [BLOCKER-2](#blocker-2-preliquibase-spring-boot-starter-2-0-0-не-опубликован) про версию артефакта.

### RISK-6: Пакетные переименования в Spring Boot 4 (auto-config packages)

**Обещано** (`architecture.md` §2 `org.springframework.boot.security.autoconfigure` имплицитно через отключение): путь к классам автоконфигов менялся в 4.x.

**Реальность**: В Spring Boot 4 многие автоконфиги переехали:
- `org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration` → `org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration` ([Boot 4 source](https://github.com/spring-projects/spring-boot/blob/main/module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/SecurityAutoConfiguration.java));
- Caching docs: «Caching documentation refers to AutoConfigureCache by its pre-4.0 package #51113» — `AutoConfigureCache` тоже переехал;
- Starter: `spring-boot-starter-web` → `spring-boot-starter-webmvc` ([Boot 4.1.1 release notes](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1) — «Refer to spring-boot-starter-webmvc, not deprecated spring-boot-starter-web #50847»).

**Вердикт: RISK.** При сборке `application.yml` со `spring.autoconfigure.exclude` **необходимо** использовать новые FQCN. Старые имена из Spring Boot 3.x не сработают (Boot 4 поддерживает `excludeName` со старыми именами только если в classpath ещё есть `AutoConfiguration.replacements` mapping — а Security не мигрируется backward). Нужен автотест на старте приложения, что исключения действительно применились (например, через `@SpringBootTest` + `ApplicationContext.getBean(DataSource.class)`).

### RISK-7: 6 автоконфигов Spring AI — какие именно

**Обещано** (`architecture.md` §4 «все 6 автоконфигов Spring AI — клиенты собираются из `LlmModel` вручную»).

**Реальность**: в Spring AI 2.0 на стороне клиента:
1. `spring-ai-autoconfigure-model-chat-openai` (ChatModel)
2. `spring-ai-autoconfigure-model-embedding-openai` (EmbeddingModel — нам не нужен в MVP, но всё равно в classpath)
3. `spring-ai-autoconfigure-model-chat-memory-*` (MessageChatMemoryAdvisor default — нам не подходит, у нас SessionStore)
4. `spring-ai-autoconfigure-mcp-client` (MCP client)
5. `spring-ai-autoconfigure-mcp-client-webflux` (alt MCP client)
6. `spring-ai-autoconfigure-vector-store-*` (vector stores)

Плюс auto-config для tool-calling (`ToolCallingAutoConfiguration`). Список в 6 штук расходится с реальностью — автоконфигов в classpath после `spring-ai-starter-model-openai` **больше**.

**Вердикт: RISK.** Число «6» в дизайне не соответствует факту. Решение: отключить через `@SpringBootApplication(exclude = {...})` или `spring.autoconfigure.exclude` все spring-ai-autoconfigure-*, которые тянутся через `spring-ai-starter-model-openai`. **Однако starter может тянуть и неприятные сюрпризы** — нужно эмпирически проверить, какие бины пытаются зарегистрироваться (через debug-логирование `ConditionEvaluationReport`).

---

## 4. ShedLock 7.9 × Boot 4

### BLOCKER-1: ShedLock 7 как артефакт не опубликован

**Обещано** (`architecture.md` §4 «ShedLock 7.9 (jdbc-template)»; `execution-model.md` §1 «POLL-контур — ShedLock-джоба раз в ~5 сек, `lockAtMostFor` ~10 сек»).

**Реальность (Maven Central, 17.09.2026)**:
- Последний опубликованный `net.javacrumbs.shedlock:shedlock-spring` — **6.6.0** (07.05.2025).
- Поиск `g:net.javacrumbs.shedlock AND a:shedlock-spring AND v:7.*` — **0 результатов**.
- Прочие провайдеры ShedLock (`shedlock-provider-jdbc-template`, `shedlock-provider-redis-jedis4` и т.д.) — все на **6.6.0**.

Документация ShedLock упомянула в support matrix: «Version 7.x requires JVM 17+ and supports Spring Boot 4.x or 3.5+» — это roadmap/будущая версия, **артефакта в Central нет**.

**Вердикт: BLOCKER.** Либо:
- (a) использовать 6.6.0 — но 6.6.0 не тестировался под Spring Boot 4.1.x (Boot 4 вышел позже 6.6.0), могут быть binary-incompatibilities (новые пакеты `*.security.autoconfigure` и т.п.);
- (b) ждать ShedLock 7.x — сроки неизвестны;
- (c) использовать форк или другой велоситити-лок (например, Quartz JDBC clustered, или свой singleton-lock на таблице `idempotency_key` из D-29).

**Рекомендация**: подтвердить, что ShedLock 7.x планируется к выпуску в окне до M1. Если нет — fallback на собственную singleton-джобу через CAS-апдейт (`UPDATE singleton_lock SET locked_by=? WHERE name=? AND (locked_by IS NULL OR locked_at < ?)`).

### OK-8: `@SchedulerLock(name, lockAtMostFor)` API

**Обещано** (`execution-model.md` §1 «`lockAtMostFor` ~10 сек»).

**Реальность**: `@SchedulerLock(name = "wake-poll", lockAtMostFor = "10s")` — стандартный API ShedLock ([Spring Integration docs](https://github.com/lukas-krecan/shedlock/blob/master/_autodocs/05-spring-integration.md)). `lockAtMostFor` принимает ISO-8601 (`PT10S`) или сокращённый (`10s`).

**Вердикт: OK** на уровне API, при условии доступности артефакта.

### OK-9: `JdbcTemplateLockProvider`

**Обещано** (`architecture.md` §4 «jdbc-template»).

**Реальность**: `JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(...).usingDbTime().build()` — присутствует в ShedLock 4.x и 6.x ([ShedLock docs](https://github.com/lukas-krecan/shedlock/blob/master/_autodocs/06-jdbc-lock-provider.md)). Поддерживает PostgreSQL.

**Вердикт: OK** (API), но см. BLOCKER-1 про версию.

---

## 5. Java 25 + виртуальные потоки

### OK-10: JDK 25 GA

**Обещано** (`architecture.md` §4 «Java 25»): LTS-релиз.

**Реальность**: JDK 25 — GA 16 сентября 2025 ([JDK 25](https://openjdk.org/projects/jdk/25/)). LTS-релиз. JEPs: 470, 502, 503, 505, 506, 507, 508, 509, 510, 511, 512, 513, 514, 515, 518, 519, 520, 521.

**Вердикт: OK.**

### OK-11: `Thread.ofVirtual()` / `Thread.startVirtualThread()` API

**Обещано** (`execution-model.md` §8 «один инстанс MVP: все Turn'ы — виртуальные потоки процесса»).

**Реальность**: `Thread.ofVirtual().start(runnable)`, `Thread.startVirtualThread(runnable)`, `Executors.newVirtualThreadPerTaskExecutor()` — все доступны с JDK 21 ([OpenJDK Thread.java source](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/lang/Thread.java), `@since 21`).

**Вердикт: OK.**

### OK-12: JEP 491 — synchronized больше не пиннит

**Обещано** (`execution-model.md` §2 «между вызовами инструментов»; `glossary.md` §5 «CancellationToken-дерево — recovery добивает выжившие»; имплицитно из контекста архитектуры): виртуальные потоки не должны пинниться на блокирующих операциях (иначе не выйдет «50+ пользователей на одной VM»).

**Реальность**: JEP 491 «Synchronize Virtual Threads without Pinning» — **delivered в JDK 24** (GA 18 марта 2025, [JDK 24](https://openjdk.org/projects/jdk/24/)), перенесён в JDK 25 по транзитивности. Цитата JEP 491: *"Improve the scalability of Java code that uses `synchronized` methods and statements by arranging for virtual threads that block in such constructs to release their underlying platform threads for use by other virtual threads."*

**Вердикт: OK.** Все `synchronized`-блоки в JDK-коде и PostgreSQL JDBC 42.7.13 (например, в `PgConnection`, `PgStatement`) больше не пиннят.

### RISK-3: Пиннинг в JDBC/docker несмотря на JEP 491

**Обещано**: «helper-контейнер через docker-java» (`execution-model.md` §4, `architecture.md` §2).

**Реальность** (JEP 491 «Future Work»): JEP 491 не покрывает:
- Class initialization (loading classes inside synchronized resolution — JNI frames);
- Class init waiting (waiting for another thread to initialize class);
- Native frames: FFM (Foreign Function & Memory API) или JNI callbacks.

docker-java ходит в `/var/run/docker.sock` через OkHttp/Jersey — это обычный socket I/O, **не** JNI. PostgreSQL JDBC — обычный JDBC, без JNI callbacks в горячих путях. Поэтому для **нашего** кода JEP 491 покрывает все блокирующие операции.

**Но**: если в classpath окажется библиотека с JNI (BouncyCastle provider native, snappy-jvm, etc.) — там пиннинг возможен.

**Вердикт: RISK.** Нужна проверка classpath — на CI прогонять с `-Djdk.tracePinnedThreads=full` (флаг ещё работает в JDK 25, см. JEP 491 — «The system property will no longer be needed once the synchronized keyword no longer pins... but we will retain [JFR event] for other pinning situations»). Если есть нативные либы — вынести их вызовы за пределы виртуальных потоков (в ForkJoinPool.commonPool() с platform-thread).

### RISK-8: spawn_subagent (синхронный) — блокировка на join в виртуальном потоке

**Обещано** (`glossary.md` §5 «`spawn_subagent` — синхронный; родительский поток ждёт»).

**Реальность**: `Thread.ofVirtual().start(() -> { ... child.join() ... })` — virtual thread блокируется на join, платформенный поток освобождается через механизм **unmounting** (любой `java.util.concurrent` примитив, включая `CountDownLatch.await` и `Thread.join`, корректно анмаунтит virtual thread с JDK 21+). Это работает.

**Но**: если внутри child Turn используется блокирующий JDBC (а у нас `preliquibase`, `Liquibase`, Hibernate — всё блокирующее), то на каждый `session_message` insert/update будет анмаунт. **Это и есть смысл VT** — много одновременных Turn'ов с минимальным числом carrier threads (по умолчанию = `availableProcessors()`, JDK 25 имеет `jdk.virtualThreadScheduler.parallelism`).

**Вердикт: RISK (допустимый).** Должно работать. Нужен нагрузочный тест (M5 критерий): 50+ одновременных Turn'ов на 8-vCPU VM — должно держаться без деградации. И мониторинг: `VirtualThreadSchedulerMXBean.getMountedVirtualThreadCount()` + `jdk.VirtualThreadPinned` JFR events.

### OK-13: `VirtualThreadSchedulerMXBean` для мониторинга

**Обещано**: (не явно, но при 50+ пользователях нужна наблюдаемость).

**Реальность**: `jdk.management` экспортирует `VirtualThreadSchedulerMXBean` с методами `getParallelism()`, `setParallelism()`, `getPoolSize()`, `getMountedVirtualThreadCount()`, `getQueuedVirtualThreadCount()` ([JDK 25 symbols](https://github.com/openjdk/jdk25u-dev/blob/master/src/jdk.compiler/share/data/symbols/jdk.management-O.sym.txt)). Micrometer-биндинг через `JdkVirtualThreadSchedulerMetrics` (есть в Spring Boot 4.x — там же, где `JdkVirtualThreadPinnedMetrics`).

**Вердикт: OK.**

---

## 6. docker-java

### OK-14: docker-java 3.5.1 — актуален на JDK 25

**Обещано** (`architecture.md` §2 «docker-java»; `decisions.md` D-30): создание per-session контейнеров, монтирование workspace, лимиты.

**Реальность**: `com.github.docker-java:docker-java-core` — последняя опубликованная версия **3.5.1** (12.05.2025, [Maven Central](https://search.maven.org/solrsearch/select?q=g:com.github.docker-java+AND+a:docker-java-core)). Требует Java 8+. Совместима с JDK 25.

**Вердикт: OK.**

### OK-15: API лимитов `cpus=2`, `memory=2g`, `pids-limit=512`

**Обещано** (`execution-model.md` §4 «лимиты по умолчанию: cpus=2, memory=2g, pids-limit=512; сеть — только состояниям, которым нужен git-клон»).

**Реальность** (поиск docker-java wiki / source): `HostConfig` поддерживает:
- `withMemory(long bytes)` — memory limit (наш `2g` = 2*1024*1024*1024 = 2147483648);
- `withCpuShares(long)` / `withCpusetCpus(String)` / `withNanoCpus(long)` — для CPU;
- `withPidsLimit(Long)` — для pids-limit;
- `withNetworkMode(String)` / `withNetworkDisabled(Boolean)` — для сети.

Современный Docker API предпочитает `NanoCpus` (1 cpu = 1e9 nanoCpu = 1000000000). `cpus=2` → `withNanoCpus(2_000_000_000L)`. Параметр `CpuShares` — старый относительный вес (по умолчанию 1024), не подходит для абсолютных лимитов.

**Вердикт: OK** (при условии использования `withNanoCpus` для CPU, не `withCpuShares`). Нужно добавить unit-тест, проверяющий, что сгенерированный `HostConfig` действительно содержит `NanoCpus=2000000000` (а не `CpuShares=1024`).

### OK-16: Монтирование volume (`withBinds`)

**Обещано** (`execution-model.md` §4 «workspace-каталог примонтирован томом»).

**Реальность**: `CreateContainerCmd.withBinds(Bind...)` — стандартный API ([docker-java wiki](https://github.com/docker-java/docker-java/wiki/Home)). `Bind("/host/path", new Volume("/container/path"))` + опционально `withReadOnlyMount(true)`. Поддерживается также named volumes.

**Вердикт: OK.**

### RISK-9: Docker API версия — engine.socket version negotiation

**Обещано**: (не явно, но имплицитно через «docker-java создаёт helper-контейнеры — D-30»).

**Реальность**: docker-java автоматически вызывает `GET /version` и подстраивается. Docker Engine API v1.46 — текущий ([Docker docs](https://docs.docker.com/reference/api/engine/version/v1.46/)). Однако `withCpusetCpus` — deprecated в пользу `withCpuCount`/`withNanoCpus` в новых версиях API; некоторые лимиты (`--pids-limit`) появились в Docker 1.11+ (2016).

**Вердикт: RISK.** Минимально — проверить, что на VM стоит Docker ≥ 20.10 (для гарантированной поддержки `pids-limit` и `NanoCpus`). Это ops-check, не код.

---

## 7. Hibernate / Jackson

### OK-17: jsonb-маппинг через `@JdbcTypeCode(SqlTypes.JSON)`

**Обещано** (design неявно через `graph_jsonb`, `payload_jsonb`, `params_jsonb`, `tools_jsonb`, `permissions_jsonb` в glossary.md и data-model): JSONB-поля маппятся на Java-объекты.

**Реальность**: Hibernate 7.4.x (который в Boot 4.1.1) поддерживает `@JdbcTypeCode(SqlTypes.JSON)` для любых колонок, и для PostgreSQL `PostgreSQLDialect` регистрирует `JSON` → `jsonb` ([Hibernate ORM source](https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java), «Prefer jsonb if possible»). Маппер по умолчанию — Jackson (через `hibernate.type.json_format_mapper`).

**Вердикт: OK.**

### OK-18: Генератор UUID v7 — самописный (D-31)

**Обещано** (`decisions.md` D-31 «генератор — самописная реализация владельца (единая точка `IdGenerator`)»).

**Реальность**: `java.util.UUID` в JDK 25 имеет `randomUUID()` (v4), `nameUUIDFromBytes()` (v3/v5) — **нет** `ofEpochMillis()` в mainline. Поиск в OpenJDK symbols: `ofEpochMillis` присутствует в `java.base-Q.sym.txt` (вероятно, incubator или jdk-рантайм). В стандартном API JDK 25 (`java.util.UUID` для пользователя) — только v4/v5/v3/v7 нет.

**Вердикт: OK.** Самописный `IdGenerator` — правильное решение, поскольку JDK 25 не предоставляет стандартный UUID v7 API. Альтернатива — `com.github.f4b6a3:uuid-creator` (5.3.0 на Maven Central, GA с 2018), но в дизайне уже выбрано «единая точка» — оставляем как есть.

### RISK-10: `session_message.id` (ULID) vs PK `(session_id, seq)` — две стратегии

**Обещано** (`glossary.md` §4 «`session_message.id` (короткий ULID — будущие маркеры в контексте)» vs «PK `(session_id, seq)`»).

**Реальность**: UUID v7 (128 бит) ≠ ULID (80 бит random + 48 бит time = 128 бит, но Crockford base32 = 26 символов). Размер в БД одинаковый, но `id` (ULID) — задаётся приложением, а `(session_id, seq)` — БД-генерируется (или тоже приложением).

**Вердикт: RISK.** Не реальная проблема API, а **семантическая**: оба идентификатора в одной строке избыточны. `seq` — append-only sequence в сессии (PK достаточно), `id` — для cross-session references (COMPACT.covers ссылается на список id). Нужно явно зафиксировать: `id` никогда не служит PK для поиска, только для cross-references.

---

## 8. Дополнительные находки (не из списка покрытия, но попутно)

### RISK-11: spring-boot-starter-web vs spring-boot-starter-webmvc

**Источник**: [Spring Boot 4.1.1 release notes #50847](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1).

**Реальность**: `spring-boot-starter-web` помечен deprecated в 4.x, переименован в `spring-boot-starter-webmvc`. Если pom.xml написан под 3.x ссылки — выдаст warning, может дать битые импорты.

**Вердикт: RISK.** Проверить pom.xml в M1 на использование нового starter.

### OK-19: PostgreSQL JDBC 42.7.13 — современный

**Источник**: Spring Boot 4.1.1 dependency upgrades.

**Реальность**: PG JDBC 42.7.13 — поддерживает JDK 21+ (там же где и JEP 491). Можно использовать `simple-jdbi-call` или прямой `JdbcTemplate`.

**Вердикт: OK.**

### OK-20: Hibernate 7.4.5.Final + Spring Boot 4.1.1

**Источник**: Spring Boot 4.1.1 dependency upgrades.

**Реальность**: Hibernate 7.4.5 — стабильная серия. Поддерживает UUID-генерацию через `@UuidGenerator` (наш `IdGenerator` интегрируется через `org.hibernate.generator.EventType.INSERT`-aware генератор или через `default-value` в схеме). Поддерживает `@JdbcTypeCode(SqlTypes.JSON)`.

**Вердикт: OK.**

---

## 9. Сводка по каждой технологии

| Технология | Обещано | Реальность | Вердикт |
|---|---|---|---|
| Java 25 | JDK 25 | JDK 25 GA (16.09.2025) | OK |
| Spring Boot 4.1.1 | parent 4.1.1 | 4.1.1 опубликован | OK |
| Spring AI 2.0.1 | BOM 2.0.1 | 2.0.1 опубликован | OK |
| Spring AI ChatClient стрим | `Flux<String>` | `Flux<String>/ChatResponse/ChatClientResponse` | OK |
| Spring AI cancel стрима | между tool-calls | Reactor `subscription.dispose()`; 2.0.1 фиксы #6656, #6759 | OK |
| Spring AI ChatMemory | наш SessionStore | `MessageChatMemoryAdvisor.builder(chatMemory)` (custom impl) | OK |
| Spring AI Structured output | для служебных вызовов | `.entity(...)`/`.parameterizedType(...)` | OK |
| Spring AI кастомный `callId` | в нашем формате | через `ToolContext` + кастомный `ToolCallResultConverter` | RISK (слой наш, не API) |
| Spring AI async tool | `ASYNC_ACCEPTED` плейсхолдер | `Mono`/`Flux` поддержка есть, но плейсхолдер — наш формат | RISK (слой наш) |
| Spring AI MCP OAuth2 | bearer + авто-refresh | `spring-ai-mcp-client-security` 3 flows | OK |
| Spring AI MCP async OAuth | не обещано | ТОЛЬКО sync | RISK (если 12+ серверов понадобятся async) |
| Spring Boot `DataSourceAutoConfiguration` exclude | вручную | `spring.autoconfigure.exclude=...` или `@SpringBootApplication(exclude=...)` | OK |
| Boot 4 пакеты security/cache | имплицитно | `org.springframework.boot.security.autoconfigure` (новый FQCN) | RISK (нужна верификация) |
| Spring AI автоконфиги 6 шт | «все 6» | фактически 8+ (model, embedding, chat-memory, mcp, vector-store, tool-calling) | RISK (число расходится) |
| ShedLock 7.9 | артефакт v7.9 | **в Maven Central нет**, последний 6.6.0 | **BLOCKER** |
| ShedLock `lockAtMostFor` | `10s` | ISO-8601/shortened, поддержка есть | OK |
| ShedLock `JdbcTemplateLockProvider` | jdbc-template | доступен | OK |
| JEP 491 (synchronized VT) | нет pinning | delivered в JDK 24, transitively в 25 | OK |
| JEP 491 JNI/FFM pinning | не покрыто | да, есть остаточные случаи | RISK (нужна проверка classpath) |
| `Thread.ofVirtual()` | виртуальные потоки | `@since 21` | OK |
| `VirtualThreadSchedulerMXBean` | мониторинг | доступен | OK |
| docker-java 3.5.1 | актуален | последняя 3.5.1 (05.2025) | OK |
| docker-java `withBinds` | volume mount | работает | OK |
| docker-java `cpus=2` | CPU лимит | `withNanoCpus(2_000_000_000L)` (НЕ `withCpuShares`) | OK (нужен правильный API) |
| docker-java `memory=2g` | memory limit | `withMemory(2L * 1024 * 1024 * 1024)` | OK |
| docker-java `pids-limit=512` | pids limit | `withPidsLimit(512L)` | OK |
| docker-java engine API | negotiation | auto-negotiation, мин. Docker 20.10 | RISK (ops-check) |
| Hibernate `@JdbcTypeCode(SqlTypes.JSON)` | jsonb маппинг | Hibernate 7.4.5, PostgreSQL dialect → jsonb | OK |
| UUID v7 как PK | самописный генератор | JDK 25 нет стандартного UUID v7 | OK (D-31 обоснован) |
| UUID v7 как ULID для `session_message.id` | короткий ULID | ULID ≠ UUID v7, разные 128-бит форматы | RISK (семантика) |
| spring-boot-starter-web | web | переименован в `spring-boot-starter-webmvc` | RISK (миграция pom) |
| PostgreSQL JDBC 42.7.13 | pg driver | в Boot 4.1.1 BOM | OK |
| Hibernate 7.4.5 | ORM | в Boot 4.1.1 BOM | OK |

---

## 10. Что должно попасть в решения / план

### BLOCKER — требуют fix до старта M1

1. **ShedLock 7.9 → fallback**: либо использовать `shedlock-spring:6.6.0` (проверить бинарную совместимость с Boot 4.1 — release notes ShedLock не подтверждают поддержку Boot 4.x для 6.x), либо реализовать singleton-lock на нашей таблице `idempotency_key` (по аналогии с CAS из D-04). Решение: добавить openspec-change на «ShedLock adapter → custom JDBC singleton lock» в M1.

2. **Preliquibase 2.0.0 → fallback**: использовать `preliquibase-spring-boot-starter:1.6.1` (последний реальный). Проверить, что 1.6.1 работает с Boot 4.1 (могут быть class-not-found на новых пакетах Spring). Если не работает — fallback на самописный bean: `ApplicationRunner` с `JdbcTemplate.execute(...)` для `CREATE TABLE IF NOT EXISTS flyway_schema_history` или аналогичной Liquibase-таблицы, перед основным `SpringLiquibase`.

### RISK — требуют уточнения в плане

3. Число «6 автоконфигов Spring AI» в `architecture.md` §4 — занижено. Зафиксировать реальный список (8+) и явно отключать через FQCN.
4. `call_id` (наш UUID v7) vs `ToolCall.id` (провайдерский) — зафиксировать различие в `intelligence` модуле.
5. `ASYNC_ACCEPTED` — наш формат, не API Spring AI. Зафиксировать в `intelligence` и в `agent-tools.md` §5.
6. pids-limit / cpus / memory — уточнить, что используем `withNanoCpus`, а не `withCpuShares`.
7. Spring AI MCP OAuth2 — только sync, async-варианта нет.
8. JEP 491 не покрывает JNI/FFM/class-init pinning — CI-проверка `-Djdk.tracePinnedThreads=full` и аудит classpath.
9. UUID v7 + ULID — две стратегии id, зафиксировать назначение.

### Спорные места (требуют ADR)

A. **ShedLock заменить или ждать?** ShedLock — широко известный проект, форкать рискованно. Но 7.x roadmap не озвучен. **Рекомендация**: ADR-33 «Singleton POLL-lock: ShedLock 6.6.0 vs custom CAS».

B. **docker-java vs docker-maven-plugin vs testcontainers?** docker-java — прямой клиент, нужен для production. testcontainers — для тестов. docker-maven-plugin — для CI-сборки helper-образа. Все три имеют смысл, проверить, что нет конфликта зависимостей.

---

## 11. Источники

- [Spring Boot 4.1.1 release notes](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1)
- [Spring AI 2.0.1 release notes](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)
- [Spring AI 2.0 — ChatClient](https://docs.spring.io/spring-ai/reference/2.0/api/chatclient.html)
- [Spring AI 2.0 — Tools](https://docs.spring.io/spring-ai/reference/2.0/api/tools.html)
- [Spring AI 2.0 — Chat Memory](https://docs.spring.io/spring-ai/reference/2.0/api/chat-memory.html)
- [Spring AI 2.0 — MCP Client Security](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html)
- [Spring AI 2.0 — MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html)
- [ShedLock — Spring Integration](https://github.com/lukas-krecan/shedlock/blob/master/_autodocs/05-spring-integration.md)
- [ShedLock — Support Matrix](https://github.com/lukas-krecan/shedlock/blob/master/_autodocs/00-index.md)
- [Hibernate ORM — PostgreSQLDialect JSON mapping](https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java)
- [Hibernate ORM — JSON basic types](https://github.com/hibernate/hibernate-orm/blob/main/documentation/src/main/asciidoc/userguide/chapters/domain/basic_types.adoc)
- [docker-java — Home wiki](https://github.com/docker-java/docker-java/wiki/Home)
- [OpenJDK — JDK 25](https://openjdk.org/projects/jdk/25/)
- [OpenJDK — JDK 24](https://openjdk.org/projects/jdk/24/)
- [JEP 491 — Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [Maven Central — docker-java-core versions](https://search.maven.org/solrsearch/select?q=g:com.github.docker-java+AND+a:docker-java-core&core=gav&rows=20&wt=json)
- [Maven Central — shedlock-spring versions](https://search.maven.org/solrsearch/select?q=g:net.javacrumbs.shedlock+AND+a:shedlock-spring&core=gav&rows=10&wt=json)
- [Maven Central — preliquibase-spring-boot-starter versions](https://search.maven.org/solrsearch/select?q=g:net.lbruun.springboot+AND+a:preliquibase-spring-boot-starter&core=gav&rows=5&wt=json)

## 12. Что НЕ покрыто (явно вне scope этого ревью)

- Реальная совместимость shedlock-spring 6.6.0 с Spring Boot 4.1 на уровне байткода (нужен эмпирический тест: запустить наш `enableScheduling` бин против 6.6.0 — будет ли stack-trace на новых FQCN?).
- Реальная совместимость preliquibase-spring-boot-starter 1.6.1 с Spring Boot 4.1 (тот же вопрос).
- Реальные лимиты Postgres JSONB на нашем размере payload'ов (TOOL_RESULT может быть до ~50KB? Без нагрузочного теста не сказать).
- Реальная пропускная способность docker-java per-second на 50+ контейнеров (известно, что docker-java — blocking I/O на сокете; при 50 одновременных контейнерах стоит подумать про пул клиентов).
- Лицензии зависимостей: Spring Boot — Apache 2.0; Spring AI — Apache 2.0; ShedLock — Apache 2.0; docker-java — Apache 2.0; preliquibase — Apache 2.0; Hibernate — LGPL 2.1 (важно для распространения!).
