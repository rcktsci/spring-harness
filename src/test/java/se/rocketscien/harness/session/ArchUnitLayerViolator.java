package se.rocketscien.harness.session;

/**
 * Негативная фикстура ArchUnit (M.1, только test-classpath): умышленно нарушает правило слоёв
 * — {@code session may only access [identity]}, а тут зависимость от {@code task}. Живёт в
 * {@code target/test-classes}; используется только тестом
 * {@code ArchitectureRulesTest.layerViolationIsCaught}.
 */
public final class ArchUnitLayerViolator {

    public se.rocketscien.harness.task.TaskRegistry taskRegistry() {
        return null;
    }
}
