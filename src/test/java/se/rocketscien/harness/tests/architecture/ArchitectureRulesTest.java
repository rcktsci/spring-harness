package se.rocketscien.harness.tests.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import se.rocketscien.harness.agent.impl.ArchUnitAgentImplFixture;
import se.rocketscien.harness.api.ArchUnitAgentImplViolator;
import se.rocketscien.harness.api.ArchUnitForeignImplViolator;
import se.rocketscien.harness.session.ArchUnitLayerViolator;
import se.rocketscien.harness.task.TaskRegistry;
import se.rocketscien.harness.task.impl.TaskRegistryImpl;
import se.rocketscien.harness.violation.alpha.CycleFixtureA;
import se.rocketscien.harness.violation.beta.CycleFixtureB;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Задача 10.1: ArchUnit-границы модулей {@code identity/session/execution/intelligence/api}
 * (architecture.md §1–§2) — чистый unit без Spring-контекста.
 *
 * <p>Слои: {@code api → {execution, intelligence, session, identity}};
 * {@code execution → {session, identity}} + закладка M2 {@code task/workflow} (пустые пакеты
 * не проверяются — optional layers) + контракты {@code intelligence} (architecture.md §2:
 * «их знают только execution/api»); {@code session → identity}; {@code identity} изолирована;
 * от {@code api} не зависит никто; циклы между модулями запрещены.</p>
 *
 * <p>Контракты: чужой {@code <модуль>.impl.*} вне своего модуля не импортируется. Технические
 * пакеты {@code common}/{@code config} — доступны всем без правил (в т.ч. {@code config}
 * собирает бины из impl-пакетов), поэтому impl-правило проверяет только доменные классы.</p>
 *
 * <p>Проверяются только main-классы ({@code target/classes}, включая сгенерированный
 * {@code api.gen}); тест-классы и сгенерированный test-клиент в границы не входят.
 * Нарушение роняет тест с описанием правила и перечнем зависимостей.</p>
 *
 * <p>M.1: правила расширены на {@code task}/{@code workflow} (параметризация, слои,
 * циклы) и дополнены негативными тестами «намеренное нарушение ловится»: фикстуры-нарушители
 * ({@code ArchUnitForeignImplViolator}, {@code ArchUnitLayerViolator}, пара
 * {@code CycleFixtureA/B}) живут только в test-classpath и проверяются отдельными
 * импортами — позитивные правила на main-классах их не видят.</p>
 *
 * <p>M3 S.1: в слои добавлены {@code agent} (агентский рантайм — как {@code execution.impl}:
 * зависит от контрактов execution/session, от него никто) и {@code mcp} (технический
 * однонаправленный {@code execution → mcp}); {@code .impl}-правило и циклы распространены
 * на оба пакета; негативный тест {@code agentImplViolationIsCaught} фиксирует, что
 * {@code api → agent.impl} ловится ({@code mcp.impl} в main не существует — правило
 * параметризовано вакуумно).</p>
 */
class ArchitectureRulesTest {

    private static final String BASE = "se.rocketscien.harness";
    private static final String API = BASE + ".api..";
    private static final String EXECUTION = BASE + ".execution..";
    private static final String TASK = BASE + ".task..";
    private static final String WORKFLOW = BASE + ".workflow..";
    private static final String SESSION = BASE + ".session..";
    private static final String IDENTITY = BASE + ".identity..";
    private static final String INTELLIGENCE = BASE + ".intelligence..";
    private static final String AGENT = BASE + ".agent..";
    private static final String MCP = BASE + ".mcp..";

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .importPaths(Paths.get("target", "classes"));

    /** Доменные пакеты для проверки циклов: common/config — технические, из графа исключены. */
    private static final JavaClasses DOMAIN_CLASSES = new ClassFileImporter().importPackages(
            BASE + ".api", BASE + ".execution", BASE + ".task", BASE + ".workflow",
            BASE + ".session", BASE + ".identity", BASE + ".intelligence",
            BASE + ".agent", BASE + ".mcp");

    private static final ArchRule MODULE_LAYERING = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("api").definedBy(API)
            .layer("execution").definedBy(EXECUTION)
            .layer("task").definedBy(TASK)
            .layer("workflow").definedBy(WORKFLOW)
            .layer("session").definedBy(SESSION)
            .layer("identity").definedBy(IDENTITY)
            .layer("intelligence").definedBy(INTELLIGENCE)
            .layer("agent").definedBy(AGENT)
            .layer("mcp").definedBy(MCP)
            .whereLayer("api").mayOnlyAccessLayers("execution", "intelligence", "session", "identity", "task", "workflow")
            .whereLayer("execution").mayOnlyAccessLayers("session", "identity", "task", "workflow", "intelligence", "mcp")
            .whereLayer("task").mayOnlyAccessLayers("identity")
            .whereLayer("workflow").mayOnlyAccessLayers("identity")
            .whereLayer("session").mayOnlyAccessLayers("identity")
            .whereLayer("identity").mayNotAccessAnyLayer()
            .whereLayer("agent").mayOnlyAccessLayers("execution", "session", "identity", "task", "workflow",
                    "intelligence", "mcp")
            .whereLayer("mcp").mayNotAccessAnyLayer()
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .as("Слои модулей: api → {execution, intelligence, session, identity, task(M2), workflow(M2)}; "
                    + "execution → {session, identity, task, workflow, intelligence, mcp(M3)}; "
                    + "agent(M3) → execution-контракты (как execution.impl); mcp(M3) — технический, "
                    + "однонаправленный execution → mcp; session → identity; identity изолирована; "
                    + "от api никто не зависит");

    @Test
    void moduleLayeringIsRespected() {
        MODULE_LAYERING.check(MAIN_CLASSES);
    }

    @Test
    void noDomainModuleDependsOnApi() {
        noClasses()
                .that().resideInAnyPackage(EXECUTION, TASK, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE, AGENT, MCP)
                .should().dependOnClassesThat().resideInAPackage(API)
                .because("api — верхний слой, доменные модули о нём не знают")
                .as("Ни один модуль не зависит от api")
                .check(MAIN_CLASSES);
    }

    @Test
    void intelligenceDoesNotDependOnExecution() {
        noClasses()
                .that().resideInAPackage(INTELLIGENCE)
                .should().dependOnClassesThat().resideInAnyPackage(EXECUTION, API)
                .because("intelligence драйвена контрактами и не знает исполняющий контур")
                .as("intelligence не зависит от execution и api")
                .check(MAIN_CLASSES);
    }

    @ParameterizedTest(name = "чужой .impl не импортируется: {0}")
    @ValueSource(strings = {"session", "execution", "intelligence", "identity", "task", "workflow", "agent", "mcp"})
    void foreignImplPackageIsHiddenBehindContract(String module) {
        List<String> importers = new ArrayList<>(List.of(
                API, EXECUTION, TASK, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE, AGENT, MCP));
        importers.remove(BASE + "." + module + "..");
        noClasses()
                .that().resideInAnyPackage(importers.toArray(new String[0]))
                .should().dependOnClassesThat().resideInAPackage(BASE + "." + module + ".impl..")
                .because("модуль наружу отдаёт только контракт; .impl — его внутренность")
                .as("Имплементации модуля " + module + " скрыты за контрактом")
                .check(MAIN_CLASSES);
    }

    @Test
    void noCyclesBetweenModules() {
        slices().matching(BASE + ".(*)..")
                .should().beFreeOfCycles()
                .because("циклы между модулями запрещены (architecture.md §2)")
                .as("Модули свободны от циклов")
                .check(DOMAIN_CLASSES);
    }

    // --- M.1: намеренное нарушение ловится (фикстуры-нарушители — test-classpath) ---

    /** Нарушение «чужой .impl» (api → task.impl) роняет правило с перечнем зависимости. */
    @Test
    void foreignImplViolationIsCaught() {
        List<String> importers = new ArrayList<>(List.of(
                API, EXECUTION, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE, AGENT, MCP));
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(importers.toArray(new String[0]))
                .should().dependOnClassesThat().resideInAPackage(BASE + ".task.impl..")
                .because("чужой .impl — внутренность модуля")
                .as("Имплементации модуля task скрыты за контрактом");

        assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(
                ArchUnitForeignImplViolator.class, TaskRegistryImpl.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Имплементации модуля task скрыты за контрактом")
                .hasMessageContaining("ArchUnitForeignImplViolator")
                .hasMessageContaining("TaskRegistryImpl");
    }

    /** S.1: нарушение «чужой .impl» (api → agent.impl) роняет параметризованное правило. */
    @Test
    void agentImplViolationIsCaught() {
        List<String> importers = new ArrayList<>(List.of(
                API, EXECUTION, TASK, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE, MCP));
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(importers.toArray(new String[0]))
                .should().dependOnClassesThat().resideInAPackage(BASE + ".agent.impl..")
                .because("чужой .impl — внутренность модуля")
                .as("Имплементации модуля agent скрыты за контрактом");

        assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(
                ArchUnitAgentImplViolator.class, ArchUnitAgentImplFixture.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Имплементации модуля agent скрыты за контрактом")
                .hasMessageContaining("ArchUnitAgentImplViolator")
                .hasMessageContaining("ArchUnitAgentImplFixture");
    }

    /** Нарушение слоёв (session → task) роняет MODULE_LAYERING. */
    @Test
    void layerViolationIsCaught() {
        assertThatThrownBy(() -> MODULE_LAYERING.check(new ClassFileImporter().importClasses(
                ArchUnitLayerViolator.class, TaskRegistry.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ArchUnitLayerViolator")
                .hasMessageContaining("TaskRegistry");
    }

    /** Цикл между слайсами роняет правило beFreeOfCycles. */
    @Test
    void dependencyCycleIsCaught() {
        assertThatThrownBy(() -> slices().matching(BASE + ".violation.(*)..")
                .should().beFreeOfCycles()
                .because("циклы между модулями запрещены")
                .as("Слайсы violation свободны от циклов")
                .check(new ClassFileImporter().importClasses(CycleFixtureA.class, CycleFixtureB.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Cycle detected");
    }
}
