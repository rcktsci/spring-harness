# Re-approval Review: ops-workspace-ownership-fix (Mercury)

**Дата:** 2026-09-24  
**Объект:** незакоммиченное рабочее дерево (фиксы к `df5448e` по F1–F4)  
**Ревьюер:** Mercury (субагент)

---

## Проверка исправлений по пунктам DeepSeek

### F1 (MAJOR): Доступ к `/var/run/docker.sock` + README обоснование

**Исправлено:**
- `README.md:27-32`: добавлен явный шаг `export HARNESS_DOCKER_GID="$(stat -c '%g' /var/run/docker.sock)"`
- `docker-compose.yml:61-67`: `group_add` с `${HARNESS_DOCKER_GID:?...}` — fail-fast без переменной
- `README.md:32-34`: "There is no default on purpose: compose refuses to start without the variable"

**Вердикт:** ✅ **Исправлено корректно**  
- Fail-fast подтверждён в отчёте разработчика: `docker compose config` падает с подсказкой без переменной
- `group_add` добавляет группу docker хоста, а не uid-пайнинг
- Комментарии согласованы между README, docker-compose.yml, Dockerfile

### F2 (MINOR): Порядок шагов

**Исправлено:**
- `README.md:22-27`: пререквизиты теперь только `mkdir` и `export HARNESS_DOCKER_GID`
- `README.md:85-92`: блок `chown` перенесён в "Start it" (после `docker compose up -d`)
- `docker-compose.yml:12-18`: комментарий first-run обновлён (шаг 1: mkdir, шаг 6: chown после старта)

**Вердикт:** ✅ **Исправлено корректно**  
Порядок исполним: mkdir → export gid → up → chown

### F3 (MINOR): Artefacts change'а

**Исправлено:**
- `openspec/changes/deployment-readme/design.md`: добавлен D-R6 с альтернативами и обоснованием
- `openspec/changes/deployment-readme/proposal.md`: Impact обновлён (scope расширен)
- `openspec/changes/deployment-readme/tasks.md`: 2.1/2.6 уточнены

**Вердикт:** ✅ **Исправлено корректно**

### F4 (MINOR): Тест и комментарий

**Исправлено:**
- `WorkspaceContainerFailureDockerTest.java:111-116`: паттерн изменён на `.+\\w+Exception` (без привязки к разделителю)
- Комментарий обновлён: точный класс зависит от ОС (AccessDeniedException / FileSystemException / NoSuchFileException)

**Вердикт:** ✅ **Исправлено корректно**

---

## Дополнительные проверки

### Согласованность комментариев

| Файл | Комментарии | Статус |
|---|---|---|
| `docker-compose.yml:61-77` | group_add, volumes (docker.sock, workspace) | ✅ Согласованы |
| `README.md:27-34` | prerequisites (export gid, chown) | ✅ Согласованы |
| `docker/Dockerfile.orchestrator:40-44` | deploy-time grants | ✅ Согласованы |

### Валидность docker compose config

- Без `${HARNESS_DOCKER_GID}`: падает с fail-fast и подсказкой
- С переменной: интерполяция корректна (`group_add: ["999"]`)

### Тест `WorkspaceContainerFailureDockerTest`

- `workspaceMountFailureYieldsError`: паттерн `.+\\w+Exception` матчит все JDK IO-исключения
- Comment: точен (AccessDeniedException / FileSystemException / NoSuchFileException)

### Регрессия

- Не изменено: backend код, Dockerfile postgres, конфигурация БД
- Удалено: бессмысленный комментарий "bind-mounted at the same UID" из Dockerfile

---

## Вердикт

`approve`

Все блокирующие и желательные находки ревью DeepSeek исправлены. Fail-fast механизм работает (compose требует переменную), порядок шагов исполним, комментарий согласован, тест не хрупкий.
