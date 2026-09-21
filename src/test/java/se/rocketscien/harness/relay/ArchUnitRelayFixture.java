package se.rocketscien.harness.relay;

/**
 * Фикстура-нарушитель ArchUnit (M4 T.2, только test-classpath): пустой класс слоя {@code relay},
 * на который ссылается {@code execution.ArchUnitRelayBridgeViolator}. Используется негативным
 * тестом {@code ArchitectureRulesTest.relayViolationIsCaught} — правило {@code execution ↛ relay}
 * (D-85) должно поймать намеренную зависимость.
 */
public final class ArchUnitRelayFixture {
}
