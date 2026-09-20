package se.rocketscien.harness.api;

/**
 * Негативная фикстура ArchUnit (M.1, только test-classpath): умышленно нарушает правило
 * «чужой .impl» — класс пакета {@code api} импортирует {@code task.impl}. Живёт в
 * {@code target/test-classes}, поэтому позитивные правила на main-классах
 * ({@code target/classes}) её не видят; используется только тестом
 * {@code ArchitectureRulesTest.foreignImplViolationIsCaught}.
 */
public final class ArchUnitForeignImplViolator {

    public Object leak(se.rocketscien.harness.task.impl.TaskRegistryImpl registry) {
        return registry;
    }
}
