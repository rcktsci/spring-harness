package se.rocketscien.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры workflow-домена: повтор вставки ревизии при UNIQUE-конфликте (R-2) — backstop
 * поверх лока строки-родителя; параллельные newRevision одного workflow сериализуются
 * локом, retry закрывает остаточные гонки READ COMMITTED.
 */
@ConfigurationProperties(prefix = "harness.workflow")
public record WorkflowProperties(int revisionInsertRetries) {

    public WorkflowProperties {
        if (revisionInsertRetries < 1) {
            throw new IllegalArgumentException(
                    "harness.workflow.revision-insert-retries должен быть >= 1");
        }
    }
}
