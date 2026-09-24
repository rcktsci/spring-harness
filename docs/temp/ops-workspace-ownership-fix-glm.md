# Закрытие замечаний ревью к `df5448e` (F1–F4) — субагент-разработчик GLM-5.3-Flash

Дата: 2026-09-24. Объект: `docs/temp/review/ops-workspace-ownership-deepseek.md` (верdict reject, блокер F1).
Изменено 6 файлов (к отчёту); коммит — за оркестратором. Файлы другой параллельной пачки
(web-desktop relay-consent) не затронуты.

## F1 (major): доступ к `/var/run/docker.sock` + владение workspace

### Решение

Оба host-зависимых требования оркестратора оформлены как **явные шаги деплоя с числами,
прочитанными с хоста, без дефолтов**:

1. **Workspace-владение** (уже было в `df5448e`, переупорядочено по F2): `chown -R` корня
   workspace на `uid:gid`, прочитанные из **запущенного** контейнера
   (`docker compose exec -T orchestrator … id -u/-g`). Перенесено в раздел «Start it».
2. **Доступ к сокету** (новое): в `docker-compose.yml` сервису `orchestrator` добавлен
   ```yaml
   group_add:
     - "${HARNESS_DOCKER_GID:?export HARNESS_DOCKER_GID=<numeric gid of the host docker group> (stat -c %g /var/run/docker.sock), see README prerequisites}"
   ```
   README (пререквизиты): `export HARNESS_DOCKER_GID="$(stat -c '%g' /var/run/docker.sock)"`.
   Дефолта нет нарочно (`:?`): без переменной `docker compose up` падает **сразу с подсказкой**,
   тогда как угаданный gid падал бы молча на первом вызове инструмента — тот же класс ошибки,
   что уже случился на живом стенде с правами workspace.
3. Переписан несостоятельный абзац README («pinned uid would lose exactly that» — посылка
   сама себя опровергала): теперь фактическая причина — uid не пинится, потому что оба гранта
   читаются с хоста при деплое (`HARNESS_DOCKER_GID` для сокета, `chown` для workspace),
   привязка uid ничего не упрощает. Пользователь — из образа (`USER harness`, non-root).
4. Комментарии согласованы: `docker/Dockerfile.orchestrator` (убран бессмысленный
   «bind-mounted at the same UID»), том сокета и workspace-том в compose.

### Отвергнутые альтернативы

- **`chmod 666 /var/run/docker.sock`** — сокет становится root-эквивалентом для всех локальных
  пользователей; может сбрасываться юнитом `docker.socket`/рестартом демона. `group_add` даёт
  доступ только контейнеру. (Вариант был предложен ревьюером как допустимый, выбран строгий.)
- **Пин `user: <uid>:<gid>` в compose** — те же host-числа, но зашитые в файл; workspace всё
  равно пришлось бы chown-ить; image-овый `USER harness` обходится.
- **Запуск оркестратора от root** — избыточная привилегия при примонтированном сокете,
  ломает non-root дизайн образа; workspace-файлы создавались бы от root.
- **docker-socket-proxy** — лишняя движущаяся часть для одной выделенной VM
  (security-multitenancy держит proxy опциональным; уровень проекта — «сломалось — пофиксили»).
- **`.env` для gid** — запрещён дизайном change'а (D-R3: «без .env»).

### Побочный эффект (принят)

`${VAR:?}` вынуждает экспортировать переменную в **каждой** shell-сессии, где вызывается
`docker compose …` (`logs`, `down`, `restart` — тоже интерполируют). Отказ loud и с подсказкой —
в духе проекта («loud configuration error beats a silently broken LLM»). Smoke-скрипты
(`web-desktop/scripts/smoke-docker.*`) compose не запускают; CI-workflow пока нет — ничего
автоматизированного переменная не ломает.

## F2 (minor): порядок шагов

- chown-блок перенесён из пререквизитов в «Start it» (после `up -d`), с пояснением «uid:gid
  читаемы только у запущенного контейнера» + «рестарт не нужен».
- Пререквизиты теперь содержат только исполнимое до клона: `mkdir -p` и `export HARNESS_DOCKER_GID`.
- First-run-комментарий compose переписан в том же порядке: mkdir → export gid → build helper →
  CHANGE_ME → up → chown → health. Комментарий workspace-тома («owned by the user…» до старта)
  заменён на «pre-create before up; fix ownership after first start».

## F3 (minor): артефакты change'а

- `proposal.md` Impact: убрано «Существующий код не меняется», вписано осознанное расширение
  scope (tasks 2.5–2.6): правка текста ошибки в `WorkspaceContainerManager` (диагностика,
  не поведение) + host-гранты в compose/README.
- `design.md` D-R3: дописано требование владения (chown после первого старта, uid из
  запущенного контейнера, AccessDeniedException на живом стенде).
- `design.md` **новый D-R6**: решение (два явных гранта) → альтернативы (chmod 666 / пин user /
  root / socket-proxy) → почему. Запись в `docs/design/decisions.md` не заводил: решения
  deployment-readme живут в design.md change'а (D-R1…R5 там же; D-нумерация decisions.md —
  из архивированных milestone-изменений) — портировать D-R6 в decisions.md логично при
  архивации change'а, решение за оркестратором.
- `tasks.md`: уточнена посылка в 2.1 (было «ему нужен доступ к сокету» — та же опровергнутая
  логика), добавлена запись 2.6 с фиксацией F1–F4 и верификацией.

## F4 (minor): тест

`WorkspaceContainerFailureDockerTest.workspaceMountFailureYieldsError`:
- паттерн `"Failed to prepare workspace directory .+ — \\w+Exception"` →
  `"Failed to prepare workspace directory .+\\w+Exception"` — разделитель (U+2014) больше не
  участвует; суть сохранена: после текста ошибки обязан стоять тип корневой причины;
- комментарий приведён к реальным типам: точный класс зависит от ОС/причины
  (AccessDeniedException / FileSystemException / NoSuchFileException), прежний
  «Windows: NoSuchFileException» был неточен (под файлом-родителем это обычно
  AccessDenied/FileSystemException).
- Защиту `rootCause()` от самоссылающейся причины **не делал** (ревьюер: «при желании,
  не блокирует»): для JDK-исключений недостижимо, а новая ветка потребовала бы собственного
  теста — раздувание ради теоретического случая.

## Проверки и результаты

- `docker compose config` без переменной: **падает** с сообщением-подсказкой
  `required variable HARNESS_DOCKER_GID is missing a value: export …` (fail-fast подтверждён);
  с `HARNESS_DOCKER_GID=999` (локальная проверка интерполяции): `group_add: ["999"]`, exit=0.
- `mvn clean verify` (JDK 25 corretto-25.0.2): **BUILD SUCCESS, Tests run: 565, Failures: 0,
  Errors: 0, Skipped: 3** (3 — известные symlink-скипы на Windows; состав как в `df5448e`).
  Целевой класс `WorkspaceContainerFailureDockerTest`: **3/3 зелёные**, в т.ч.
  `workspaceMountFailureYieldsError` (surefire-отчёт).
- Docker 29.8.0 / compose v5.5.1 локально — те же версии, что заявлены в README.

## Открытые риски / наблюдения (вне scope находок)

- README, строки 53–55: абзац «One naming note» разрывает таблицу env — вторая половина
  рендерится без шапки. Дефект существовал **до** `df5448e`, в F1–F4 не входит; не правил
  (дисциплина scope), но стоит починить отдельной косметикой.
  **Закрыто** (доработка по поручению оркестратора): абзац перенесён под таблицу; таблица —
  единый блок из 8 строк (49–58), все строки сохранены дословно, сверены с env-блоками compose
  (orchestrator: POSTGRES_PASSWORD/USERNAME/DATABASE, KEYCLOAK_*, HARNESS_*; потерь/выдуманных
  нет; POSTGRES_HOST/POSTGRES_PORT/MANAGEMENT_SERVER_PORT — fixed-wiring без owner-ввода,
  в таблицу не входят и раньше не входили; POSTGRES_USER/POSTGRES_DB postgres-образа —
  в naming-note под таблицей). Других разрывов таблиц в README нет (проверены все `|`-строки:
  env-таблица и KC-таблица — обе непрерывны).
- «Tested with Docker 29.8.0 and compose v5.5.1» теперь подтверждено и для `group_add`/`:?`.
- Задача 3.1 change'а (прогон владельца на VM) по-прежнему открыта: финальное подтверждение
  «с нуля на типовой VM» — за прогоном владельца.
