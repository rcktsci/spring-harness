# Ре-аппрув ревью: закрытие F1–F4 к `df5448e` (ops-workspace-ownership)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-24.
Мой исходный отчёт: `docs/temp/review/ops-workspace-ownership-deepseek.md` (reject, F1 major).
Отчёт разработчика: `docs/temp/ops-workspace-ownership-fix-glm.md`.
Объект: незакоммиченное рабочее дерево относительно HEAD (`df5448e`); файлы параллельной пачки (web-desktop relay-consent) в разбор не входят.
Метод: чтение `git diff` по ops-файлам (README, compose, Dockerfile, deployment-readme, тест), сверка инструкций между собой и с env-блоком compose; сборка/тесты/docker не запускались.

## F1 (major): доступ к `/var/run/docker.sock` — **закрыто**

- `docker-compose.yml:61-66`: сервису `orchestrator` добавлен `group_add` с gid из обязательной переменной `"${HARNESS_DOCKER_GID:?export HARNESS_DOCKER_GID=<numeric gid of the host docker group> (stat -c %g /var/run/docker.sock), see README prerequisites}"`. Это явный, воспроизводимый грант: gid читается с хостового сокета, не угадывается; `:?` даёт fail-fast с подсказкой вместо тихой поломки на первом tool-call. Соответствует требованию «явный грант / без магических значений».
- `README.md:27-33`: пререквизит с `export HARNESS_DOCKER_GID="$(stat -c '%g' /var/run/docker.sock)"` + объяснение «no default on purpose» + указание экспортировать «в той же shell, где запускаете docker compose».
- `README.md:94`: переписан несостоятельный абзац про «pinned uid would lose exactly that»; новая формулировка фактическая (оба гранта читаются с хоста при деплое). Проверил grep: старое утверждение осталось только в моём отчёте и в цитате отчёта разработчика — в README/compose/design его нет.
- `docker/Dockerfile.orchestrator:40-43`: комментарий «bind-mounted at the same UID» убран, новый корректен.
- Технически `group_add` кладёт gid в supplementary-группы процесса `harness` → доступ к `root:docker 660` (или `root:root 660`, если `stat %g` вернёт 0) появляется; helper-контейнерам сокет не нужен.

**Проверка «не ломает ли обязательная переменная повторный `docker compose up -d`»:** `:?` интерполируется для всех подкоманд compose, поэтому без экспорта падают и `up`, и `ps`, и `logs`, и `exec`. Для владельца это не тихая поломка: отказ мгновенный и с императивной подсказкой, README требует экспорт в рабочей shell; повторный `up -d` с экспортированной переменной просто пересоздаёт orchestrator (изменился `group_add`), workspace/PG-тома и ownership сохраняются. Автоматизации, которая бы звала compose без переменной, в репо нет (`.github` отсутствует; smoke-скрипты compose не запускают — подтверждено grep). Принимаю как осознанный trade-off (описан в отчёте, §«Побочный эффект»).

## F2 (minor): порядок шагов — **закрыто**

- `README.md:26` (пререквизиты) теперь ссылается на «Start it» и объясняет, что uid:gid читается только у запущенного контейнера; сам `chown` вынесен в `README.md:85-94` после `docker compose up -d` (`README.md:79-83`).
- Комментарий first-run в `docker-compose.yml:11-19` переписан в том же порядке: mkdir → export gid → build helper → CHANGE_ME → up → chown → health. Расхождений с README нет (сверял построчно).
- Комментарий workspace-тома (`docker-compose.yml:72-77`) заменён на «pre-create before up; fix ownership after first start».

## F3 (minor): артефакты change'а — **закрыто**

- `proposal.md:27`: «Существующий код не меняется» заменено на осознанное расширение scope (правка текста ошибки в `WorkspaceContainerManager` + host-гранты), совпадает с `tasks.md:11-12` (2.5/2.6).
- `design.md`: D-R3 дополнен требованием владения (chown после первого старта, uid из запущенного контейнера, `AccessDeniedException` на живом стенде); добавлен D-R6 (два host-гранта → альтернативы chmod 666 / пин user / root / socket-proxy → почему).
- Перенос D-R6 в `docs/design/decisions.md` не сделан, но это осознанно и обосновано: D-R1…R5 этого change'а живут в design.md и портируются при архивации; AGENTS.md-требование «строкой в decisions.md» для сущностных решений закрывается на архивации change'а. Принимаю (остаточный пункт — не блокирует).

## F4 (minor): тест — **закрыто**

- `WorkspaceContainerFailureDockerTest.java:112`: паттерн отвязан от типографского разделителя — `"Failed to prepare workspace directory .+\\w+Exception"`; `containsPattern` = поиск подстроки, U+2014 больше не участвует, требование «после текста ошибки стоит тип корневой причины» сохранено. Регресс к прежнему `e.getMessage()` (путь без «Exception») тест поймает.
- Комментарий (`:110-114`) приведён к реальности: класс зависит от ОС/причины (AccessDeniedException / FileSystemException / NoSuchFileException).
- `rootCause()` от самоссылающейся причины не защищён — я сам помечал как «при желании, не блокирует»; согласен с решением не плодить ветку ради теоретического случая.

## Риск (2) из отчёта: разрыв markdown-таблицы — **закрыто**

- `README.md:49-58`: env-таблица непрерывна (шапка + 8 строк, `|`-строки 49→58 без разрывов); абзац «One naming note» перенесён под таблицу (`:60`).
- Сверка строк таблицы с env-блоком compose (`docker-compose.yml:79-110`): POSTGRES_PASSWORD/USERNAME/DATABASE, KEYCLOAK_ISSUER_URI/JWKS_URI, HARNESS_WEBHOOK_SECRET/BASE_URL, HARNESS_LLM_KEY_V1, HARNESS_WORKSPACE_ROOT — все присутствуют, потерь/выдуманных нет. `POSTGRES_HOST`, `POSTGRES_PORT`, `MANAGEMENT_SERVER_PORT` — fixed-wiring без owner-ввода, в таблицу не входят. KC-таблица (`:113-122`) тоже непрерывна.

## Дополнительные проверки (согласованность текстов)

- Инструкции README ↔ комментарии compose ↔ Dockerfile не противоречат друг другу: единая причина (uid не пинится, оба гранта с хоста), единый порядок first-run.
- Остаточные наблюдения (не блокеры, правок не требую):
  - `README.md:47` «everything lives in the `environment:` block» и отдельно экспортируемый `HARNESS_DOCKER_GID` — лёгкое смысловое трение (переменная нужна для интерполяции `group_add`, не для контейнерного env), но пререквизит `:27-33` это явно объясняет.
  - `HARNESS_DOCKER_GID` сделана обязательной для всех подкоманд compose — задокументированный trade-off; будущий change `ci-backend-image` (tasks 3.1/3.3, ещё не выполнен) должен будет учесть экспорт в своих инструкциях по `docker compose up -d`.
  - `mkdir -p /srv/harness/workspaces` без `sudo` — существовавшая ранее мелочь, вне F1–F4.

## Итог

Все четыре замечания (F1 major + F2–F4 minor) и дополнительный риск (2) закрыты по коду и документации; найдено/подтверждено;
противоречий в инструкциях нет, тест устойчив к пунктуации и реально сторожит тип причины, обязательная переменная даёт loud fail-fast и не ломает повторный сценарий владельца. Новых блокеров не выявлено.

## ВЕРДИКТ: `approve`
