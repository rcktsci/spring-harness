# M4 Batch U Review — Mercury-2.5

**Дата:** 2026-09-22  
**Ревьюер:** Mercury-2.5  
**Коммит:** `508326c` (workspace download: controller + canonical path guard)

---

## Резюме

Batch U реализует эндпоинт `GET /sessions/{id}/workspace/files` с точечным canonical-path гвардом согласно D-72. Проверки: существование сессии → symlink-check по сегментам → canonical resolve → containment → extension safe-list → pre-stat 413 → streaming. 537 тестов зелёных (18 новых). AGENTS.md соблюдён.

---

## 1. Чистота реализации

### WorkspaceFilesController (`WorkspaceFilesController.java:29–50`)

```java
public ResponseEntity<Resource> downloadWorkspaceFile(String path, UUID id) {
    sessionStore.findSession(id).orElseThrow(() -> 
            new SessionNotFoundException("Сессия %s не найдена".formatted(id)));
    Path file = guard.resolve(id, path);        // canonical-path check
    guard.checkExtension(file);           // safe-list
    long size = guard.ensureWithinLimit(file); // pre-stat 413
    InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(size)
            .body(new InputStreamResource(stream));
}
```

**Поток:**
1. Сессия → 404 session-not-found
2. Canonical-path guard → 422 path-invalid / 404 file-not-found
3. Extension safe-list → 422 extension-not-allowed
4. Pre-stat → 413 payload-too-large
5. Streaming (no in-memory loading)

### WorkspacePathGuard (`WorkspacePathGuard.java:44–129`)

| Метод | Проверка |
|-------|----------|
| `resolve()` | absolute path, `.`/`..`/empty segments, symlink per segment, canonical resolve, containment in root, directory reject |
| `checkExtension()` | case-insensitive safe-list |
| `ensureWithinLimit()` | pre-stat before streaming |

**TOCTOU:** между проверкой и открытием — принят (потребитель — аутентифицированный SSO-пользователь, D-72).

---

## 2. D-72 соответствие

| Требование D-72 | Реализация |
|-----------------|------------|
| Точечный гвард только в `/workspace/files` | ✓ `WorkspaceFilesController` |
| Посегментный symlink-чек | ✓ `Files.isSymbolicLink(next)` в цикле |
| Canonical resolve каждого сегмента | ✓ `path.toRealPath()` |
| Containment в `workspaceRoot/{sessionId}` | ✓ `nextReal.startsWith(rootReal)` |
| NOFOLLOW_LINKS на финальный компонент | ✓ `Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)` |
| Pre-stat 413 до заголовков | ✓ `Files.size(file)` перед streaming |
| Safe-list расширений (case-insensitive) | ✓ `.toLowerCase(Locale.ROOT)` |

---

## 3. Тестирование

### Unit-тесты (`WorkspacePathGuardTest.java:48–145`)

| Тест | Покрытие |
|------|----------|
| `resolvesExistingFileInsideWorkspace()` | базовое разрешение |
| `resolvesNestedFile()` | вложенные пути |
| `rejectsParentEscape()` | `..` эскейп |
| `rejectsAbsolutePath()` | абсолютные пути |
| `rejectsEmptySegment()` | `//` и конечный `/` |
| `rejectsDirectory()` | каталоги |
| `rejectsMissingFile()` | 404 file-not-found |
| `rejectsMissingSessionWorkspace()` | 404 когда workspace |
| `rejectsSymlinkEscapingWorkspace()` | symlink наружу |
| `rejectsSymlinkInsideWorkspace()` | symlink внутри |
| `extensionCheckIsCaseInsensitive()` | case-insensitive |
| `preStatRejectsOversizeFile()` | 413 |

**Symlink-тесты на Windows:** пропущены через `assumeTrue(symlinksSupported())` — рантайм Linux.

### Интеграционные (`WorkspaceFilesApiTest.java:38–103`)

| Тест | HTTP | Код |
|------|------|-----|
| `downloadsExistingTextFile()` | 200 | correct body + Content-Type + Content-Length |
| `returnsFileNotFoundForMissingFile()` | 404 | file-not-found |
| `returnsSessionNotFoundForUnknownSession()` | 404 | session-not-found |
| `rejectsPathEscape()` | 422 | path-invalid |
| `rejectsDisallowedExtension()` | 422 | extension-not-allowed |
| `rejectsOversizeFile()` | 413 | payload-too-large |

---

## 4. AGENTS.md соответствие

| Правило | Проверка |
|---------|----------|
| Один инстанс на VM, без энтерпрайз-раздутия | ✓ точечный гвард, контейнеры изолируют внутренние инструменты (D-30) |
| Все числовые параметры — конфиг | ✓ `harness.workspace.download.*` (max-bytes, allow-extensions) |
| Каждая сущность — сценарий необходимости | ✓ workspace-download для §8 api-contracts (roaming: «офис» скачивает серверный workspace) |
| Design-решения в `decisions.md` | ✓ D-72 зафиксирован |

---

## 5. Замечания к ApiExceptionHandler

**NOT_IMPLEMENTED удалён из ProblemCodes** (`ProblemCodes.java`): stub machinery удалена. Это корректно — эндпоинт теперь реализован.

**Хендлеры для workspace-исключений добавлены:**
- `WorkspacePathInvalidException` → 422 path-invalid
- `ExtensionNotAllowedException` → 422 extension-not-allowed
- `WorkspaceFileNotFoundException` → 404 file-not-found
- `PayloadTooLargeException` → 413 payload-too-large

---

## Итог

**Статус:** Принято  
**Аппрув Mercury-2.5:** ✓

Batch U корректен. D-72 полностью реализован, тесты покрывают все сценарии, AGENTS.md соблюден, TOCTOU принят как осознанный риск.
