# Кросс-чек: `relay-connect-consent-confirm` — DeepSeek (reject) vs Mercury (approve)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-24.
Мой отчёт: `docs/temp/review/relay-connect-consent-confirm-deepseek.md` (reject).
Отчёт коллеги: `docs/temp/review/relay-connect-consent-confirm-mercury.md` (approve; decline-reason — «improvement, не баг»).

Оба отчёта совпадают по фактологии механизма (авто-коннект, pending-consent, починка `welcome.protocol`, диалоги, e2e). Расхождение одно: квалификация отсутствия причины отказа после decline. P2–F4 коллега не разбирал.

---

## A. Требует ли причину отказа *утверждённый* контракт? — **частично согласен**

**Согласен с коллегой по узкому факту:** базовый (main) контракт причину *клиентского* decline не требует.

- `docs/design/api-contracts.md` §5 (стр. 114-116, 122-124, 189-191, 205-208): описывает **серверные** отказы (`error {code,message}` + close 4409: `workspace-occupied`, `superseded`, `session-not-found`, `wrong-session-kind`, `duplicate-tool-name`) и `welcome/handshake`-close-коды. Клиентское согласие (consent) в §5 отсутствует — это клиентский UX из D-93.
- Базовый `openspec/specs/desktop-relay-client/spec.md:24` требует «соответствующие сообщения пользователю» только для **серверных** error-кодов; про decline — ничего. Спека «Безопасность локального исполнения» (стр. 108-120) описывает только факт запроса согласия, не исход отказа.
- Базовый `openspec/specs/desktop-chat/spec.md:74-81` («Активная сессия и релей-статус») знает три состояния: «не подключён» / «подключён (N инструментов)» / «сессия открыта в другом месте», и тоже не требует строки-причины.
- D-77 (аудит handshake) и D-93 (`confirmCommands=always`) об отображении decline-исхода молчат.

Так что формально требование «отказ согласования виден в строке релея» — **новелла delta-спеки этого change'а**.

**Но не согласен с выводом «improvement, не баг».** Delta-спека — это спека самого артефакта под ревью (её затем синкают в main). В ней требование сформулировано императивно и с явным сценарием:

- `openspec/changes/relay-connect-consent-confirm/specs/desktop-chat/spec.md:11`: «Причина последней неудачи регистрации (**отказ согласования**, `workspace-occupied` и пр.) SHALL быть видна в строке релея».
- Там же `:23-26` (Scenario «отказ в согласовании виден»): «THEN в строке релея видна причина, **а не безликое «подключён»**».
- `.../specs/desktop-relay-client/spec.md:25` (Scenario «первая регистрация на сессии»): «после «отклонить» — регистрация не отправляется и **причина видна в UI**».

Плюс change предъявляет это же как выполненное:
- `docs/design/web-desktop-client.md:48` (изменён этим change'ом): «…причина отказа регистрации — строкой».
- `openspec/changes/relay-connect-consent-confirm/tasks.md:15` (3.4): «`relayLabel` показывает `reason` при незарегистрированном состоянии».
- Отчёт разработчика (`docs/temp/relay-connect-consent-confirm-glm.md:25`) утверждает то же.

Итого: требование не «привнесено со стороны», оно **объявлено самим change'ом** и заявлено реализованным. Несоответствие имплементации собственному delta-спеку + собственным docs/tasks — дефект артефакта независимо от того, обязателен ли он был «до» change'а. Если владелец считает требование лишним — надо править delta-спеку/доки/tasks, а не одобрять спеку, противоречащую коду.

## B. Что фактически показывает UI при decline — **расхождение подтверждено**

Факт по коду (проверено заново):

1. `src/main/relay-client.ts:262-267`: при `!allowed` — `return { ok:false, message:'User declined local execution.' }`; **ни одного `emit('status', …)` нет** (ни в `requestRegistrationConsent` 641-645, ни в `resolveRegistrationConsent` 654-666).
2. Последний статус остаётся от `welcome` (`relay-client.ts:491-497`): `{connected:true, registered:false, phase:'connected'}` без `reason`.
3. `src/renderer/src/views/ChatView.vue:39-56`: ветка `if (s.connected) return 'подключён'` (стр. 50) срабатывает, т.к. `!s.registered && s.reason` (стр. 49) — falsy. Значит строка релея = **«подключён»**.
4. При этом `relayConnected` (стр. 58) = `registered` = false → кнопка «Подключить» (стр. 289).

То есть UI **противоречив** (метка «подключён», кнопка «Подключить») и **не показывает ни отказа, ни причины**. Это буквально тот случай, который delta-спека запрещает словами «а не безликое «подключён»» (desktop-chat:26). Даже базовая `desktop-chat:74-81` в незарегистрированном состоянии ждёт «не подключён», а не «подключён».

Mercury сам зафиксировал симптом (`relay-connect-consent-confirm-mercury.md:109`), но понизил до minor без проверки против delta-спеки и без учёта противоречия label/button. Классификация неверна: минимум **major** (нарушение двух delta-сценариев + ложное утверждение в tasks/docs), и точно не «не баг».

Тестовый аспект (коллега не отметил): `relay-client.test.ts:215-232` проверяет только исход `ok:false`; `relay-dialogs.test.ts:87-97` — только факт `confirmRegistration(...,false)`; e2e сценарий 1 только одобряет consent (`electron-smoke.spec.ts:138`). Ни один тест не проверяет строку статуса после decline → заявленный «страж» зелёный при сломанном поведении.

## C. F2–F4: коллега их не рассматривал

Mercury **не заметил** — в его отчёте нет пунктов по этим темам (не «принял», а пропустил).

- **F2 (STATE-индикатор), подтверждаю как minor.** `useRelay.ts:72-73` для `kind==='STATE'` молча выходит; `ChatView.vue:39-56` не учитывает `activeSession.kind`; при открытии STATE-сессии после FREE строка покажет «подключён (6 инструментов)» от чужой сессии. Delta `desktop-relay-client/spec.md:67-70` ждёт «релей доступен только для root-сессий». Оговорка: кнопка для STATE `disabled` (`ChatView.vue:286`), т.е. сценарий «пытается зарегистрироваться» напрямую не воспроизводится — поэтому minor, а не major.
- **F3 (параллельные регистрации), подтверждаю как minor/known.** `relay-client.ts:273-275` (`if (this.registering) return this.registering;`), `:642` (`consentResolvers.set` перезапись), `:643` (`openConsent` перезапись); `useRelay.ts:44,76-77` — глобальный `autoRequested`, не per-session. Смягчено модальным оверлеем (`RelayDialogs.vue:99-107`). Сам разработчик зафиксировал это (`relay-connect-consent-confirm-glm.md:44`). Mercury не разбирал.
- **F4 (smoke.md), подтверждаю как minor.** `web-desktop/docs/smoke.md` остался с «Running 1 test» и старым сценарием, тогда как `electron-smoke.spec.ts` теперь содержит 2 теста; синк доков сделан только в `docs/design/web-desktop-client.md`. Mercury доки не проверял.

## D. Что в отчёте Mercury корректно и мной принимается

- Механизм и контракт §5.1 (`welcome.protocol`) — совпадает (`mercury:13-20`, `70`).
- Диалоги показывают tool/args/basePath (`RelayDialogs.vue:72-75`) — совпадает.
- e2e действительно проверяет полный tool-цикл и оба диалога (approve/deny) — совпадает.
- Оценки открытых вопросов 1/2/3/5 разработчика (повторный consent, английские тексты, takeover, temp-каталоги) — не оспариваю.

## Итог кросс-чека

По узкому факту «утверждённый до change'а контракт причину decline не требует» — **согласен с Mercury**. По квалификации «improvement, не баг» — **не согласен**: требование объявлено delta-спекой данного change'а (`desktop-chat/spec.md:11,23-26`; `desktop-relay-client/spec.md:25`) и дублировано в `web-desktop-client.md:48` и `tasks.md:15`, а имплементация ему прямо противоречит и даёт противоречивый UI (метка «подключён» при незарегистрированном релее). Вердикт не меняю.

## ВЕРДИКТ по артефакту: `reject`

Блокирующая: отказ согласования не отображается (F1, major) — нарушены два delta-сценария + собственные docs/tasks change'а; guard-теста нет.
Желательные: F2 (STATE-индикатор), F3 (дедуп параллельных регистраций), F4 (smoke.md).
