# Ревью целостности дизайн-корпуса после де-скоупа (D-39…D-42)

> Автор: DeepSeek-V4.1-Flash. Дата: 2026-09-17. Метод: сплошное чтение `docs/glossary.md` и `docs/design/*.md`, сверка «живых» разделов с решениями D-39…D-42 (в первую очередь D-41).
> Правило отбора: дефекты только в **живых** разделах. Строки `decisions.md`, где выброшенное названо явно («Выброшено: …»), дефектами не считаются. Файлы `docs/temp/review/*` (артефакты прошлых раундов) вне объёма.

## Сводка

| Класс | Смысл | Кол-во |
|---|---|---|
| 1 | Висячие ссылки на вырезанное в живых разделах | **18** |
| 2 | Противоречия между доками после правок | **11** |
| 3 | Сломанное несущее (механизмы/инварианты) | **2** |

**Топ-3 (по убыванию риска):**
1. **`architecture.md` §3 (стр. 33) + `roadmap.md` M1 (стр. 7): висячий контракт `AccessPolicy`.** D-41 удалил матрицу прав, но контракт остался (и даже назначен в объём M1) — реализатор построит удалённую дверь; внутреннее противоречие с `architecture.md` §1 (стр. 9) и `security-multitenancy.md` §1.
2. **`archived_at` / «не архивна» в eligibility (glossary §4, execution-model §1, data-model §5) при вырезанной архивации (D-41, api §8).** Несущий POLL-скан и partial-index завязаны на колонку удалённой фичи — eligibility неопределён.
3. **`execution-model.md` §8 (стр. 108): «EVENT-путь идемпотентен благодаря CAS-локу».** D-40 удалил CAS-колонки сессии; объяснение несущей идемпотентности wake ссылается на несуществующий механизм (должно быть ShedLock `sess-{id}` / tryStart).

---

## Класс 1. Висячие ссылки на вырезанное (живые разделы)

| Док + раздел | Дефект | Предложение |
|---|---|---|
| `glossary.md` §4 (стр. 65) | Термин **Fork** определён как живой (только FREE, `source_session_id + seq_cutoff`). D-41: fork выброшен. | Удалить строку термина. |
| `glossary.md` §4 (стр. 66) | Термин **Rewind** определён как живой. D-41: rewind выброшен. | Удалить строку термина. |
| `glossary.md` §4 (стр. 67) | Термин **Экспорт** определён как живой. D-41: экспорт выброшен. | Удалить строку термина. |
| `glossary.md` §5 (стр. 75) | «внутри сессии свободен — субагенты, **форки**, любые паттерны». Fork вырезан. | Заменить на «субагенты и любые паттерны». |
| `glossary.md` §7 (стр. 97) | **SSO**: «Пользователи и **группы** синхронизируются оттуда». Группы не моделируются (D-41; `data-model.md` §1). | Оставить только пользователей; группа — источник claim'а, не сущность. |
| `glossary.md` §8 (стр. 103) | **Attach-CLI**: «провал в субагентов (**VIEW**), дописывание им (**PARTICIPATE**)». Оба уровня вырезаны. | Переписать через «одно правило доступа»: провал в поддерево и дописывание без уровней. |
| `glossary.md` §8 (стр. 104) | **WebUI**: «Второй клиент, **позже**». Противоречит D-42 (порядок гибкий). | Убрать «второй/позже»; зафиксировать D-42. |
| `glossary.md` §9 (стр. 114) | Инвариант 6: «Fork/rewind — только свободные сессии». Механизмы вырезаны. | Удалить инвариант (перенумеровать). |
| `data-model.md` §5 `session` (стр. 149–151) | Колонки **`fork_source_session_id`, `fork_seq_cutoff`, `rewind_seq`** — схема вырезанного fork/rewind. | Удалить колонки. |
| `data-model.md` §5 `session` (стр. 152, 172) | Колонка **`archived_at`** («архивация») и «политика очистки **архивных** сессий». Архивация вырезана (D-41). | Удалить `archived_at` и правки retention (см. также класс 2/3). |
| `data-model.md` §5 `session` (стр. 156) | Денормализации **`message_count/tokens_total`** объявлены живыми в одном предложении с `last_seq/last_consumed_seq`. D-41: вырезаны; колонок в таблице нет. | Оставить только `last_seq/last_consumed_seq`. |
| `architecture.md` §3 (стр. 33) | Контракт **`AccessPolicy`** «единая точка кому что видно/можно» — вырезан D-41; противоречит §1 (стр. 9). | Удалить строку контракта из таблицы. |
| `workflow-domain.md` §7 (стр. 75) | Threat-model перечисляет живыми **опциональный `Idempotency-Key`** и **`rate-limit`** — оба вырезаны (D-41, api §0.3/§8). | Оставить только «идемпотентность по построению + TLS». |
| `roadmap.md` M1 (стр. 7) | В объёме M1: **«Keycloak-синхронизация users/groups», «AccessPolicy-каркас», «билеты»**. Всё три вырезано/перенесено (D-40/D-41/D-42, api §8). | Убрать AccessPolicy и groups; билеты вынести в WebUI-фазу. |
| `roadmap.md` M4 (стр. 24) | Объём CLI: «tree/inject/stop/**fork/rewind**/compact». Fork/rewind вырезаны. | Убрать fork/rewind из перечня. |
| `client-cli.md` §1 (стр. 10–11) | Флаги **`--folder`** в `sessions` и `new`. Папки вырезаны (D-41). | Удалить `--folder`. |
| `client-cli.md` §2 (стр. 27) | «билет одноразовый … (`POST /auth/ticket`)» для CLI. Билеты — WebUI-фаза; CLI ходит по Bearer. | Убрать билет из UX-CLI. |
| `operations.md` §1 (стр. 9) | Маскирование **билетов** (`?ticket=`) — фичи в MVP нет (WebUI-фаза). | Убрать билеты из списка (вернуть в WebUI-фазе). |

## Класс 2. Противоречия между доками

| Док + раздел | Дефект | Предложение |
|---|---|---|
| `architecture.md` §1/§3 | §1: «контракта-матрицы нет»; §3: контракт `AccessPolicy`. Взаимоисключающе. | Убрать `AccessPolicy` (оставить только SSO-фильтр). |
| `glossary.md` §4 / `execution-model.md` §1 / `data-model.md` §5 vs `api-contracts.md` §8, D-41 | Eligibility требует «не **архивна**» и index `WHERE archived_at IS NULL`, но архивация вырезана и API `archive/unarchive` нет. | Либо оставить архивацию (тогда вернуть API/DTO/фильтр), либо убрать `archived_at` из eligibility, индекса и retention. |
| `data-model.md` §5 (стр. 156) vs D-41 | Схема-комментарий обещает `message_count/tokens_total`, D-41 их вырезал, колонок нет. | Синхронизировать список денормализаций. |
| `roadmap.md` M1 (стр. 7) / `operations.md` §1 / `client-cli.md` §2 vs D-41, `api-contracts.md` §8, `roadmap.md` стр. 10 | Билеты одновременно «в объёме M1» и «в WebUI-фазе»; CLI и operations на них завязаны. | Единая позиция: билеты — только WebUI-фаза. |
| `roadmap.md` M4/M5 (стр. 24, 27, 29) / `client-cli.md` стр. 3 / `glossary.md` §8 vs D-42, `api-contracts.md` §8, `roadmap.md` стр. 10 | Roadmap жёстко ставит CLI (M4) раньше WebUI (M5 «позже»); client-cli/glossary — «первый/второй клиент». D-42 делает порядок гибким. | Переформулировать M4/M5 как «клиенты» с явной ссылкой на D-42. |
| `client-cli.md` §3 (стр. 31) / `roadmap.md` M4 vs `api-contracts.md` §8 | «Скачивание артефактов кнопкой — **REST уже есть**» и включено в M4, хотя `GET …/workspace/files` отнесён к WebUI-фазе и в MVP-контракте отсутствует. | Убрать «REST уже есть»; вернуть endpoint в WebUI-фазу (или внести в MVP явно). |
| `workflow-domain.md` §7 (стр. 75) vs `security-multitenancy.md` §4 | workflow-domain заявляет `rate-limit` как действующую компенсацию, security — «rate-limit не делаем». | Убрать rate-limit из threat-model §7. |
| `roadmap.md` M1 (стр. 7) / `glossary.md` §7 (стр. 97) vs `data-model.md` §1, `glossary.md` §1 | Синхронизация `groups` заявлена, но группы не моделируются. | Убрать groups-синхронизацию из объёма. |
| `data-model.md` §5 (стр. 149–151) / `roadmap.md` M4 vs D-41 | Fork/rewind вырезаны, но живут в схеме и roadmap. | Удалить из схемы и roadmap (см. класс 1). |
| `client-cli.md` стр. 3 vs стр. 20 | Заголовок: «Первый клиент (D-19: CLI → WebUI)»; примечание: «WebUI может пойти раньше CLI (D-42)». | Оставить D-42, снять D-19-формулировку из заголовка. |
| `data-model.md` §6 (стр. 191) | Ссылка на **(D-04)** как обоснование «история попыток вебхуков не хранится»; D-04 — про отсутствие таблицы Turn/Run и CAS-локи, и сам перекрыт D-40. | Заменить/убрать ссылку (дать прямое обоснование). |

## Класс 3. Сломанное несущее

| Док + раздел | Дефект | Предложение |
|---|---|---|
| `execution-model.md` §8 (стр. 108) | «EVENT-путь идемпотентен благодаря **CAS-локу**» — CAS-локи сессии удалены D-40 (единственный механизм — ShedLock). Несущее обоснование идемпотентности wake ссылается на вырезанное; противоречит `data-model.md` §7.4. | Заменить на «исключительность tryStart по ShedLock-локу `sess-{id}`; проигравший выходит». |
| `glossary.md` §4 Eligibility (стр. 63) / `execution-model.md` §1 (стр. 32) / `data-model.md` §5 (стр. 156) | Eligibility и partial-index POLL-скана завязаны на `archived_at IS NULL` / «не архивна», а архивация вырезана D-41 ⇒ предикат готовности сессии неопределён (несущий eligible-скан). | Удалить клаузу архива из eligibility и индекса (либо вернуть архивацию целиком — тогда синхронно glossary/data-model/api). |

## Несущее, подтверждённое как целое (без дефектов)

Проверено и консистентно: append-only `session_message`/`task_transition_history`; ShedLock-локи `sess-{id}` + `LockExtender`/TTL и отсутствие колонок локов; двухуровневый wake EVENT+POLL + старт-скан LOST; CAS-переходы с stop-исключением `'$CANCELLED'`; ретраи LLM exp+backoff без bulkhead; авто-компакция ~80% + `/compact`; helper- и task-контейнеры (D-30); `maxDepth` как единственный лимит спавна; `POST /sessions/{id}/compact` в api; SSO-гейт по `groups`-claim.

## Рекомендуемый порядок правок

1. `architecture.md` §3 + `roadmap.md` M1 — снять `AccessPolicy` (топ-1).
2. `data-model.md` §5 + `glossary.md` §4 + `execution-model.md` §1 — закрыть вопрос архивации/`archived_at` (топ-2).
3. `execution-model.md` §8 — заменить «CAS-лок» на ShedLock-исключительность tryStart (топ-3).
4. Единым проходом вычистить fork/rewind/export, билеты, `--folder`, `message_count/tokens_total`, Idempotency-Key/rate-limit.
5. Привести roadmap M4/M5 к D-42 (порядок клиентов гибкий).

---

## Cross-check

> Сверка с `descope-review-glm.md` и `descope-review-mercury.md`. Нумерация моих пунктов — по порядку строк таблиц выше: **Кл.1.1…1.18**, **Кл.2.1…2.11**, **Кл.3.1…3.2** (расшифровка — в таблицах).
> Вердикты: `agree` (принимаю, новый для меня), `disagree` (не согласен, почему), `duplicate` (мой №).

### GLM (`descope-review-glm.md`)

| Их № | Тема | Вердикт | Мой № / обоснование |
|---|---|---|---|
| 1.1 | data-model §5: колонки fork/rewind/archive | duplicate | Кл.1.9 + 1.10 (+ индекс архива — Кл.3.2) |
| 1.2 | glossary Fork/Rewind/Экспорт + «форки» | duplicate | Кл.1.1–1.4 |
| 1.3 | glossary §9 инвариант 6 | duplicate | Кл.1.8 |
| 1.4 | «не архивна» в Eligibility/гвардах | duplicate | Кл.3.2 (+ Кл.2.2); у меня отнесено к классу 3 как несущий eligible-скан |
| 1.5 | execution-model §4: удаление контейнера «при архивации» (стр. 78) | **agree** | Новый. Пропустил: то же вырезанное, но отдельная живая ссылка lifecycle |
| 1.6 | message_count/tokens_total (стр. 156) | duplicate | Кл.1.11 |
| 1.7 | architecture §3 AccessPolicy | duplicate | Кл.1.12 |
| 1.8 | roadmap M4 fork/rewind | duplicate | Кл.1.15 |
| 1.9 | client-cli `--folder` | duplicate | Кл.1.16 |
| 1.10 | glossary Attach-CLI VIEW/PARTICIPATE | duplicate | Кл.1.6 |
| 1.11 | client-cli `POST /auth/ticket` | duplicate | Кл.1.17 |
| 1.12 | operations маскирование билетов | duplicate | Кл.1.18 |
| 2.1 | roadmap M1: билеты + AccessPolicy + groups | duplicate | Кл.1.14 (+ Кл.2.1/2.4/2.8) |
| 2.2 | workflow-domain §7: rate-limit + Idempotency-Key | duplicate | Кл.1.13 (+ Кл.2.7) |
| 2.3 | execution-model §6 + glossary §5: «recovery добивает поддерево» = докатка отмен | **agree** | Новый. D-41 выбросил «докатку отмен»; живые «recovery-спуск по БД» (execution-model стр. 94) и «recovery добивает выжившие» (glossary стр. 77) обещают именно её. Считаю это **несущим** (семантика cancel-поддерева после crash) — кандидат в мой класс 3 |
| 2.4 | execution-model §1 vs §2: один `lockAtMostFor` на два TTL | **disagree** | `lockAtMostFor` — параметр **per-lock** ShedLock (`@SchedulerLock` vs программный `LockProvider.lock(name, …)`); джоба и сессия задают свои значения, доки не утверждают их равенство. Не противоречие; разведение имён конфиг-ключей — полезная мелочь, но не фикс дефекта |
| 2.5 | decisions: superseded-строки без пометок | **agree** | Новый, низкий приоритет. D-29/D-34/D-36/D-37 и т.п. читаются как действующие; пометка «частично отменено D-41» уместна (ADR append-only). Мой Кл.2.11 — частный случай (D-04) |
| 2.6 | api `workspaceBinding(s)` без источника в модели | **agree** | Новый, minor. Поля — вычисляемая проекция (FREE: `SERVER_DIR auto` из `{sessionId}`; STATE: `workspace`-декларация пиннутой ревизии), но это нигде не сказано |
| 2.7 | glossary §8 «первый/второй клиент, позже» vs D-42 | duplicate | Кл.1.7 (+ Кл.2.5/2.10) |
| 2.8 | roadmap M4 «скачивание workspace-файлов» vs WebUI-фаза | duplicate | Кл.2.6 |
| 2.9 | Eligibility «сессия не терминальна» без поля в схеме | **agree** | Новый, minor. Признака терминальности сессии в `data-model` §5 нет; переформулировать через задачу/`last_turn_outcome` |
| 2.10 | agent-tools §2b vs §4: статус `metaTools` после D-41 | **agree** | Новый, низкий. У меня в аудите помечено как «пограничное»; развести выброшенный гейт D-38 и остающийся флаг-allowlist, дописать флаг в §4 |
| — | Класс 3: «дефектов несущего нет» | **disagree** | У меня **два** несущих дефекта: Кл.3.1 (`execution-model` §8 «CAS-лок» — GLM пропустил) и Кл.3.2 (eligibility на `archived_at`; у GLM это Кл.1/2.4, не класс 3) |

### Mercury (`descope-review-mercury.md`)

| Их № | Тема | Вердикт | Мой № / обоснование |
|---|---|---|---|
| 1.1 | glossary стр. 103 PARTICIPATE (указан §1.1 — неверно, §8) | duplicate | Кл.1.6 (у меня VIEW **и** PARTICIPATE) |
| 1.2 | glossary «Session … поля fork_source_session_id/…» (локация неверна: поля в `data-model.md` §5) | duplicate | Кл.1.1–1.4, 1.9. Существо верно, но в glossary этих полей нет — правит `data-model` |
| 1.3 | data-model §5 message_count/tokens_total | duplicate | Кл.1.11 |
| 1.4 | architecture §3 AccessPolicy | duplicate | Кл.1.12 |
| — | security §1 `spawn.maxDepth` — «согласовано, OK» | **disagree** | Не дефект вообще: `maxDepth` — обязательный сохраняемый лимит (D-41). Пункт не является находкой; в классе 1 ему не место |
| 1.5 | client-cli `--folder` | duplicate | Кл.1.16 |
| 1.6 | workflow-domain §7 Idempotency-Key | duplicate | Кл.1.13 |
| 1.7 | roadmap M1 билеты | duplicate | Кл.1.14 |
| 1.8 | roadmap M4 fork/rewind (+export) | duplicate | Кл.1.15. В M4 `export` не значится — только fork/rewind |
| 2.1 | TaskDto.params иммутабельны vs data-model `params_jsonb` без пометки | **disagree** | Не противоречие: иммутабельность заявлена в `api-contracts` §4.1 и `agent-tools` §2b; отсутствие пометки в схеме — omission, а не конфликт. Максимум — добавить строку в инварианты §7 |
| 2.2 | roadmap M1 vs api §8: tickets/WebUI-фаза | duplicate | Кл.2.4 (+ Кл.1.14) |
| 2.3 | roadmap M4 vs D-41: fork/rewind | duplicate | Кл.2.9 (+ Кл.1.15) |
| — | Класс 3: «все core-инварианты OK» | **disagree** | См. GLM-строку выше: Кл.3.1 (CAS-лок) и Кл.3.2 (archived_at eligibility) |

### Итог сверки

| Вердикт | GLM (22 п.) | Mercury (12 п.) | Всего |
|---|---|---|---|
| agree | 6 (1.5, 2.3, 2.5, 2.6, 2.9, 2.10) | 0 | **6** |
| disagree | 1 (2.4) | 2 | **3** |
| duplicate | 15 | 10 | **25** |

**Новые дефекты, принятые от GLM (не были у меня):** 1.5 (контейнер «при архивации»), 2.3 (докатка отмен в recovery — несущее), 2.5 (пометки superseded в ADR), 2.6 (`workspaceBinding` без источника), 2.9 (терминальность сессии не определена), 2.10 (`metaTools` §2b vs §4).

**Единственное уникальное несущее, не найденное ни GLM, ни Mercury:** Кл.3.1 — `execution-model.md` §8 «EVENT-путь идемпотентен благодаря CAS-локу» при удалённых D-40 CAS-колонках.

**Список disagree (обоснования — в таблицах):**
1. GLM 2.4 — `lockAtMostFor` не один на два TTL: это per-lock параметр ShedLock.
2. Mercury M1 (класс 1, `spawn.maxDepth`) — не дефект; подтверждённый несущий лимит, место в классе 3, не 1.
3. Mercury 2.1 — иммутабельность `params` не противоречит схеме; правило заявлено в API/agent-tools, в схеме — лишь нет дублирующей пометки.

---

## Fixes approval

> Проверка заявленных применений (1)–(13) по живым докам на 2026-09-17.
> **Итог: approve 12, reject 1.** Блокер один — регрессия в п. (1) (дублирующая строка «Рестарт-скан» в глоссарии); всё остальное заявленное — применено корректно.
> Замечание к нумерации: «CAS-лок» — это мой **Кл.3.1**, а не 3.2; «lockAtMostFor» — GLM 2.4 (я был `disagree`), а не мой Кл.3.1.

| # | Применено | Вердикт | Строка |
|---|---|---|---|
| 1 | Колонки fork/rewind/archived, инвариант-6, Fork/Rewind, «не архивна» в eligibility, note денормализаций, partial-индекс | **reject** | Substantive-часть ✓ (`data-model` 134–152; glossary 63/113; execution-model 32), но **внесена регрессия**: две строки **«Рестарт-скан»** (glossary 65 и 67) — редактирование не удалило старую; плюс в claimed-объём не попали `Экспорт` (66), «форки» (74), guard «(не архивна)» (execution-model 25), retention «архивных» (data-model 168) |
| 2 | AccessPolicy из architecture §3 и roadmap M1 («SSO-гейт + users») | approve | `architecture` §3 больше не содержит AccessPolicy; §1 согласован; `roadmap` M1 — «SSO-гейт по groups-claim + синхронизация users» |
| 3 | Билеты убраны из M1 | approve | `roadmap` M1 чист; нота (стр. 10) относит их к WebUI-фазе |
| 4 | lockAtMostFor: параметр джобы vs `harness.lock.session-ttl` | approve | execution-model 13 и 42 разведены корректно (это был спорный GLM-пункт; применение безвредно и полезно) |
| 5 | «CAS-лок» → ShedLock-лок | approve | execution-model 108: «EVENT-путь идемпотентен благодаря ShedLock-локу `sess-{id}`» (мой Кл.3.1) |
| 6 | «добивается recovery-спуском» → повторный stop | approve | glossary 76 и execution-model 94: «повторным `stop` (идемпотентен) — отдельного recovery-механизма нет» |
| 7 | PARTICIPATE из attach-CLI | approve | glossary 102: «провал в субагентов, дописывание им (атрибуция `[username]:`)»; VIEW тоже убран |
| 8 | SSO-строка | approve | glossary 96: «Пользователи синхронизируются; вход — SSO-гейт по `groups`-claim (D-41)» |
| 9 | `--folder` | approve | client-cli 10/11: флаги убраны |
| 10 | fork/rewind из M4 | approve | roadmap 24: «tree/inject/stop/compact, задачи» |
| 11 | Idempotency-Key/rate-limit из workflow-domain §7 | approve | workflow-domain 75: «идемпотентность по построению (`409`), TLS-only, логирование вызовов в `reason`» |
| 12 | params-иммутабельность в data-model | approve | data-model 95: «**иммутабельны после создания** (api §4.1)» |
| 13 | «удаление при чистке» | approve | execution-model 78: «удаление при чистке» |

### Блокер (требует правки перед закрытием)

- **B-1 (регрессия, п.1):** `glossary.md` содержит **две** записи «Рестарт-скан» — стр. 65 (с «у сессий без живого лока» и осиротевшими контейнерами) и стр. 67 (старая, «Иначе сессия висит вечно»). Слить в одну (оставить формулировку 65 и дописать «иначе сессия висит вечно»), удалить дубль.

### Осталось из моих находок (не заявлено, не исправлено)

- Кл.1.3 `glossary` 66 — **«Экспорт»** всё ещё живой термин (fork/rewind убраны, export — нет).
- Кл.1.4 `glossary` 74 — «субагенты, **форки**, любые паттерны».
- Кл.1.7 `glossary` 103 — WebUI «Второй клиент, **позже**» (Attach-CLI уже несёт «порядок гибкий (D-42)» — внутреннее противоречие §8 сохранилось).
- Кл.1.10 (частично) `data-model` 168 — retention «очистки **архивных** сессий».
- Кл.1.17 `client-cli` 27 — `POST /auth/ticket` в CLI (билеты — WebUI-фаза).
- Кл.1.18 `operations` 9 — маскирование «**билеты** (`?ticket=`)» в MVP-разделе.
- Кл.2.5/2.10 `client-cli` 3 (заголовок «Первый клиент (D-19…)») и `roadmap` 27 («M5 — WebUI (позже)») — порядок клиентов всё ещё фиксирован вопреки D-42.
- Кл.2.6 `roadmap` 24 («скачивание workspace-файлов» в M4) и `client-cli` 31 («REST уже есть») — endpoint живёт в WebUI-фазе (api §8).
- Кл.2.11 `data-model` 187 — ссылка «(D-04)» для истории вебхуков (устаревшее/нерелевантное решение).
- Из Cross-check: GLM 2.6 (`workspaceBinding(s)` без источника), GLM 2.9 («не терминальна» без поля), GLM 2.10 (`metaTools` §2b vs §4), GLM 2.5 (пометки superseded в ADR) — не тронуты.

---

## Fixes approval (re)

> Повторная проверка по 5 заявленным пунктам (2026-09-17). **Итог: approve 5 / reject 0.** Регрессия B-1 устранена.
>
> ⚠️ Заявление «резидуалы закрыты» — только частично: закрыты именно 5 ниже; из прошлого списка «Осталось» сохраняются `glossary` 101 (WebUI «Второй клиент, позже»), `client-cli` 3 (заголовок D-19), `operations` 9 (билеты), `roadmap` 24 («скачивание workspace-файлов»), `data-model` 187 («(D-04)»), а также GLM 2.5/2.6/2.9/2.10.

| # | Что проверялось | Вердикт | Строка |
|---|---|---|---|
| 1 | Дубль «Рестарт-скан» слит | approve | `glossary.md` 65 — одна строка: «…(иначе сессия висит вечно); осиротевшие контейнеры — удаление»; вторая (быв. 67) удалена |
| 2 | Строка «Экспорт» удалена | approve | `glossary.md` — термин отсутствует (проверены §4 и далее) |
| 3 | «форки» убраны из «Двухуровневой параллельности» | approve | `glossary.md` 72 — «субагенты и любые паттерны» |
| 4 | «(не архивна)» убрана из гвардов late-результатов | approve | `execution-model.md` 25 — «принимается только если задача в соответствующем состоянии» |
| 5 | Retention — «старых сессий» | approve | `data-model.md` 168 — «политика выгрузки/очистки **старых** сессий» |
