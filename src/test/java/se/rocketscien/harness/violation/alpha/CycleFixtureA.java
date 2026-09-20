package se.rocketscien.harness.violation.alpha;

/**
 * Негативная фикстура ArchUnit (M.1, только test-classpath): половина умышленного цикла
 * {@code alpha ↔ beta} (вторая — {@code ...violation.beta.CycleFixtureB}). Пакет
 * {@code violation} намеренно вне доменных слоёв — цикл не должен ловиться позитивным
 * правилом на домене; используется только тестом
 * {@code ArchitectureRulesTest.dependencyCycleIsCaught}.
 */
public final class CycleFixtureA {

    public se.rocketscien.harness.violation.beta.CycleFixtureB next() {
        return null;
    }
}
