package se.rocketscien.harness.violation.beta;

/**
 * Негативная фикстура ArchUnit (M.1, только test-classpath): вторая половина умышленного
 * цикла {@code alpha ↔ beta}; используется только тестом
 * {@code ArchitectureRulesTest.dependencyCycleIsCaught}.
 */
public final class CycleFixtureB {

    public se.rocketscien.harness.violation.alpha.CycleFixtureA back() {
        return null;
    }
}
