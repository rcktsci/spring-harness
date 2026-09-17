## Findings (severity, файл:строка, дефект, предложение)

**Low | WorkspaceContainerManager:63-72** — В `ensureContainer`, если `createAndStart` выбрасывает после запуска контейнера (execStart прошёл, но awaitRunning упал), контейнер остаётся в Docker без записи в map. Редкий утечка ресурса.
*Предложение:* Добавить finally-блок для удаления containerId при исключении в `createAndStart` (использовать `dockerClient.removeContainerCmd()`).

**Low | WorkspacePathGuard (общий)** — TOCTOU: проверка `realPath` на host до exec. Между проверкой и exec злонамеренный процесс может создать symlink внутри workspace, ведущий наружу. exec-команды (`cat`, `ls`) пойдут по нему.
*Предложение:* Для MVP ок (workspace read+write, нет эскалации), но зафиксировать в ADR как known limit.

**Info | HelperImageDockerTest** — Тест зависит от наличия `harness-helper:test` в registry. Если нет — упало. Тест не полностью изолирован от внешней среды.
*Предложение:* Убедиться, что DockerTestSupport гарантированно строит образ локально перед тестом.

**Info | ContainerWorkspaceToolsDockerTest.tearDown** — `manager.removeContainer(sessionId)` чистит контейнер, но не workspace-каталог на host.
*Предложение:* Ок, т.к. @TempDir очищает после каждого теста. Зафиксировать в комменте теста.

## Проверено и валидно (без замечаний)

1. **Concurrency (WorkspaceContainerManager):** `computeIfAbsent` thread-safe; TOCTOU в `isRunning` компенсируется проверкой Docker daemon; `exec` цепочка с `isContainerRunning` между шагами корректна.
2. **Тестовая гигиена:** `DockerTestSupport.helperImage()` cache; статический `dockerClient` в `setUpClass`/`@AfterAll`; @TempDir cleanup.
3. **LlmInvoker.isTransient:** Цепочка исключений обрабатывается корректно; защита от цикла `cause == cause.getCause()`.
4. **ContainerWorkspaceTools.writeFile/exec:** Защита от shell-инъекции через `--` и позиционные аргументы.
5. **WorkspaceContainerManager.exec:** Объединение stdout/stderr; timeout handling; containerStoppedWithin компенсация задержки демона.
6. **WorkspaceContainerManager.removeContainer:** `containers.remove(sessionId)` вызывается всегда; корректная обработка null из `findContainerId`.
7. **Утечка M3/M4:** Отсутствует async-режим, CLIENT_EXEC relay — соответствует спеке.

## Summary (числа + вердикт)

**Итого замечаний:** 4 (2 Low, 2 Info)
**Проверено валидным:** 13 пунктов
**Рекомендация:** ✅ **Approve** с внесением комментариев в код (Low findings). Критических дефектов нет. Риск утечки контейнеров минимален (редкий race), TOCTOU для MVP приемлем.
