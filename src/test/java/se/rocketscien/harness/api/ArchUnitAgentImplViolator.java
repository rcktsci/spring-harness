package se.rocketscien.harness.api;

/**
 * Негативная фикстура ArchUnit (M3 S.1, только test-classpath): умышленно нарушает правило
 * «чужой .impl» — класс пакета {@code api} импортирует {@code agent.impl}. Живёт в
 * {@code target/test-classes}, поэтому позитивные правила на main-классах
 * ({@code target/classes}) её не видят; используется только тестом
 * {@code ArchitectureRulesTest.agentImplViolationIsCaught}.
 */
public final class ArchUnitAgentImplViolator {

    public Object leak(se.rocketscien.harness.agent.impl.ArchUnitAgentImplFixture fixture) {
        return fixture;
    }
}
