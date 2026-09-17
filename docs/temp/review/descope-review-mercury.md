# Дефекты дизайн-корпуса после де-скоупа (D-39…D-42)

Дата: 2026-09-17
Статус: review

---

## Сводка по классам

| Класс | Счёт |
|-------|------|
| 1. Висячие ссылки на вырезанное | 9 |
| 2. Противоречия между доками | 3 |
| 3. Сломанное несущее | 0 |

**Итого:** 12 дефектов

---

## Класс 1: Висячие ссылки на вырезанное

### glossary.md §1.1 (строка 103) → PARTICIPATE
> В CLI-описании: "дописывание им (PARTICIPATE)" — роль PARTICIPATE выкинута D-41.
**Предложение:** Заменить на «дописывание им (VIEW)» или «добавление сообщений в сессию».

### glossary.md §5.1-5.6 (строки 55-66) → fork/rewind/export
> В определении Session указаны поля fork_source_session_id, fork_seq_cutoff, rewind_seq — они выкинута D-41.
**Предложение:** Переместить описание fork/rewind/export в раздел "Вне MVP / WebUI-фаза".

### data-model.md §5 (строка 156) → message_count/tokens_total
> Указано: «Денормализации `last_seq/last_consumed_seq/message_count/tokens_total` обновляются...». D-41 выкинул message_count/tokens_total.
**Предложение:** Удалить упоминание message_count/tokens_total из схемы.

### architecture.md §3 (строка 33) → AccessPolicy контракт
> AccessPolicy указан как контракт в identity-модуле. D-41 выкинул матрицу прав.
**Предложение:** Удалить AccessPolicy из таблицы контрактов (единого правила доступа достаточно).

### security-multitenancy.md §1 (строка 30) → spawn.maxDepth
> Указано: «Спавн: единственный лимит `spawn.maxDepth`». Это согласованно с D-41.
**Предложение:** OK — оставить.

### client-cli.md §1 (строки 10, 11) → --folder
> Команды `sessions [--folder ...]` и `new <agentKey> [--folder]` — папки выкинута D-41.
**Предложение:** Удалить флаг `--folder` из CLI-спеки.

### workflow-domain.md §7 (строка 75) → Idempotency-Key
> В threat-model capability-URL упомянут «опциональный `Idempotency-Key`». D-41 выкинул хранилище идемпотентности.
**Предложение:** Удалить упоминание Idempotency-Key из threat-model.

### roadmap.md M1 (строка 7) → tickets
> В M1 указано: «билеты, минимальный attach...». D-41 выкинул билеты до WebUI-фазы.
**Предложение:** Переместить упоминание билетов из M1 в WebUI-фазу (M5).

### roadmap.md M4 (строка 24) → fork/rewind/export
> В M4 указано: «attach-CLI полный (tree/inject/stop/fork/rewind/compact, задачи)». Fork/rewind выкинута D-41.
**Предложение:** Удалить fork/rewind из M4-описания; оставить compact.

---

## Класс 2: Противоречия между доками

### roadmap.md §4.1 и api-contracts.md §4.1 → TaskDto.params
> api-contracts.md §4.1: `params` иммутабельны после создания. Data-model.md §4: `params_jsonb` без явного запрета UPDATE.
**Предложение:** Добавить в data-model.md примечание «params_jsonb иммутабелен после создания» для согласованности.

### roadmap.md M1 и api-contracts.md §8 → tickets/WebUI-фаза
> M1 включает «билеты», api-contracts.md §8: «WebUI-фаза: билеты...». D-41 выкинул билеты до WebUI.
**Предложение:** Согласовать: билеты только в WebUI-фазе, не в M1.

### roadmap.md M4 и D-41 → fork/rewind
> roadmap.md M4 включает fork/rewind, D-41 выкинул fork/rewind из MVP.
**Предложение:** Удалить fork/rewind из M4-описания в roadmap.md.

---

## Класс 3: Сломанное несущее

| № | Дефект | Статус |
|---|--------|--------|
| — | Все core-инварианты сохранены: ShedLock sess-{id} + heartbeat/TTL, wake EVENT+POLL, CAS-переходы, LOST-скан, ретраи LLM, auto-compaction, helper/task-контейнеры, /compact REST, maxDepth, SSO groups-claim | OK |

---

## Рекомендации

1. **Приоритет:** исправить Class 1 висячие ссылки перед началом кодинга.
2. **Документация:** Переместить все упоминания fork/rewind/export/archive в раздел «Вне MVP / WebUI-фаза».
3. **Контракты:** Удалить AccessPolicy из api-contracts и architecture; оставить только SSO-гейт и «аутентифицированный видит всё».

---

## Cross-check

### Сводка совпадений

| Ревьюер | Мои пункты (дубликаты) | Мои пункты (пропущено) |
|---------|------------------------|------------------------|
| GLM | 9 из 12 Class 1, 3 из 10 Class 2 | 3 Class 1 + 7 Class 2 |
| DeepSeek | 15 из 18 Class 1, 3 из 11 Class 2 | 3 Class 1 + 7 Class 2 + 2 Class 3 |

### Список disagree (отличия в трактовке)

| № | Пункт | Статус | Причина |
|---|-------|--------|---------|
| 1 | GLM 2.3 / DeepSeek Class 3 «recovery добивает поддерево» | **disagree** | Это не «докатка отмен» (D-41), а описание естественного поведения POLL'а после TTL лока; D-41 выбросил именно **отдельный механизм** докатки, а не recovery-контур. |
| 2 | GLM 2.4 «lockAtMostFor два TTL» | **disagree** | Документ говорит «lockAtMostFor ~10 сек» (джоба) и «TTL сессионного лока = lockAtMostFor» (сессия); это **ошибка в описании**, а не два разных параметра — нужно исправить текст, а не вводить новые конфиги. |
| 3 | DeepSeek Class 3 «archived_at в eligibility» | **disagree** | archivel_at не в eligibility-условии, а в partial-индексе; сам eligibility говорит «не архивна» (текст), что тоже нужно исправить, но это Class 1 (ссылка на вырезанное), а не Class 3 (сломанное несущее). |

### Дополнительные дефекты (мои пропуски)

| Док | Дефект | Класс |
|-----|--------|-------|
| glossary.md §4 | Eligibility «не архивна» при вырезанной архивации | 1 |
| operations.md §1 | Билеты в списке маскирования | 1 |
| workflow-domain.md §7 | rate-limit в threat-model | 2 |
| decisions.md | Superseded строки D-16, D-29 и др. без пометок | 2 |

---

## Fixes approval

**approve** — PARTICIPATE → атрибуция, --folder удалён, message_count/tokens_total убраны, fork/rewind/archive из схемы, AccessPolicy из контрактов, билеты из M1, lockAtMostFor разведён, params иммутабельность добавлена.

---

## Fixes approval (re)

**approve** — регрессий нет: «не архивна», «архивных», fork, rewind, PARTICIPATE, --folder, message_count/tokens_total удалены; «Рестарт-скан» слит, «Экспорт» и «форки» почищены.
