# Aspect 1 — Стек-валидация дизайн-базиса (DeepSeek-V4.1-Flash)

> Дата: 2026-09-16. Метод: выписка поведенческих обещаний, завязанных на стек, из `docs/glossary.md`, `docs/design/architecture.md`, `docs/design/execution-model.md`, `docs/design/agent-tools.md`, `docs/design/api-contracts.md` (+ `data-model.md`, `decisions.md` как источник D-30/D-31) → сверка с реальными API/доками актуальных версий.
> Источники сверки: Spring Boot 4.1 Release Notes / Release Highlights, Spring AI reference 2.0-SNAPSHOT (docs.spring.io), Spring AI GitHub issues, ShedLock README, Pre-Liquibase README, Hibernate discourse/javadocs, OpenJDK JEP 491, docker-java README/releases.
> Верифицированная база из репозитория: `pom.xml` (Boot parent 4.1.1, Java 25, Spring AI BOM 2.0.1, `shedlock 7.9.0`, `preliquibase-spring-boot-starter 2.0.0`, `spring-ai-starter-model-openai`, `jackson-datatype-jsr310` (Jackson 2), `testcontainers 2.0.5`), `src/main/resources/application.yml`.
> Формат находки: **обещано (док+раздел) → что реально в API (источник) → вердикт OK/RISK/BLOCKER → альтернатива.**

## 0. Факты о версиях (проверено)

| Утверждение дизайна | Реальность | Источник |
|---|---|---|
| Spring Boot 4.1.1 | Существует, релиз 2026-08-20; база — Spring Framework 7.0.8 | spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now |
| Spring AI 2.0.1 (BOM) | Существует, релиз 2026-08-21, первый maintenance поверх 2.0.0 GA; заявлена совместимость с Boot 4.0/4.1 + Framework 7.0 | spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now |
| Java 25 + виртуальные потоки | Boot 4 — first-class Java 25; JEP 491 (снятие pinning на `synchronized`) входит начиная с Java 24, т.е. присутствует в 25 | spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now; openjdk.org/jeps/491 |
| ShedLock 7.9 (spring + jdbc-template) | 7.x-матрица ShedLock: тестировано с Spring 7.0 и Spring Boot 4.x | github.com/lukas-krecan/ShedLock (Compatibility matrix) |
| preliquibase 2.0.0 | 2.0.x — ветка для Spring Boot **4.0.x** (JDK 17); про 4.1.x явной записи нет | github.com/lbruun-net/Pre-Liquibase (Spring Boot compatibility) |
| docker-java (создание контейнеров) | Актуальный релиз 3.7.1 (март 2026); в `pom.xml` **зависимости нет** | github.com/docker-java/docker-java/releases |

---

## 1. Spring AI 2.0.1 — ChatClient, tool-объекты, отмена стрима, ChatMemory, structured output

### F1. Кастомные tool-объекты под наши контракты
**Обещано:** `agent-tools.md §1–2`, `architecture.md §4` — нативные инструменты + мета-инструменты декларируются агенту и вызываются моделью; `LlmGateway` владеет стримингом и tool-calling.
**Реально:** в Spring AI 2.0 tool-calling — первоклассный компонент advisor-цепочки: `ToolCallback`/`ToolCallbacks`, `ToolCallingAdvisor` (авто-регистрируется `DefaultChatClient`), `ToolCallingManager`; есть программное отключение авто-цикла через `AdvisorParams.toolCallingAdvisorAutoRegister(false)` и ручной прогон цикла.
**Источник:** docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/tools.html; .../api/advisors-recursive.html
**Вердикт:** **OK**
**Альтернатива:** — (наш цикл из `execution-model.md §2` ложится на ручной `ToolCallingManager`).

### F2. `ASYNC_ACCEPTED`-плейсхолдер + поздний второй `TOOL_RESULT` с тем же `call_id`
**Обещано:** `glossary.md §4` («второй TOOL_RESULT с тем же call_id, флаг late=true»), `execution-model.md §4`, `agent-tools.md §1, §5` — async-инструменты (в первую очередь `bash`): окно ~30 с → плейсхолдер `ASYNC_ACCEPTED(callId)` → ход возвращается → поздний результат дописывается.
**Реально:** встроенный tool-цикл Spring AI синхронный: `ToolCallingManager.executeToolCalls()` выполняет callback и добавляет **ровно один** результат на каждый `tool_call`; нативного понятия «плейсхолдер + поздний дубль» нет. Ручной цикл возможен, но именно **второй** `tool`-месседж с тем же `tool_call_id` — нестандартен: OpenAI-совместимый chat-completions API отвергает дублирующийся `tool_call_id`, а Spring AI не имеет API «заменить результат в истории» (история append-only by design).
**Источник:** docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/tools.html (`ToolExecutionResult.conversationHistory()`, один результат на call); семантика OpenAI messages (tool_call_id уникален на диалог).
**Вердикт:** **RISK** (наш append-only рендер и API провайдера расходятся).
**Альтернатива:** (1) при рендере контекста не подавать плейсхолдер и поздний результат как два `tool`-сообщения — поздний результат подавать как `SYSTEM`/`USER`-событие («фоновая операция завершилась…»), плейсхолдер оставить единственным `tool`-ответом; (2) либо ввести «виртуальную» пару: плейсхолдер хранится как `TOOL_CALL`-статус, а не как `tool`-сообщение; (3) либо ограничить async-инструменты теми, что модель вызывает не в том же раунде.

### F3. Отмена стрима при `cancel_requested`
**Обещано:** `architecture.md §3` (`LlmGateway`: «стриминг, отмена»), `execution-model.md §2, §6` — цикл проверяет `cancel_requested`; `stop = отмена Turn'а цели + поддерева`.
**Реально:** `ChatClient.stream()` отдаёт `Flux`; отмена подписки формально прекращает выдачу. Но в Spring AI 2.0 транспорт OpenAI — официальный `openai-java` SDK (`com.openai.core.http.AsyncStreamResponse`), мост в Reactor реализован через `FluxCreate` + `CompletableFuture` (видно в стектрейсе issue #6441). Явной гарантии, что dispose Flux отменяет подписку SDK и останавливает генерацию у провайдера (биллинг), в API нет; first-class cancel-token отсутствует.
**Источник:** github.com/spring-projects/spring-ai/issues/6441 (стек: `com.openai.core.http.AsyncStreamResponseKt`, `OpenAiChatModel.lambda$internalStream$4`); docs.spring.io/spring-ai/reference/api/chatclient.html (`Flux`).
**Вердикт:** **RISK**
**Альтернатива:** проверять `cancel_requested` **между раундами** (уже заложено) как основной механизм; для жёсткого обрыва — вручную закрывать/`cancel()` подписку `openai-java`-стрима (обернуть стрим в собственный `Disposable` и в `doOnCancel` вызывать закрытие SDK-стрима); валидировать интеграционным тестом «отмена → нет новых токенов провайдера».

### F4. Подмена ChatMemory/Advisor на наш `SessionStore`
**Обещано:** `glossary.md §2` («per-agent ChatModel собирается вручную»), `execution-model.md §5` — контекст рендерим сами из `session_message`.
**Реально:** Spring AI даёт интерфейс `ChatMemory` (`add/get/clear`) и advisor'ы `MessageChatMemoryAdvisor`/`PromptChatMemoryAdvisor`; возможна собственная реализация или полный обход через `ChatClient.prompt().messages(...)`. При этом `ToolCallingAdvisor` авто-регистрируется — нужно осознанно управлять набором default-advisor'ов.
**Источник:** docs.spring.io/spring-ai/reference/api/chat-memory.html; .../2.0-SNAPSHOT/api/tools.html (auto-register).
**Вердикт:** **OK**
**Альтернатива:** раз контекст рендерится вручную (`renderVisibleEvents`), проще вообще не подключать memory-advisor и передавать `messages` явно — но не забыть про авто-регистрацию `ToolCallingAdvisor`.

### F5. Structured output «при необходимости»
**Обещано:** `glossary.md §2`, `api-contracts.md` (`params`/граф — JSON-Schema), `agent-tools.md` (`create_workflow` с graph) — модели иногда нужен структурированный вывод.
**Реально:** Spring AI 2.0 — `entity()` + `StructuredOutputValidationAdvisor` (self-correcting, ретраи), поддержка provider-native structured output.
**Источник:** spring.io/blog/2026/06/23/spring-ai-self-correcting-structured-output; docs.spring.io/spring-ai/reference/api/structured-output/converters.html
**Вердикт:** **OK**
**Альтернатива:** —

### F6. Длинные ходы / reasoning-модели не режутся таймаутом
**Обещано:** `glossary.md §2` (`params_jsonb` напр. `reasoning_effort`), `execution-model.md §1–4` — длительные раунды модель↔инструменты, окно async ~30 с.
**Реально:** в Spring AI 2.0.1 — **открытая регрессия** (issue #6915): `OpenAiChatOptions` в конструкторе подставляет `DEFAULT_TIMEOUT = 60s` и `DEFAULT_MAX_RETRIES = 3`; т.к. `buildRequestPrompt` не мержит default-options, любой per-prompt options-объект (а мы обязаны передавать туда tools) навязывает 60-секундный таймаут и 3 ретрая, перебивая клиентскую настройку. В 2.0.0 этого не было. Репорт — ровно на Boot 4.1 + Spring AI 2.0.1.
**Источник:** github.com/spring-projects/spring-ai/issues/6915 (open, waiting-for-triage)
**Вердикт:** **RISK** (потенциально BLOCKER для долгих агентских Turn'ов и reasoning-моделей)
**Альтернатива:** явно ставить `.timeout(...)` **и** `.maxRetries(...)` в **каждом** per-prompt `OpenAiChatOptions` (документированный workaround); либо остаться на 2.0.0 (но там не исправлен F7); либо ждать фикса. Обязательно покрыть тестом «ход дольше 60 с».

### F7. Стриминг tool-call'ов и учёт токенов
**Обещано:** `execution-model.md §5` (prompt-cache/дельта), `data-model.md` (`session_message.tokens`).
**Реально:** 2.0.1 чинит слияние OpenAI streaming tool-call дельт **по индексу**, обработку пустых `tool_calls` чанков и аккумуляцию usage между итерациями tool-calling; `streamToolCallResponses` как отдельный механизм **удалён** в 2.0 (замена — ручной цикл).
**Источник:** spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now; docs.spring.io/spring-ai/reference/upgrade-notes.html; issue #6441 (milestone 2.0.1, closed)
**Вердикт:** **OK** (на 2.0.1; на 2.0.0 был баг `IndexOutOfBoundsException` в `ChunkMerger`)
**Альтернатива:** не использовать/не искать `streamToolCallResponses` — его нет.

---

## 2. MCP-клиент Spring AI — bearer, авто-обновление OAuth, 12+ серверов

### F8. MCP-клиенты как второй источник инструментов, токены обновляет сервер
**Обещано:** `architecture.md §1, §2` (`integration`: MCP-клиенты SSO), `agent-tools.md §3` («клиент Spring AI; 12+ корпоративных серверов за SSO-прокси; токены обновляет сервер — агент про OAuth не знает»), `glossary.md §6` (источники инструментов ровно два).
**Реально:** Spring AI предоставляет MCP-client boot starter, map именованных streamable-http соединений (`spring.ai.mcp.client.streamable-http.connections.<name>.url`), `McpSyncHttpClientRequestCustomizer` для подстановки заголовка, OAuth2-кастомайзеры (`OAuth2AuthorizationCodeSyncHttpRequestCustomizer`) и `AuthenticationMcpTransportContextProvider` поверх Spring Security `OAuth2AuthorizedClientManager` (он и делает авто-refresh). Возможность есть, НО: (a) в `pom.xml` нет ни `spring-ai-starter-mcp-client`, ни `spring-boot-starter-oauth2-client`/`spring-boot-starter-security`; (b) OAuth-интеграция завязана на Spring Security client-registration'ы (по одному на сервер) и на «первый auth-exchange», что для 12+ серверов за SSO-прокси — заметная конфигурационная и эксплуатационная работа; известен issue #4178 (starter ожидает конфигурацию AuthorizationServer).
**Источник:** docs.spring.io/spring-ai/reference/2.0-SNAPSHOT/api/mcp/mcp-security.html; .../api/mcp/mcp-client-boot-starter-docs.html; spring.io/blog/2025/05/19/spring-ai-mcp-client-oauth2; github.com/spring-projects/spring-ai/issues/4178
**Вердикт:** **BLOCKER** (обещание про «подключение MCP» в текущем build-плане нереализуемо: зависимостей и модели токенов нет)
**Альтернатива:** добавить `spring-ai-starter-mcp-client` (+ при необходимости `-webflux`, но для SYNC/streamable-http достаточно JDK-HttpClient-транспорта) и `spring-boot-starter-oauth2-client`; для «токены обновляет сервер» — собственная реализация `McpSyncHttpClientRequestCustomizer` + `McpTransportContextProvider`, отдающая актуальный bearer из хранилища токенов, вместо по-серверных Spring Security registration'ов; предусмотреть ленивую инициализацию MCP-клиентов, чтобы недоступный сервер не тормозил старт.

---

## 3. Spring Boot 4.1 — ручная сборка datasource, отключённая авто-конфигурация

### F9. Отключение 6 авто-конфигураций Spring AI по именам классов
**Обещано:** `architecture.md §4` — «все 6 автоконфигураций Spring AI отключены»; `application.yml` перечисляет 6 классов `org.springframework.ai.model.openai.autoconfigure.*`.
**Реально:** пакет совпадает: модуль `auto-configurations/models/spring-ai-autoconfigure-model-openai`, класс `OpenAiChatAutoConfiguration` существует; набор из 6 (chat/audio-speech/audio-transcription/embedding/image/moderation) соответствует модулю. Риск хрупкости: `spring.autoconfigure.exclude` по FQCN «ломается молча», если 2.0.x добавит/переименует класс; и наоборот — исключение несуществующего класса даёт ошибку старта.
**Источник:** zread spring-projects/spring-ai (Quick Start / OpenAI Integration / Module architecture); docs.spring.io/spring-boot (auto-configuration exclude).
**Вердикт:** **OK** (с оговоркой о хрупкости)
**Альтернатива:** предпочесть семантические ключи (`spring.ai.model.chat=none`, `spring.ai.openai.enabled=false`) — они не зависят от имён классов; либо не тянуть `spring-ai-autoconfigure-model-openai` вовсе, а собрать `OpenAiChatModel` вручную из `spring-ai-openai`.

### F10. `DataSourceAutoConfiguration` отключён, datasource собирается вручную; порядок «preliquibase → liquibase → пул»
**Обещано:** `architecture.md §4` — исключён `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, «datasource собирается вручную — порядок preliquibase → liquibase → пул».
**Реально:** (1) FQCN корректный для Boot 4 (модуль `spring-boot-jdbc`, `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`); (2) исключение само по себе **не нужно**: если объявить собственный `@Bean DataSource`, автоконфиг отступает по `@ConditionalOnMissingBean(DataSource)`; при этом исключении теряется биндинг `spring.datasource.*` и часть health/метаданных; (3) заявленный порядок «preliquibase → liquibase → пул» **неточен**: и Pre-Liquibase, и Liquibase потребляют один и тот же `DataSource`-бин, поэтому пул должен существовать **до** них; (4) Pre-Liquibase README прямо предупреждает: модуль работает только при **авто-конфигурации Liquibase** (если вручную объявить `SpringLiquibase`, Pre-Liquibase не сработает) и использует тот же DataSource, что Liquibase-модуль Boot.
**Источник:** github.com/spring-projects/spring-boot (module/spring-boot-jdbc/.../jdbc/autoconfigure/DataSourceAutoConfiguration.java, `@ConditionalOnMissingBean`); github.com/lbruun-net/Pre-Liquibase (Which DataSource is used? / Troubleshooting).
**Вердикт:** **RISK**
**Альтернатива:** оставить `DataSourceAutoConfiguration` включённой и переопределить пул своим `@Bean` (или через `DataSourceProperties`), не отключая авто-конфиг; порядок зафиксировать как «собрать `DataSource` (Hikari) → Pre-Liquibase (SQL-предусловия) → Liquibase (changeSet'ы)»; не объявлять `SpringLiquibase` вручную, иначе Pre-Liquibase молча не выполнится.

### F11. preliquibase 2.0.0 × Boot 4.1.1
**Обещано:** `architecture.md §4` — Preliquibase для pre-DDL.
**Реально:** версия 2.0.x официально соответствует Boot **4.0.x**; отдельно задокументирована регрессия Boot 3.5.8/4.0.0, «негативно влияющая на Pre-Liquibase», с рекомендацией использовать 4.0.1+. Boot 4.1.1 старше 4.0.1, значит регрессия должна быть закрыта, но 4.1.x в матрице совместимости не заявлен.
**Источник:** github.com/lbruun-net/Pre-Liquibase (banner 25-Nov-2025; compatibility table).
**Вердикт:** **RISK** (непроверенная комбинация)
**Альтернатива:** прогнать smoke-тест «schema создаётся до Liquibase» именно на 4.1.1; в случае проблем — ручная сборка `PreLiquibase`-бинов (Example 2 в README) или SQL-предусловие на стороне деплоя.

### F12. `spring-boot-starter-liquibase` как артефакт Boot 4
**Обещано:** `architecture.md §4`, `data-model.md` (предисл.).
**Реально:** артефакт `spring-boot-starter-liquibase` существует в линейке Boot 4 (есть 4.0.0+).
**Источник:** mvnrepository.com/artifact/org.springframework.boot/spring-boot-starter-liquibase
**Вердикт:** **OK**
**Альтернатива:** —

### F13. (доп.) Keycloak OIDC: проверка JWT на REST/SSE
**Обещано:** `glossary.md §7` (SSO Keycloak), `api-contracts.md §0.1` (`Authorization: Bearer <Keycloak JWT>` на всех эндпоинтах).
**Реально:** в `pom.xml` нет `spring-boot-starter-security`/`spring-boot-starter-oauth2-resource-server`, нет Keycloak-адаптера. План аутентификации ничем не обеспечен на уровне сборки.
**Источник:** `pom.xml` (сверка состава зависимостей).
**Вердикт:** **BLOCKER** (для фазы, где появляется auth)
**Альтернатива:** `spring-boot-starter-oauth2-resource-server` + `issuer-uri` Keycloak; `AccessPolicy` поверх `JwtAuthenticationToken`; для SSE/WS — ticket-эндпоинт (`api-contracts.md §1.4`) уже спроектирован.

---

## 4. ShedLock 7.9 × Spring Boot 4

### F14. Совместимость и `lockAtMostFor`
**Обещано:** `architecture.md §4` (ShedLock 7.9 jdbc-template), `glossary.md §4` (POLL раз в ~5 с, `lockAtMostFor` ~10 с), `execution-model.md §1`.
**Реально:** ShedLock 7.x тестируется с Spring 7.0 и Spring Boot 4.x; `lockAtMostFor`/`lockAtLeastFor`/`@EnableSchedulerLock(defaultLockAtMostFor=…)` поддерживаются; `JdbcTemplateLockProvider` + таблица `shedlock(name PK, lock_until, locked_at, locked_by)`; рекомендован `.usingDbTime()` (использует часы БД, защищает от рассинхрона нод).
**Источник:** github.com/lukas-krecan/ShedLock (Compatibility matrix — строка 7.x.x: «Spring 7.0, 6.2 / Spring Boot 4.x, 3.5, 3.4»; JdbcTemplate provider; Duration specification).
**Вердикт:** **OK**
**Альтернатива:** —; обязательна Liquibase-миграция таблицы `shedlock`; версия уже пиннится вручную (ShedLock отсутствует в `spring-boot-dependencies`).

### F15. Режим интеграции и риск «10 с < времени скана»
**Обещано:** `execution-model.md §1` — «`lockAtMostFor` ~10 сек, отпускает сама».
**Реально:** по умолчанию `PROXY_METHOD` (AOP вокруг метода, лок ставится даже при прямом вызове; `PROXY_SCHEDULER` deprecated). `lockAtMostFor` — это «safety net» на случай смерти ноды, а не максимальное время работы: если скан превысит 10 с, другая нода может запустить ту же джобу.
**Источник:** github.com/lukas-krecan/ShedLock (Modes of Spring integration; Behavior §5).
**Вердикт:** **RISK** (минорный)
**Альтернатива:** `lockAtMostFor` заведомо больше максимального времени скана (напр. `20–30s`), а частоту задавать `fixedDelay`, не `fixedRate`; плюс `LockAssert.assertLocked()` в джобе для защиты от мисконфигурации AOP.

---

## 5. Java 25 × виртуальные потоки × блокирующий `spawn_subagent`

### F16. Блокирующее ожидание дочернего Turn'а в виртуальном потоке
**Обещано:** `execution-model.md §4, §8`, `glossary.md §5` — `spawn_subagent` синхронный, родительский **виртуальный** поток ждёт; все Turn'ы — виртуальные потоки.
**Реально:** блокирующее ожидание в виртуальном потоке дёшево (unmount). JEP 491 (Java 24) снимает привязку (pinning) виртуальных потоков внутри `synchronized`; Java 25 включает это. Значит блокирующие вызовы под `synchronized` больше не «съедают» carrier-поток.
**Источник:** openjdk.org/jeps/491; spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now (first-class Java 25).
**Вердикт:** **OK**
**Альтернатива:** —; но Turn'ы должны крутиться на **явно созданном** `Executors.newVirtualThreadPerTaskExecutor()`, т.к. `spring.threads.virtual.enabled=true` (в `application.yml` не задан) переключает только Tomcat-потоки и не делает произвольные задачи виртуальными.

### F17. Pinning-риски (JDBC/JDBC-драйверы, `synchronized`)
**Обещано:** `architecture.md §4`, `execution-model.md §8` — виртуальные потоки + JDBC/JPA.
**Реально:** после JEP 491 `synchronized`-методы/блоки не пинят виртуальные потоки; PostgreSQL JDBC — чистый Java, блокирующий I/O. Остаточный pinning по-прежнему возможен на нативных/JNI-фреймах и части файлового I/O (`java.io`), но серверное исполнение файлов/bash вынесено в Docker-контейнеры (`D-30`), а не на хост, поэтому влияние низкое.
**Источник:** openjdk.org/jeps/491; baeldung.com/java-synchronize-virtual-thread-no-pinning.
**Вердикт:** **OK**
**Альтернатива:** не ограничивать одновременные Turn'ы семафором/пулом, заём пермита в котором делает **блокирующийся родитель** (риск дедлока: родитель держит permit, пока ждёт дочерний Turn, которому тоже нужен permit).

### F18. `spring.threads.virtual.enabled`
**Обещано:** `architecture.md §4` — «Java 25 (виртуальные потоки для Turn'ов)».
**Реально:** в `application.yml` флаг не выставлен; это осознанно (`execution-model.md §8` — виртуальные потоки для Turn'ов, а не для HTTP). Свойства из `Threading` Boot влияют только на web/executor-инфраструктуру и требуют Java 21+.
**Источник:** github.com/spring-projects/spring-boot (core/spring-boot/.../thread/Threading.java).
**Вердикт:** **OK** (но исполнительный модуль должен сам поднять виртуальный executor).

---

## 6. docker-java — per-session контейнеры, лимиты, монтирование

### F19. Зависимость docker-java в сборке
**Обещано:** `architecture.md §2, §4`, `execution-model.md §4`, `agent-tools.md §1`, `D-30` — `ContainerWorkspaceTools` создаёт per-session контейнеры `harness-<sessionId>` через docker-java.
**Реально:** в `pom.xml` docker-java **отсутствует**, хотя это ядро `D-30`. Без него `ContainerWorkspaceTools` не собрать.
**Источник:** `pom.xml`; github.com/docker-java/docker-java/releases.
**Вердикт:** **BLOCKER** (для реализации `D-30`)
**Альтернатива:** добавить `com.github.docker-java:docker-java-core` + транспорт (`docker-java-transport-httpclient5` или `zerodep`) — по актуальной документации нужно минимум два артефакта (агрегат `docker-java` тянет всё, но docs рекомендуют core+transport); версия 3.7.x (март 2026).

### F20. Лимиты `cpus`/`memory`/`pids-limit` и монтирование volume
**Обещано:** `execution-model.md §4` — `cpus=2`, `memory=2g`, `pids-limit=512`, workspace примонтирован томом, имя `harness-<sessionId>`.
**Реально:** API docker-java это поддерживает: `HostConfig` (`withMemory`, `withPidsLimit`, `withNanoCPUs`/`withCpuCount`), `Bind`/`withBinds` для монтирования, `DockerClientConfig` с `DOCKER_HOST=unix:///var/run/docker.sock`. Т.е. API-возможности достаточны.
**Источник:** docker-java docs (getting_started.md, transports.md); Docker Engine resource constraints.
**Вердикт:** **OK** (API есть; блокирует лишь отсутствие зависимости — см. F19)
**Альтернатива:** для изоляции лимитов дополнительно выставлять `withCpuPeriod/withCpuQuota` или `NanoCPUs` (у `withCpus`/`cpuCount` исторически разная трактовка между версиями API) — зафиксировать точные методы при реализации.

### F21. JDK 25 × docker-java и собственная Jackson
**Обещано:** `architecture.md §4` — JDK 25, `D-30`.
**Реально:** docker-java 3.7.1 (2026) актуален; минимальные требования низкие (Java 8/11), на JDK 25 работоспособен. Нюанс: docker-java имеет собственный `ObjectMapper` (`DockerClientConfig#getObjectMapper()`), исторически Jackson 2 (`com.fasterxml.jackson`); на Boot 4 с Jackson 3 это либо тянет Jackson 2 транзитивно, либо требует настройки. Прямого конфликта нет (библиотека самодостаточна), но стоит зафиксировать.
**Источник:** docker-java docs (getting_started.md, раздел Jackson).
**Вердикт:** **RISK** (минорный, к интеграции)
**Альтернатива:** не пытаться переиспользовать Jackson-3-`ObjectMapper` приложения в docker-java; проверить, что транзитивный Jackson 2 не конфликтует, и при необходимости исключить/переопределить.

---

## 7. Hibernate/Jackson — jsonb (`graph_jsonb`, `payload_jsonb`) и UUID v7

### F22. jsonb-маппинг через `@JdbcTypeCode(SqlTypes.JSON)`
**Обещано:** `data-model.md §3, §4, §5` — `graph_jsonb`, `params_jsonb`, `reason_jsonb`, `payload_jsonb`, `tools_jsonb`, `permissions_jsonb`, `skills_jsonb`.
**Реально:** Hibernate 7 маппит `SqlTypes.JSON` на `jsonb` в PostgreSQL, НО автоматический `FormatMapper` для JSON ищет Jackson **2** (`com.fasterxml.jackson`) либо JSONB-имплементацию (Yasson). Boot 4 по умолчанию использует Jackson **3** (`tools.jackson`) — в этом случае Hibernate падает с `Could not find a FormatMapper for the JSON format`. Официальный обходной путь от Hibernate — собственный `FormatMapper` на Jackson 3 + `spring.jpa.properties.hibernate.type.json_format_mapper=<class>`.
**Источник:** discourse.hibernate.org/t/missing-formatmapper-for-json-format-with-jackson-3-x-hibernate-7-x/11819 (плюс готовый пример `AbstractJsonFormatMapper` на `tools.jackson`); docs.spring.io/spring-boot (JSON: Jackson 3 — preferred/default, Jackson 2 deprecated).
**Вердикт:** **RISK** (ядро модели данных — jsonb — не заработает «из коробки» на Jackson 3)
**Альтернатива:** (рекомендуемо) реализовать custom Jackson-3 `AbstractJsonFormatMapper` и указать `hibernate.type.json_format_mapper`; либо осознанно оставить Jackson 2 databind на classpath для JSON-маппинга Hibernate.

### F23. `jackson-datatype-jsr310` (Jackson 2) в Boot 4
**Обещано:** `pom.xml` — зависимость `com.fasterxml.jackson.datatype:jackson-datatype-jsr310`.
**Реально:** Boot 4 предпочитает Jackson 3, где поддержка JSR-310 встроена в `jackson-databind` (отдельного `tools.jackson.datatype:*` нет); поддержка Jackson 2 объявлена deprecated и будет удалена в будущих 4.x. Текущая зависимость — легаси; при этом именно она (транзитивно) приносит Jackson 2 databind, что **случайно** спасает F22.
**Источник:** docs.spring.io/spring-boot (features/json: «Jackson 3 is the preferred and default… support for Jackson 2 is deprecated»); spring-boot-jackson build (нет `tools.jackson.datatype:*`).
**Вердикт:** **RISK** (скрытая хрупкая связь: уберёшь «лишнюю» jsr310 — сломается jsonb-маппинг Hibernate)
**Альтернатива:** определиться явно: либо Jackson 3 (+ custom FormatMapper, F22), либо намеренно зафиксировать Jackson 2 для Hibernate JSON; убрать неявную зависимость от транзитивного Jackson 2.

### F24. UUID v7 как PK — самописный генератор `IdGenerator`
**Обещано:** `data-model.md §0` (id — UUID v7, генератор `IdGenerator`, `D-31`), `api-contracts.md §3.2` (`task_transition_history.id` — UUIDv7, «монотонный», он же SSE `id:` и курсор).
**Реально:** самописный генератор как источник `@Id` работает без проблем. Дополнительно Hibernate 7 уже имеет встроенный UUID v7: `@UuidGenerator(style = UuidGenerator.Style.TIME)` → `UuidVersion7Strategy` (RFC 4122 v7). Важно: UUID v7 **time-ordered**, но **не строго монотонный** (внутри одной миллисекунды — случайные биты), поэтому как «монотонный курсор» для SSE/`?since=` он не гарантирует порядок при нескольких событиях в одну ms.
**Источник:** docs.hibernate.org/orm/7.4/javadocs/org/hibernate/id/uuid/UuidVersion7Strategy.html; docs.hibernate.org/orm/7.0/javadocs/org/hibernate/annotations/UuidGenerator.Style.html (TIME = v7).
**Вердикт:** **OK** (для PK); **RISK** (для cursor/SSE-`id` — формулировка «монотонный» в `api-contracts.md §3.2` неточна)
**Альтернатива:** либо использовать встроенный `@UuidGenerator(style=TIME)` вместо самописного (меньше кода — но `D-31` выбирает самописный, если нужна «отшлифованная библиотека владельца»), либо для курсора истории опираться на `seq`/`created_at`+tie-breaker, а не на «монотонность» UUID.

### F25. (доп.) `tags text[]` и `payload`-тип каста
**Обещано:** `data-model.md §4` (`tags text[]`, GIN-индекс).
**Реально:** Hibernate 7 поддерживает маппинг массивов (`@JdbcTypeCode(SqlTypes.ARRAY)`/`@Array`) на PostgreSQL `text[]`; GIN-индекс — на стороне Liquibase.
**Источник:** Hibernate ORM user guide (basic/array mapping) — общая возможность.
**Вердикт:** **OK** (проверить при реализации, т.к. отдельно не подтверждал версией).

---

## 8. Сводка

| # | Область | Вердикт |
|---|---|---|
| F1 | ChatClient tool-объекты | OK |
| F2 | ASYNC_ACCEPTED + поздний TOOL_RESULT | RISK |
| F3 | Отмена стрима | RISK |
| F4 | ChatMemory/Advisor → SessionStore | OK |
| F5 | Structured output | OK |
| F6 | 60s-таймаут per-prompt в 2.0.1 | RISK |
| F7 | Streaming tool-call дельты/usage | OK |
| F8 | MCP-клиент + OAuth + 12 серверов | **BLOCKER** |
| F9 | Отключение 6 авто-конфигов Spring AI | OK |
| F10 | Ручной datasource + порядок preliquibase/liquibase | RISK |
| F11 | preliquibase 2.0.0 × Boot 4.1.1 | RISK |
| F12 | starter-liquibase в Boot 4 | OK |
| F13 | Keycloak OIDC в сборке | **BLOCKER** |
| F14 | ShedLock 7.9 × Boot 4 | OK |
| F15 | ShedLock Mode/`lockAtMostFor` | RISK |
| F16 | Блокирующий spawn на VT | OK |
| F17 | Pinning JDBC/synchronized | OK |
| F18 | `spring.threads.virtual.enabled` | OK |
| F19 | docker-java в сборке | **BLOCKER** |
| F20 | Лимиты/volume docker-java | OK |
| F21 | docker-java × JDK25/Jackson 2 | RISK |
| F22 | jsonb × Jackson 3 FormatMapper | RISK |
| F23 | jackson-datatype-jsr310 (Jackson 2) | RISK |
| F24 | UUID v7 PK / «монотонный курсор» | OK / RISK |
| F25 | `text[]`/массивы | OK |

**Итог: OK — 13, RISK — 9, BLOCKER — 3.**

## 9. Топ-3 (по влиянию на план)

1. **BLOCKER — `docker-java` отсутствует в `pom.xml` (F19).** `D-30` (per-session контейнеры `harness-<sessionId>`, лимиты, монтирование workspace) — центральный механизм изоляции исполнения, но зависимости в сборке нет. API docker-java 3.7.x лимиты/тома поддерживает (F20 = OK), так что требуется лишь добавить `docker-java-core` + транспорт и зафиксировать методы `HostConfig`.
2. **RISK — jsonb не работает «из коробки» на Jackson 3 (F22 → следствие F23).** Boot 4 использует Jackson 3 (`tools.jackson`), а Hibernate 7 авто-настраивает JSON-`FormatMapper` только под Jackson 2/Yasson → `Could not find a FormatMapper for the JSON format`. Сейчас сборку «случайно» спасает легаси-зависимость `jackson-datatype-jsr310` (Jackson 2). Нужно осознанное решение: custom Jackson-3 `FormatMapper` + `hibernate.type.json_format_mapper` **или** намеренный Jackson 2 для Hibernate. Покрывает половину модели данных (`graph_jsonb`, `payload_jsonb`, `params_jsonb`, …).
3. **RISK — регрессия таймаута Spring AI 2.0.1 (F6).** Любой per-prompt `OpenAiChatOptions` (а нам нужно передавать tools) молча навязывает 60 с таймаут и 3 ретрая, перебивая клиентскую настройку; репорт ровно на Boot 4.1 + Spring AI 2.0.1. Для длинных агентских Turn'ов и reasoning-моделей это функциональный стопор. Обходится явным `.timeout()/.maxRetries()` в каждом per-prompt options (и тестом «ход > 60 с»).

**Ближайшие преследующие:** MCP-клиент и Keycloak-безопасность целиком отсутствуют в сборке (F8, F13) — обе «двери» интеграции и весь auth-контур не обеспечены зависимостями; а append-only модель `ASYNC_ACCEPTED` + поздний `TOOL_RESULT` с тем же `call_id` (F2) может конфликтовать с ограничениями OpenAI-совместимого API на дублирующийся `tool_call_id`.
---

## Cross-check

> Ретро-свод по значимым (CRITICAL/MAJOR ≈ RISK/BLOCKER) пунктам коллег: `aspect1-stack-glm.md`, `aspect1-stack-mercury.md`.
> Метки: **agree-was-valid** — находка была верной (если закрыта — указано чем); **disagree** — не согласен, обоснование;
> **stale** — находка опиралась на устаревшую реальность/цитату.
> Закрытия после судьи/владельца (учтены ниже): UUID v7 (генератор владельца работает на Boot 3.4) — снято;
> пиннинг (JEP 491, в JDK 25 нет) — снято; ShedLock → 7.10.1 — в pom; preliquibase 2.0.0 существует (repo1) — подтверждено;
> Jackson-3-ловушка и #6915-таймауты — внесены в `architecture.md`; callId-маппинг `ASYNC_ACCEPTED` — внесён в `agent-tools.md`.

| Пункт коллеги | Их вердикт | Мой cross-check | Комментарий |
|---|---|---|---|
| GLM §6 — MCP OAuth/bearer, 12+ серверов | RISK | **agree-was-valid** | Совпадает с моим F8. Крючки API (`McpSyncHttpClientRequestCustomizer`, `OAuth2AuthorizedClientManager`) есть, но «включил-и-забыл» нет. Расхождение только в severity: я дал BLOCKER, потому что в `pom.xml` нет ни MCP-starter, ни oauth2-client/security — это фиксится добавлением зависимостей; по существу находка верна. |
| GLM §8 — preliquibase / ручной `SpringLiquibase` / порядок | RISK | **agree-was-valid** | Совпадает с моими F10–F11. Подтверждено: 2.0.0 существует (repo1), но (а) ручной `SpringLiquibase` молча глушит Pre-Liquibase, (б) формулировка «preliquibase → liquibase → пул» неверна — бин пула создаётся первым. Регрессия Boot 3.5.8/4.0.0 на 4.1.1 не действует. |
| GLM §14 — jsonb × Jackson 3 FormatMapper | RISK | **agree-was-valid** | Совпадает с моими F22–F23 (см. топ-2). Закрыто на уровне дизайна: ловушка внесена в `architecture.md`. У GLM точнее указан срок: встроенный Jackson-3 mapper появляется только в Hibernate 7.3, а Boot 4.1 держит 7.2.x. |
| Mercury §1 — отмена стрима в Spring AI | RISK | **agree-was-valid** | Вывод (turnkey-отмены нет, нужна ручная) верен и совпадает с моим F3. Но цитаты API устаревшие (1.x-эра: `ChatClientMessageAggregator`, `StreamedChatResponse`) — в 2.0 это `Flux<ChatClientResponse>` + `ChatModelStreamAdvisor`; факт-основание stale, вердикт оставлен. |
| Mercury §5 — pinning синхронизации на виртуальных потоках | RISK | **stale** | Опровергнуто JEP 491: с JDK 24 `synchronized`/`Object.wait()` **не** пинят виртуальный поток; в JDK 25 риска нет. Пункт снят владельцем. Мой F17 = OK. |
| Mercury §7 — UUID v7 как PK (нет встроенной поддержки, нужен `com.fasterxml.uuid`) | RISK | **stale** | Hibernate 6.5+/7 имеет `@UuidGenerator(style = VERSION_7)`/`UuidVersion7Strategy` и крюк `algorithm()`; «нет встроенной поддержки» устарело (у GLM §15 — верно). Сверх того закрыто решением владельца: самописный генератор работает на Boot 3.4. Мой F24 = OK для PK. |
| Mercury §7 — jsonb-маппинг признан OK через `@Type(postgresql-jsonb)` / `org.hibernate.type.JsonBinaryType` | OK | **disagree** | Механизм неверный: это API сторонней библиотеки `hibernate-types`/`hypersistence-utils`, а не нативный Hibernate; канонический путь — `@JdbcTypeCode(SqlTypes.JSON)` — и он как раз упирается в Jackson-3-ловушку (F22). «OK» пропускает реальный RISK, подтверждённый GLM §14 и мной. |
| Mercury §2 — MCP OAuth признан OK «токены обновляются `OAuth2AuthorizedClientManager`» | OK | **disagree** | Автообновление возможно, но не является готовой автоподстановкой на 12+ серверов за SSO: нужен собственный customizer/`McpTokenProvider`, per-server registrations и обработка 401→refresh→retry. «OK» недооценивает объём; GLM §6 и мой F8 = RISK. Ссылка на `OAuth2AuthorizationCodeExchangeFilterFunction` также неточна для JDK-HttpClient-транспорта. |

**Итог cross-check: agree-was-valid — 4, stale — 2, disagree — 2.**

Смежное: мой F2 (дубль `tool_call_id` при позднем `TOOL_RESULT`) расходился с GLM §2 (OK); после уточнения маппинга `callId` в `agent-tools.md` риск снят — пункт закрыт, отдельного disagreement не остаётся.

---
_Артефакт промежуточного ревью. Не коммитить._
