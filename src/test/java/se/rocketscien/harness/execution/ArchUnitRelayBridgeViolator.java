package se.rocketscien.harness.execution;

/**
 * Фикстура-нарушитель ArchUnit (M4 T.2, только test-classpath): доменный класс {@code execution},
 * зависящий от {@code relay.ArchUnitRelayFixture}. Живёт в {@code target/test-classes}, поэтому
 * позитивное правило на main-классах его не видит; ловится негативным тестом
 * {@code ArchitectureRulesTest.relayViolationIsCaught} (D-85: {@code execution ↛ relay} —
 * обращение только через SPI {@link ClientToolBridge}).
 */
public final class ArchUnitRelayBridgeViolator {

    public Object leak(se.rocketscien.harness.relay.ArchUnitRelayFixture fixture) {
        return fixture;
    }
}
