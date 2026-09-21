# workspace-download Specification

## Purpose

Скачивание файлов workspace-сессии по HTTP (api-contracts §8): для роуминга «одна сессия — два офиса» и для будущего Web Desktop. Точечный canonical-path-гвард вместо удалённого в M1 общего path-guard'а.

## ADDED Requirements

### Requirement: Эндпоинт скачивания файлов

`GET /api/v1/sessions/{id}/workspace/files?path=<relative>` SHALL отдавать содержимое файла из workspace сессии: 200 + тело (Content-Type по расширению, safe-лист), 404 `session-not-found` / `file-not-found`, 422 `path-invalid` (абсолютный путь, `..`-эскейп, нулевые сегменты). Доступ — SSO-гейт D-41 (тот же, что у остальных операций сессии; owner-поле — метаданные, не барьер). Только файлы (директории → 422 `path-invalid` с указанием, что это каталог). Тело отсекается по `harness.workspace.download.max-bytes` (конфиг, дефолт 10 МБ) — при превышении 413 `payload-too-large`.

#### Scenario: скачивание файла

- **WHEN** клиент запрашивает существующий файл из workspace сессии
- **THEN** 200 с содержимым

#### Scenario: файла нет

- **WHEN** запрошен несуществующий путь
- **THEN** 404 `file-not-found`

#### Scenario: сессии нет

- **WHEN** id сессии неизвестен
- **THEN** 404 `session-not-found`

#### Scenario: запрошен каталог

- **WHEN** path указывает на директорию
- **THEN** 422 `path-invalid`

### Requirement: Canonical-path-гвард

Сервер SHALL проверять путь: нормализация через канонический резолв **каждого** сегмента (включая `..` и `.`), резолв symlink'ов (реальный целевой путь), и отказ, если результат выходит за пределы корня workspace сессии `workspaces/sessions/{sessionId}`. Абсолютный путь на входе → 422. Любой symlink, целевой резолв которого покидает корень, → 422. Гвард — точечный, только в этом эндпоинте (D-72).

#### Scenario: эскейп через ..

- **WHEN** `path=../../etc/passwd`
- **THEN** 422 `path-invalid`

#### Scenario: symlink за пределы

- **WHEN** в workspace есть symlink на `/etc/passwd` и запрошен он
- **THEN** 422 `path-invalid`

#### Scenario: легитимный symlink внутри

- **WHEN** symlink ведёт на другой файл внутри workspace
- **THEN** 200 с содержимым цели

### Requirement: Safe-лист расширений

Отдаваться SHALL только файлы с расширениями из safe-листа `harness.workspace.download.allow-extensions` (конфиг, дефолт: текстовые и кодовые — `txt,md,json,yaml,yml,xml,html,css,js,ts,java,kt,py,sql,sh,gitignore,properties,log,csv,toml`). Остальные → 422 `extension-not-allowed` (бинарники — `jar,class,exe,dll,png,jpg,gif,zip,gz` и пр. — не отдаются; отдельный allow-list для медиа — эволюция).

#### Scenario: текстовый файл

- **WHEN** запрошен `report.md`
- **THEN** 200

#### Scenario: бинарник

- **WHEN** запрошен `build/app.jar`
- **THEN** 422 `extension-not-allowed`

### Requirement: Streaming для больших файлов

Отдача SHALL идти потоком (chunked/streaming), не загружая файл в память целиком; лимит `max-bytes` считается по факту отданных байт — при превышении поток обрывается с 413 (заголовки уже отправлены, клиент обязан детектить обрыв по Content-Length/усечению).

#### Scenario: большой файл

- **WHEN** запрошен файл 12 МБ при лимите 10
- **THEN** отдаётся 10 МБ, затем обрыв с 413-маркером
