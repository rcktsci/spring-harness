# Review m1-session-core — apply, пачка C (5.1–5.3 llm-gateway, 6.1–6.4 workspace-tools) — судейский вердикт

> Прогон m3 по незакоммиченной пачке C: `intelligence/` (LlmGateway/Impl, ChatModelFactory, LlmInvoker, AesGcmCredentialDecryptor, LlmModel/LlmCredentials+репо), `execution/` (ContainerWorkspaceTools, WorkspaceContainerManager, WorkspacePathGuard, единый `ToolResult`), `config/` (DockerConfig, LlmConfig, DockerProperties, LimitsProperties, LlmProperties), `common/AesGcmEncryption`, `docker/Dockerfile`, `application.yml`, тесты `execution/`+`intelligence/`, `tasks.md`.
> Ревью: GLM-5.3-Flash · DeepSeek-V4.1-Flash · Mercury-2.5. Сборки/Docker не запускались (запрет; принят прогон разработчика `mvn clean verify` 104 теста / 0 падений / 1 skipped).

## Findings (severity, файл:строка, дефект с фактом, предложение)

### major

1. **OOM при bash/read_file с большим выводом** — `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:116-124` (`ExecStartResultCallback(output, output)` пишет stdout+stderr в неограниченный `ByteArrayOutputStream`), `ContainerWorkspaceTools.java:270-277` (`truncate()` применяется только после полного сбора).
   **Факт:** `bash("yes")`, `cat` большого файла, `find/grep` по большому дереву за `exec-timeout`/5m-cap + 5 s собирают сотни МБ…ГБ в heap оркестратора → `OutOfMemoryError` убивает процесс (и все сессии). Спека `workspace-tools` «вывод SHALL ограничиваться» трактуется как усечение *результата*, а не *сбора*. Консенсус GLM C-1 / DS-C1 (major).
   **Предложение:** bounded `OutputStream` (порог ≈ `limits.toolOutput()` + ε) внутри `exec`; при превышении — досрочное завершение callback, `truncated=true`. Один фикс закрывает bash/read/find/grep. Проверить, что `ExecStartResultCallback.onNext()` поддерживает прерывание записи (иначе — `Thread.interrupt` потока `awaitCompletion`).

### minor

2. **Mid-stream retry дублирует доставленные deltas** — `src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:36-51`.
   **Факт:** `Retry.backoff` навешен на весь `Flux.defer(() -> model.stream(prompt))`. Обрыв SSE в середине стрима, классифицированный как retryable (5xx/network/OkHttp-fluke), пересоздаёт подписку: потребитель (SSE-broadcaster 7.2/8.5) получает «префикс + полный повтор». WireMock-тесты покрывают только ошибки ДО начала тела ответа. Консенсус GLM C-M2 / DS-C2.
   **Предложение:** флаг «был эмит» (счётчик или `Sinks`-обёртка) в фильтре ретрая — ретраить только до первого элемента; mid-stream ошибка уходит как неисправимая. Минимум — задокументировать поведение и добавить тест «ошибка после N deltas».

3. **Утёкший полусозданный контейнер навсегда кэшируется как LOST** — `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:161-198` + `:62-72` + `:95-100`.
   **Факт:** если `createContainerCmd` успешен, а `startContainerCmd`/`awaitRunning` падают, контейнер остаётся в Docker (created/exited) и НЕ удаляется в catch. Следующий `ensureContainer` через `findContainerId(showAll=true)` находит остановленный → кэширует id → все вызовы получают LOST (`isContainerRunning=false` → `WorkspaceContainerException(duringExecution=true)`). Восстановиться перезапуском вызова невозможно до рестарт-скана (7.6). Консенсус GLM C-M4 (medium) / Mercury (low).
   **Предложение:** в `createAndStart` catch — best-effort `removeContainerCmd(force)` созданного id; либо в `exec` при «exists but not running» делать remove+пересоздание вместо мгновенного LOST.

4. **`exitCode ≥ 128` всегда вызывает ожидание полного `startTimeout` (30 с)** — `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:147-152` + `:252-261`.
   **Факт:** `containerStoppedWithin` безусловно опрашивает демон до истечения `startTimeout` (30 s); если контейнер жив (а он жив — долгоживущий `while true`), метод крутится ВСЕ 30 s и возвращает `false`. Условие срабатывает на любом `bash("exit 137")` / `bash("exit 200")` / `kill -9 $$` → 137. Итог — гарантированный 30-секундный простой на валидном не-сигнальном выходе. Консенсус GLM C-L7 / DS-C6.
   **Предложение:** применять окно подтверждения только когда первичный `isContainerRunning` уже дал «мертв», а не как безусловный опрос; короткое окно (`state-poll-interval × N`) вместо `startTimeout`; новый конфиг-ключ (например, `container-stop-confirm`).

5. **NUL-байт в path или кривой glob → необработанный runtime, не ToolResult.error** — `src/main/java/se/rocketscien/harness/execution/WorkspacePathGuard.java:25` (`Paths.get`), `ContainerWorkspaceTools.java:133,307,138` (`FileSystems.getDefault().getPathMatcher("glob:")`).
   **Факт:** `Paths.get(NUL)` бросает `InvalidPathException`; `PathMatcher("glob:[")` бросает `PatternSyntaxException`. Эти исключения не ловятся в `try { … } catch (WorkspacePathException)`, уходят как `RuntimeException` → падение Turn'а вместо честного `ToolResult.error`. Контрактное нарушение `WorkspaceTools`. Один ревьюер (DS-C5), но сериозно по контракту.
   **Предложение:** в `resolveHostPath` обернуть `Paths.get` в `try/catch (InvalidPathException)` → `WorkspacePathException`; в glob/grep ловить `PatternSyntaxException` и отдавать `ToolResult.error`. Тогда любой ввод модели даёт детерминированный `ERROR`.

6. **Хардкод `60_000` в `splitByUtf8Bytes`** — `src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:234`.
   **Факт:** `int limit = 60_000;` — D-39 «все числа — конфиг». 60000 — лимит `MAX_ARG_STRLEN` Linux (131072, запас ×2). При смене обёртки exec / ядра лимит может потребовать правки. Один ревьюер (GLM C-M1, medium).
   **Предложение:** `harness.docker.write-chunk-bytes` (int) в `DockerProperties` + дефолт 60000 в `application.yml`; Javadoc-ссылку на `MAX_ARG_STRLEN` сохранить.

7. **`isTransient` не матчит `OpenAIIoException`** — `src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:53-67`.
   **Факт:** сетевые сбои SDK openai-java-okhttp бросает как `OpenAIIoException extends OpenAIException` (а не `OpenAIRetryableException`/`OpenAIServiceException`); единичный refused/reset/DNS-фликер = немедленный FAILED Turn'а, хотя спека просит ретраить временные ошибки. Один ревьюер (GLM C-M3, medium).
   **Предложение:** добавить `cause instanceof OpenAIIoException → true` в фильтр (до первого байта стрима); mid-stream критерий см. находку 2.

8. **Многочанковая запись не атомарна** — `src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:216-231`.
   **Факт:** `write_file`/`edit_file` пишут чанк-за-чанком (`>` → `>>`); смерть контейнера/`LOST` между чанками оставляет обрезанный файл, инструмент возвращает ERROR без указания, что файл уже частично изменён. Консенсус GLM C-L3 / DS-C4.
   **Предложение:** писать во временный файл в контейнере, затем один `mv -f tmp target` (атомарно в границах монтирования). Принять и задокументировать — тоже корректный вариант для текстовых файлов.

9. **`grep`-фильтр `include` даёт ложные срабатывания** — `src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:303-309`.
   **Факт:** `name.contains(include.replace("*", ""))` — fallback: `include="*.java"` ошибочно матчит `x.java.bak`; `include="src/*.py"` не сработает (glob по basename). Контракт `agent-tools` §1 для `include` не детализирован.
   **Предложение:** оставить только glob-матч по basename; убрать substring-fallback; зафиксировать семантику в `agent-tools` §1.

10. **`params_jsonb` маппится избирательно** — `src/main/java/se/rocketscien/harness/intelligence/ChatModelFactory.java:59-68`.
    **Факт:** поддержаны только `temperature`/`maxTokens`/`topP` (camelCase). `{"reasoning_effort": "high"}` из `data-model.md` §2 теряется без следа; snake_case-варианты (`max_tokens`, `top_p`) тоже игнорируются. Консенсус GLM C-L5 / DS-C3.
    **Предложение:** добавить `reasoningEffort`; логировать WARN со списком неизвестных ключей при сборке клиента; зафиксировать допустимый набор в `agent-tools` / `data-model`.

### nit

11. **`timeout` без `-k` → процессы-зомби в контейнере** — `ContainerWorkspaceTools.java:184`. `timeout -s TERM` убивает прямого потомка; внук, игнорирующий TERM, держит exec-стрим открытым до `execTimeout` (60 s). GLM C-L1 / DS-C9. Фикс: `timeout -s TERM -k <n>` (n — конфиг).

12. **`workspaceRoot` по умолчанию относительный → bind-mount зависит от CWD** — `application.yml:22` (`workspaces/sessions`). `workspaceDir()` возвращает относительный путь; `toAbsolutePath()` в `createAndStart` ресолвится от CWD JVM. GLM C-L2 / DS-C12. Задокументировать требование абсолютного пути в yml-комментарии.

13. **`sleep(50)` в `awaitRunning` (WorkspaceContainerManager.java:244) при наличии `properties.statePollInterval()`** — GLM C-L6 / DS-C10. Использовать `properties.statePollInterval().toMillis()`.

14. **`LlmInvoker.stream` бросает `LlmConfigurationException` синхронно** — `LlmInvoker.java:37`: `llmGateway.chatModel(llmModelId)` вызывается ДО `Flux.defer`, поэтому `LlmConfigurationException` летит из метода, а не как сигнал в `Flux` (в отличие от `LlmRetriesExhaustedException`). DS-C15. Обернуть: `Flux.defer(() -> { ChatModel m = ...; return m.stream(prompt); })`.

15. **Тест-пробел: целостность многочанковой записи и спецсимволы** — `ContainerWorkspaceToolsDockerTest`. Не покрыты: файл >60 КиБ, контент с `%`/`\n`/backslash, unicode на границе чанка, пустой контент. GLM C-L6. Docker-тест roundtrip «100–150 КиБ unicode-контент с `%`» + юнит-тест `splitByUtf8Bytes`.

16. **Неверная ссылка на раздел в Javadoc** — `LlmModel.java:16`, `LlmCredentials.java:13`: «data-model §3», фактически §2 (`docs/design/data-model.md:19` — `## 2. llm`). DS-C13. Заменить на §2.

17. **`glob` возвращает каталоги** — `ContainerWorkspaceTools.java:129`: `find` без `-type f`. DS-C12. Если контракт «список файлов» — добавить `-type f`; иначе зафиксировать в спеке.

18. **Стиль теста** — `AesGcmEncryptionTest.java:23-31`: try/catch+`fail` вместо `assertThatThrownBy`. DS-C14. Заменить на AssertJ.

19. **TOCTOU в `WorkspacePathGuard`** — между `resolveHostPath` (host) и exec в контейнере злоумышленник мог бы создать symlink, ведущий наружу. Mercury (low). Для MVP приемлемо — workspace read+write, эскалации нет; зафиксировать в ADR как known limit.

20. **Задача 6.1 «smoke-сборка в CI» фактически не выполнена** — `docker/Dockerfile` собирается только в `DockerTestSupport.helperImage()` через Testcontainers при `mvn verify`; CI-конфигов (`.github/`, `.gitlab-ci.yml`, Jenkinsfile) в корне нет. Задача помечена `[x]`. DS-C8. Либо добавить CI-шаг, либо переформулировать 6.1 (smoke в docker-тестах) и снять формулировку «в CI».

21. **Утечка контейнера при исключении в `createAndStart`** — `WorkspaceContainerManager.java:63-72`. Консенсус GLM C-M4 / Mercury (low). Уже описано в находке 3.

## Проверено и валидно (консенсус 3/3)

- **WorkspacePathGuard** — relative path → hostPath через `realPath` (canonical + symlink), плюс `..`/абсолютные/пустой гварды. **Windows containment проверен GLM исполнением**: `C:\foo` ловится через `isAbsolute()`; drive-relative `C:foo` — через `startsWith`/resolve-вне-root. Хост-ФС недостижима.
- **bash-контракт** — non-zero exit ≠ ошибка (exitCode в отчёте, статус OK; тест `bashNonZeroExitIsNotToolError`); TERM→124→`timedOut` (тест `bashTimeoutIsReported`); stdout+stderr объединены (один `ByteArrayOutputStream` в оба коллбэка `ExecStartResultCallback`); cwd-резолв через гвард; экранирование позиционных аргументов (`"$1"`, `"$2"`, `"$3"`) — инъекций в `sh -c` нет.
- **Docker lifecycle** — ленивое создание + иднопотентность `ensureContainer`; имя `harness-<sessionId>`; bind-mount хоста; network=none; memory/NanoCPUs из конфига (тест инспектирует HostConfig); удаление `withForce(true)`; повторное удаление отсутствующего — no-op.
- **Pull-политика** — `imageAvailableLocally()` первый; backoff-ретраи pull; недоступность registry не ломает существующие сессии с локальным образом (тест `prefersLocalImageWhenRegistryIsUnreachable`).
- **Кэш клиентов** — `computeIfAbsent` потокобезопасен, провалы маппера не кэшируются (гарантия CHM); разные `base_url` → разные клиенты (тест `buildsDistinctClientsForModelsWithDifferentBaseUrls`); инвалидация — рестартом (D-M1-3).
- **AES-GCM** — random IV 12B + tag 128b, IV|ciphertext base64; roundtrip + wrong-key тесты; `key_version` → ключ из конфига; отсутствие ключа → `LlmConfigurationException` (не кэшируется).
- **Явные `.timeout()`/`.maxRetries(0)` (#6915)** — применены и в `OpenAiChatOptions` (`:55-56`), и в `setupSyncClient`/`setupAsyncClient` (`timeout + NO_RETRIES` → `ClientOptions`); подтверждено bytecode-дизассемблером.
- **Async-клиент не лишний** — `OpenAiChatModel.openAiClientAsync` используется в стрим-пути (Spring AI 2.0.1, проверено javap).
- **Helper-образ** — `alpine:3.20` + явные пакеты bash/findutils/grep/coreutils/git (не busybox-апплеты); `WORKDIR=/workspace`; smoke-тест утилит.
- **Тестовая гигиена** — AAA, без моков в Spring-контексте (`LlmGatewayTest` — чистые Mockito-юниты изолированных кирпичиков); docker-тесты — чёрный ящик против реального демона; единый `DockerTestSupport` с однократной сборкой образа.
- **Утечек scope M3/M4 нет** — async-окно bash, CLIENT_EXEC-релей, transition — отсутствуют в коде.
- **ToolStatus.ASYNC_ACCEPTED** — зарезервирован под M3 (D-09), зафиксирован в Javadoc enum'а и `ToolResult` — контракт §5 целиком, чтобы JSON-схема M1 не менялась в M3.
- **Семантика `llm-retries` = «до N попыток»** — `retries = llmRetries - 1`; тест `exhaustingRetriesFailsWithDedicatedException` ожидает 3 POST при llm-retries=3.
- **`timeout 0` не протекает** — `effectiveBashTimeout` приводит null/≤0 к `bash-timeout` + cap; `timeout`-аргумент = `max(1, seconds)`; exec-дедлайн `effective+5s` ≥ аргумента.
- **LOST-трансляция при смерти контейнера** — смерть на любом шаге → `!isContainerRunning` → `WorkspaceContainerException(duringExecution=true)` → `ToolResult.lost`; 2 docker-теста: `containerDeathDuringBashYieldsLost`, `callOnAlreadyDeadContainerYieldsLost`.
- **ERROR при ошибке монтирования** — `workspaceMountFailureYieldsError` (docker-тест).

## Консенсус ревьюеров

| Тема | GLM | DS | Mercury | Консенсус |
|---|---|---|---|---|
| OOM bash/read без лимита ByteArrayOutputStream (C-1/DS-C1) | medium | **major** | — | **2/3 → major** |
| Mid-stream retry дублирует deltas | medium (C-M2) | minor (DS-C2) | — | **2/3 → minor** |
| Полусозданный контейнер кэшируется как LOST | medium (C-M4) | — | low | **2/3 → minor** |
| Exit ≥ 128 → 30 s стоп (C-L7/DS-C6) | low | minor | — | **2/3 → minor** |
| NUL/PatternSyntax → не ToolResult.error | — | minor (DS-C5) | — | **1/3 → minor (контрактное)** |
| Хардкод 60_000 (D-39) | medium (C-M1) | — | — | **1/3 → minor (D-39)** |
| `OpenAIIoException` не transient | medium (C-M3) | — | — | **1/3 → minor** |
| Многочанковая запись не атомарна | low (C-L3) | minor (DS-C4) | — | **2/3 → minor** |
| grep include — ложные срабатывания | — | minor (DS-C7) | — | **1/3 → nit** |
| params_jsonb не покрывает reasoning_effort | low (C-L5) | minor (DS-C3) | — | **2/3 → nit** |
| timeout без `-k` (зомби) | low (C-L1) | nit (DS-C9) | — | **2/3 → nit** |
| workspaceRoot относительный | low (C-L2) | — | — | **1/3 → nit** |
| sleep(50) хардкод в awaitRunning | low (C-L6) | nit (DS-C10) | — | **2/3 → nit** |
| LlmConfigurationException синхронно | — | nit (DS-C15) | — | **1/3 → nit** |
| ContainerWorkspaceToolsDockerTest пробел | low (C-L6) | — | — | **1/3 → nit** |
| Неверная ссылка §3 vs §2 | — | nit (DS-C13) | — | **1/3 → nit** |
| glob возвращает каталоги | — | nit (DS-C12) | — | **1/3 → nit** |
| Стиль теста AesGcmEncryptionTest | — | nit (DS-C14) | — | **1/3 → nit** |
| TOCTOU WorkspacePathGuard | — | — | low | **1/3 → nit (known limit)** |
| Утечка контейнера (find→cached stopped) | medium (C-M4) | — | low | **2/3 → minor (см. выше)** |
| Smoke-сборка в CI (6.1) фактически нет | — | minor (DS-C8) | — | **1/3 → nit** |
| Containment Windows | ✅ | — | — | validated |
| bash контракт (exit≠error, timedOut, stdout+stderr) | ✅ | ✅ | ✅ | **3/3** |
| Docker lifecycle, bind-mount, network, limits | ✅ | ✅ | ✅ | **3/3** |
| Pull-policy | ✅ | ✅ | — | **2/3** |
| Кэш LLM-клиентов, AES-GCM, .timeout/.maxRetries(0) | ✅ | ✅ | ✅ | **3/3** |
| Async-клиент не лишний | ✅ | — | — | **1/3** |
| Helper-образ alpine:3.20 | ✅ | ✅ | ✅ | **3/3** |
| Утечки M3/M4 | ✅ | ✅ | ✅ | **3/3** |
| LOST/ERROR при отказах контейнера | ✅ | ✅ | ✅ | **3/3** |
| Тестовая гигиена | ✅ | ✅ | ✅ | **3/3** |

## Summary

**Находки:** 1 **major** (OOM bash/read — единственный блокер) · 6 **minor** (mid-stream retry, лимит started-контейнера, exit≥128 стоп, NUL/Pattern не в ERROR, хардкод 60_000, OpenAIIoException) · 13 **nit**. Прогон разработчика 104 теста / 0 падений / 1 skipped сохраняется.

**Консенсус 2+/3:**
1. **OOM `ByteArrayOutputStream` без лимита сбора** (GLM medium, DS major) — блокер. `bash("yes")`/`cat big` за 60 s/5 m cap + 5 s собирает гигабайты → падение оркестратора (все сессии). Фикс: bounded `OutputStream` в `exec`, порог ≈ `limits.toolOutput()`.
2. **Mid-stream retry дублирует deltas** (GLM medium, DS minor) — спека «частичный результат не фиксируется» нарушается: ретрай после первого байта = дубль префикса. Фикс: счётчик эмиссии в фильтре ретрая.
3. **Полусозданный контейнер кэшируется stopped → LOST навсегда** (GLM medium, Mercury low) — `findContainerId(showAll=true)` находит exited-контейнер, кэширует id; восстановление только через рестарт-скан 7.6.

**Вердикт: REJECT** — фикс #1 (OOM) обязателен до merge. После него — желательно в той же пачке: #2 (mid-stream retry), #3 (cache stopped), #4 (30 s стоп на exit≥128), #5 (NUL/Pattern → ERROR), #6 (60_000 → конфиг), #7 (OpenAIIoException); остальное — backlog. После устранения #1 — APPROVE.

## Fixes approval

Прогон m4 после правок фикс-цикла (C-J-1…C-J-6). Проверено по `WorkspaceContainerManager.java`, `BoundedOutputStream.java`, `ContainerWorkspaceTools.java`, `WorkspacePathGuard.java`, `LlmInvoker.java`, `ChatModelFactory.java`, `DockerProperties.java`, `LimitsProperties.java`, `application.yml`:

- **C-J-1 (OOM bounded capture, major)** — `BoundedOutputStream.java`: жёсткий лимит буфера + `truncated` флаг + `setOnOverflow(Runnable)`. `WorkspaceContainerManager.exec:135-144`: `captureLimit = limits.toolOutput() + limits.toolCaptureMargin()`, `BoundedOutputStream(captureLimit)` обёрнут вокруг `ExecStartResultCallback`, при overflow `callback.close()` досрочно завершает чтение (процесс в контейнере продолжает писать в stdout, но Docker daemon дропает без читателя). Новый `ContainerExecResult` несёт `truncated` через `ToolResult.truncated`. ✓
- **C-J-2 (mid-stream retry)** — `LlmInvoker.stream:43-60`: `Flux.defer` + `AtomicBoolean delivered` + `doOnNext(r -> delivered.set(true))` + `retryWhen(filter(isTransient && !delivered.get))`. После первого эмита обрыв уходит как неисправимая ошибка. Бонус: `chatModel` внутри `Flux.defer` → `LlmConfigurationException` как сигнал Flux, не синхронный выброс. ✓
- **C-J-3 (cache stopped → LOST)** — `WorkspaceContainerManager.ensureContainer:66-87` (`synchronized`): если cached не работает → удалить; если `findContainerId` нашёл stopped → `removeContainerId` + пересоздать. `createAndStart:221-225`: в catch при `containerId != null` → `removeContainerId(containerId)` (полусозданный не остаётся в Docker). ✓
- **C-J-4 (params_jsonb)** — `ChatModelFactory.KNOWN_PARAMS` (camelCase + snake_case алиасы), проброс `reasoningEffort`, `verbosity`, `seed`, `stop[]`, `frequencyPenalty`, `presencePenalty`, кастомные `number/integer/string`-хелперы; неизвестные ключи — WARN со списком (`:120-153`). ✓
- **C-J-5 (60_000 → конфиг, D-39)** — `DockerProperties.writeChunkBytes` (int) + `application.yml:32` (`write-chunk-bytes: 60000`) + `splitByUtf8Bytes:264` использует `dockerProperties.writeChunkBytes()`. Бонус: атомарность `writeContainerFile` через `tmp` + `mv -f` + `discardTemp` при ошибке (C-4 / DS-C4). ✓
- **C-J-6 #4 (exit≥128 → 30 s стоп)** — `containerStoppedWithin:290-302`: ранний возврат `false` если контейнер жив на входе (`:291-293`), окно подтверждения сокращено до `properties.containerStopConfirm()` (3 s по умолчанию). ✓
- **C-J-6 #5 (NUL/Pattern → ERROR)** — `WorkspacePathGuard:27-33` ловит `InvalidPathException` → `WorkspacePathException`; `ContainerWorkspaceTools.glob:142-143` и `grep:173-174` ловят `PatternSyntaxException` → `ToolResult.error`. ✓
- **C-J-6 #7 (`OpenAIIoException` transient)** — `LlmInvoker.isTransient:65` добавлен `cause instanceof OpenAIIoException` (`OpenAIIoException extends OpenAIException` в openai-java-okhttp). ✓

Дополнительно: `splitByUtf8Bytes` через конфиг (выше), `writeContainerFile` атомарна через `tmp`+`mv`+`discardTemp` (закрывает DS-C4), `grep` `include` теперь только glob-матч по basename — substring-fallback убран (закрывает DS-C7). Прогон разработчика `mvn clean verify`: 120/0/0/1 skip (+16 тестов с m3).

**Вердикт: APPROVE.** Пачка C готова к вливанию.
