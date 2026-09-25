# Ре-аппрув ревью: change `relay-connect-consent-confirm`

Рецензент: субагент-ревьюер (DeepSeek-V4.1-Flash). Дата: 2026-09-24.
Вход: незакоммиченное рабочее дерево (HEAD = `df5448e`); change `openspec/changes/relay-connect-consent-confirm/`; отчёт разработчика `docs/temp/relay-connect-consent-confirm-glm.md` (§7 — фиксы F1–F4).
Мой исходный вердикт: `reject` (F1 major — отказ согласования не виден; F2 STATE-индикатор; F3 параллельные регистрации; F4 устаревший `smoke.md`).
Метод: чтение текущих файлов кода/спеч/тестов/e2e и сверка с `docs/design/api-contracts.md` §5, `openspec/specs/desktop-relay-client/`, `openspec/specs/desktop-chat/`, D-77/D-88/D-91/D-93. Сборка/тесты/docker не запускались; заявления разработчика проверял по коду.

## F1 (major): отказ согласования виден пользователю — ЗАКРЫТО

- `web-desktop/src/main/relay-client.ts:284-294`: при `!allowed` из `register()` эмитится `status` `{connected:true, registered:false, phase:'connected', sessionId, basePath, code:'consent-declined', reason:CONSENT_DECLINED_REASON}` (константа `:67`). Ветка срабатывает в обоих путях согласования — и через резолвер, и через раннее решение `pendingConsent` (оба возвращают `false` из `requestRegistrationConsent`).
- Текст метки вынесен в `src/renderer/src/lib/relay-label.ts`: `if (status.reason) return status.reason;` (`:15`); безликая ветка `if (s.connected) return 'подключён'` **удалена** — «подключён» отдаётся только при `registered` (`:11-14`). `ChatView.vue:39-41` вызывает `relayLabelOf(relay.status.value, activeSession.value?.kind)`; `relayConnected = registered` (`:43`) → после decline кнопка = «Подключить».
- Guard-тесты ловят регресс по существу: `tests/unit/relay-client.test.ts:234-250` (сам факт/состав статуса: code, registered=false, phase='connected', reason содержит 'declined'); `tests/unit/relay-label.test.ts:21-29` («никогда не «подключён» для незарегистрированного»: decline → reason, connected-без-reason → «релей: ожидание регистрации…», disconnected → «не подключён»); e2e `tests/e2e/electron-smoke.spec.ts:189-198` (deny → `relay-status` содержит `declined`, toggle = «Подключить» → повторное подключение → снова consent → approve → «подключён (6 инструментов)»).
- **Ручное переподключение после отказа не сломано:** после decline `sendRegister` не вызывался, `registering`/`registeringSession` = null, `consentResolvers[A]` удалён, `openConsent` = null (`:284-294`, `:696-703`). Повторный `ensureConnected(A)` проходит гейты (`useRelay.ts:72-86`) и заново доводит регистрацию до `registered` (покрыто e2e-шагом).

## F2 (minor): STATE-индикатор — ЗАКРЫТО

- `relay-label.ts:4-6`: `sessionKind === 'STATE'` → «релей доступен только для root-сессий» **до** всех остальных ветвей, поэтому перекрывает протухший статус предыдущей FREE-сессии (в т.ч. `registered`). `ChatView.vue` передаёт `activeSession.kind`.
- Guard: `relay-label.test.ts:37-42` — индикатор поверх зарегистрированного статуса и поверх decline.
- Delta `specs/desktop-chat/spec.md:11` + новый `Scenario STATE-сессия показывает индикатор` (`:28-31`) совпадают с кодом. Замечание закрыто.

## F3 (minor): параллельные регистрации — ЗАКРЫТО

- `relay-client.ts:250-259`: до `mkdir`/`connect`/consent — `busyRegisteringOtherThan(sessionId)` (`:301-309`: in-flight register другой сессии **или** pending-consent другой сессии) → `{ok:false, code:'register-in-progress'}` без побочных эффектов; `consentResolvers.has(sessionId)` → `{ok:false, code:'consent-pending'}` (без перезаписи резолвера/`openConsent`).
- `sendRegister:311-320` дедуплицирует только ту же сессию; `registeringSession` сбрасывается во всех исходах (`:336`, `:345`, `:361`) и в `disconnect()` (`:216`).
- Renderer: `useRelay.ts:44,76-85` — per-session `Set<string> autoInFlight` вместо одиночного флага (разные сессии не блокируют друг друга).
- Guard: `relay-client.test.ts:252-270` (B отклонён `register-in-progress`, согласование A не тронуто, A завершается ok), `:272-288` (duplicate same-session → `consent-pending`), `use-relay.test.ts:110-123` (две сессии независимо). Delta `specs/desktop-relay-client/spec.md:45,57-60` описывает ровно эту политику.

## F4 (minor): smoke.md — ЗАКРЫТО

- `web-desktop/docs/smoke.md`: секция 1 переписана под фактический e2e — 2 сценария пошагово (включая decline-шаг, повторное подключение и подмену `dialog.showSaveDialog`), ожидаемый вывод «Running 2 tests … 2 passed», реальные селекторы (`li.session-item`, `data-testid`); ручной smoke (п.5) дополнен авто-подключением, consent/confirm-диалогами, STATE-индикатором. Согласуется с `electron-smoke.spec.ts`.

## Контракт: расхождений не внесено

- **api-contracts §5**: серверные фреймы и close-коды не менялись; `consent-declined`/`register-in-progress`/`consent-pending` — клиентские состояния (D-93), на wire не влияют. Серверные отказы (`workspace-occupied`, `superseded`, `session-not-found`, `wrong-session-kind`, `duplicate-tool-name`) по-прежнему показываются через `status.reason` (`relay-label.ts:15`); `superseded` → «сессия открыта в другом месте» (`:8-10`).
- **desktop-relay-client**: MODIFIED-требование дополнено дедупликацией, политикой параллельных регистраций и эмитом статуса при decline (`spec.md:45`); ADDED pending-consent/UI-согласования/UI-подтверждения остаются выполненными (`relay-client.ts:687-703`, `RelayDialogs.vue`, `ipc-contract.ts:71`/`preload/index.ts:76-77`/`main/index.ts:387`).
- **desktop-chat**: MODIFIED-требование (`spec.md:11`) — «подключён» только для registered, причина/фаза иначе, STATE-индикатор; реализовано `relay-label.ts`.
- **D-77** (аудит register/disconnect) не тронут; **D-88** (локальное исполнение без path-guard, basePath виден) сохранён; **D-91** (main владеет сетью/секретами, renderer — через типизированный IPC) сохранён, новый канал типизирован; **D-93** (`confirmCommands=always`, per-session consent + confirm-гейты) сохранён.
- Логика метки прочёсывалась по комбинациям `registered × kind × phase × reason × code`: ложного «подключён» для незарегистрированного нет ни в одной ветке; `fatal` с reason (protocol-mismatch) показывает reason, `fatal` без reason → «не подключён».

## Два замечания без severity из прошлого отчёта — НЕ БЛОКЕРЫ

1. `respondConsent`/`respondToolConfirm` (`useRelay.ts:87-98`) очищают prompt до `await` IPC. Обработчики main (`main/index.ts:382-391`) синхронные и не бросают; reject возможен лишь при разрушении renderer/main, когда диалог неактуален. Известный крайний случай, аппрув не блокирует.
2. Гигиена временных каталогов e2e (`%TEMP%\harness-e2e-*`, `~/harness-workspaces/<id>`) — влияет только на прогонную машину/CI, не на прод. Не блокер.

## Остаточные наблюдения (не блокеры)

- `relay-client.ts` `disconnect()` (`:207-217`) сбрасывает `registeringSession`, но не `registering`/`consentResolvers`/`openConsent`. Узкий случай «Отключить во время in-flight register» оставляет `registering` живым до таймаута `relayRegisterTimeoutMs` (~10 c), и `busyRegisteringOtherThan` в этом окне отвергает повторный register. По UI практически недостижимо (consent-оверлей `.overlay{inset:0;z-index:100}` блокирует кнопку «Отключить»; после decline register не запускался). Пожелание: чистить `registering`/`consentResolvers` в `disconnect()` — отдельной косметикой.
- `relay-label.test.ts` внесён в exclude `tsconfig.node.json:62`, хотя это чистая функция без Vue/рендера → тест не проходит typecheck (mirror существующего паттерна для renderer-зависимых тестов; сам `relay-label.ts` типизируется в `tsconfig.web`). Мелкая гигиена.

## Итог

F1–F4 закрыты по существу: код эмитит и отображает причину отказа, устраняет противоречие метки/кнопки, пинит STATE-индикатор, вводит явную политику параллельных регистраций и синхронизирует smoke.md; спеки (delta desktop-relay-client/desktop-chat) и `web-desktop-client.md` приведены в соответствие. Guard-тесты (unit + e2e decline с ручным переподключением) действительно ловят регресс; расхождений с §5 / specs / D-77/D-88/D-91/D-93 не появилось; два прошлых замечания без severity — не блокеры. Новых блокеров не выявлено.

## ВЕРДИКТ: `approve`
