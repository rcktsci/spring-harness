# workspace-download Specification

## Purpose
Скачивание файлов **серверного** workspace-сессии по HTTP (api-contracts §8): для просмотра артефактов будущим Web Desktop и для серверных сессий. Точечный canonical-path-гвард вместо удалённого в M1 общего path-guard'а (D-72).

## Requirements

### Requirement: Эндпоинт скачивания файлов

`GET /api/v1/sessions/{id}/workspace/files?path=<relative>` SHALL отдавать содержимое файла из **серверного** каталога workspace сессии `workspaces/sessions/{sessionId}`: 200 + тело, 404 `session-not-found` / `file-not-found`, 422 `path-invalid` (абсолютный путь, `..`-эскейп, нулевые сегменты, каталог), 422 `extension-not-allowed`, 413 `payload-too-large`. Доступ — SSO-гейт D-41. Для CLIENT-сессий (релей) серверный workspace может быть пуст — файлы находятся у клиента; это задокументированное ограничение, основные потребители — SERVER-сессии и Web Desktop.

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

Сервер SHALL проверять путь перед отдачей: посегментная проверка symlink'ов (`Files.isSymbolicLink` на каждый компонент), канонический резолв каждого сегмента (включая `..` и `.`), containment результата в корне `workspaces/sessions/{sessionId}`, открытие с `LinkOption.NOFOLLOW_LINKS` на финальный компонент. Абсолютный путь на входе → 422. Symlink, покидающий корень (даже на этапе обхода) → 422. Остаточный TOCTOU между проверкой и открытием — принятый риск: писатель workspace — агент (arbitrary `bash` в примонтированном каталоге), читатель — аутентифицированный SSO-пользователь; митигация — NOFOLLOW + ре-канонизация сегментов (D-72).

#### Scenario: эскейп через ..

- **WHEN** `path=../../etc/passwd`
- **THEN** 422 `path-invalid`

#### Scenario: symlink за пределы

- **WHEN** в workspace есть symlink на `/etc/passwd` и запрошен он
- **THEN** 422 `path-invalid`

#### Scenario: легитимный symlink внутри запрещён

- **WHEN** symlink ведёт на другой файл внутри workspace
- **THEN** 422 `path-invalid` (гвард следует NOFOLLOW-семантике: symlink'и не разыменовываются)

### Requirement: Safe-лист расширений

Отдаваться SHALL только файлы с расширениями из safe-листа `harness.workspace.download.allow-extensions` (конфиг, дефолт: `txt,md,json,yaml,yml,xml,html,css,js,ts,java,kt,py,sql,sh,properties,log,csv,toml`). Сравнение — case-insensitive. Остальные → 422 `extension-not-allowed` (бинарники — `jar,class,exe,dll,png,jpg,gif,zip,gz` и пр. — не отдаются; медиа-allow-list — эволюция).

#### Scenario: текстовый файл

- **WHEN** запрошен `report.md`
- **THEN** 200

#### Scenario: бинарник

- **WHEN** запрошен `build/app.jar`
- **THEN** 422 `extension-not-allowed`

### Requirement: Pre-stat перед streaming

Размер файла SHALL определяться до начала отдачи (`Files.size`); превышение `harness.workspace.download.max-bytes` (конфиг, дефолт 10 МБ) → честный 413 до отправки заголовков. Отдача SHALL идти потоком с `Content-Length`, не загружая файл в память целиком. Усечение потока «в процессе» не используется (контракт содержит либо полную отдачу, либо 413 до неё).

#### Scenario: большой файл

- **WHEN** запрошен файл 12 МБ при лимите 10
- **THEN** 413 `payload-too-large` до отправки тела

#### Scenario: файл в пределах лимита

- **WHEN** запрошен файл 3 МБ при лимите 10
- **THEN** 200, потоковая отдача
