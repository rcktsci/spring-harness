# Ревью рабочего дерева — change `relay-connect-consent-confirm` (Web Desktop)

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-24.
Линза: корректность main-процесса и жизненный цикл релея, соответствие контракту §5 / D-77/D-84/D-88/D-91/D-93.
Метод: чтение рабочего дерева (`git status`/`git diff`), новых файлов, openspec-артефактов, отчёта разработчика (`docs/temp/relay-connect-consent-confirm-glm.md`), серверного контракта. Сборка/тесты/docker не запускались.

Объём незакоммиченного: 12 изменённых файлов (`src/main`, `src/preload`, `src/shared`, 3× renderer, e2e/unit, tsconfig), новые `RelayDialogs.vue`, `use-relay.test.ts`, `relay-dialogs.test.ts`, каталог change'а.

## 0. Что проверено и подтверждено

- **Диагноз «релей не подключался из-за отсутствия авто-коннекта» подтверждён кодом:** до правки `useRelay.connect/register` вызывались только из `toggleRelay` (`ChatView.vue`), а `relayActiveSessionId` сохранялся лишь каналом `RELAY_REGISTER` (`src/main/index.ts:340-360`), который renderer не дергал. Авто-коннект добавлен `ChatView.vue:70-74` → `useRelay.ensureConnected` (`useRelay.ts:72-86`), который идёт через `RELAY_REGISTER` (сохраняющий сессию) — соответствует MODIFIED-требованию delta-спеки.
- **PENDING-CONSENT:** `openConsent` заполняется в `requestRegistrationConsent` (`relay-client.ts:641-645`), отдаётся `getPendingConsent()` (`649-651`), очищается в `resolveRegistrationConsent` при совпадении sessionId (`654-657`); IPC `relay:pending-consent` (`ipc-contract.ts:71`, `main/index.ts:387`, `preload/index.ts:76-77`), re-fetch при монтировании (`RelayDialogs.vue:18-22`). Соответствует ADDED-требованию.
- **Баг stub-сервера реальный и починен корректно:** `parseRelayFrame` для `welcome` требует `payload['protocol'] === 1` (`ws-frames.ts:172-179`), прежний stub слал `protocolVersion` → handshake-таймаут. Новый stub шлёт `{type:'welcome', protocol:1}` (`stub-server.ts:646-653`) — совпадает с api-contracts §5.1 (`hello { protocol: 1 }`/`welcome { protocol: 1 }`).
- **Конкурентность `tool.confirm` не является дефектом:** сервер исполняет синхронные вызовы последовательно (`AgentTurnEngine.java:257-268`), а `clientToolBridge.invoke` блокируется на `future.get(toolCallTimeout)` (`ClientToolRegistry.java:188-193`), т.е. в один момент на сессию летит один `tool.call`. Одиночный слот `toolConfirm` достаточен.
- **Hardcoded-чисел в новом runtime-коде нет** (числа — только CSS e2e/таймауты из конфига). Новые комментарии есть, но соседний код (`relay-client.ts`, `useChat.ts`, `ChatFeed.vue`) столь же обильно закомментирован — стилю соответствует, нарушением не считаю.

## 1. Находки

### F1 (MAJOR, спека-нарушение). После «Отклонить» в consent строка релея показывает «подключён», причина отказа теряется
- `src/main/relay-client.ts:262-267`: при отказе (`!allowed`) `register()` просто возвращает `{ok:false, message:'User declined local execution.'}` и **не эмитит `status`**. Последний статус остаётся от `welcome`: `{connected:true, registered:false, phase:'connected'}` (`relay-client.ts:491-497`).
- `src/renderer/src/views/ChatView.vue:45-55`: `relayLabel` при `connected:true, registered:false, reason===undefined` доходит до `if (s.connected) return 'подключён'` (стр. 50) → строка релея показывает **«подключён»**, хотя `relayConnected=false` (стр. 58) и кнопка показывает «Подключить» (стр. 289). Противоречивый UI + потерянная причина.
- Прямо противоречит:
  - delta `specs/desktop-chat/spec.md:23-26` (Scenario «отказ в согласовании виден»: «…в строке релея видна причина, **а не безликое «подключён»**»), требование там же стр. 11;
  - delta `specs/desktop-relay-client/spec.md:25` («после «отклонить» — регистрация не отправляется и **причина видна в UI**»);
  - `docs/design/web-desktop-client.md:48` («причина отказа регистрации — строкой»).
- Тесты это не ловят: `relay-client.test.ts:215-232` проверяет только исход `ok:false/message`, но не `status`; `relay-dialogs.test.ts:87-97` проверяет факт вызова `confirmRegistration(...,false)` и закрытие диалога, но не строку статуса. e2e-сценарий 1 (`electron-smoke.spec.ts:138`) только одобряет consent, сценарий 2 начинает уже после регистрации. Т.е. «страж» подтверждает зелёное, пока фича сломана.

**Требование к исправлению:** в `relay-client.ts` при `!allowed` эмитить `status` с `registered:false`, `phase:'connected'` и `reason`/`code` (напр. `consent-declined`), чтобы `relayLabel` показал причину; убрать «слепой» `if (s.connected) return 'подключён'` для незарегистрированного состояния (или переставить проверку `reason`/`registered` так, чтобы «подключён» не показывался при `registered:false`). Добавить guard: unit на статус при decline + e2e-шаг «отклонить consent → в строке релея причина, регистрация не выполнена».

### F2 (MINOR). STATE-сессия: индикатор «релей доступен только для root» не показывается, статус врёт от прошлой FREE-сессии
`useRelay.ensureConnected` (`useRelay.ts:72-86`) для `kind==='STATE'` молча выходит, ничего не эмитя; `relayLabel` (`ChatView.vue:39-56`) не учитывает `activeSession.kind`. Если до этого была зарегистрирована FREE-сессия, то при открытии STATE-сессии строка релея продолжит показывать «подключён (6 инструментов)» от чужой сессии. Delta `specs/desktop-relay-client/spec.md:67-70` (Scenario STATE-сессия) и базовый `openspec/specs/desktop-relay-client/spec.md:41-44,99-106` требуют сообщения «релей доступен только для root-сессий». Дефект частично унаследован, но change затронул именно этот путь.

**Требование:** при STATE-сессии показывать явный индикатор (и/или сбрасывать/не показывать статус прежней сессии).

### F3 (MINOR). Ограничение параллельных регистраций в main (подтверждено разработчиком)
`relay-client.ts:273-275` (`if (this.registering) return this.registering;` — возвращает promise прежней сессии), `642` (`consentResolvers.set` перезаписывает резолвер той же сессии), `643` (`openConsent` перезаписывается) + renderer-флаг `autoRequested` глобальный, не по сессии (`useRelay.ts:44,76-77`). При быстром переключении двух сессий, пока consent/регистрация первой не завершены, вызов второй может потерять первую регистрацию (осиротевший promise/резолвер). Практический риск снижен модальным оверлеем `RelayDialogs` (`.overlay{inset:0;z-index:100}`, `RelayDialogs.vue:99-107`) — UI заблокирован до ответа. Приемлемо как задокументированное ограничение, но зафиксировать стоит явно.

**Требование (желательно):** сделать `autoRequested`/дедуп привязанным к сессии, а в main — не переиспользовать `registering` для другой сессии (или отклонять параллельный register).

### F4 (MINOR). Устаревшая документация smoke
`web-desktop/docs/smoke.md` после переписывания e2e осталась прежней: ожидаемый вывод «Running 1 test», старое описание сценария, старые селекторы. `electron-smoke.spec.ts` теперь содержит 2 теста. tasks 4.4/3.2 ничего про smoke.md не говорят; в `docs/design/web-desktop-client.md:75-86` синк сделан, а smoke.md (ключевой документ по AGENTS.md) — нет.

**Требование:** обновить smoke.md (2 сценария, реальные селекторы `li.session-item`/`data-testid`, save-as с записью файла).

## 2. Замечания без severity (не блокируют)

- `respondConsent`/`respondToolConfirm` (`useRelay.ts:87-98`) очищают локальное состояние до `await` IPC; при исключении IPC диалог исчезает, а резолвер в main остаётся висеть. Крайний случай.
- `ensureConnected` опирается на `status.value`, который на старте подгружается асинхронно (`useRelay.ts:54-56`); если renderer вызовет ensureConnected до прихода статуса, возможна повторная регистрация (перекрывается дедупом по сессии/`autoRequested`).
- e2e оставляет каталоги `~/harness-workspaces/<uuid>` (basePath из auto-connect) — гигиена, отмечена разработчиком для `%TEMP%`, но не для домашнего каталога.

## 3. Итог

Основная механика (авто-подключение через сохраняющий канал, глобальные модальные диалоги, pending-consent re-fetch, починенный `welcome`-кадр stub'а, сериализация серверных tool-вызовов) корректна и соответствует §5. Но одна MODIFIED-спека и её сценарий не выполнены: **отказ согласования не отображается** (строка релея показывает «подключён»), и это не покрыто тестами, хотя change объявляет e2e «стражем». Это не позволяет считать требования выполненными на 100%.

## ВЕРДИКТ: `reject`

Блокирующая: F1 (major) — эмитить `status` с причиной при decline + корректный `relayLabel` + guard-тест.
Желательные: F2, F3, F4.
