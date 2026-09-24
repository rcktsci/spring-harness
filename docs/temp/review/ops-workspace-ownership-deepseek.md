# Ревью коммита `df5448e` — «fix(ops): workspace root must belong to the orchestrator user»

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-24.
Объект: `df5448e` (HEAD == `origin/main`). Метод: чтение кода/диффа/доков, побайтовая проверка литералов; сборка/тесты/docker не запускались.

## 0. Что делает коммит

- `README.md`: инструкция `chown` корня workspace на uid:gid пользователя контейнера + обоснование, почему uid не пинится в compose.
- `docker-compose.yml`: комментарии first-run и тома.
- `WorkspaceContainerManager.java`: в текст ошибки подготовки каталога добавлен тип корневой причины (`rootCause`), вместо повтора пути.
- `WorkspaceContainerFailureDockerTest.java`: добавлена регрессионная проверка наличия типа причины.
- `openspec/changes/deployment-readme/tasks.md`: записи 2.1/2.5.

## 1. Проверка заявленного механизма порчи (подтверждён)

Заявление: оркестратор сам создаёт подкаталог сессии и потому корень bind-mount должен быть ему доступен на запись. Код подтверждает:

- `WorkspaceContainerManager.createAndStart` — `src/main/java/se/rocketscien/harness/execution/WorkspaceContainerManager.java:306-317`: `hostDir = workspaceDir.toAbsolutePath().normalize()`, затем `Files.createDirectories(hostDir)` (стр. 313). Именно эта ветка выбрасывает «Failed to prepare workspace directory …» (стр. 315-316).
- `workspaceDir()` (стр. 70-72) = `Paths.get(properties.workspaceRoot(), namespace + id)`; `workspaceRoot` = `${HARNESS_WORKSPACE_ROOT:workspaces/sessions}` (`src/main/resources/application.yml:99`), в compose — `/srv/harness/workspaces` (`docker-compose.yml:98`), и тот же абсолютный путь примонтирован томом (`docker-compose.yml:69`).
- Процесс в контейнере не root: `docker/Dockerfile.orchestrator:42-43` — `adduser -S harness` + `USER harness`. Значит, `createDirectories` пишет на хостовую ФС (bind-mount) от uid `harness`; root-owned корень → `AccessDeniedException`. Механизм порчи описан верно.

Замечание по маршруту ошибки: каталог создаётся **до** обращения к docker (`createAndStart` сначала `createDirectories`, стр. 313, потом `ensureImage()`/`createContainerCmd`, стр. 319+). Поэтому при одновременной поломке прав на workspace и недоступности docker.sock наблюдался бы именно workspace-ошибка — это объясняет «app looked healthy». Диагноз не противоречив.

## 2. Находки

### F1 (MAJOR, блокирует «развёртывание с нуля»). Обоснование выбора пользователя контейнера несостоятельно, а доступ к docker.sock не задокументирован
`README.md:33`:
> The container user is deliberately left to the image (`USER harness`) rather than pinned in compose: it needs access to `/var/run/docker.sock` to create helper containers, and a pinned uid that is not in the host's `docker` group would lose exactly that.

Проблема: образ (`docker/Dockerfile.orchestrator:42-43`) создаёт группу `harness` и пользователя `harness`, но **нигде** не добавляет его ни в какую группу хоста; `docker-compose.yml` не использует `group_add` (`docker-compose.yml:60-69`); в README нигде нет `chgrp`/`chmod`/`usermod` для сокета (проверено grep'ом по `docker.sock|docker group|group_add|usermod|chmod` — вне этого абзаца совпадений нет). На типовой VM сокет — `root:docker 660`; uid/gid `harness` (Alpine system user, ~100) в группе `docker` не состоит, т.е. тот же самый «pinned uid, не входящий в docker group», к которому апеллирует абзац, — это и есть фактический пользователь контейнера. Посылка абзаца сама себя опровергает: непропинованный uid не даёт доступ волшебным образом. На живой VM, судя по «bash returns OK», сокет был доступен (666 либо совпадение gid) — но это внешнее, нереплицируемое состояние, которое README не фиксирует.

Требование на исправление (любой из вариантов):
1. Добавить в README обязательный шаг доступа к сокету — например, `group_add` с gid группы `docker` хоста (`stat -c '%g' /var/run/docker.sock` → `group_add: ["<gid>"]` в `orchestrator`), либо явно задокументированный `chmod 666 /var/run/docker.sock` как принятая для выделенной VM мера (с отсылкой к `security-multitenancy.md:29`);
2. и/или удалить/переформулировать недоказуемое предложение `README.md:33`, заменив на реальную причину выбора `USER harness`.

### F2 (MINOR, «инструкции неисполнимы»). Блок `chown` стоит до `git clone`/`docker compose up`
`README.md:20-31`: раздел «What you need on the VM» (пререквизиты) требует сначала `mkdir -p …`, затем — внутри того же пререквизитного списка — `docker compose exec -T orchestrator …` (стр. 29-30), хотя compose-файла ещё нет (клонирование и `up -d` в этом же разделе ниже, стр. 37-83). Только при последовательном копипасте команда `docker compose exec` падает (`no configuration file provided`/сервис не запущен). Сам текст оговаривает «you can fix it after the first start», но размещение противоречит порядку. Дополнительно `docker-compose.yml:12-13` («First run: 1. mkdir … **and chown it to the orchestrator container's user**») предлагает сделать chown на шаге 1, где uid контейнера ещё неизвестен (образ даже не собран).

Требование: перенести блок chown в раздел «Start it» (после `docker compose up -d`) либо явно вынести его отдельным шагом «After first start»; согласовать формулировку `docker-compose.yml:12-13` (например, «uid must be taken from the running container, see README»).

### F3 (MINOR). Расхождение со «спекой» открытого change'а и отсутствие записи в decisions.md
Коммит меняет main-код (`WorkspaceContainerManager.java`) и тест, тогда как `openspec/changes/deployment-readme/proposal.md:27` утверждает «Существующий код не меняется. Поведение приложения не меняется», а `design.md:41` (D-R3) описывает только `mkdir -p`, без требования владения. Записи в `docs/design/decisions.md` о решении (chown корня на uid контейнера, диагностика через тип причины) нет, хотя AGENTS.md требует фиксировать сущностные решения строкой.

Требование: обновить `design.md` D-R3 и Impact `proposal.md` (или пометить осознанное расширение scope), при необходимости добавить строку решения.

### F4 (MINOR). Хрупкость регрессионного теста/комментария
`WorkspaceContainerFailureDockerTest.java:111-114`: проверка `containsPattern("Failed to prepare workspace directory .+ — \\w+Exception")` зависит от конкретного символа `—` (U+2014) в *обоих* файлах. Сейчас совпадение корректно (побайтовая проверка: в `WorkspaceContainerManager.java:316` и в тесте — один и тот же U+2014), но любая перекодировка одного файла сломает тест не по существу. Комментарий (стр. 113) неточен: на Windows при родителе-файле это обычно `FileSystemException`/`AccessDeniedException`, а не `NoSuchFileException`. Также `rootCause()` (`WorkspaceContainerManager.java:351-360`) формально может зациклиться на самоссылающейся причине (на практике для JDK-исключений недостижимо).

Требование (не блокирует): не привязываться к конкретному разделителю (искать `Exception` отдельно от текста ошибки), привести комментарий в соответствие с реальными типами; при желании — завернуть обход причин в защиту от цикла.

## 3. Проверки «обратной стороны»

- Текст ошибки: `rootCause(e)` (стр. 351-360) для `AccessDeniedException` даст `"AccessDeniedException: <path>"`, т.е. тип причины добавлен, прежняя информативность (`e.getMessage()`) сохранена. Регресса нет. Единственный потребитель формата — сам тест (grep по `Failed to prepare workspace directory` даёт только 3 вхождения: код, тест, tasks.md).
- Утечка чувствительного: в сообщение попадает хостовый путь и текст корневой причины (для IO — путь), секретов нет; путь `workspaces/<sessionId>` и так логировался. Утечки нет.
- Тест по существу: `workspaceMountFailureYieldsError` (стр. 101-115) создаёт файл вместо каталога и проверяет ERROR + наличие типа причины. Для `Files.createDirectories` под файлом все возможные типы (`NotDirectoryException`, `FileSystemException`, `AccessDeniedException`, `FileAlreadyExistsException`) оканчиваются на `Exception` и матчатся `\w+Exception` — переносимо Windows/Linux.
- Остальные места с неверным утверждением: grep по `root-owned|does not write there` — вне этого коммита вхождений нет; `design.md:41` не содержит ложного тезиса (говорит лишь «не создавать заранее → daemon создаст от root»), но и не фиксирует требование владения (см. F3).
- `rootCause` не затрагивает ветку «Workspace path is not a directory» (стр. 308-310) и docker-ветки — изменения локальны.

## 4. Итог

Основная правка (владение корнем workspace) корректна и подтверждена кодом; тест и текст ошибки не деградировали; утечек нет. Однако заявленная цель «воспроизводимый подъём с нуля» не достигнута: новый абзац README даёт несостоятельное обоснование и не закрывает недокументированную зависимость от доступа к `docker.sock` (F1, major), а сам блок chown неисполним в порядке изложения и противоречит комментарию compose (F2). Это не позволяет считать требования выполненными на 100%.

## ВЕРДИКТ: `reject`

Блокирующая находка: F1 (major). Обязательные к исправлению: F1, F2; желательные: F3, F4.
