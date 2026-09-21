# Ревью M4 batch U (workspace download), commit 508326c

> Ревьюер: DeepSeek-V4.1-Flash (субагент).
> Дата: 2026-09-22.
> Объект: `api/{WorkspaceFilesController,WorkspacePathGuard,ExtensionNotAllowedException,WorkspaceFileNotFoundException,WorkspacePathInvalidException,ApiExceptionHandler,ProblemCodes}.java`, `src/test/{api/WorkspacePathGuardTest,api/WorkspaceFilesApiTest}`, `application-test.yml`, openapi `workspace/files`.
> Контекст: спека `openspec/changes/m4-clients-relay/specs/workspace-download/spec.md`, D-72, api-contracts §8, `WorkspaceContainerManager.workspaceDir` (форма корня), M1-канон (`PayloadTooLargeException`, `ProblemCode` enum).
> Сборки не запускались; dev verify — 537 тестов (2 symlink-теста скипнуты на Windows).
> Severity: **HIGH** — обход гварда/ломает контракт; **MEDIUM** — баг/пробел; **MINOR/NIT** — косметика/hardening.

## Сводка

| Severity | Кол-во |
|---|---|
| HIGH | 0 |
| MEDIUM | 0 |
| MINOR/NIT | 9 |
| **Итого** | **9** |

По пунктам промпта: (1) canonical-гвард — обхода по `..`/absolute/empty/normalization/trailing не найдено; 1 hardening-замечание U-1; (2) TOCTOU — реализуемость низкая, но threat-model назван неверно (U-3); (3) pre-stat 413 — ✅ до заголовков; (4) safe-list case-insensitive — ✅; (5) streaming — ✅ без полной загрузки; (6) коды/статусы — ✅, кроме устаревшего `not-implemented` (U-5); (7) AGENTS — ✅.

---

## MINOR / NIT

### U-1 (MINOR, hardening). Корень containment не пришпилен к `workspaceRoot`: симлинкованный каталог сессии становится якорем
- **Пункт:** `WorkspacePathGuard.java:47–51,74,118–128`.
- **Проблема:** `rootReal = canonical(root)` = `toRealPath()` **разрешает** симлинк каталога сессии; containment затем проверяется относительно `rootReal`, а не относительно `canonical(workspaceRoot)`. Если `workspaces/sessions/{sessionId}` — симлинк наружу (например, подменён на хосте), то целевой каталог становится «корнем», и файлы из него проходят проверку. Все `path=…` сегменты при этом симлинками быть не должны — обход только через сам корень.
- **Достижимость:** по HTTP недостижимо (корень формируется сервером из конфига + случайного UUID и создаётся `WorkspaceContainerManager.createAndStart`); требует локального/хост-доступа либо подмены каталога. Из контейнера агента mount-point не заменяется. Тем не менее это ослабление границы, которую охраняет сам гвард.
- **Предложение:** после `rootReal` проверить `rootReal.startsWith(canonical(Paths.get(workspaceRoot)))` (или `Files.isSymbolicLink(root) → 422`). Дешёвая страховка без «слоёв в глубину».

### U-2 (MINOR). Непокрытый `InvalidPathException` → 500 вместо 422
- **Пункт:** `WorkspacePathGuard.java:66` (`current.resolve(segment)`), `:118` (`Paths.get`).
- **Проблема:** недопустимый символ пути (NUL на Linux; `<>:"|?*` на Windows) даёт `InvalidPathException` (RuntimeException) — не ловится, уходит в `ApiExceptionHandler.unexpected` → **500**, а не 422 `path-invalid`. Для Linux-рантайма кейс узкий (`%00` может резаться коннектором), но дешёво закрыть.
- **Предложение:** `try { ... } catch (InvalidPathException e) { throw new WorkspacePathInvalidException(...) }`.

### U-3 (MINOR). Threat-model TOCTOU называет неверного актора; митигация неполна для промежуточных сегментов
- **Пункт:** `WorkspacePathGuard.java:22–23`, D-72:59–61 (`design.md`), workspace-download spec:35.
- **Проблема:** обоснование «потребитель — аутентифицированный SSO-пользователь» неточно: **писатель** workspace — агент (произвольный `bash` в контейнере, каталог примонтирован), поэтому symlink-раст ставки возможен со стороны агента (prompt-injection). `NOFOLLOW` на финальном компоненте + повторный `canonical` защищают от эскейпа при спокойной ФС, но гонка «проверка промежуточного каталога → `open`» остаётся: `Files.newInputStream` следует за промежуточными симлинками. Риск мал (нужно выиграть гонку с HTTP-запросом), но формулировка риска должна называть агента, а не только SSO-пользователя.
- **Предложение:** либо уточнить обоснование (принято осознанно, актор — агент+SSO), либо закрыть через `SecureDirectoryStream`/`openat`-обход с `O_NOFOLLOW` посегментно (если считать оправданным).

### U-4 (MINOR). Спека/OpenAPI обещают `chunked`, реализация ставит `Content-Length`
- **Пункт:** `WorkspaceFilesController.java:47–50` (`contentLength(size)`), workspace-download spec:68 («потоком (chunked)»), openapi:404 («Отдача — потоком (chunked)»).
- **Проблема:** с `Content-Length` отдача не chunked. Фактически это **лучше** (клиент детектит усечение), но контракт-текст разъезжается с поведением. Плюс между pre-stat и стримом файл может вырасти → `Content-Length` не совпадёт с фактом (агент может дописывать).
- **Предложение:** привести формулировку к «потоковая отдача с `Content-Length`», либо стримить с жёстким байтовым капом и без `Content-Length`. Осознанно принять конкурентный рост файла (или ловить несоответствие).

### U-5 (MINOR). Устаревший `not-implemented` в замороженном OpenAPI-каталоге
- **Пункт:** `openapi.yaml:1548,1558–1559`; `ProblemCodes.java` (константа удалена); `ApiNotImplementedException` удалён.
- **Проблема:** enum `ProblemCode` всё ещё содержит `not-implemented` с описанием «пачка U заменит реализацией; не ошибка контракта». Пачка U выполнена → код мёртв и описание ложно; после архива M4 каталог будет рекламировать несуществующий 501.
- **Предложение:** убрать `not-implemented` из enum/описании (спека-правка) либо обновить description.

### U-6 (NIT). `PayloadTooLargeException` переиспользован под размер *ответа*; javadoc про тело запроса
- **Пункт:** `PayloadTooLargeException.java` (javadoc: «тело запроса превышает `harness.limits.body`»), `WorkspacePathGuard.ensureWithinLimit` (`:110`).
- **Проблема:** семантически это пре-статический лимит файла-ответа, а не request-body (этот класс бросает `PayloadSizeFilter`). Код `payload-too-large` совпадает с нужным, но javadoc вводит в заблуждение.
- **Предложение:** расширить javadoc (request-body **и** download pre-stat) либо завести отдельное исключение с тем же кодом.

### U-7 (NIT). Мелкая чистота гварда
- Недостижимый финальный `throw new WorkspacePathInvalidException("Путь не задан")` после цикла (`:88`) — можно убрать/replace на `IllegalStateException`.
- `sessionRoot` использует `normalize()` без `toAbsolutePath()` (`:119`), тогда как `WorkspaceContainerManager` создаёт каталог через `toAbsolutePath().normalize()`; `toRealPath()` позже всё равно абсолютизирует — для консистентности добавить `toAbsolutePath()`.
- `replace('\\','/')` (`:53`) на Linux меняет семантику имён с `\` (мисселект, не дыра) — ок для target-Linux, но стоит зафиксировать как осознанное.

### U-8 (NIT, вне батча). В `openapi.yaml:1494` `PARKED_CLIENT` описан как «присвоение с M4»
- **Проблема:** M4 (fix round 2) вывел `PARKED_CLIENT` из объёма (proposal Non-goals). Замороженный контракт обещает присвоение, которого не будет — дрейф, зафиксированный в re-approval propose (R-4). Относится к закрытию M4, не к batch U.
- **Предложение:** обновить формулировку/openapi перед архивом.

### U-9 (NIT). Тесты: symlink-кейсы скипаются на Windows
- `WorkspacePathGuardTest.rejectsSymlink*` через `assumeTrue` — на dev-Windows не выполняются; на Linux CI должны идти. Убедиться, что CI/приёмка гоняет Linux-профиль (иначе ключевая NOFOLLOW-проверка окажется непокрытой).

---

## Подтверждено (не находки)

- **Pre-stat 413 до заголовков:** размер считается `Files.size` в `ensureWithinLimit` **до** формирования `ResponseEntity`; исключение → `@ExceptionHandler` пишет 413 без тела файла. Тест `rejectsOversizeFile`/`preStatRejectsOversizeFile` зелёные.
- **Streaming:** `Files.newInputStream` + `InputStreamResource` + `contentLength` — тело не грузится в память целиком.
- **Safe-list:** `checkExtension` сравнивает `toLowerCase(Locale.ROOT)` с обеих сторон; extensionless и multi-dot отклоняются (как в спеке); тест `extensionCheckIsCaseInsensitive`.
- **Canonical-гвард:** `..`/`.`/пустой сегмент/absolute (`/`)/symlink (включая ведущий внутрь)/каталог/не-обычный файл — отклонены; `a//b`, `a/` → 422; `../../etc/passwd` → 422 (тест API). Композиций обхода (double-encode `%252e`, unicode-гомоглифы, trailing separators, `C:`/ADS) не найдено — на Linux всё сводится к 404/422.
- **Коды/статусы:** 200/404(session-not-found|file-not-found)/413(payload-too-large)/422(path-invalid|extension-not-allowed)/401/406 — совпадают с `openapi.yaml` и api-contracts §8; сигнатура сгенерированного `WorkspaceApi.downloadWorkspaceFile(String path, UUID id)` совпадает с контроллером.
- **AGENTS:** числа (`max-bytes`, `allow-extensions`) — конфиг (`WorkspaceDownloadProperties` + `application.yml`, `application-test.yml` override); импорты корректны; Lombok `@RequiredArgsConstructor`/`@Slf4j`/`@SneakyThrows` в стиле проекта; Jackson-3 не затронут.

## Вердикт

**APPROVE — 0 блокеров (0 HIGH/MEDIUM; 9 MINOR/NIT).**

Обхода canonical-гварда по заявленным векторам не найдено; pre-stat 413, streaming, safe-list, коды/статусы и контракт OpenAPI выполнены; покрытие тестами (unit 12 + API 6) соответствует заявленным сценариям. Рекомендации не блокируют: **U-1** (пришпилить `rootReal` к `workspaceRoot` — дешёвый hardening границы), **U-3** (уточнить актора TOCTOU: агент-писатель, а не только SSO-пользователь), **U-5** (убрать мёртвый `not-implemented` из каталога), **U-4/U-2** (текст `chunked`→`Content-Length`; `InvalidPathException`→422), **U-8** (`PARKED_CLIENT «с M4»` — к закрытию M4).
