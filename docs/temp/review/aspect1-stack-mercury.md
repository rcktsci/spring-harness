# Stack Validation Review: Spring Harness Design Basis

**Дата:** 2026-09-16  
**Проверенный стек:** Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, PostgreSQL, ShedLock 7.9, docker-java 3.7.1  
**Метод:** сопоставление обещаний дизайна с реальными API

---

## 1. Spring AI ChatClient — tool calling, streaming, memory

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Кастомные tool-объекты с контрактами (`ASYNC_ACCEPTED` плейсхолдер) — `docs/glossary.md` §6 | Custom `ToolCallingManager` позволяет определить кастомные `ToolCallback` и обработчики исключений | OK | — |
| Отмена стрима при cancel request — `docs/execution-model.md` §2 | В Spring AI нет встроенной отмены стрима; требуется ручная агрегация через `ChatClientMessageAggregator` и проксирование потока на SSE | RISK | Использовать `StreamedChatResponse` + `Disposable` для отмены; внедрить кастомный advisor, который слушает `cancel_requested` и прерывает поток |
| Подмена ChatMemory/Advisor на SessionStore — `docs/execution-model.md` §2 | `ChatMemory` можно реализовать через кастомный `ChatMemoryRepository` и подать в `MessageWindowChatMemory` | OK | Создать `SessionStoreChatMemoryRepository` и бинд в `ChatMemory` |
| Structured output при необходимости | `ChatOptions` поддерживает `responseFormat` для JSON schema | OK | — |

---

## 2. MCP-клиент Spring AI — OAuth, bearer токены

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Bearer-токены, автообновление OAuth (24-часовые SSO, 12+ серверов) — `docs/agent-tools.md` §3 | Spring AI MCP поддерживает OAuth2 через `spring-security-oauth2-client`; токены обновляются `OAuth2AuthorizedClientManager` | OK | — |
| Конфигурация через `spring.ai.mcp.client.type` | Поддерживаются SYNC и WEBFLUX клиенты; OAuth через `Mc`OAuth2AuthorizationCodeExchangeFilterFunction` | OK | — |

---

## 3. Spring Boot 4.1 — ручная сборка datasource

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Порядок preliquibase→liquibase→пул при отключённой автоконфигурации — `docs/architecture.md` §4 | Отключение `DataSourceAutoConfiguration` позволяет создать собственный `DataSource` бин; liquibase и preliquibase работают как ручные бинды | OK | Использовать `@AutoConfigureBefore` / `@AutoConfigureAfter` для упорядочивания ручных биндов |

---

## 4. ShedLock 7.9 × Boot 4

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Совместимость с Boot 4, `lockAtMostFor`, `lockAtLeastFor` — `docs/execution-model.md` §1 | `JdbcTemplateLockProvider` с `@EnableSchedulerLock(defaultLockAtMostFor)` полностью поддерживается | OK | — |

---

## 5. Java 25 — виртуальные потоки, pinning

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Виртуальные потоки × блокирующий spawn_subagent (ожидание дочернего Turn) — `docs/glossary.md` §5 | Виртуальные потоки блокируются при I/O без потери carrier; `Thread.ofVirtual().start()` + `join()` работают | OK | — |
| Pinning-риски (synchronized в драйверах/JDBC) — `docs/execution-model.md` §2 | Virtual thread **пинается** при выполнении synchronized-блоков / нативных методов; pgjdbc может использовать synchronized на потоке carrier | RISK | Использовать `ReentrantLock` вместо `synchronized`; включить JFR-событие `jdk.VirtualThreadPinned` для мониторинга |

---

## 6. docker-java — API под JDK 25, resource limits

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| Создание контейнеров с лимитами (cpus/mem/pids) — `docs/execution-model.md` §4 | `HostConfig` предоставляет `withCpuCount()`, `withMemory()`, `withPidsLimit()` | OK | — |
| Монтирование volume | `HostConfig.withBinds()` для томов | OK | — |
| Актуальность API под JDK 25 | docker-java 3.7.1 работает на JDK 17+ (без специфичных зависимостей от JDK версии) | OK | — |

---

## 7. Hibernate/Jackson — jsonb-маппинг, UUID v7

| Обещано (док+раздел) | Что реально в API (источник) | Вердикт | Альтернатива |
|----------------------|------------------------------|---------|--------------|
| jsonb-маппинг `graph_jsonb`/`payload` — `docs/data-model.md` | Hibernate 6.4+ поддерживает `@Type(postgresql-jsonb)` через `org.hibernate.type.JsonBinaryType` | OK | Использовать `@Column(columnDefinition = "jsonb")` + `@Type(JsonBinaryType.class)` |
| Генератор UUID v7 как PK — `docs/data-model.md` | UUID v7 требует кастомного `IdentifierGenerator` (Hibernate 6.2+) | RISK | Использовать готовую библиотеку `com.fasterxml.uuid` для генерации v7 и подключить через `@GeneratedValue(generator = "uuid7")` |

---

## Сводка

| Вердикт | Количество |
|---------|------------|
| OK | 14 |
| RISK | 3 |
| BLOCKER | 0 |

### Топ-3 рисков

1. **Отмена стрима в Spring AI** — требуется ручная агрегация потока и внедрение кастомного advisor для отслеживания `cancel_requested`.
2. **Pinning виртуальных потоков** — synchronized-блоки в JDBC-драйвере или коде могут деградировать throughput; требуется мониторинг через JFR и замена на `ReentrantLock`.
3. **UUID v7 как PK** — Hibernate не имеет встроенной поддержки v7; нужна интеграция с библиотекой `com.fasterxml.uuid` и кастомный `IdentifierGenerator`.

---

## Итоговый вердикт

Design basis валиден: большинство обещаний покрывается актуальными API. Три риска не являются блокирующими, но требуют инженерных решений:
- Ручная реализация отмены стрима
- Замена synchronized на ReentrantLock для критических секций
- Кастомный UUID v7 generator для PK

Принципы (перехват отмены, виртуальные потоки для Turn'ов, ручной datasource) согласуются со стеком.

---

## Cross-check с ревьюями коллег (GLM, DeepSeek)

| Коллега | Пункт | Мой вердикт | Cross-check |
|---------|-------|-------------|-------------|
| GLM | #6 MCP OAuth (RISK) | OK | **agree-was-valid** — автообновление OAuth требует самописной прослойки (customizer + OAuth2AuthorizedClientManager) |
| GLM | #8 PreLiquibase (RISK) | не проверял | **agree-was-valid** — manual `SpringLiquibase` блокирует срабатывание PreLiquibase |
| GLM | #14 jsonb×Jackson 3 (RISK) | не проверял | **agree-was-valid** — Hibernate 7.2 требует Jackson 2 FormatMapper или кастомный Jackson 3 |
| DeepSeek | F2 ASYNC_ACCEPTED+late (RISK) | не проверял | **agree-was-valid** — duplicate tool_call_id конфликтует с OpenAI API |
| DeepSeek | F3 отмена стрима (RISK) | RISK | **agree-was-valid** — cancel подписки не гарантирует stop генерации у провайдера |
| DeepSeek | F6 #6915 60s таймаут (RISK) | не проверял | **stale** — документация #6915 уже в architecture |
| DeepSeek | F8 MCP BLOCKER | OK | **disagree** — MCP-стартер есть, OAuth интегрируется вручную (см. GLM #6) |
| DeepSeek | F13 Keycloak BLOCKER | не проверял | **agree-was-valid** — в pom нет spring-boot-starter-oauth2-resource-server |
| DeepSeek | F19 docker-java BLOCKER | OK | **disagree** — docker-java в сборке не помечен, но API работает (нужно добавить в pom) |
| DeepSeek | F22 jsonb×Jackson 3 | не проверял | **agree-was-valid** |
| DeepSeek | pinning (OK) | RISK | **agree-was-valid** — JEP 491 снимает pinning в JDK 25 |

**Согласованные RISK/BLOCKER:** MCP OAuth, PreLiquibase, jsonb×Jackson 3, ASYNC_ACCEPTED, cancel стрима, Keycloak, docker-java.

**Решённые после ревью:** UUID v7 (генератор владельца работает), pinning (JEP 491), ShedLock 7.10.1 (в pom), preliquibase 2.0.0 (repo1 подтверждён).
