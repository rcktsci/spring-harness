# Spec Delta: sso-gate

## Purpose

Единая точка допуска: OIDC/JWT-аутентификация всех клиентов и SSO-гейт по groups-claim; внутри платформы действует одно правило доступа — «аутентифицированный видит всё и пишет куда угодно» (D-41).

## ADDED Requirements

### Requirement: Bearer JWT на всех API-эндпоинтах

Все эндпоинты `/api/v1/**` SHALL требовать `Authorization: Bearer <JWT>` (Keycloak OIDC). Запрос без токена, с просроченным или не прошедшим проверку подписи/issuer токеном SHALL отклоняться `401 unauthenticated` (Problem Details RFC 9457, без challenge).

#### Scenario: запрос без токена

- **WHEN** клиент вызывает любой `/api/v1/**`-эндпоинт без заголовка Authorization
- **THEN** система отвечает `401` с кодом `unauthenticated`

#### Scenario: токен с чужого issuer

- **WHEN** JWT подписан ключом/issuer, не соответствующим конфигурации
- **THEN** система отвечает `401` с кодом `unauthenticated`

### Requirement: SSO-гейт по groups-claim

JWT SHALL содержать в claim `groups` хотя бы одну группу из конфигурации `harness.security.allowed-groups`. Аутентифицированный, но не состоящий в разрешённой группе, SHALL получать `401 unauthenticated`. Прошедший гейт получает полный доступ (просмотр и запись любых сущностей) без дополнительных проверок прав.

#### Scenario: пользователь вне разрешённых групп

- **WHEN** валидный JWT не содержит ни одной группы из `harness.security.allowed-groups`
- **THEN** система отвечает `401` с кодом `unauthenticated`

#### Scenario: пользователь в разрешённой группе

- **WHEN** валидный JWT содержит группу из `harness.security.allowed-groups`
- **THEN** запрос обрабатывается без каких-либо проверок владения/прав

### Requirement: Синхронизация пользователей

При первом успешном запросе нового `keycloak_subject` система SHALL создавать запись пользователя (username, display_name из токена). При изменении username/display_name в токене система SHALL обновлять их при очередном запросе. Существующие пользователи не удаляются.

#### Scenario: первый запрос нового пользователя

- **WHEN** запрос проходит гейт, и `keycloak_subject` отсутствует в БД
- **THEN** создаётся пользователь с username и display_name из JWT

#### Scenario: смена display_name в токене

- **WHEN** `keycloak_subject` уже известен, а display_name в токене отличается
- **THEN** display_name обновляется при обработке запроса
