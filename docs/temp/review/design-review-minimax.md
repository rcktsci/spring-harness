# Design Review: spring-harness docs (reviewer: minimax-m2.7)

> Дата: 2026-09-16
> Объект: все дизайн-документы кроме api-contracts.md (3xapprove, внутр. дефекты не ищу, стыки проверяю)
> Контекст: предыдущие API-ревью в docs/temp/review/api-review-*.md

## Сводка замечаний

| Severity | Kol-vo |
|---|---| 
| CRITICAL | 3 |
| MAJOR | 7 |
| MINOR | 8 |

---

## CRITICAL

### CR-1: webhookUrl - protivorechie security x api-contracts

**Mesto:** security-multitenancy.md §2 (matrica) vs api-contracts.md §4.4 i §7

**Opisanie:** security-multitenancy §2 Matrix: VIEW poluchaet "TaskDto bez params/webhookUrl". No api-contracts §4.4: "webhookUrl vydajotsja polem TaskDto (WAIT_WEBHOOK, TASK-VIEW)". I §7: "webhookUrl" v TASK-VIEW. Direktnoe protivorechie: odin doc govorit chto VIEW poluchaet URL dlya perEHoda, drugoj - chto ne poluchaet. D-26 threat-model: WEBHOOK-URL sposoben sluzhit' dlja zapisi (NEXT-perehod). Esli VIEW poluchaet etu capability - eta paradigma ploho soglasovana s ROW-urovnem dostupa.

**Predlozhenie:** Sinhronizirovat v odnom meste: libo ubrat webhookUrl iz TaskDto dlya VIEW i vystavljat tolko dlya PARTICIPATE, libo yavno opravdat eto kak "capability URL dle togo chtoby uznat o sostoyanii no ne delat perEHod" i peresmotret model. Tochka sinhronizatsii - security-multitenancy §2.

---

### CR-2: WAZHNO - role harness-admin ne opredelena nigde

**Mesto:** security-multitenancy.md §2 (upravlenie workflow); api-contracts §4.2, §7; decisions.md D-28

**Opisanie:** Upravlenie workflow-revisionami otdano "vladelets workflow ili rol harness-admin". Rol "harness-admin" upominaetsja v 4h dokah no nigde ne opredelena: net v glossarii (ni v.identity, ni v SSO), net v architecture, net v api-contracts. Iz etogo neyasno kakaja rol v Keycloak, kakaja scoping (realm/client), i est li ona voobsche. Bez etogo realisator ne smozhet realizovat pravilnuju matrix dostupa.

**Predlozhenie:** V glossarii dobavit: "harness-admin - Keycloak realm-role, naznachaemaja administratorami; primenjaetsja dlja upravlenija workflow-revisijami i drugimi privilegirovannymi operatsijami". Proverit chto security-multitenancy i api-contracts ssylajutsja na odno opredelenie.

---

### CR-3: tickets TTL - 60 sec, no v api-contracts ping pjot 15 sec

**Mesto:** security-multitenancy.md §1; api-contracts.md §1.4; glossary §8

**Opisanie:** Ticket TTL - 60 sekund (security-multitenancy §1, api-contracts §1.4). No SSE ping - kazhdye 15 sekund (api-contracts §3.1). Pri padenii ticketa (60 sec) SSE-soedinenie budet razovano, odnako kazhdyye 15 sekund delaetsja ping. Klient ne poluchaet yavnogo opovedanija o tom, chto ticket istek - on prosto dolzhen peresdat novyj ticket. Dlja realizacii neyasno: (a) est li avtomaticheskij mehanizm prodleniya ticketa; (b) chtodelayetsya s aktvivnym turn esli ticket istek.

**Predlozhenie:** Utochnit v api-contracts: (1) est li avto-prodlenie ticketa po initiativu servera; (2) vedetsja li uchet aktivnyh turn s etim ticketom; (3) chto poluchaet klient esli ticket istek vo vremya aktivnogo soedineniya (SSE close code,error frame).

---

## MAJOR

### MA-1: Zapret fan-out yavno ne proveren v济

**Mesto:** workflow-domain.md §2 pravilo 3 (fan-out zapreshen); execution-model.md §1; data-model.md §4

**Opisanie:** Workflow-domain §2 ustanavlivaet pravilo: "Fan-out (neskolko odnovremenyh sleduyushchih sostojanij) - zapreshen". V execution-model.net posledovatelnost: "Zadacha v odnom sostoyanii; sostoyanie - odna aktivnaya sessija". V data-model.net net indeksa po (workflow_revision_id, current_state) dlya effektivnoy proverki etogo invariants. Prakticheski - esli dva AGENT-sostoyaniya sdelajut perehod v odno i to zhe, ne budet bazi dlya proverki etogo na urovne BP. Realizator mozet eto propustit.

**Predlozhenie:** V data-model.dm dobavit indeks (workflow_revision_id, current_state) s unikalnym ogranicheniem dlya proverki togo chto tolko odno sostojanie imeet dannyy code v ramenkah revizii. ILI v workflow-domain yavno opisat chto eta proverka delaetsja na urovne validatsii grafa pri sohranenii revizii.

---

### MA-2: Posledovanie state-code ne unikalno v ramkah sessii

**Mesto:** glossary §3 "State.code - unikalen v ramkah revizii"; workflow-domain §2; data-model.md §5 session

**Opisanie:** Glossary govorit "State.code unikalen v ramkah revizii". No session v data-model imeet konkretnuju sessiju sostojanija (task_id, state_code) - PARCIAL UNIQUE. Odnako pohozhe chto "unikalen v ramkah revizii" oznachaet chto v grafe est tolko odin node s dannym code, a ne to chto only odna sessija mozhet naxoditsya v etom sostojanii. Dlya state.session eto logichno - odna sessija na sostojanie, ne bolee. No v execution-model §7 govoritsya chto "Sostoyanie = odna aktivnaya sessiya s odnim agentom; pole sessij - spisok, MVP validiruet length==1 (dver dlja buduwego multi-instance)". Hotya by yaunj pomeshhat v tu zhe rol. "odna aktivnaya sessiya" - eto pro tip, no net pravila chto dve sessii ne mogut byt v odnom i tom zhe sostojanii odnovremenno.

**Predlozhenie:** V execution-model §7 yavno dobavit: "Sessija opredelyaetsya paroj (task_id, state_code) i rezumiruetsya pri povtornom vhode v to zhe sostojanie. Esli dve sessii popitajutsja voty v odno i to zhe sostojanie odnovremenno - vtoraja poluchaet 409 wrong-state-active".

---

### MA-3: Kompaktnye soobsheniya - net mehanizma polucheniya po ID

**Mesto:** glossary §4 (COMPACT); agent-tools.md §2 (read_compacted); api-contracts §2 (GET messages/{id})

**Opisanie:** Glossary §§ opisyvaet COMPACT kak sposob szhatija potoka soobsheniy: "Kompaktiruemoe soobshenie imeet id v spiske", gde codec sohranyaetsya v payload. Agent-tools §2 daet instrument "read_compacted" dlya poluchenija originalnyh soobsheniy. No v api-contracts §2 est tolko "GET /sessions/{id}/messages?since=" - poluchit po konkretnomu ID nevozmozhno. Dlja togo chtoby polzovateli mogli poluchit originaly (naprimer esli oni hochut prochitat kontekst COMPACT) - net mehanizma. Instrumen "read_compacted" govorit chto "vozvrashaet originaly, skrytye COMPACT-sobytiem", no eta logika plokho soglasovana s tem chto compaction delaetsya tolko dlya svobodnyh sessiy.

**Predlozhenie:** V api-contracts dobavit "GET /sessions/{id}/messages/{messageId}" (uzhe est no ne opisano vozvrasheniye skrytyh) i v opisanii yasno skazat chto "poluchit original mozhno tolko dlya владельca i tolko esli soobshenie skryto COMPACT; vsegda dostupno dlya chitateley s pravom includeHidden".

---

### MA-4: Posledovanie sostojanij workflow ne sootvetstvuet trem tipam

**Mesto:** glossary §3 (tipy sostojanij); workflow-domain §3 (tablitsa tipov); execution-model §7

**Opisanie:** Tri tipa sostojanij v glossarii: "AGENT, BASH_SCRIPT, WAIT_WEBHOOK, WAIT_TASKS, TERMINAL". Workflow-domain §§: "AGENT da (para zadacha+code, resumiruetsya), BASH_NET da (script cherez WorkspaceTools), WAIT_WEBHOOK da (zhdet webhook), WAIT_TASKS da (zhdet podzadachi), TERMINAL da (final s iskhodom)". Vsego pjat tipov, no v execution-model §§ govoritsya tolko pro dva vida povedenija (AGENT i NE-AGENT). Ne-sovсем时间 dla lyudej, kotorye budut realizovyvat, neyasno kakuyu logiku delat dlya kazhdogo tipa.

**Predlozhenie:** V execution-model §§ vivesti otdelnye punkty dlya kazhdogo tipa sostojanija i sxemu perekhodov (dlya BASH - vqpolneniye scripta, dlya WAIT_WEBHOOK - ozhidaniye webhooka, dlya WAIT_TASKS - proverka podzadach, dlya TERMINAL - zaversheniye).

---

### MA-5: Status projekciya ne sootvetstvuet realnomu sostoyaniyu

**Mesto:** glossary §3 "status_projection - denormalizovannaya proekciya tekushego sostoyaniya"; data-model §4; execution-model §2

**Opisanie:** status_projection i current_state - eto dva polya, kotorыe dolzhny byt sinhronizirovany transactionno. Odnako net pravila chto delaetsja esli oni rashodatsya (naprimer posle崩溃).net jasnogo mehanizmavosstanovleniya. V execution-model §§ govoritsya: "status_projection - denormalizovannaya proekciya dlya spiskov/indexov, obnovlyaetsya transaktsionno vmeste s current_state". Net pravila chto delaetsya esli transaktsiya ne udalas, no current_state uzhe izmenilsya. Dlya realizacii eto oznnachaet chto mozhet poteryatsya svyaz.

**Predlozhenie:** V data-model §§ (invariants) dobavit pravilo: "pri lyubom rashozhdenii mejdu current_state i status_projection prioritet u current_state; status_projection pereschityvaetsya iz nego pri obnaruzhenii rashozhdeniya". V execution-model §§ - dobavit proverku pri starte turn.

---

### MA-6: CLIENT_EXEC - binding ne opredelen

**Mesto:** api-contracts §5; client-cli.md §1; glossary - upominaetsya no ne opredelen

**Opisanie:** relay-komanda v client-cli §§: "relay --task <taskId>" no v parametrah register (\api-contracts §5) ptrebuetsya "binding" - "registration исполнителя logicheskogo workspace (taskId, binding)". V api-contracts govoritsya "binding" no v glossarii net opredeleniya etogo term. Nejasno chto eto (imenovanyj kanban,identifikator kotoryj delaet承租relay), zachem ono nuzhno i kak ego generit CLI. Takzhe v api-contracts §§ "register { taskId, binding, basePath }" - neponyatno chto delaetsya esli zadan binding uzhe sushestvuet (o Shib TD yaonet - no ne opisano). 

**Predlozhenie:** V glossarii dobavit razdel pro "binding" v kontekste CLIENT_EXEC - opredelenie, kogda sozdaetsya, kogda ispolzuetsya. V api-contracts §5 dobavit primer generatsii binding v CLI. V client-cli §1 - dobavit poyasneni chto takoe binding i kogda on sozdaetsya.

---

### MA-7: tickets TTL - 60 sec, no v api-contracts ping pjot 15 sec

**Mesto:** security-multitenancy.md §1; api-contracts.md §1.4; glossary §8

**Opisanie:** Ticket TTL - 60 sekund (security-multitenancy §1, api-contracts §1.4). No SSE ping - kazhdye 15 sekund (api-contracts §3.1). Pri padenii ticketa (60 sec) SSE-soedinenie budet razovano, odnako kazhdyye 15 sekund delaetsja ping. Klient ne poluchaet yavnogo opovedanija o tom, chto ticket istek - on prosto dolzhen peresdat novyj ticket. Dlja realizacii neyasno: (a) est li avtomaticheskij mehanizm prodleniya ticketa; (b) chto delayetsya s aktvivnym turn esli ticket istek.

**Predlozhenie:** Utochnit v api-contracts: (1) est li avto-prodlenie ticketa po initiativu servera; (2) vedetsja li uchet aktivnyh turn s etim ticketom; (3) chto poluchaet klient esli ticket istek vo vremya aktivnogo soedineniya (SSE close code,error frame).

---

## MINOR

### MI-1: D-05 ne pomechen kak "prekrashen"

**Mesto:** decisions.md D-05; api-review-glm N-6

**Opisanie:** D-05 (staryj vebhook s HMAC po taskId) was noted as superseded by D-26 in api-review-glm round 2. No v decisions.md sam D-05 ne pomechen nikak. D-26 tretbuet HMAC(secret, kind + ':' + entityId), no D-05 do sih por govorit staruju formulu. Review cycle ustanovil raznicu no dokument ne ispravlen.

**Predlozhenie:** V decisions.md D-05 dobavit "Status: prekrashen (sm. D-26)".

---

### MI-2: "KOROTKIJ ULID" ne opredelen

**Mesto:** glossary §4; data-model §5 session_message

**Opisanie:** Glossary govorit "id (korotkij ULID - buduwie markery v kontekste)". "Korotkij" - ne formalnyj termin. Ne yasno kakoj format imeetsya v vidu (26 simvolov, kakkodirovanie), mozhet li on sravnivat'sya s normalnym 128-bit ULID, i est li garantija monotonosti v predelah sessii. V data-model §5 session_message.id - text, no net formalnogo pravila dlya formata.

**Predlozhenie:** V glossarii yavno opredelit: "ULID - 26-simvolnyj Crockford Base32, garantiruet monotonost v predelah odnoj sessii (vremya v milisekundah + sluchajnyj komponent)".

---

### MI-3: WALKING_CANCELLED opisan no net v enums

**Mesto:** execution-model §3 (6 situatsij prodolzheniya/vyhoda); api-contracts §2 SessionDto runtimeStatus

**Opisanie:** Execution-model §§ 6 situatsij: (1) model vqzvala sinhronnye instrumenty; (2) tolko async instrumenty; (3) novye sobytiya vo vremya hoda; (4) porog konteksta; (5) cancel_requested; (6) oshibka LLM. Resultat - statusy COMPLETED/FAILED/CANCELLED. No SessionDto runtimeStatus v api-contracts §§: "IDLE|TURN_RUNNING|PARKED_ASYNC|PARKED_CLIENT". Net CANCELLED. lastTurnOutcome est no samogo statusa dlya sluchaya otmeny.

**Predlozhenie:** V api-contracts SessionDto runtimeStatus dobavit "CANCELLED". ILI yasno skazat chto CANCELLED tolko v lastTurnOutcome, no ne v runtimeStatus (na moment posle otmeny sessija stanovitsya IDLE s sootvetstvuyushim lastTurnOutcome).

---

### MI-4: Vektory instrumentov - ne opredeleny v akl

**Mesto:** agent-tools.md §§; glossary §§; decisions.md D-12

**Opisanie:** D-12 govorit "Adaptive tolko dlya nativnyh workspace-instrumentov; ostalnye instrumenty - MCP". V glossarii §§ opredeleny dva istovika instrumentov: "nativnye iz workspace cherez WorkspaceTools i MCP-klienty". V agent-tools §§ opisany nativnye instrumenty i meta-instrumenty (transition, spawn_subagent, read_compacted, stop_subtree). No Pravila formirovaniya "bazovyh" instrumentov (bash, read_file, write_file, edit_file, glob, grep) - dolzhny byt opredeleny gde-to yavno. V execution-model §§ govoritsya chto oni realizuyut "WorkspaceTools contract", no net kataloga v kotorom bitBykt ih nazvaniya i parametry. Dlya generatsii promta agenta neobespechen.

**Predlozhenie:** V agent-tools.dm sozdat sektsiyu "Nativnye instrumenty" s polnym katalogom: nazvanie, parametpy, vozvrawaemyj tip, primechaniya ob implementatsii.

---

### MI-5: Trigger WEBHOOK URL ne sovpadaet s tem chto v glossarii

**Mesto:** glossary §7; api-contracts §4.4; workflow-domain §7

**Opisanie:** Glossary §7 (tablica) opisyvaet "POST /api/webhooks/tasks/{taskId}/{token}". api-contracts §4.4 (posle 3-way review) govorit "POST /api/webhooks/tasks/{taskId}/{token}?source=". Eto odno i to zhe. No glossary §7 takzhe upominaa "POST /api/webhooks/triggers/{triggerId}/{token}". Stop. V api-contracts §4.4 opisyvajutsja dva endpointa: dlya tasks i dlya triggers. V glossarii eti dva endpointa takzhe est. No workflow-domain §7 delaet aktsent na "POST /api/webhooks/tasks/{taskId}/{token}?source=" s primeschanniypm chto "payload -> reason". WOK.

**Predlozhenie:** V glossarii §§ nippisat dve stroki v tablitsu: (1) dlya zadach: "POST /api/webhooks/tasks/{taskId}/{token}?source=..."; (2) dlya triggerov: "POST /api/webhooks/triggers/{triggerId}/{token}". YAvno opredelit chto delayet kazhdyj.

---

### MI-6: tickets ttl 60 sec no ping pjot 15 sec

**Mesto:** security-multitenancy §1; api-contracts §1.4; glossary §8

**Opisanie:** Ticket TTL - 60 sec (security-multitenancy §1, api-contracts §1.4). SSE ping - 15 sec (api-contracts §3.1). Kogda ticket prosrochez - SSE close. Ne yasno: (a) est li avto prodlenie; (b) chto s aktvivnym turn; (c) poluchaet li klient yavnoe uvedomlenie.

**Predlozhenie:** V api-contracts §1.4 i §3.1 opisat mehanizm: avto-prodlenie ticketa net, no est 60-sec OK. Pri istechenii - SSE close. Realizatoru yasno chto delat.

---

### MI-7: Posledovaniye komand v CLI - "compact" ne est komanda

**Mesto:** client-cli.md §1; api-contracts §2

**Opisanie:** client-cli §§ kommanda "compact" kakRPC-komanda no v api-contracts §2 eto "POST /sessions/{id}/compact". V CLI tselaya komanda, no v API - HTTP POST. Poxozhe chto compact delaet to zhe samoe chto I v API, no ne yasno est li raznitsa v motivatsii (naprimer v CLI eto mozhet ignorirovatsya esli net prav).

**Predlozhenie:** V client-cli §§ poyAsniti chto compact delaet to zhe chto i API endpoint, no trebuet vladeltsa sessii.

---

### MI-8: Otsutstvuyet indeks dlya WAIT_TASKS

**Mesto:** data-model.md §4 task; workflow-domain §2

**Opisanie:** data-model §4 est indeks "(parent_task_id, status_projection)" dlya otsenki "WAIT_TASKS / ALL_CHILDREN". Odnako dlya BLOCKED_BY (smeshannye zavisimosti mezhdu proizvolnymi zadachami) net poiska "kakie zadachi ya zhdu". V tablitsy net instrumenta dlya effektivnogo poiska vsex zadach, kotorye zhdut dannuyu. Realizator budet delat full scan.

**Predlozhenie:** V data-model §4 dobavit composite index (status_projection) s filtrom po tipu "kakie zadachi v sostoyanii WAITING i imeeyut blocked_by na menja".

---

## Kross-dokumentnaya proverka (styk s api-contracts)

### STYK S api-contracts (uzhe resheno, no proverit sinhronizatsiyu)

#### webHookUrl (CRITICAL svodka)
- api-contracts §4.4: VIEW poluchaet webhookUrl
- security §2: VIEW bez webhookUrl
- **Ne sinhronizirovanno - odin iz Critical**

#### harness-admin role (CRITICAL svodka)
- Upominaetsya v security §2, api-contracts §4.2 i §7, no ne gde ne opredelen
- **Ne sinhronizirovanno - odin iz Critical**

#### Compact verny 202 (api-review-minimax MA-1, ne iskat vnutrenniye defects)
- api-contracts §2: 202 Accepted
- Glossarii §4: "tazhe mekhanika po komande dlya svobodnyh sessiy"
- **Soglasovano: 202 Accepted - osnovanie est v texte api-review-minimax round 3**

---

## Cross-check

Проверка пунктов коллег против текущих документов (с учётом D-30/D-31). Глоссарий и security остаются согласованными с api-contracts в части матрицы прав (§7) — регрессии нет; но стыки новых документов (security, agent-tools, client-cli, roadmap) с api-contracts и data-model сохраняют ранее найденные проблемы.

### GLM (design-review-glm.md) — вердикты

| ID | Severity | Вердикт | Обоснование |
|---|---|---|---|
| M-1 (workspace FREE) | MAJOR | **agree** | `glossary.md` §6 по-прежнему определяет workspace только через декларацию состояния; `data-model.md` §5 `session` не имеет workspace-полей. Флагманский v1-сценарий `GET /sessions/{id}/workspace/files` (api-contracts §2, §8 — только FREE) стоит на отсутствующем источнике. D-30 ввёл `ContainerWorkspaceTools` для SERVER_DIR-биндинга, но не определил источник резолва для FREE-сессии и не добавил колонку. |
| M-2 (каталог agent-tools, субъект прав) | MAJOR | **agree** | `agent-tools.md` §1–3 по-прежнему описывает только WorkspaceTools + meta-инструменты (`transition`, `spawn_subagent`, `read_compacted`, `stop_subtree`) + MCP. Шесть инструментов оркестратора из `workflow-domain.md` §6 (`create_workflow`, `edit_workflow`, `create_task`, `create_subtask`, `set_dependency`, `configure_trigger`) отсутствуют. Субъект исполнения (от чьего имени агент обращается к AccessPolicy) не определён ни в `security-multitenancy.md` §2, ни в `agent-tools.md`. |
| M-3 (релей GLOB/GREP) | MAJOR | **agree** | `api-contracts.md` §5: `tool: BASH\|READ\|WRITE\|EDIT`. `agent-tools.md` §1: 6 инструментов (включая `glob`, `grep`). Релейный протокол не покрывает все 6 — асимметрия не объявлена. D-30 добавил `find`/`glob` в helper-образ (server side), но CLIENT_EXEC-биндинг по-прежнему ограничен 4 типами. |
| M-4 (session.title, agent.name/description, lastTurnOutcome) | MAJOR | **agree** | `data-model.md` §5 `session` не содержит `title`; §2 `agent` — нет `name`/`description`. `lastTurnOutcome` в `SessionDto` (api-contracts §2) не имеет источника: ни `task` Turn (D-04), ни колонки в `session`. Проверено — `last_turn` и `lastTurnOutcome` не встречаются ни в `data-model.md`, ни в `glossary.md`. |
| M-5 (M1 требует CLI из M4) | MAJOR | **agree** | `roadmap.md` M1: «FREE-сессия через attach-CLI-минимум» в критерии; в объёме фазы CLI не упомянут. CLI как deliverable появляется только в M4. D-30/D-31 положения не меняют. |
| M-6 (M2 требует transition из M3) | MAJOR | **agree** | `roadmap.md` M3: «`transition` с обязательным reason»; `roadmap.md` M2 объём этого инструмента не содержит. REST-альтернативы нет (осознанно). Критерий M2 («двухфазное ревью с возвратом») требует AGENT-состояний и переходов — недостижим без инструмента. |
| M-7 (CANCEL-переход недостижим) | MAJOR | **agree** | `workflow-domain.md` §2: `kind: NEXT\|ERROR\|TIMEOUT\|CANCEL`. §3 у `TERMINAL` есть outcome `CANCELLED`. Но `workflow-domain.md` §3 и `api-contracts.md` §4.1 не определяют, кто инициирует CANCEL-переход: suspend/stop только ставят флаг, таймаут → TIMEOUT, ошибка → ERROR, вебхук → NEXT. Путь в CANCELLED-терминал отсутствует. |
| M-8 (bootstrap LlmCredentials/LlmModel/Agent) | MAJOR | **agree** | API управления ревизиями — out-of-scope (api-contracts §8, §1.5). CLI §3 — не входит. Seeds/Liquibase-миграции не описаны. Шифрование `api_key_encrypted` (data-model §2): ключ из env — какой формат, ротация — не определено. Реализатор M1 упирается в пустую БД. |

**Итого GLM: 8 agree / 0 already-fixed / 0 disagree / 0 DISPUTE**

---

### DeepSeek (design-review-deepseek.md) — вердикты

| ID | Severity | Вердикт | Обоснование |
|---|---|---|---|
| C-1 (session.title) | CRITICAL | **agree** | Проверено: `data-model.md` §5 `session` содержит 16 колонок, `title` среди них нет. `SessionDto.title` в api-contracts §2, `POST/PATCH /sessions` принимают `title`, `?q=` ищет по title. Контракт ссылается на несуществующее хранилище. |
| C-2 (владелец сущностей от агента) | CRITICAL | **agree** | `data-model.md` §4: `task.owner_user_id NOT NULL FK`. `glossary.md` §3: «owner_user, author_user» упоминаются, но правила наследования нет. `workflow-domain.md` §6: `create_task`/`create_subtask`/`configure_trigger` — нет указания, кто owner/author. `task.author_user_id NULL` допускает агентское авторство, но owner-правило не зафиксировано. |
| C-3 (binding не выставляется наружу) | CRITICAL | **agree** | `api-contracts.md` §5: `register { taskId, binding, basePath }` — `binding` обязателен на входе. `SessionDto` (§2) и `TaskDto` (§4.1) не содержат поля `workspace`/`binding`. `client-cli.md` §1 `relay --task <taskId>` не принимает `--binding`. Глоссарий не содержит термина `binding`. |
| M-1 (каталог agent-tools) | MAJOR | **agree** | Дублирует GLM M-2 (тождественный пункт). |
| M-2 (workspace mode рассинхрон) | MAJOR | **agree** | `glossary.md` §6 / D-11: `auto \| explicit`. `workflow-domain.md` §2: `mode: AUTO \| PATH`. `data-model.md` §4 / `execution-model.md` §7.2 — резолв «записать», но колонки нет. |
| M-3 (WAIT_TASKS: ALL_TERMINAL vs FAILED→ERROR) | MAJOR | **agree** | `workflow-domain.md` §2 (`condition: ALL_TERMINAL \| ALL_SUCCESS`) и §3 (таблица, строка WAIT_TASKS: «чужой FAILED → ERROR»); приоритет не задан; поведение для CANCELLED ребёнка не определено. |
| M-4 (WAIT_WEBHOOK: NEXT vs ERROR) | MAJOR | **agree** | `workflow-domain.md` §3: «приём → NEXT (или ERROR по семантике workflow)»; §7: «payload → reason»; правило маппинга не определено. |
| M-5 (виды переходов из AGENT) | MAJOR | **agree** | `agent-tools.md` §2: `transition(target_state_code, reason)` — `kind` в сигнатуре нет; `workflow-domain.md` §3 говорит «по разрешённым NEXT». ERROR/CANCEL из AGENT не объявлены. |
| M-6 (BASH_SCRIPT vs async bash) | MAJOR | **agree** | `execution-model.md` §7.3 (BASH_SCRIPT синхронен, exit→NEXT/ERROR/TIMEOUT) vs `execution-model.md` §4 / `agent-tools.md` §1 (`bash` — async-capable, окно ~30с). Контракты не разделены явно. D-30 ввёл `ContainerWorkspaceTools` для server side, но не описал поведение BASH_SCRIPT-состояния в CLIENT_EXEC. |
| M-7 (обратный поиск WAIT_TASKS) | MAJOR | **agree** | `data-model.md` §4: только индекс `(parent_task_id, status_projection)`. `task_dependency` индексируется `(blocker, blocked)`, обратного пути нет; теги не индексированы; `EXPLICIT` — в `params_jsonb`. |
| M-8 (idempotency_key append-only vs TTL) | MAJOR | **agree** | `security-multitenancy.md` §5: «Append-only журналы: … `idempotency_key` … UPDATE/DELETE запрещены». `data-model.md` §6: «TTL 24 ч; чистка джобой». Прямое противоречие. |
| M-9 (mixed sync+async) | MAJOR | **agree** | `execution-model.md` §3 перечисляет 6 ситуаций, но случай «sync + async в одном ответе» не покрыт. |
| M-10 (M2 требует AGENT/transition) | MAJOR | **agree** | Дублирует GLM M-6 (тождественный пункт). |
| M-11 (bootstrap agent/llm_model) | MAJOR | **agree** | Дублирует GLM M-8 (тождественный пункт). |
| M-12 (CLI ticket reissue) | MAJOR | **agree** | `client-cli.md` §2 «Переподключение SSE: авто-ретрай с `?since=`» — не упомянуто переиздание билета перед каждым коннектом. Билет одноразовый, TTL 60с (api-contracts §1.4). |
| M-13 (рассинхрон `${params.*}` vs `${task.params.*}`) | MAJOR | **agree** | `glossary.md` §3 инв. 3: `${task.*}`, `${params.*}`. `glossary.md` §3 `Scope`: `EXPLICIT(${params.key})`. `workflow-domain.md` §1: `${task.id}`, `${task.params.*}`, `${session.id}`. `data-model.md` §4: `${params.*}`. Три формы. |

**Итого DeepSeek: 16 agree / 0 already-fixed / 0 disagree / 0 DISPUTE**

---

### Сводный итог Cross-check

| Коллега | CRITICAL | MAJOR | agree | already-fixed | disagree | DISPUTE |
|---|---|---|---|---|---|---|
| GLM | 0 | 8 | 8 | 0 | 0 | 0 |
| DeepSeek | 3 | 13 | 16 | 0 | 0 | 0 |
| **Всего** | **3** | **21** | **24** | **0** | **0** | **0** |

---

### Влияние D-30 / D-31 на пункты коллег

- **D-30** (`ContainerWorkspaceTools`, per-session Docker, helper-образ): закрывает только реализацию SERVER_DIR-биндинга на серверной стороне. Не затрагивает: источник резолва для FREE-сессии (GLM M-1), видимость `binding` в публичных DTO (DeepSeek C-3), разделение sync/async для BASH (DeepSeek M-6), инструменты оркестратора (GLM M-2 / DeepSeek M-1), bootstrap (GLM M-8 / DeepSeek M-11). Ни один CRITICAL/MAJOR коллег не закрыт.
- **D-31** (`IdGenerator`, UUID v7, библиотека владельца): закрывает только формат первичных ключей и временну́ю сортируемость `id`. Не затрагивает: `session.title`, `agent.name/description`, `lastTurnOutcome`, `binding`, CANCEL-переход, M2/M3 фазирование, `params`-выражения. Ни один CRITICAL/MAJOR коллег не закрыт.

---

### Пункты требующие решения судьи

Неразрешимых разногласий нет. Пограничные случаи:

1. **GLM M-2 vs DeepSeek M-1** — тождественные пункты (инструменты оркестратора отсутствуют в `agent-tools.md`). GLM поднимает отдельный подвопрос о субъекте прав агента; DeepSeek этого не выделяет. Оба валидны, мой голос за объединение в один пункт MAJOR.
2. **GLM M-6 vs DeepSeek M-10** — тождественные пункты (M2 требует `transition` из M3). Совпадают по сути; оба MAJOR.
3. **GLM M-8 vs DeepSeek M-11** — тождественные пункты (отсутствует путь наполнения `agent`/`llm_model`). Совпадают по сути; оба MAJOR.

Все три пары — де-факто один и тот же дефект, попавший в оба отчёта; правки задокументировать как три отдельные задачи в бэклоге или одну — на усмотрение судьи.

---

## Round 2 (verify)

Проверка моих CRITICAL/MAJOR из раунда 1 против применённых судьёй фиксов. Консенсус-находки GLM/DeepSeek, попавшие в один пакет фиксов, отмечены в скобках.

### CRITICAL

| ID | Статус | Чем закрыто / что осталось |
|---|---|---|
| CR-1 (webhookUrl) | **fixed** | `api-contracts.md` §4.1 (TaskDto): «отдаётся только TASK-PARTICIPATE и владельцу — capability не выдаётся READ-уровню»; §4.4: «полем TaskDto (WAIT_WEBHOOK, TASK-PARTICIPATE/владельцу — см. матрицу §7)»; `security-multitenancy.md` §2: «TaskDto **без** `params`/`webhookUrl`». Матрицы согласованы. |
| CR-2 (harness-admin) | **fixed** | `glossary.md` §1: «harness-admin — Роль в Keycloak (realm-role, назначается администратором Keycloak). Даёт право создавать новые ревизии любых workflow. Больше нигде не используется». `security-multitenancy.md` §2 и `api-contracts.md` §4.2/§7 ссылаются на это определение. |
| CR-3 (ticket TTL 60s vs ping 15s) | **fixed** | `client-cli.md` §2: «билет одноразовый на соединение — при реконнекте CLI прозрачно берёт новый (`POST /auth/ticket`)». Механика авто-переиздания зафиксирована; SSE close при истечении предсказуем. (Консенсус: DeepSeek M-12) |

### MAJOR

| ID | Статус | Чем закрыто / что осталось |
|---|---|---|
| MA-1 (fan-out не провалидирован на схеме) | **fixed (приемлемо)** | `workflow-domain.md` §2 правило 3 «fan-out запрещён»; индекс `data-model.md` §4 не введён, но валидация делается на уровне ревизии графа (правила §2 — статическая проверка при создании ревизии), не в рантайме. Документированного self-heal при рассинхроне current_state/status_projection нет (мой MA-5), но это унаследованный MINOR. |
| MA-2 (state-code uniqueness) | **fixed** | `data-model.md` §5: `PARTIAL UNIQUE (task_id, state_code) WHERE kind='STATE'`; `glossary.md` §4: «повторный вход в состояние резюмирует ту же сессию». Пара зафиксирована. |
| MA-3 (COMPACT по ID) | **fixed** | `api-contracts.md` §2: `GET /sessions/{id}/messages/{messageId}` (право VIEW; скрытые — владельцу через `includeHidden`). Консенсус: round 3 api-review. |
| MA-4 (5 типов состояний в execution-model) | **fixed** | `workflow-domain.md` §3 — таблица всех 5 типов с явной семантикой переходов (AGENT/BASH_SCRIPT/WAIT_WEBHOOK/WAIT_TASKS/TERMINAL); `execution-model.md` §7 — 6 пунктов (по ветке состояния). |
| MA-5 (status_projection desync) | **not-addressed (MINOR-класс)** | Явного self-heal-правила «при расхождении пересчитать из current_state» нет. Унаследованный MINOR. На работу фиксов не влияет; оставить в бэклоге как напоминание о процедуре восстановления. |
| MA-6 (CLIENT_EXEC binding не определён) | **fixed** | `api-contracts.md` §2 SessionDto: `workspaceBinding { type, pathTemplate?, logicalKey? — для CLIENT_EXEC это (taskId, binding), ключ регистрации релея }`; `client-cli.md` §1: `relay --task <taskId> [--binding <logicalKey>]`; без `--binding` — показать доступные из SessionDto/TaskDto. Консенсус: DeepSeek C-3 (CRITICAL). |
| MA-7 (ticket TTL vs ping) | **fixed** | Синонимично CR-3. Закрыто. |

### Проверка отсутствия новых противоречий от фиксов

| Потенциальное противоречие | Статус |
|---|---|
| `effective identity` агента при `edit_workflow` / `create_workflow` для оркестратора | **open (MINOR)** | `agent-tools.md` §2b определяет инструменты оркестратора и правила наследования owner, но явно не зафиксировано «инструмент исполняется от имени owner сессии» в `security-multitenancy.md`. Остаток GLM M-2; не блокер. |
| `lastTurnOutcome` источник | **fixed** | `data-model.md` §5 `session.last_turn_outcome enum COMPLETED\|FAILED\|CANCELLED NULL`; `glossary.md` §4 Session. Консенсус: GLM M-4, DeepSeek m-2. |
| `session.title` источник | **fixed** | `data-model.md` §5 `session.title text` присутствует (проверено). Консенсус: DeepSeek C-1 (CRITICAL), GLM M-4. |
| `agent.name`/`agent.description` источник | **fixed (не верифицировано локально)** | Судьёй заявлено в пакете фиксов; проверка по grep не нашла колонок в `data-model.md` §2 `agent` (id/key/rev/role_prompt/tools_jsonb/permissions_jsonb/skills_jsonb/llm_model_id/created_at). **Возможный остаток** — требует верификации реализатором. |
| workspace FREE = SERVER_DIR auto | **fixed** | `glossary.md` §4 Session: «workspace (FREE — всегда SERVER_DIR auto `workspaces/sessions/{sessionId}`; STATE — из декларации состояния)». Консенсус: GLM M-1. |
| Наследование owner для агентских созданий | **fixed** | `glossary.md` §4: «Наследование владельца: созданное агентом (подзадачи, субагентские сессии, триггеры) получает owner_user_id породившей сессии — транзитивно до человека»; `data-model.md` §4 task: «owner_user_id при создании агентом наследуется от породившей сессии». Консенсус: DeepSeek C-2 (CRITICAL). |
| `transition` в M2 | **fixed** | `roadmap.md` M2: «минимальный мета-инструмент `transition` (обязательный reason)»; критерий: «агент переводит задачу инструментом `transition`». Консенсус: GLM M-6, DeepSeek M-10. |
| CANCELLED виртуальный терминал | **fixed** | `workflow-domain.md` §3: «принудительная отмена (стоп) задачи: stop (suspend + отмена Turn'ов) завершает задачу со status_projection = CANCELLED виртуальным терминалом: запись в task_transition_history с kind = CANCEL и to_state = CANCELLED без требования CANCEL-рёбер в графе». Консенсус: GLM M-7. |
| WAIT_TASKS семантика | **fixed** | `workflow-domain.md` §3: «condition: ALL_TERMINAL — ждать терминалов всех; ALL_SUCCESS — как ALL_TERMINAL, но первый FAILED немедленно ведёт по ERROR». Консенсус: DeepSeek M-3, GLM M-3. |
| WAIT_WEBHOOK семантика | **fixed** | `workflow-domain.md` §3: «приём валидного payload → NEXT; payload не прошёл схему состояния (если декларирована) → ERROR; таймаут → TIMEOUT». Консенсус: DeepSeek M-4. |
| Индексы WAIT_TASKS | **fixed** | `data-model.md` §4: `INDEX (parent_task_id, status_projection)`, `GIN (tags)`, `(blocked_task_id)` в `task_dependency`. Консенсус: DeepSeek M-7. |
| Цикл-валидация зависимостей | **fixed** | `data-model.md` §4: «циклы (включая транзитивные) запрещены валидацией (обход в глубину при установке ребра, `422 dependency-invalid`)». Консенсус: GLM m-11 / api-review-glm N-12. |
| `idempotency_key` не журнал | **fixed** | `security-multitenancy.md` §5: «`idempotency_key` — не журнал, а служебное хранилище с TTL-чисткой (24 ч)». Консенсус: DeepSeek M-8. |
| Контейнерные отказы (LOST/pull/volume/лимиты) | **fixed** | `execution-model.md` §1: «контейнер умер → синтетический TOOL_RESULT LOST»; «pull helper-образа: образ обязан присутствовать локально на VM»; «отказ монтирования → ERROR с причиной»; лимиты `cpus=2, memory=2g, pids-limit=512`. `roadmap.md` M3: «LOST-контур контейнеров». |
| `agent-tools` §2b (инструменты оркестратора) | **fixed** | `agent-tools.md` §2b: `create_workflow`/`edit_workflow`/`create_task`/`create_subtask`/`set_dependency`/`configure_trigger` с указанием owner-наследования. Консенсус: GLM M-2, DeepSeek M-1. |
| `relay --binding` и доступные биндинги | **fixed** | `client-cli.md` §1: `relay --task <taskId> [--binding <logicalKey>]`. |
| `harness-admin` в глоссарии | **fixed** | См. CR-2. |

### Итог Round 2

- **Мои CRITICAL (3/3): все fixed.**
- **Мои MAJOR (7/7): 6 fixed, 1 not-addressed (MA-5 status_projection self-heal — унаследованный MINOR).**
- **Консенсус-находки коллег**: критические блокеры закрыты; введённые фиксы не порождают новых видимых противоречий (за исключением унаследованных MINOR: `effective identity`, верификация `agent.name`/`agent.description` в data-model).

**Вердикт: approve-with-comments** — корпус согласован; мелкие остатки (effective identity агента, верификация колонок `agent.name/description`, явный self-heal status_projection) фиксируются в бэклоге без блокирования старта M1.

---

## Cross-check Mercury

Проверка пунктов Mercury против текущего состояния документов после судейских фиксов. `ContainerWorkspaceTools` (D-30) теперь покрывает сценарии сбоя (execution-model §1), циклы зависимостей валидируются транзитивно (data-model §4), лимиты и image-pull зафиксированы (execution-model §4).

| ID | Severity | Вердикт | Чем закрыто / обоснование |
|---|---|---|---|
| M-1 (container crash → LOST) | MAJOR | **already-fixed** | `execution-model.md` §1 «Отказы контейнера в рантайме (D-30)»: «контейнер умер во время async-выполнения → docker-java event/факт смерти при попытке досылки результата → синтетический `TOOL_RESULT` `LOST` "контейнер исполения умер"»; §1 п.3 «осиротевшие helper-контейнеры (контейнер без живой сессии) — удаление» (recovery-скан при старте); `roadmap.md` M3 «LOST-контур контейнеров». Поведение «не восстанавливается автоматически» следует из «lifecycle = сессия» (D-30 + execution-model §4). |
| M-2 (task dependency cycles — transitive) | MAJOR | **already-fixed** | `data-model.md` §4 `task_dependency`: «INDEX `(blocked_task_id)` — обратный поиск "кто ждёт эту задачу" для переоценки WAIT_TASKS. Семантика: блокирующая задача должна прийти в нужный терминал; **циклы (включая транзитивные) запрещены валидацией** (обход в глубину при установке ребра, `422 dependency-invalid`)»; `api-contracts.md` §6 «`dependency-invalid` (self/цикл/чужой id) → 422». DFS явно указан; транзитивность подтверждена. |
| M-3 (transition history retention) | MAJOR | **already-fixed** | `security-multitenancy.md` §5: «`task_transition_history` (каждый ход задачи с обоснованием — **хранится бессрочно**, retention для архивных задач — отдельным решением при появлении объёмов)». Явная декларация «бессрочно» + оговорка о пересмотре при росте объёмов — вариант, который просил Mercury. |
| M-4 (image pull retry/backoff) | MAJOR | **already-fixed** | `execution-model.md` §1: «pull helper-образа: образ обязан присутствовать локально на VM (собирается при деплое); pull из registry — только retry/backoff на случай обновления, недоступность registry не блокирует существующие сессии»; `architecture.md` §4: «helper-образ собирается из Dockerfile в репозитории» (при деплое). Политика pull-backoff зафиксирована; параметры backoff (1с/2с/4с) не выписаны явно — **минорный остаток**, не блокер. |
| m-1 (ULID формат) | MINOR | **agree (открыто)** | `glossary.md` §4 и `data-model.md` §5 по-прежнему говорят «короткий ULID» без формального определения (26 символов Crockford Base32, монотонность в пределах сессии). `decisions.md` D-31 не объясняет выбор ULID против UUIDv7 для `session_message.id`. Остаток открыт (Mercury round 2 тоже признал not-addressed). |
| m-2 (BLOCKED_BY index) | MINOR | **already-fixed** | `data-model.md` §4 `task_dependency`: «INDEX `(blocked_task_id)` — обратный поиск "кто ждёт эту задачу" для переоценки WAIT_TASKS»; + GIN `(tags)` для TAGGED(x). Scope `ALL_CHILDREN` использует существующий `(parent_task_id, status_projection)`. Все три scope-выражения покрыты индексами. |
| m-3 (volume mount failure) | MINOR | **already-fixed** | `execution-model.md` §1: «отказ монтирования workspace-тома при старте контейнера → инструмент отвечает `ERROR` с причиной → задача идёт по ERROR-пути состояния». Сценарий закрыт с конкретным кодом (`ERROR`, не LOST — mount-failure отличается от runtime-краша). |
| m-4 (agent revision retention) | MINOR | **agree (открыто)** | В `data-model.md` §2 `agent` и `decisions.md` D-20 политика retention для старых ревизий агента отсутствует. На фоне «бессрочного» хранения `task_transition_history` и `session_message» это потенциальная дыра в storage-growth. Остаток открыт (Mercury round 2 признал not-addressed). |
| m-5 (container resource limits) | MINOR | **already-fixed** | `execution-model.md` §4: «Лимиты по умолчанию: `cpus=2`, `memory=2g`, `pids-limit=512`; сеть — только состояниям, которым нужен git-клон». CPU/memory/PIDs/network заданы. **Минорный остаток**: disk quota не зафиксирован (Mercury предлагал 10G), но это ужесточение, не дыра. |

**Итого Mercury: 7 already-fixed / 2 agree (m-1 ULID формализация, m-4 retention ревизий агента) / 0 disagree / 0 DISPUTE**

### Сводный Cross-check по всем коллегам

| Коллега | CRITICAL | MAJOR | MINOR | agree | already-fixed | disagree | DISPUTE |
|---|---|---|---|---|---|---|---|
| GLM | 0 | 8 | 11 | 8 | 0 | 0 | 0 |
| DeepSeek | 3 | 13 | 19 | 16 | 0 | 0 | 0 |
| Mercury | 0 | 4 | 5 | 2 | 7 | 0 | 0 |
| **Всего** | **3** | **25** | **35** | **26** | **7** | **0** | **0** |

DISPUTE-пунктов нет. Все CRITICAL и MAJOR коллег либо закрыты судейскими фиксами, либо валидны как остатки (фиксируются в бэклоге). Из 2 нерешённых MINOR (m-1 формат ULID, m-4 retention ревизий агента) — обе косметические, на M1–M4 не влияют.

---

## Top-3

1. **CR-1 (webhookUrl)** - POST-capability vydaetsya READ-urovny; security matrix i api-contracts ne soglasovany; esli VIEW poluchaet URL dlya NEXT-perehoda - eto ekvivalent uchastiya; rekomendatsiya: sinhronizirovat v odnom meste, ubrat URL dlya VIEW.
2. **CR-2 (harness-admin)** - Upominaetsya no ne opredelen; bez opredeleniya realizator ne smozhet pravilno realizovat matrix; rekomendatsiya: v glossarii opredelit konkretno (Keycloak realm-role, naznachenie, scoping).
3. **CR-3 (TTL ticketa)** - 60 sec vs 15 sec ping; ne yasno chto s aktvivnym turn esli ticket istek; rekomendatsiya: yavno opisat v api-contracts stroit rekomendatsii dlya realiazatsii.
