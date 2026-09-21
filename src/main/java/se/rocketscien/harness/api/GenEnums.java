package se.rocketscien.harness.api;

import se.rocketscien.harness.api.gen.model.MessageKind;
import se.rocketscien.harness.api.gen.model.SessionKind;
import se.rocketscien.harness.api.gen.model.SessionRuntimeStatus;
import se.rocketscien.harness.api.gen.model.TurnOutcome;

/**
 * Конвертер доменных перечислений в сгенерированные (спека совпадает с доменом по именам
 * констант). Простые имена генерации конфликтуют с доменными ({@code SessionKind},
 * {@code MessageKind} и пр. — оба нельзя импортировать в одном файле), поэтому conversion
 * живет здесь, где импортированы только gen-model-типы: {@code ApiMappers} вызывает его по
 * короткому имени, не используя inline-FQDN.
 */
final class GenEnums {

    private GenEnums() {
    }

    static SessionKind sessionKind(String name) {
        return SessionKind.valueOf(name);
    }

    static MessageKind messageKind(String name) {
        return MessageKind.valueOf(name);
    }

    static SessionRuntimeStatus runtimeStatus(String name) {
        return SessionRuntimeStatus.valueOf(name);
    }

    static TurnOutcome turnOutcome(String name) {
        return TurnOutcome.valueOf(name);
    }
}
