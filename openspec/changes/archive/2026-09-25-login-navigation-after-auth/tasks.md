## 1. РџРѕРґРіРѕС‚РѕРІРєР°

- [x] 1.1 Change СЃРѕР·РґР°РЅ РґРѕ РєРѕРґР° (proposal/design/tasks + delta-СЃРїРµРєР° desktop-shell).

## 2. Р¤РёРєСЃ

- [x] 2.1 `LoginView.vue`: РІС…РѕРґ С‡РµСЂРµР· `useAuthStore().login()` (РµРґРёРЅС‹Р№ РїСѓС‚СЊ СЃРѕ Settings).
- [x] 2.2 `SettingsView.vue`: РїРѕСЃР»Рµ `auth.logout()` РїРµСЂРµС…РѕРґ РЅР° `/login`.

## 3. Р РµРіСЂРµСЃСЃРёРѕРЅРЅС‹Р№ С‚РµСЃС‚

- [x] 3.1 `login-navigation.test.ts` (jsdom): РЅР°СЃС‚РѕСЏС‰РёР№ router + `installAuthGuard` + РЅР°СЃС‚РѕСЏС‰РёР№ СЃС‚РѕСЂ + РјРѕРє IPC вЂ” РЅРµ Р·Р°Р»РѕРіРёРЅРµРЅ в†’ `/login`; РІС…РѕРґ СЃ СЌРєСЂР°РЅР° Р»РѕРіРёРЅР° в†’ `/chat`; Sign out РёР· Settings в†’ `/login`.

## 4. Р’РµСЂРёС„РёРєР°С†РёСЏ

- [x] 4.1 `pnpm verify` Р·РµР»С‘РЅС‹Р№.
- [x] 4.2 `pnpm e2e:electron` Рё `pnpm e2e:stub` Р·РµР»С‘РЅС‹Рµ.
- [x] 4.3 `openspec validate login-navigation-after-auth --strict`.
