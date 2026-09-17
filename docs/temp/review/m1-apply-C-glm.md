# Ревью пачки C (задачи 5.1–5.3 llm-gateway, 6.1–6.4 workspace-tools) — GLM-5.3-Flash

Дата: 2026-09-17. Объект: незакоммиченные изменения — `intelligence/` (ChatModelFactory, LlmGateway/Impl, LlmInvoker, AesGcmCredentialDecryptor, LlmModel/LlmCredentials+репо, исключения), `execution/` (ContainerWorkspaceTools, WorkspaceContainerManager, WorkspacePathGuard, ToolResult/ToolStatus, WorkspaceTools), `config/DockerConfig|LlmConfig`, `common/AesGcmEncryption`, `docker/Dockerfile`, правки Properties/application.yml, 10 тестов (intelligence+execution).
Эталоны: specs/llm-gateway, specs/workspace-tools (по сценариям), design.md D-M1-3/D-M1-7, agent-tools.md §1/§5, data-model §2, AGENTS.md.
Сборки/Docker не запускались (запрещено); прогон разработчика 104 теста принят как верифицирующий. Все выводы — статический анализ.

## Findings

### major

**C-1. Вывод exec-а копится в памяти без ограничения — агентский OOM всего процесса** — `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:116-124` (+ отсроченное усечение в `ContainerWorkspaceTools.truncate`, :270-277).
`ExecStartResultCallback` пишет кадры в `ByteArrayOutputStream` без ограничения; конфигурируемый лимит `harness.limits.tool-output` применяется ТОЛЬКО после полного получения вывода. `bash("yes")`, `cat /workspace/<большой файл>`, `find` по огромному дереву — всё это льёт в heap столько байт, сколько успеет пройти по сокету за exec-timeout (60s; для bash — bash-timeout-cap 5м+5s). Локальный демоном + NVMe дают реалистичные сотни МБ…ГБ за окно → OOM убивает оркестратор целиком (все сессии), причём инициировано штатным вызовом инструмента агентом. LOST/лимиты не спасают — они после факта.
Предложение: ограничивающий OutputStream в exec (порог = limits.toolOutput()+ε; при превышении — прекратить сбор, пометить truncated, при желании досрочно завершить callback), маркер прокинуть в ContainerExecResult. Один пункт закрывает cat/find/grep/bash сразу.

### minor

**C-2. Ретрай пере-подписывает весь стрим — дублирование уже доставленных дельт** — `src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:38-50`.
`Retry.backoff` на `model.stream(prompt)` при временной ошибке ПОСЛЕ начала выдачи (обрыв соединения в середине стрима классифицируется как retryable 5xx/network) повторно исполняет весь запрос: потребитель получает префикс ответа дважды. На SSE-broadcaster (7.2) это ляжет как повторный текст; спецификация требует «частичный/неиспользуемый результат не фиксируется», но про ретрай-дубли молчит. 429 на этапе запроса — безопасен, риск именно в mid-stream обрывах.
Предложение: ретраить только до первого элемента (обёртка с флагом «ничего не эмитуто» в retry-фильтре) либо ретраить построение стрима, а не сам Flux, и mid-stream ошибку отдавать наверх как неисправимую.

**C-3. `params_jsonb` маппится избирательно, неизвестные ключи молча игнорируются** — `src/main/java/se/rocketscien/harness/intelligence/ChatModelFactory.java:59-68`.
Поддержаны только `temperature`/`maxTokens`/`topP`; пример из data-model.md §2 (`{"reasoning_effort": "high"}`) молча теряется — модель работает с дефолтом без какого-либо следа. Для ручного конфигурирования в БД (D-M1-3) это ловушка на ровном месте.
Предложение: замапить `reasoning_effort` (OpenAiChatOptions поддерживает), неизвестные ключи — `log.warn` с перечнем; допустимый набор зафиксировать в data-model/agent-tools.

**C-4. Многочанковая запись не атомарна — частичный файл после сбоя** — `src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:216-231`.
`write_file`/`edit_file` пишут чанк-за-чанком (`>` затем `>>`); смерть контейнера/LOST между чанками оставляет обрезанный файл, при этом инструмент вернёт ошибку без упоминания, что файл изменён. Сама схема корректна (argv-передача, формат `%s`, чанк 60000 байт < MAX_ARG_STRLEN, UTF-8-деление по codepoint, busybox-sh builtin printf печатает аргумент литерально) — но обёртку стоит сделать транзакционной.
Предложение: писать чанки во временный файл в контейнере, затем одиночный `mv -f temp target` (атомарно в пределах монтирования).

### nit

**C-5. `timeout` без `-k` (kill-after)** — `ContainerWorkspaceTools.java:184`: команда, traps-ящая TERM, висит до внешнего exec-timeout, а брошенный exec не убивается — процесс остаётся в контейнере до его смерти. `timeout -s TERM -k <n> <sec>` (n — конфиг/константа) закрывает дыру.

**C-6. Хардкод `sleep(50)` в `awaitRunning`** — `WorkspaceContainerManager.java:244`, при том что `state-poll-interval` из конфига используется рядом (:258). Единообразие: взять из `DockerProperties.statePollInterval()`. Попутно: `containerStoppedWithin` переиспользует `startTimeout` как окно подтверждения смерти — семантически другой параметр, лучше отдельный ключ.

**C-7. Единицы `offset/limit` у `read_file` не зафиксированы** — реализация в байтах с UTF-8-границами (можно разрезать codepoint, замещающий символ на краю; тест — только ASCII). Прописать единицы в agent-tools §1 (байты/символы) и решение по границе.

**C-8. `glob` возвращает и каталоги** — `find /workspace -mindepth 1` без `-type f` (`ContainerWorkspaceTools.java:129`); если контракт — «список файлов», добавить `-type f` (или явно разрешить каталоги в agent-tools §1).

**C-9. Ошибочные ссылки на раздел data-model** — `LlmModel.java:16` и `LlmCredentials.java:13` ссылаются на «§3» — llm-таблицы в §2 (§3 — workflow).

**C-10. Стиль ассерта** — `AesGcmEncryptionTest.decryptWithWrongKeyFails`: try/catch+fail вместо `assertThatThrownBy` (корпоративные правила — AssertJ/AAA).

### Честность тестовых подмен (запрошено)

- **symlink-skip**: `WorkspacePathGuardTest.rejectsSymlinkEscapingWorkspace` использует `assumeTrue(false)` при невозможности создать symlink (Windows без привилегии) — на Linux CI выполняется. Ограничение среды, не кода; сам гвард реализован корректно (см. ниже). Принимается.
- **mount-failure**: тест подменяет каталог ФАЙЛОМ (`workspaceMountFailureYieldsError`) — это проверяет host-пре-чек `Files.isDirectory`, а не отказ монтирования на стороне демона. Однако обе ветки маппятся одним механизмом (`WorkspaceContainerException(duringExecution=false)` → ERROR, daemon-ветка накрыта catch-all в `createAndStart`:194-197), так что контракт «ошибка монтирования → ERROR с причиной» структурно покрыт. Для M1 честно; полный daemon-level отказ — можно добавить в 10.x при желании.
- **registry-unreachable**: эмуляция честная — локальный образ тегается на заведомо недоступный registry (`127.0.0.1:1`), pull фейлится по-настоящему, контейнер стартует из локального тега (WorkspaceContainerManagerDockerTest.prefersLocalImageWhenRegistryIsUnreachable).

## Проверено и валидно (без замечаний)

1. **Сценарии llm-gateway — 6/6 покрыты реальными тестами**: потоковая доставка (SSE-чанки по одному), отмена (doOnCancel+take), 429→успех (verify 2 запроса), исчерпание (3 запроса = llm-retries, LlmRetriesExhaustedException), usage→tokens (final chunk), две модели с разными base_url (клиенты различны), dangling credentials/missing model/missing key_version → LlmConfigurationException без падения старта.
2. **Сценарии workspace-tools — 9/9 покрыты на реальном Docker**: ленивое создание (isRunning false→true, идемпотентный ensure), bind-mount (файл хоста виден в /workspace), лимиты+сеть (inspect: memory/nanoCPUs/networkMode=none), registry недоступен (см. выше), чтение (+offset/limit), path traversal (ERROR, хост не тронут), edit ambiguous/not-found (файл не меняется), exit 3 → OK+exitCode, таймаут → timedOut, усечение → truncated+маркер, смерть контейнера mid-bash → LOST (+уже-мёртвый → LOST).
3. **Containment**: запрет абсолютных путей и `..` + normalize-проверка `startsWith(root)` + realPath ближайшего существующего предка со «приклейкой» несуществующего остатка — symlink-побег (включая симлинки, созданные самим агентом внутри workspace: контейнерный путь строится из host-realPath) → ошибка. Недостижимость хост-ФС обеспечена и тем, что все операции идут в контейнере по `/workspace/...`.
4. **Docker lifecycle/pull**: reuse по имени (переживает рестарт процесса — основа для 7.6), pull только при отсутствии локального образа, retries+linear backoff из конфига, недоступность registry → warn и работа на локальном (D-M1-7 точно).
5. **LLM #6915**: `.timeout(конфиг)` и `.maxRetries(0)` заданы ДВАЖДЫ — в setup sync/async клиентов (OpenAiSetup) и в каждой сборке options; streamUsage(true) — tokens из usage доезжают. Кэш по `llm_model.id` без кэширования конфигурационных ошибок. AES-GCM: случайный IV на шифрование, tag 128 бит, IV-префикс, base64; ключ по `key_version` из конфига, пустой/отсутствующий → LlmConfigurationException (Turn FAILED, старт штатен) — D-M1-3 точно.
6. **Хардкод чисел**: только алгоритмические (crypto-константы, 60 КиБ чанк < MAX_ARG_STRLEN, exit 124/сигналы ≥128) — допустимо; исключения — C-6.
7. **Скоуп**: только 5.x/6.x; bash строго sync (ASYNC_ACCEPTED — M3), MCP/CLIENT_EXEC/spawn не занесены; tasks.md — 5.1–5.3, 6.1–6.4 = [x], 7.x не тронуты.
8. **Единый отчёт**: `{callId, tool, status OK|ERROR|CANCELLED|LOST, output?, exitCode?, truncated?, timedOut?}` — соответствует spec-дельте M1 (timedOut только bash, late — M3 не заполняется); `sh -c <script> harness <args>` с позиционными аргументами — безопасная передача путей/паттернов без shell-инъекций (grep-паттерн через `-e "$1"`, пути через `"$1"`).

## Fixes approval

Ре-аппрув по `docs/temp/review/m1-apply-C-judge.md`. Сборки/Docker не запускались (запрещено); прогон разработчика `mvn clean verify` 120/0/0/1 skip принят. Все проверки — по факту файлов.

| Моя находка | Судья | Статус по файлам | Итог |
|---|---|---|---|
| C-1 (major) безлимитный capture вывода exec | C-J-1 (major) | `BoundedOutputStream` (execution/BoundedOutputStream.java): жёсткий предел `tool-output + tool-capture-margin` (конфиг, application.yml `tool-capture-margin: 4KB`), байты сверх — отбрасываются (дренаж без роста памяти), `truncated` наружу; onOverflow → `callback.close()` (WorkspaceContainerManager.java:135-144); флаг прокинут через `ContainerExecResult` → `truncate(output, alreadyTruncated)` в каждом инструменте. Тесты: `bashHugeOutputIsBoundedNotAccumulated`, BoundedOutputStreamTest (4 кейса) | **Approve (закрыто)**: память ограничена детерминированно (дренаж-ветка условия судьи); закрытие exec при переполнении — best-effort, что допускалось («kill ИЛИ дренаж»). |
| C-2 (minor) ретрай дублирует дельты | C-J-2 | LlmInvoker.java:49-59: `AtomicBoolean delivered`, фильтр ретрая `isTransient && !delivered` — после первого элемента ретрай невозможен, mid-stream обрыв уходит наверх неисправимым; + chatModel резолвится в defer (конфиг-ошибка как сигнал Flux). Тесты: `doesNotRetryAfterFirstDelta`, `retriesWhenErrorArrivesBeforeFirstDelta` (LlmInvokerRetrySemanticsTest) | **Approve (закрыто)** — ровно требуемая семантика «ретрай только до первой дельты». |
| C-3 (minor) params_jsonb маппится частично, молча | C-J-4 | ChatModelFactory.options(): temperature/maxTokens(+alias)/topP(+alias)/frequencyPenalty/presencePenalty/seed/reasoning_effort(+alias)/verbosity/stop; WARN со списком неизвестных ключей (ChatModelFactory.java:116-125). Тесты: `mapsKnownParamsIncludingReasoningEffort`, `acceptsCamelCaseAliases`, `warnsOnUnknownParamsWithoutFailing` | **Approve (закрыто)** — reasoning_effort из примера data-model §2 доезжает, молчаливых потерь нет. |
| C-4 (minor) неатомарная многочанковая запись | C-J-6 | writeContainerFile: чанки → `<path>.harness-tmp`, затем `mv -f -- tmp target` (атомарно на монтировании); discardTemp на всех ветках сбоя (:238,:244,:250). Тест: `largeUnicodeContentRoundtripsAcrossChunks` | **Approve (закрыто)**. |
| C-5 (nit) `timeout` без `-k` | не взят (C-J-6 «спорное — с обоснованием») | `timeout -s TERM` без `-k` (ContainerWorkspaceTools.java:192) | **Не закрыто — backlog (nit, non-blocking)**: изолированная per-session команда + внешний exec-timeout ограничивают последствия. |
| C-6 (nit) sleep(50)/переиспользование startTimeout | C-J-6 #4 | awaitRunning → `statePollInterval` (:278); смерть контейнера подтверждается отдельным `container-stop-confirm: 3s` с ранним выходом при живом контейнере (:290-302). Тест: `signalExitCodeOfLiveContainerIsNotReportedAsLost` | **Approve (закрыто)**. |
| C-7 (nit) единицы offset/limit | — | WorkspaceTools.java:13: «offset/limit — в байтах UTF-8; граница может разрезать code point» | **Approve (закрыто)**. |
| C-8 (nit) glob возвращает каталоги | не взят | `find -mindepth 1` без `-type f` (:131) | **Не закрыто — backlog (nit, non-blocking)**: семантика «пути» vs «файлы» не закреплена в agent-tools §1; предложить зафиксировать при случае. |
| C-9 (nit) refs «data-model §3» | — | См. примечание ниже | **Закрыто (в трактовке судьи/дева: C-9 = include basename-glob)**: `matchesInclude` с fallback-`contains` удалён, фильтр — basename-glob через PathMatcher + `PatternSyntaxException` → ERROR `invalid include pattern` (ContainerWorkspaceTools.java:155-157,165,173-174); тесты `grepFindsPatternRespectingInclude`, `invalidIncludePatternYieldsError`. Ошибочные ссылки «§3» в Javadoc LlmModel/LlmCredentials остаются — nit-backlog вместе с C-8/C-5. |
| C-10 (nit) стиль ассерта | — | AesGcmEncryptionTest:26 — `assertThatThrownBy` | **Approve (закрыто)**. |

### Судейские пункты вне моего списка (проверены попутно)

- **C-J-3 (полусозданный контейнер, вечный LOST)** ✓: `ensureContainer` — synchronized, проверяет живость кэша, остановленный контейнер по имени удаляется и пересоздаётся (WorkspaceContainerManager.java:66-87); `createAndStart` удаляет контейнер при падении старта (:221-225). Тесты: `deadContainerIsRecreatedAndCallCompletes`, `recreatesStoppedContainerInsteadOfKeepingLost`.
- **C-J-5 (хардкод 60_000)** ✓: `harness.docker.write-chunk-bytes: 60000` в yml, `DockerProperties.writeChunkBytes`.
- **C-J-6 #5 (NUL/Pattern)** ✓: `rejectsNulByteInsteadOfThrowingRuntime`, `invalidGlobPatternYieldsError`, `invalidIncludePatternYieldsError`.
- **C-J-6 #7 (OpenAIIoException transient)** ✓: LlmInvoker.isTransient:65.

### Итог ре-аппрува

**APPROVE.** Все гейтящие пункты судьи C-J-1…C-J-6 закрыты по факту (+16 тестов, включая требуемые OOM/mid-stream/пересоздание-контейнера); мои C-1…C-4, C-6, C-7, C-10 закрыты; C-5/C-8 (+остаточные Javadoc-ссылки) — nit-backlog, не блокирует. Условие «verify зелёный» принято по прогону разработчика (120/0/0/1 skip — symlink-тест на Windows).

## Summary

Пачка C зрелая: LLM-контур полностью соответствует D-M1-3 (двойная защита #6915, кэш по id, AES-GCM c key_version, backoff/лимит из конфига, отмена через dispose), docker-контур — D-M1-7 (lazy lifecycle, сеть off, лимиты, pull-политика с честным тестом недоступного registry), containment-гварды с realPath реально держат symlink-побеги, все 15 сценариев двух спек покрыты тестами на реальных WireMock/Docker. Один major: вывод exec копится в heap без ограничения (C-1) — агентский `yes`/`cat big` валит оркестратор OOM-ом до применения лимита; фикс точечный (bounded capture в manager). Далее minor: ретрай дублирует частично доставленный стрим (C-2), молчаливое игнорирование params_jsonb вроде reasoning_effort (C-3), неатомарная многочанковая запись (C-4); шесть nit. Вердикт: к аппруву после C-1 (C-2…C-4 — желательно в этой же пачке, остальное — backlog).
