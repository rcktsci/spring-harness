# Наблюдаемость и эксплуатация spring-harness

> Минимум для MVP. Всё, что не здесь, — не делаем.

## 1. Логи

- Структурированный JSON **только на stdout**.
- Корреляция через MDC: `sessionId?, taskId?, turnId?`.
- Маскирование: `api_key`, `Authorization`, билеты (`?ticket=`), capability-токены вебхуков, `task.params`.

## 2. Метрики и health

- **Actuator + Prometheus** (`management.server.port=8081`, `/actuator/prometheus`) — стандартные метрики http/jvm/db + прикладные Micrometer по мере надобности.
- Health: базовый readiness (БД + конфиг), liveness — процесс. Никаких гистерезисов и тумблеров.

## 3. Деплой (docker-compose на VM)

- `postgres`, `orchestrator`, helper-образ собирается при деплое.
- Миграции — Liquibase при старте (один инстанс — без конкурентного старта).
- Трейсинг: correlation-логи (§1), ничего больше. Graceful shutdown не проектируем (D-41): передеплой = рестарт, локи истекут, POLL поднимет.

## 4. Конфигурация

12-factor: env (`HARNESS_*`); `application.yml` — структурные дефолты; `.env` в dev. **Все числа — конфиг** (`@ConfigurationProperties`), хардкод запрещён (architecture §4).

## 5. Вне MVP

Бэкапы/WAL-политики, restore-drills, алерты, OTel-мост, SLA-дашборды — не делаем; вернёмся при реальной эксплуатации.

## 6. Web Desktop — сборка и дистрибуция (D-86/D-90)

Десктоп-клиент `web-desktop/` — отдельный npm-пакет в monorepo (pnpm + Vite + electron-vite + electron-builder). Билд и распространение полностью изолированы от Java-конвейера; `mvn clean verify` ничего не знает про Node.

### Сборка

```bash
cd web-desktop
pnpm install
pnpm run build      # electron-vite build → out/{main,preload,renderer}
pnpm run package:win   # electron-builder --win nsis → dist/win-unpacked/*.exe + dist/*.exe setup
pnpm run package:linux # electron-builder --linux appimage → dist/*.AppImage
```

Артефакты — `out/main/index.js` (main), `out/preload/index.js` (preload), `out/renderer/{index.html, assets/*}` (рендер); `dist/` после `package:*`. `publish: null` — никакого auto-update.

### Конфигурация клиента

Все параметры — в `userData/config.json` (см. `ServerConfig` в `shared/ipc-contract.ts`): `serverBaseUrl`, `keycloakIssuer`, `keycloakClientId`, `confirmCommands` (default `always`, D-93), `theme`, `windowWidth/Height`, таймауты relay/SSE/tree/history. Числа в коде запрещены (правило владельца).

### Каналы дистрибуции

- Primary — Windows: NSIS-инсталлятор, без автообновлений (внутренний MVP). Раздача через корпоративный сетевой ресурс; пользователь ставит вручную.
- Secondary — Linux: AppImage (по запросу владельца).
- macOS — пока вне MVP (отдельный DMG-конфиг добавим при необходимости).

### CI / проверка

- `pnpm verify` = lint (eslint `--max-warnings=0`) + typecheck (`tsc --noEmit -p tsconfig.node.json` + `vue-tsc --noEmit -p tsconfig.web.json`) + tests (Vitest, 19 файлов / 166 unit-тестов) + build (`electron-vite build`).
- `pnpm e2e` — Playwright-electron против in-test stub-сервера (отдельный процесс). На CI по умолчанию off (X11-нужен для Electron на headless Linux; см. `web-desktop/docs/smoke.md`).
- Smoke против docker-compose — автоматический скрипт `web-desktop/scripts/smoke-docker.ps1` (`.sh`), см. ту же доки.

### Апдейт-политика

- Internal MVP — ручной передеплой (пользователь получает новый `.exe` через корпоративный канал; `userData/config.json` и `safeStorage`-токены переживают апдейт).
- Автообновление (`electron-updater`) — вне M5, см. AGENTS.md → эволюция.
