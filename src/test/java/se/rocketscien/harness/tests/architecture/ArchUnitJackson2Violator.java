package se.rocketscien.harness.tests.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * D-94: намеренный нарушитель границы Jackson — main-класс, пекующий Jackson 2.
 * Живёт только в test-classpath и импортируется отдельным {@code ClassFileImporter}
 * в негативном тесте {@code jackson2ViolationIsCaught}.
 */
public class ArchUnitJackson2Violator {

    public String render(Object value) {
        return new ObjectMapper().valueToTree(value).toString();
    }
}
