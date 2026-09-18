# M2 D.2 Review - Mercury

**Дата:** 2026-09-18  
**Объект:** pom.xml, SecurityConfig, stub-контроллеры

**Status:** APPROVE

## Findings

1.  **Controller Stubs (6 контроллеров)**
    *   Все интерфейсы (TaskApi, WorkflowApi, TriggerApi, WebhookApi, SessionApi, AgentApi) реализованы.
    *   Методы возвращают `ResponseEntity<T>`, типы совпадают с генерацией.

2.  **SecurityConfig**
    *   **HMAC capability-URL:** Путь `/api/webhooks/**` — `permitAll()` (до `/api/v1/**`, `authenticated`).
    *   Порядок правил верный (Spring Security использует первое совпадение).

3.  **Jackson Dependency (pom.xml)**
    *   `jackson-databind-nullable` scope: `provided` (соответствует AGENTS.md: компиляционная нужда).
    *   `jackson-databind` (v3) scope: `compile` (main-runtime).

4.  **D-59 (metaTools-гейт)**
    *   REST-эндпоинта `transition` нет.
    *   `transition` остаётся агент-инструментом (без REST API).

## Verdict

approve
