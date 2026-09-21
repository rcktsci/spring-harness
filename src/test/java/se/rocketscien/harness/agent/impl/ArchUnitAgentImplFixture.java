package se.rocketscien.harness.agent.impl;

/**
 * Негативная фикстура ArchUnit (M3 S.1, только test-classpath): фиктивный класс
 * {@code agent.impl}. В main-классах {@code agent} пока не имеет {@code impl}-подпакета,
 * поэтому чужая зависимость от {@code agent.impl} проверяется этой фикстурой в
 * {@code ArchitectureRulesTest.agentImplViolationIsCaught}. В {@code target/classes} её нет —
 * позитивные правила на main-классах её не видят.
 */
public final class ArchUnitAgentImplFixture {
}
