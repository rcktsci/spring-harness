package se.rocketscien.harness.tests.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

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

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .importPaths(Paths.get("target", "classes"));

    /** Доменные пакеты для проверки циклов: common/config — технические, из графа исключены. */
    private static final JavaClasses DOMAIN_CLASSES = new ClassFileImporter().importPackages(
            BASE + ".api", BASE + ".execution", BASE + ".task", BASE + ".workflow",
            BASE + ".session", BASE + ".identity", BASE + ".intelligence");

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
            .whereLayer("api").mayOnlyAccessLayers("execution", "intelligence", "session", "identity")
            .whereLayer("execution").mayOnlyAccessLayers("session", "identity", "task", "workflow", "intelligence")
            .whereLayer("task").mayOnlyAccessLayers("identity")
            .whereLayer("workflow").mayOnlyAccessLayers("identity")
            .whereLayer("session").mayOnlyAccessLayers("identity")
            .whereLayer("identity").mayNotAccessAnyLayer()
            .whereLayer("api").mayNotBeAccessedByAnyLayer()
            .as("Слои модулей: api → {execution, intelligence, session, identity}; "
                    + "execution → {session, identity, task(M2), workflow(M2), intelligence}; "
                    + "session → identity; identity изолирована; от api никто не зависит");

    @Test
    void moduleLayeringIsRespected() {
        MODULE_LAYERING.check(MAIN_CLASSES);
    }

    @Test
    void noDomainModuleDependsOnApi() {
        noClasses()
                .that().resideInAnyPackage(EXECUTION, TASK, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE)
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
    @ValueSource(strings = {"session", "execution", "intelligence", "identity"})
    void foreignImplPackageIsHiddenBehindContract(String module) {
        List<String> importers = new ArrayList<>(List.of(
                API, EXECUTION, TASK, WORKFLOW, SESSION, IDENTITY, INTELLIGENCE));
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
}
