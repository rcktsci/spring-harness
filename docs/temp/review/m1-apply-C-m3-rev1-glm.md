# Ревью пачки C (m1-session-core): llm-gateway (5.1–5.3) + workspace-tools (6.1–6.4)

Ревьюер: GLM-5.3-Flash (rev 1) · Дата: 2026-09-17
Объект: незакоммиченный diff (`git status`: execution/, intelligence/, common/AesGcmEncryption, config/DockerConfig+LlmConfig+DockerProperties+LlmProperties+LimitsProperties, docker/, application.yml, тесты execution/ + intelligence/).
Эталоны: specs/llm-gateway, specs/workspace-tools, agent-tools §1/§5, data-model §2, decisions D-39/D-41/D-43, tasks 5.1–5.3 / 6.1–6.4.
Ограничения соблюдены: mvn/Docker не запускались; сборки/коммиты — нет. Верификация гипотез — чтением кода, bytecode-дизассемблированием зависимостей (javap) и standalone-пробой `Paths.get` на Windows.

---

## Findings

### MEDIUM

**C-M1. `splitByUtf8Bytes`: хардкод `60_000` — нарушение D-39**
- Файл: `src/main/java/se/rocketscien/harness/execution/ContainerWorkspaceTools.java:234` (`int limit = 60_000;`)
- Дефект: D-39 — «все числовые параметры — конфиг, хардкод чисел запрещён». 60000 — лимит аргумента `printf` (MAX_ARG_STRLEN Linux, 32 страницы = 131072 байта, здесь запас ×2). Операционный параметр: при смене базового образа/ядра/обёртки exec лимит может потребовать правки — сейчас только пересборка.
- Предложение: `harness.docker.write-chunk-bytes` (DataSize/int) в `DockerProperties` + application.yml (дефолт 60000); Javadoc-ссылку на MAX_ARG_STRLEN сохранить.

**C-M2. Ретрай стрима после частичной выдачи → дублирование контента**
- Файл: `src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:36-51`
- Дефект: `Retry.backoff` навешан на весь `Flux.defer(() -> model.stream(prompt))`. При сбое СРЕДИ стрима (обрыв соединения / SSE-ошибка после того, как часть deltas уже доставлена подписчику) `retryWhen` пересоздаёт подписку — модель генерирует ответ заново, потребителю (и далее в журнал сессии, 7.x) уйдёт склейка «частичный текст + полный повтор». WireMock-тесты покрывают только ошибки ДО начала SSE-тела (429/503 в статусе ответа).
- Дополнительно: SseException в openai-java наследует OpenAIServiceException — если его statusCode окажется ≥500, фильтр пропустит такой сбой в ретрай именно в mid-stream сценарии (самом опасном).
- Предложение: ретраить только до первого элемента — например, счётчик эмиссии (`AtomicBoolean`/`Sinks`-обёртка) в фильтре ретрая, либо не ретраить стрим вовсе, а ретраить только установление соединения. Минимум — тест «ошибка после N deltas → поведение задокументировано».

**C-M3. Изоляция сетевых ошибок от ретраев: `OpenAIIoException` не считается transient**
- Файл: `src/main/java/se/rocketscien/harness/intelligence/LlmInvoker.java:53-67`
- Дефект (проверено по исходникам openai-java-client-okhttp 4.34.0, `OkHttpClient.kt:45-46,65-66`): любые IO-сбои (refused/reset/DNS/таймаут сокета) SDK бросает как `OpenAIIoException extends OpenAIException`, а НЕ как `OpenAIRetryableException`/`OpenAIServiceException`. `isTransient` их не матчит → единичный сетевой фликер = немедленный FAILED Turn'а. Формально спека требует ретрай только «429/5xx» (соответствие не нарушено), но Purpose спеки — «ретраи на временные ошибки», а сетевой сбой — каноничная временная ошибка. `OpenAIRetryableException` в фильтре почти мёртвый код.
- Предложение: добавить `cause instanceof OpenAIIoException → true` (до начала стрима это безопасно); критерий mid-stream см. C-M2.

**C-M4. Утёкший полусозданный контейнер навсегда кэшируется как LOST**
- Файл: `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:161-198, 62-72, 95-100`
- Дефект: если `createContainerCmd` успешен, а `startContainerCmd`/`awaitRunning` падают (битый образ, гонка демона), контейнер остаётся в Docker (created/exited) и НЕ удаляется в catch. Следующий `ensureContainer` найдёт его через `findContainerId` (showAll=true видит остановленные), закэширует id — и все вызовы получат LOST (`isContainerRunning=false` → `WorkspaceContainerException(duringExecution=true)`), причём восстановиться перезапуском вызова невозможно до рестарт-скана (7.6).
- Предложение: в catch `createAndStart` — best-effort `removeContainerCmd(force)` созданного id; либо в `exec` при «exists but not running» делать remove+пересоздание вместо мгновенного LOST.

### LOW

**C-L1. Отсутствие `--kill-after` у `timeout` → процессы-зомби в контейнере**
- Файл: `ContainerWorkspaceTools.java:184`
- Дефект: `timeout -s TERM` убивает только прямого потомка `sh`; внук, игнорирующий TERM и держащий stdout, оставит exec-стрим открытым: `awaitCompletion` повиснет до `effective+5s`, затем вернёт `exitCode=-1, timedOut=true`, а процесс продолжит жить в контейнере (память/CPU) до его смерти.
- Предложение: `timeout -s TERM -k 5` (5 с — окно между TERM и KILL; при выносе в конфиг — см. C-M1, чтобы не породить новый хардкод).

**C-L2. `workspaceRoot` по умолчанию относительный → bind-mount зависит от CWD процесса**
- Файл: `application.yml:22` (`workspaces/sessions`), `WorkspaceContainerManager.java:57-59,162`
- Дефект: `workspaceDir()` возвращает относительный путь; `toAbsolutePath()` в `createAndStart` резолвится от CWD JVM. Смена рабочего каталога запуска (systemd WorkingDirectory, ручной старт) молча меняет место workspace-каталогов и делает существующие контейнеры/тома несогласованными. Windows-дев-проба (Docker Desktop) с относительным путём вдобавок может не попасть в shared drive.
- Предложение: задокументировать требование абсолютного пути в yml-комментарии, или резолвить дефолт от явной базовой директории; отдельная строка в operations.md.

**C-L3. Незавершённая многочанковая запись оставляет частичный файл**
- Файл: `ContainerWorkspaceTools.java:216-231`
- Дефект: при сбое chunk k>0 файл уже содержит первые k чанков без маркера недописанности; инструмент вернёт ERROR, но содержимое останется повреждённым. Для `edit_file` (read-modify-write) это теряет исходник.
- Предложение (nit-уровень): писать в `"$1.tmp"` + `mv` в конце (в рамках одного контейнера атомарно), либо принять и задокументировать — файлы текстовые, источник контента восстановим повторным вызовом.

**C-L4. `InvalidPathException` не перехвачен в `resolve` → немаршрутизируемое исключение вместо TOOL_RESULT**
- Файл: `ContainerWorkspaceTools.java:256-260`, `WorkspacePathGuard.java:25`
- Дефект: путь с NUL/невалидным для ОС символом → `Paths.get` бросит `InvalidPathException` (RuntimeException), который не ловится в `ToolResult`-маппинге инструментов (ловятся только WorkspacePathException/WorkspaceContainerException) → уйдёт в общий обработчик Turn'а вместо честного ERROR-инструмента.
- Предложение: обернуть `Paths.get` в `WorkspacePathException` (гвард) или добавить catch в `resolve`.

**C-L5. `params_jsonb`: `reasoning_effort` и прочие ключи молча игнорируются**
- Файл: `ChatModelFactory.java:52-70`; эталон `docs/design/data-model.md:37` (`например {"reasoning_effort": "high"}`)
- Дефект: маппятся только temperature/maxTokens/topP; пример из data-model не покрыт, неизвестные ключи не диагностируются (опечатка `max_tokens` молча не применится).
- Предложение: как минимум логировать незнакомые ключи при сборке клиента; pass-through произвольных параметров — решить отдельно (минимум осознанное решение в decisions.md).

**C-L6. Тест-пробел: целостность многочанковой записи и спецсимволы не покрыты**
- Файл: `ContainerWorkspaceToolsDockerTest.java` (весь файл)
- Дефект: `write_file` проверяется только короткими ASCII-строками. Самая рискованная самописная логика пачки — `splitByUtf8Bytes` + `printf %s` — не имеет тестов: (а) файл >60КиБ (несколько exec-чанков, `>`→`>>` склейка), (б) content с `%`, `\n`, backslash, (в) unicode на границе чанка (суррогатные пары), (г) пустой content (ветка `chunks.isEmpty()`).
- Предложение: docker-тест roundtrip «100–150 КиБ unicode-контент с `%`» + юнит-тест на границы чанков `splitByUtf8Bytes`.

**C-L7. Задержка до `startTimeout` (30 c) на любой команде с exit ≥ 128**
- Файл: `WorkspaceContainerManager.java:147-152, 252-261`
- Дефект: `bash "exit 137"` (не убивший контейнер) повлечёт поллинг `containerStoppedWithin` все 30 с (`startTimeout`) перед возвратом штатного результата. Latency-квирк, не ошибка корректности (окно нужно для различения «kill контейнера» от «код 128+»); агентские сессии такое заметят.
- Предложение: короткое окно подтверждения (например, 3×`state-poll-interval` … отдельный конфиг `container-stop-confirm`), а не полный `startTimeout`.

### INFO / NIT

**C-I1. `AesGcmCredentialDecryptor(null)` → NPE в конструкторе** (`AesGcmCredentialDecryptor.java:16`): `Map.copyOf(null)` бросит NPE. Bean-путь безопасен — `LlmProperties` каноническим конструктором нормализует null → `Map.of()` (проверено, `LlmProperties.java:16-18`). Прямая инстанциация (тесты) с null упадёт — допустимо; замечание для памяти.

**C-I2. `computeIfAbsent` и исключения** (`LlmGatewayImpl.java:36-44`): провал маппера НЕ кэшируется (гарантия CHM) — dangling credentials повторяются на каждом Turn'е, как и требует спека. Валидно.

**C-I3. Async-клиент не лишний** (`ChatModelFactory.java:39-46`): bytecode `OpenAiChatModel` (spring-ai-openai 2.0.1) показывает использование `openAiClientAsync.chat()` в стрим-пути — sync для `call()`, async для `stream()`. Оба нужны.

**C-I4. Явные `.timeout()`/`.maxRetries(0)` (#6915) применены в обоих местах**: `ChatModelFactory.options` (строки 55-56) и в `setupSyncClient`/`setupAsyncClient` (timeout+NO_RETRIES → `ClientOptions.timeout/maxRetries`, подтверждено javap: `ClientOptions$Builder.timeout` + `maxRetries` вызываются в обоих setup-методах). Требование 5.1 выполнено. `NO_RETRIES=0` — семантическая константа, не D-39-нарушение.

**C-I5. Семантика `llm-retries` = «до N попыток»** (`LlmInvoker.java:40-50`): `retries = llmRetries - 1` → всего N попыток; тест `exhaustingRetriesFailsWithDedicatedException` ожидает 3 POST при llm-retries=3 — согласовано с Javadoc `TurnProperties` («до llmRetries попыток»). Валидно.

**C-I6. `timeout 0` не протекает**: `effectiveBashTimeout` приводит `null/≤0` к `bash-timeout`, затем cap; `timeout`-аргумент = `max(1, seconds)`; exec-дедлайн `effective+5s` всегда ≥ аргумента `timeout`. «Утечки долгого bash через timeout 0» нет; `plusSeconds(5)` — осознанный запас на сигнал TERM (остаточный риск зомби — C-L1).

**C-I7. Containment на Windows — проверено исполнением** (`WorkspacePathGuard.java:22-33`): `path.startsWith("/")` НЕ ловит `C:\foo` (и `\\srv\share`), но `Paths.get(...).isAbsolute()` ловит все: `C:\foo`→abs, `\\srv\share\f`→abs, `a:/b`→abs. Единственный проскочивший мимо absolute-чеков случай — drive-relative `C:foo` (`isAbsolute()==false`), но тогда `root.resolve(...)` даёт `C:foo`, `startsWith(root)==false` → «escapes workspace». Все пробы отклонены; хост-ФС недостижима. Symlink-гвард через `toRealPath` ближайшего существующего предка корректен (root всегда существует — создаётся в `createAndStart`).

**C-I8. LOST-трансляция при смерти контейнера — корректна** (`WorkspaceContainerManager.java:111-152`): смерть после `execCreate` до `execStart` → исключение старта → `!isContainerRunning` → LOST(duringExecution=true); смерть в `awaitCompletion` → тот же путь; смерть после завершения exec, но до inspect → LOST (консервативно: результат мог не сохраниться — приемлемо). Проверка `exitCode>=128 && containerStoppedWithin` — разумная защита от задержки состояния демона (latency-цена — C-L7).

**C-I9. `ensureImage` после исчерпания pull-попыток продолжает создание** (`WorkspaceContainerManager.java:200-224`): да, продолжает — и корректно: контейнер упадёт с NotFoundException → `ERROR` с причиной. При локальном образе pull не выполняется вовсе (`imageAvailableLocally` сначала) → «registry недоступен, образ локально» работает (тест `prefersLocalImageWhenRegistryIsUnreachable`). `imageAvailableLocally` на прочих исключениях возвращает false → путь pull → ERROR; не падает. Валидно по спеке.

**C-I10. `printf %s`-чанки: формат-инъекции нет** (`ContainerWorkspaceTools.java:224`): контент передаётся позиционным аргументом (`$2`), формат фиксированный `%s` — `%`/`\n`/backslash в контенте безопасны; чанкер режет по code point (без разрыва UTF-8). Ограничения: NUL-байт в argv sh невозможен (тихое повреждение при `\u0000` в контенте — текстовая модель инструмента это исключает); MAX_ARG_STRLEN=131072 > 60000 — запас есть. Всё остальное — см. C-L3/C-L6.

**C-I11. Отмена стрима**: `Retry.backoff` отменяется dispose'ом (штатная семантика Reactor: scheduled-задачи ретрая диспозятся вместе с подпиской, лишних попыток нет); тест `cancellationStopsTheStream` это фиксирует. Валидно.

**C-I12. Утечек scope M3/M4 нет**: async-окно bash (30 c → ASYNC_ACCEPTED → late=true), CLIENT_EXEC-релей, transition — отсутствуют в коде. Bash в M1 синхронный с `timedOut` — соответствует specs/workspace-tools (async-capable — только пометка в agent-tools §1 на M3).

**C-I13. `ToolStatus.ASYNC_ACCEPTED` не используется** — зарезервирован под M3 (D-09), задокументирован в Javadoc enum'а и `ToolResult`. Не лишнее: фиксация контракта §5 целиком, чтобы M1-схема JSON не менялась. Приемлемо.

**C-I14. Токены из usage** (`LlmInvokerWireMockTest.recordsCompletionTokensFromUsage:149-160`): gateway-уровень готов — `streamUsage(true)` в options, usage доезжает до последнего ChatResponse (тест с финальным usage-чанком зелёный на прогоне разработчика). Запись tokens в событие ASSISTANT журнала — зона TurnManager (7.2): в пачке C её нет и быть не могло. Отметить в 7.2, чтобы scenario «провайдер вернул usage» был закрыт там; NPE-риск `getUsage()==null` — тоже (там контекст «если провайдер передал usage»).

**C-I15. D-41/D-43 соблюдены**: никакой AccessPolicy/матриц/ролей в пачке C нет; DataSource не тронут. `DockerConfig` — клиент без соединения при старте (daemon down не роняет контекст) — соответствует D-M1-7.

---

## Проверено и валидно (без замечаний)

1. **WorkspacePathGuard** — относительность, `..`, absolute (включая Windows-специфику, C-I7), canonical-path через ближайшего существующего предка (8.3-алиасы + symlink), container-path маппинг; юнит-тесты с assume-fallback для symlink-запретных окружений.
2. **bash-контракт** — non-zero exit ≠ ошибка (exitCode в отчёте, статус OK — тест `bashNonZeroExitIsNotToolError`); TERM→124→`timedOut` (тест `bashTimeoutIsReported`); stdout+stderr объединены (один `ByteArrayOutputStream` в оба коллбэка — осознанно, для `ExecStartResultCallback` это корректный способ получить оба потока; grep-фильтр по префиксу `/workspace/` отсеивает мусор stderr); cwd-резолв через гвард; экранирование позиционных аргументов (`"$1"`, `"$2"`, `"$3"`) — инъекции в `sh -c` скрипт нет.
3. **Docker lifecycle** — ленивое создание + идемпотентность `ensureContainer` (тот же id); имя `harness-<sessionId>`; bind-mount абсолютного хост-каталога (сам путь абсолютен — C-L2 лишь про происхождение дефолта); network=none; memory/NanoCPUs из конфига (тест инспектирует HostConfig); удаление в `removeContainer` с force; повторное удаление отсутствующего — no-op.
4. **Pull-политика** — локальный приоритет, backoff-ретраи pull, недоступность registry не ломает сессии с локальным образом (C-I9).
5. **Кэш клиентов** — по `llm_model.id`, без кэширования провалов (C-I2), разные base_url → разные клиенты (тест), рестарт как стратегия инвалидации (D-M1-3, документировано).
6. **AES-GCM** — random IV 12B + tag 128b, IV|ciphertext base64, AAD не используется (ок для строк); roundtrip + wrong-key тесты; key_version → ключ из конфига; отсутствие ключа → `LlmConfigurationException` (не кэшируется).
7. **Конфиг** — все числовые параметры пачки (таймауты, лимиты, pull, memory/cpu) в `@ConfigurationProperties` + application.yml; привязка дефолтов покрыта `ConfigPropertiesBindingTest`.
8. **Спека llm-gateway** — стриминговая доставка (тест инкрементальности), отмена (тест), 429→успех со вторым POST (сценарный тест), исчерпание → `LlmRetriesExhaustedException` (журнальная часть — 7.2, C-I14).
9. **Спека workspace-tools** — все 6 инструментов реализованы по контрактам agent-tools §1 (created|overwritten, not-found/ambiguous — с regex-safe replace, glob/grep, bash); единый отчёт `{callId, tool, status, output?, exitCode?, truncated?, timedOut?}`; усечение с маркером (тест, «первые N байтов» — ровно как в спеке); LOST при смерти (2 docker-теста: mid-bash и already-dead), ERROR при ошибке монтирования (docker-тест).
10. **Helper-образ** — alpine 3.20 + явные пакеты (не busybox-апплеты), smoke-тест утилит (bash/find/grep/git + /workspace); Open Question design.md закрыт комментарием в Dockerfile.
11. **Тестовая гигиена** — AAA, без моков в Spring-контексте (LlmGatewayTest — чистые Mockito-юниты изолированных кирпичиков, docker-тесты — чёрный ящик против реального демона), единый DockerTestSupport с однократной сборкой образа.

---

## Summary

| Severity | Кол-во |
|---|---|
| Critical | 0 |
| Major (medium) | 4 (C-M1…C-M4) |
| Minor (low) | 7 (C-L1…C-L7) |
| Info/nit | 15 (C-I1…C-I15) |

Топ-3 находки:
1. **C-M2** — ретрай всего стрима при mid-stream сбое дублирует частично выданный контент (журнал сессии получит склейку); ретраи должны заканчиваться с первым выданным элементом.
2. **C-M4** — полусозданный контейнер (create ok, start fail) не удаляется и навсегда кэшируется как stopped → все инструменты LOST без восстановления до рестарт-скана.
3. **C-M1** — `60_000` хардкод лимита чанка: прямое нарушение D-39 (все числовые параметры — конфиг).

**Вердикт: RETURN (нужны фиксы по C-M1…C-M4, далее re-approve).** Блокирующих требований спеки не нарушено (спецификационные сценарии закрыты, 104/0 на прогоне разработчика правдоподобен), но C-M1 — формальное нарушение правила владельца, C-M2/C-M4 — реальные деградации в эксплуатации, чинятся локально и дёшево. C-M3 рекомендуется решить вместе с C-M2 (одна точка — фильтр ретрая).
