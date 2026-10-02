package com.praxedo.securefiles.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.springframework.context.annotation.Bean;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The hexagon, verified on every build.
 *
 * <pre>
 *   DRIVING adapters                 APPLICATION                   DRIVEN adapters
 *   (they call the core)            (the core)                    (the core calls them)
 *
 *   web ─────────┐            ┌─ port/in ──▶ service ──▶ port/out ─┐       ┌── persistence
 *   scheduling ──┼──────────▶ │  (use cases)  (implements)  (needs)  │ ◀─────┼── storage
 *   metrics ─────┘            └──────────────── domain ────────────┘       ├── antivirus
 *                                                                          └── security
 * </pre>
 *
 * <ul>
 *   <li>A driving adapter reaches the core <strong>only through a port in</strong>:
 *       it never sees a service, nor a port out.</li>
 *   <li>A driven adapter <strong>implements a port out</strong> and never calls a
 *       use case: the core calls it, not the other way round.</li>
 *   <li>Adapters never depend on one another: each one can be replaced alone.</li>
 *   <li>Every port in has a service implementing it; ports are interfaces.</li>
 * </ul>
 *
 * <p>The composition root ({@code com.praxedo.securefiles.config}) is the one
 * place allowed to know services: it assembles them and publishes each one as
 * its port in. It is also the one place that builds an adapter behind a port:
 * reading it is enough to know how the hexagon is assembled.
 */
@AnalyzeClasses(
        packages = "com.praxedo.securefiles",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    private static final String ROOT = "com.praxedo.securefiles";
    private static final String PORTS_IN = ROOT + ".application..port.in..";
    private static final String PORTS_OUT = ROOT + ".application..port.out..";
    private static final String SERVICES = ROOT + ".application..service..";
    private static final String COMPOSITION = ROOT + ".config";

    private static final String[] DRIVING_ADAPTERS = {
            ROOT + ".infrastructure.web..",
            ROOT + ".infrastructure.scheduling..",
            ROOT + ".infrastructure.metrics.."};

    private static final String[] DRIVEN_ADAPTERS = {
            ROOT + ".infrastructure.persistence..",
            ROOT + ".infrastructure.storage..",
            ROOT + ".infrastructure.antivirus..",
            ROOT + ".infrastructure.security.."};

    @ArchTest
    static final ArchRule driving_adapters_enter_through_ports_in = noClasses()
            .that().resideInAnyPackage(DRIVING_ADAPTERS)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICES, PORTS_OUT)
            .because("a controller, a scheduler or a metrics binder asks the core through a use case "
                    + "(port in); it never calls a service directly, nor reaches past the core to a port out");

    @ArchTest
    static final ArchRule driven_adapters_never_call_the_core = noClasses()
            .that().resideInAnyPackage(DRIVEN_ADAPTERS)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICES, PORTS_IN)
            .because("a driven adapter answers the core through the port out it implements; "
                    + "it does not trigger use cases");

    @ArchTest
    static final ArchRule adapters_are_independent = slices()
            .matching(ROOT + ".infrastructure.(*)..")
            .should().notDependOnEachOther()
            .because("each adapter can be replaced alone: web, scheduling, metrics, persistence, "
                    + "storage, antivirus and security only meet in the core");

    @ArchTest
    static final ArchRule services_are_reached_only_through_their_ports = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".application..", COMPOSITION)
            .should().dependOnClassesThat().resideInAPackage(SERVICES)
            .because("only the composition root knows which service implements a use case");

    @ArchTest
    static final ArchRule ports_are_wired_in_the_composition_root = methods()
            .that().areAnnotatedWith(Bean.class)
            .and().haveRawReturnType(describe("a port",
                    type -> type.getPackageName().startsWith(ROOT + ".application.")
                            && (type.getPackageName().endsWith(".port.in")
                            || type.getPackageName().endsWith(".port.out"))))
            .should().beDeclaredInClassesThat().resideInAPackage(COMPOSITION)
            .because("which implementation answers a port is decided in one package, config: "
                    + "an adapter built by a @Bean method anywhere else would be wiring nobody finds");

    @ArchTest
    static final ArchRule ports_are_interfaces = classes()
            .that().resideInAnyPackage(PORTS_IN, PORTS_OUT)
            .and().areTopLevelClasses()
            .should().beInterfaces();

    @ArchTest
    static final ArchRule use_cases_are_named_after_what_they_do = classes()
            .that().resideInAPackage(PORTS_IN)
            .and().areTopLevelClasses()
            .should().haveSimpleNameEndingWith("UseCase");

    @ArchTest
    static final ArchRule every_use_case_is_implemented_by_a_service = classes()
            .that().resideInAPackage(PORTS_IN)
            .and().areTopLevelClasses()
            .should(beImplementedByAService());

    @ArchTest
    static final ArchRule services_implement_ports_not_frameworks = classes()
            .that().resideInAPackage(SERVICES)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    ROOT + ".application..", ROOT + ".domain..", "java..", "org.slf4j..")
            .because("a service orchestrates ports and the domain — nothing else");

    private static ArchCondition<JavaClass> beImplementedByAService() {
        return new ArchCondition<>("be implemented by a service of the application layer") {
            @Override
            public void check(JavaClass useCase, ConditionEvents events) {
                boolean implemented = useCase.getAllSubclasses().stream()
                        .anyMatch(candidate -> candidate.getPackageName().startsWith(ROOT + ".application.")
                                && candidate.getPackageName().endsWith(".service"));
                if (!implemented) {
                    events.add(SimpleConditionEvent.violated(useCase,
                            useCase.getName() + " has no service implementing it"));
                }
            }
        };
    }
}
