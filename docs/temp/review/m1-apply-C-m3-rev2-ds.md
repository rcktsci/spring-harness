# Ревью пачки C, rev2 — DeepSeek-V4.1-Flash

Дата: 2026-09-17. Объект: незакоммиченные изменения пачки C (задачи 5.1–5.3 llm-gateway, 6.1–6.4 workspace-tools):
`intelligence/` (LlmGateway/Impl, ChatModelFactory, LlmInvoker, AesGcmCredentialDecryptor, LlmModel/LlmCredentials+репо, исключения),
`execution/` (ContainerWorkspaceTools, WorkspaceContainerManager, WorkspacePathGuard, ToolResult/ToolStatus/ContainerExecResult,
WorkspaceTools, WorkspaceContainerException), `config/` (DockerConfig, LlmConfig, DockerProperties, LimitsProperties, LlmProperties),
`common/AesGcmEncryption`, `docker/Dockerfile`, `application.yml`, 10 тестов, `tasks.md`.
Эталоны: `specs/llm-gateway`, `specs/workspace-tools`, `agent-tools.md` §1/§5, `data-model.md` §2, `decisions.md` (D-41/D-43),
`architecture.md` (#6915), AGENTS.md. Кросс-чек: `docs/temp/review/m1-apply-C-glm.md` (GLM-5.3-Flash).

**Верификация:** сборки/тесты/Docker НЕ запускались (запрет задачи). Все выводы — статический анализ по коду, спекам и тестам;
прогон разработчика (`mvn clean verify`, 104 теста, 0 падений, 1 skipped) принят как внешний сигнал, но мной не воспроизводился.
По каждому пункту ниже указан конкретный путь исполнения/дефект; где вывод — следствие (не факт), это отмечено.

## Findings (severity, файл:строка, дефект, предложение)

### major

**DS-C1. Вывод `exec` копится в heap без ограничения → агентский OOM всего процесса** (agree GLM C-1) —
`src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:116-124`,
`srv/.../ContainerWorkspaceTools.java:270-277`.
`ExecStartResultCallback` пишет stdout+stderr в неограниченный `ByteArrayOutputStream`; лимит `harness.limits.tool-output`
применяется только в `truncate()` ПОСЛЕ полного сбора (`bash("yes")`, `cat` большого файла, `find/grep` по большому дереву).
За `exec-timeout` (60s; для bash — `bash-timeout-cap` 5m + 5s) локальный демон + NVMe отдают сотни МБ…ГБ → `OutOfMemoryError`
убивает оркестратор (все сессии), инициировано штатным tool-call агента. Спека `workspace-tools` («вывод SHALL ограничиваться»)
трактуется реализацией лишь как усечение *результата*, а не *сбора*.
Предложение: bounded `OutputStream` в `exec` (порог ≈ `limits.toolOutput()` + ε): при превышении — прекращать сбор,
досрочно завершать callback, помечать `truncated` и прокидывать маркер в `ContainerExecResult`/`ToolResult`. Один фикс закрывает
`cat/find/grep/bash`. Проверить, что `ExecStartResultCallback` можно прервать из `write()` (иначе — отдельный счётчик + `Thread.interrupt`
потока `awaitCompletion`, либо лимит на уровне демона).

### minor

**DS-C2. Ретрай пере-подписывает весь стрим → дублирование уже доставленных дельт** (agree GLM C-2) —
`src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:38-50`.
`Retry.backoff` навешен на `Flux.defer(() -> model.stream(prompt))`. Обрыв в середине SSE, классифицированный как retryable
(5xx/network), повторно исполняет запрос целиком: потребитель (SSE-broadcaster 7.2/8.5) получает префикс ответа дважды.
Спека требует «частичный/неиспользованный результат не фиксируется», но про дубли-ретраи молчит.
Предложение: обернуть источник флагом «был эмит» и ретраить только когда ничего не доставлено; mid-stream ошибку отдавать
наверх как неисправимую (или ретраить построение стрима, а не сам `Flux`).

**DS-C3. `params_jsonb` маппится избирательно, неизвестные/иные ключи молча игнорируются** (agree GLM C-3) —
`src/main/java/se/rocketscien/harness/intelligence/ChatModelFactory.java:59-68`.
Поддержаны только `temperature`/`maxTokens`/`topP` (camelCase). Пример из `data-model.md` §2 — `{"reasoning_effort": "high"}` —
теряется без следа; оператор, заполняющий БД вручную (D-M1-3), получает дефолт модели без диагностики. Snake_case-варианты
(`max_tokens`, `top_p`) тоже игнорируются.
Предложение: добавить `reasoning_effort` (`OpenAiChatOptions.reasoningEffort`), залогировать `warn` со списком неизвестных ключей,
зафиксировать допустимый набор в `data-model`/`agent-tools`.

**DS-C4. Многочанковая запись не атомарна — частичный файл при сбое между чанками** (agree GLM C-4) —
`src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:216-231`.
`write_file`/`edit_file` пишут чанк-за-чанком (`>` затем `>>`); смерть контейнера/`LOST` между чанками оставляет обрезанный
файл, а инструмент возвращает ошибку без указания, что файл уже изменён. Сама схема передачи корректна (argv, `%s`, 60_000 <
`MAX_ARG_STRLEN`, деление по codepoint).
Предложение: писать во временный файл в контейнере, затем один `mv -f temp target` (атомарно в границах монтирования).

**DS-C5. Путь/шаблон от модели могут уронить tool-call необработанным runtime-исключением** (NEW) —
`src/main/java/se/rocketscien/harness/execution/WorkspacePathGuard.java:25`,
`src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:133,307`.
`Paths.get(relativePath)` бросает `InvalidPathException` на NUL (`\u0000`) и недопустимых для платформы символах;
`FileSystems.getDefault().getPathMatcher("glob:" + pattern|include)` бросает `PatternSyntaxException` на кривом glob (`"["`, `"{"`).
`resolveHostPath` и методы инструментов ловят только `WorkspacePathException`/`WorkspaceContainerException`, поэтому исключение
улетает из метода вместо `ToolResult.error(...)` — нарушение контракта `WorkspaceTools` (агент получает не ERROR, а падение Turn'а).
Прочие небезопасные места того же класса: `Path.of(relative)` (`glob`, :138), `matchesInclude`.
Предложение: в `resolveHostPath` обернуть `Paths.get` в `try/catch (InvalidPathException)` → `WorkspacePathException`; в `glob/grep`
валидировать/ловить `PatternSyntaxException`. Тогда любой ввод модели даёт детерминированный `ERROR`.

**DS-C6. Exit code ≥ 128 всегда вызывает ожидание полного `startTimeout` (30 s)** (NEW) —
`src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:149-152` + `:252-261`.
`containerStoppedWithin` опрашивает `isContainerRunning` до истечения `startTimeout` и возвращает `!isContainerRunning`.
Если контейнер жив (штатно — он жив всегда, долгоживущий `while true`), метод честно крутится ВСЕ `startTimeout` (30 s) и
возвращает `false`. Условие срабатывает на любой команде с `exitCode >= 128` (например `bash("exit 200")`, `kill -9 $$` → 137,
SIGTERM → 143). Итог — гарантированный 30-секундный простой виртуального потока/раунда на валидном не-сигнальном выходе.
Предложение: применять окно подтверждения только когда первичный `isContainerRunning` уже дал «мертв» (или проверить смерть
в коротком окне `statePollInterval`×N), а не как безусловный опрос; при желании — отдельный конфиг-ключ вместо `startTimeout`.

**DS-C7. `grep`-фильтр `include` даёт ложные срабатывания и не матчит include с путём** (NEW) —
`src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:303-309`.
`name.contains(include.replace("*", ""))` как fallback: `include="a*"` совпадёт с любым именем, содержащим `a`;
`include="*.java"` ошибочно включит `x.java.bak` (contains `.java`). При этом include с каталогом (`src/*.py`) не сработает
никогда: glob применяется к basename, у которого нет `/`. Контракт `agent-tools` §1 для `include` не детализирует —
поведение непредсказуемо.
Предложение: оставить только glob-матч (по basename) и/или применить `PathMatcher` к относительному пути; убрать substring-fallback;
зафиксировать семантику `include` в `agent-tools` §1.

**DS-C8. Задача 6.1 «smoke-сборка в CI» фактически не выполнена — CI в репозитории отсутствует** (NEW) —
`docker/Dockerfile`, `tasks.md:38`.
`docker/Dockerfile` есть, но в корне репозитория нет ни одного CI-конфига (`/.github`, `.gitlab-ci.yml`, Jenkinsfile — не найдено).
Образ helper-а собирается только в `DockerTestSupport.helperImage()` (Testcontainers) в момент прогона docker-тестов; это
подтверждает сборку локально при `verify`, но не «в CI». Задача помечена `[x]`.
Предложение: либо добавить CI-шаг «docker build docker/Dockerfile» (если платформа определена), либо явно переформулировать
задачу 6.1 (smoke-сборка в docker-тестах Testcontainers) и снять формулировку «в CI»; зафиксировать решение строкой в tasks/apply-notes.

### nit

**DS-C9. `timeout` без `-k`** (agree GLM C-5) — `ContainerWorkspaceTools.java:184`: команда, игнорирующая TERM, висит до внешнего
`exec-timeout`, брошенный процесс остаётся в контейнере. `timeout -s TERM -k <n> <sec>` (n — конфиг).

**DS-C10. Хардкод `sleep(50)` и переиспользование `startTimeout`** (agree GLM C-6) — `WorkspaceContainerManager.java:244`
(при наличии `statePollInterval` рядом, :258); `containerStoppedWithin` семантически просит отдельный ключ.

**DS-C11. Единицы `offset/limit` у `read_file` не зафиксированы** (agree GLM C-7) — `ContainerWorkspaceTools.java:56-59`:
байты с разрезом codepoint (замещающий символ на краю, тест только ASCII). Прописать единицы в `agent-tools` §1.

**DS-C12. `glob` возвращает и каталоги** (agree GLM C-8) — `ContainerWorkspaceTools.java:129`: `find` без `-type f`; если контракт
«список файлов» — добавить `-type f`.

**DS-C13. Неверная ссылка на раздел** (agree GLM C-9) — `LlmModel.java:16`, `LlmCredentials.java:13`: «data-model §3», фактически
§2 (`docs/design/data-model.md:19` — `## 2. llm`).

**DS-C14. Стиль теста** (agree GLM C-10) — `AesGcmEncryptionTest.java:23-31`: try/catch+`fail` вместо `assertThatThrownBy`.

**DS-C15. `LlmInvoker.stream` бросает конфигурационную ошибку синхронно, ломая реактивный контракт** (NEW) —
`src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:37`: `llmGateway.chatModel(llmModelId)` вызывается ДО `Flux.defer`,
поэтому `LlmConfigurationException` летит из метода, а не сигнализируется в `Flux` (при этом `LlmRetriesExhaustedException`
приходит именно в потоке). Потребитель (7.2), построенный на `onErrorResume`, пропустит синхронный выброс.
Предложение: завести `Flux.defer(() -> { ChatModel m = llmGateway.chatModel(id); return m.stream(prompt); })` — ошибка станет
частью потока, единообразно с ретраями.

**DS-C16. Контейнер исполнения — root без hardening** (NEW) — `WorkspaceContainerManager.java:177-186`, `docker/Dockerfile`:
нет `withUser`/`USER`, нет `withCapDrop`, нет read-only rootfs. Для агент-контролируемого bash + bind-mount это лишняя
поверхность. Для M1 допустимо, но зафиксировать (D-строка) или добавить `--cap-drop=ALL --security-opt no-new-privileges`
и непривилегированного пользователя.

**DS-C17. Dangling-symlink внутри контейнера обходит host-гвард** (NEW) — `WorkspacePathGuard.java:62-71`: `realPath` привязывается
к ближайшему СУЩЕСТВУЮЩЕМУ предку; симлинк на несуществующую цель не детектится (`Files.exists` = false), и запись в
`/workspace/link` уйдёт за пределы `/workspace` внутри контейнера. Хост при этом не достигается (монтирование ограничено
`/workspace`, сеть off), поэтому нит/информ; при желании — `Files.isSymbolicLink` для каждого сегмента (NOFOLLOW).

**DS-C18. Мелкие наблюдения по надёжности** (NEW, сгруппировано):
`isContainerRunning` глотает любое исключение → `false` (`WorkspaceContainerManager.java:263-270`): при кратком сбое демона
валидный вызов классифицируется как `LOST`. `ensureImage`: результат `awaitCompletion` при pull игнорируется
(`:207-210`) — неполный pull считается успехом, ошибка всплывёт в `createContainerCmd`; плюс TOCTOU `imageAvailableLocally` →
`createContainerCmd`. `LlmModelRepository.java:12`/`LlmCredentialsRepository.java:12` — избыточный override `findById`
(CrudRepository уже объявляет) + несоблюдённый порядок импортов (`java.util.UUID` / пустая строка / `java.util.Optional`).

## Проверено и валидно (без замечаний)

1. **Containment (host-side)** — `WorkspacePathGuard` :18-43: пустой путь, `/`- и `\`-префиксы, `isAbsolute()`, `normalize()`
   + `startsWith(root)` + `realPath` ближайшего существующего предка; симлинк-побег с существующей целью → `WorkspacePathException`.
   `toContainerPath` строит `/workspace/...` от host-normalized пути, `\`→`/`. Пути передаются в контейнер позиционными
   аргументами (`"$1"`), shell-инъекция невозможна.
2. **`writeContainerFile`** :216-254: `printf %s "$2"` — формат `%s` литерален, `%`/`$`/`\` в аргументе не интерпретируются;
   чанк 60_000 байт < `MAX_ARG_STRLEN` (128 KiB); деление по codepoint UTF-8; пустой контент → `printf %s '' > "$1"`. Корректно
   (кроме атомарности — DS-C4).
3. **`bash`** :183-189: `cd -- "$1" && timeout -s TERM "$2" sh -c "$3"`, `$2`/`$3` — позиционные аргументы (нет инъекции);
   `effectiveBashTimeout` клампится `bash-timeout-cap`, дефолт `bash-timeout`, нулевой/отрицательный → дефолт; внешний
   `exec-timeout = effective + 5s` > внутреннего, `timedOut` = exit 124. Non-zero exit → `OK`+`exitCode` (спека).
4. **`grep`/`glob`** — паттерн через `-e "$1"`, `--` перед путём; `grep -rn` exit 1 (нет совпадений) не считается ошибкой;
   `glob` матчит relative-пути `PathMatcher`; `containers.ensureContainer` вызывается.
5. **Docker lifecycle** — `ensureContainer` через `computeIfAbsent` (идемпотентно, ошибка не кэшируется; повтор пересоздаст);
   `findContainerId` переживает рестарт процесса (основа 7.6); `createAndStart` — bind `hostDir→/workspace`, `workingDir`,
   `network none`, `memory`/`nanoCPUs` из конфига; `awaitRunning` по `startTimeout`; `removeContainer` сначала чистит кэш,
   `withForce(true)`.
6. **Pull-политика** — локальный образ приоритетен (`inspectImageCmd`), pull только при отсутствии, retries×linear backoff
   из конфига, финальный warn «relying on local image»; недоступность registry не фейлит при наличии локального
   (`WorkspaceContainerManagerDockerTest.prefersLocalImageWhenRegistryIsUnreachable` — честная эмуляция через тег на
   `127.0.0.1:1`). D-M1-7 соблюдён.
7. **LLM #6915 / D-M1-3** — `.timeout(конфиг)` и `.maxRetries(0)` заданы дважды (в `OpenAiSetup.setup*Client` и в каждой
   `OpenAiChatOptions`), `streamUsage(true)`; кэш `ConcurrentMap.computeIfAbsent` по `llm_model.id`, атомарен, ошибки конфигурации
   не кэшируются; клиенты для разных `base_url`/`model_id` различны (тест). `LlmConfigurationException` при отсутствии
   модели/учётных данных/key_version — старт процесса не затрагивается.
8. **AES-GCM** — случайный IV 12 байт на шифрование, тег 128 бит, `IV||ciphertext` в base64, ключ по `key_version` из
   `Map.copyOf`-конфига; пустой/отсутствующий ключ → `LlmConfigurationException`; `decrypt` обёрнут catch-all. Ключ и plaintext
   нигде не логируются; в `application.yml` секрет берётся из env с пустым дефолтом (`HARNESS_LLM_KEY_V1`) — утечки нет.
9. **Ретраи/отмена** — `Retry.backoff(retries, backoffBase)`, `retries = max(0, llmRetries-1)` → ровно `llmRetries` попыток
   (тест verify 3 запроса при `llm-retries=3`); `.filter(isTransient)` — 429/5xx/`OpenAIRetryableException` по цепочке causes
   с защитой от self-cycle; `onRetryExhaustedThrow` → `LlmRetriesExhaustedException`; отмена через `dispose` подписки
   (`doOnCancel`+`take(1)` в тесте). `isTransient` соответствует спеке.
10. **SQL/JPA** (упор задачи) — в пачке нет author-written JPQL/SQL: репозитории — `CrudRepository`, миграции пачки A
    (`llm_credentials`/`llm_model`, `2026-09-17__create_schema__m1_core.xml:63-150`). Маппинг сущностей совпадает со схемой:
    `@Column(name/nullable)` явные, `params_jsonb` ↔ `JSONB` (nullable), `key_version` ↔ `INT NOT NULL DEFAULT 1`,
    `ddl-auto: validate` — согласовано. `@JdbcTypeCode(SqlTypes.JSON)` + `Jackson3JsonFormatMapper` (`JpaConfig`) — ловушка
    Jackson 3/Hibernate закрыта (пачка A). N+1/ленивых связей нет (`open-in-view: false`, только basic-поля после `findById`).
    Ключевые слова/нейминг — вне scope (нет запросов).
11. **Параметризация** — все числовые параметры вынесены в `application.yml`/`@ConfigurationProperties`; «магические» числа —
    только алгоритмические (60_000 < MAX_ARG_STRLEN, 12/128 crypto, 124/128 сигналы); `ConfigPropertiesBindingTest` проверяет
    привязку новых ключей (Docker/LLM/Limits) — правило AGENTS.md соблюдено (кроме DS-C6/DS-C10).
12. **Единый отчёт и скоуп** — `ToolResult {callId,tool,status,output?,exitCode?,truncated?,timedOut?}` соответствует
    `agent-tools` §5 (M1: `late`/`ASYNC_ACCEPTED` не заполняются); `tasks.md` изменён по 5.1–5.3/6.1–6.4, 7.x не тронуты;
    M3/M4-сущности не протащены (кроме резервного enum-значения, nit).

## Summary

- **major: 1** (DS-C1 — неограниченный сбор вывода exec → агентский OOM).
- **minor: 7** (DS-C2 ретрай-дубли стрима, DS-C3 молчаливый `params_jsonb`, DS-C4 неатомарная запись, DS-C5 необработанные
  `InvalidPathException`/`PatternSyntaxException` на вводе модели, DS-C6 30-секундный стоп на exit≥128, DS-C7 ложные/неработающие
  `include` в grep, DS-C8 «CI-сборка» задачи 6.1 не выполнена).
- **nit: 10** (DS-C9…DS-C18).
- **Кросс-чек GLM-5.3-Flash:** все 10 находок GLM (C-1…C-10) подтверждаю — C-1 (major), C-2/C-3/C-4 (minor), C-5…C-10 (nit);
  расхождений нет. Дополнительно (не отражено у GLM): DS-C5 (необработанные исключения от ввода модели),
  DS-C6 (полный `startTimeout` на exit≥128), DS-C7 (семантика `include`), DS-C8 (отсутствие CI), DS-C15 (синхронный выброс
  из `LlmInvoker.stream`), DS-C16 (root-контейнер), DS-C17 (dangling symlink), DS-C18 (надёжность/стиль репо).

**Топ-3:** DS-C1 — OOM оркестратора штатным tool-call'ом (блокер); DS-C6 — 30-секундный стоп на любом exit≥128 (реальный
латентный дефект, у GLM отсутствует); DS-C5 — ввод модели роняет tool-call исключением вместо `ERROR` (контракт + security-край).

**Вердикт: reject (не аппрувлю) до устранения DS-C1.** DS-C5, DS-C6, DS-C2, DS-C3, DS-C4 — рекомендую в эту же пачку
(точечные правки, без смены дизайна); DS-C8 — решить организационно (добавить CI-шаг либо переформулировать 6.1); DS-C7 и
DS-C9…DS-C18 — backlog/по желанию. После фикса потребуется повторное ревью изменённого кода (re-approve).

**Verified with:** статический анализ кода/спеки/тестов (файлы и строки в находках); внешний прогон разработчика
(`mvn clean verify`, 104 теста) принят как заявление.
**Not verified:** сборка/lint/typecheck, WireMock/Docker-тесты и OOM-сценарий мной не запускались (запрет).
**Residual risk:** достижимость OOM (DS-C1) подтверждена рассуждением о пути исполнения, не репродукцией; симлинк- и
mount-сценарии оценены по коду и существующим тестам.
