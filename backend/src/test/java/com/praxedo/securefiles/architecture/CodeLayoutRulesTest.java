package com.praxedo.securefiles.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import jakarta.persistence.Entity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * How the code is laid out, everywhere: <strong>by concept first, then by
 * kind</strong>.
 *
 * <pre>
 * domain/&lt;concept&gt;/&lt;kind&gt;                      domain/file/model, domain/file/valueobject, domain/owner/valueobject
 * application/&lt;concept&gt;/&lt;kind&gt;                 application/file/port/in, …/port/out, …/service, …/model, …/exception
 * infrastructure/&lt;adapter&gt;/&lt;concept&gt;/&lt;kind&gt;     infrastructure/web/file/controller, infrastructure/persistence/file/adapter, …
 * </pre>
 *
 * <p>The concept says <em>what the code is about</em> — today {@code file}, with
 * {@code owner} in the domain and {@code common} for what every concept shares.
 * The kind says <em>what the code is</em>: a controller, a DTO, an adapter, a
 * port, an exception. Opening a folder should be enough to know what is inside;
 * a new concept gets its own folder with the same kinds beneath. These rules
 * keep the layout from eroding one convenient shortcut at a time.
 */
@AnalyzeClasses(
        packages = "com.praxedo.securefiles",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CodeLayoutRulesTest {

    private static final String ROOT = "com.praxedo.securefiles";

    // ── depth: nothing sits flat above its kind ───────────────────────────

    @ArchTest
    static final ArchRule domain_is_sorted_by_concept_then_kind = classes()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().resideInAPackage(ROOT + ".domain.*.*..");

    @ArchTest
    static final ArchRule application_is_sorted_by_concept_then_kind = classes()
            .that().resideInAPackage(ROOT + ".application..")
            .should().resideInAPackage(ROOT + ".application.*.*..");

    @ArchTest
    static final ArchRule infrastructure_is_sorted_by_adapter_then_concept_then_kind = classes()
            .that().resideInAPackage(ROOT + ".infrastructure..")
            .should().resideInAPackage(ROOT + ".infrastructure.*.*.*..");

    // ── kinds: each kind of class lives in the folder named after it ─────

    @ArchTest
    static final ArchRule controllers_live_in_controller_folders = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(ROOT + ".infrastructure.web.*.controller")
            .andShould().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule responses_live_in_dto_folders = classes()
            .that().resideInAPackage(ROOT + ".infrastructure..")
            .and().haveSimpleNameEndingWith("Response")
            .should().resideInAPackage(ROOT + ".infrastructure.web.*.dto");

    @ArchTest
    static final ArchRule mappers_live_in_mapper_folders = classes()
            .that().resideInAPackage(ROOT + "..")
            .and().haveSimpleNameEndingWith("Mapper")
            .should().resideInAPackage("..mapper");

    @ArchTest
    static final ArchRule exceptions_live_in_exception_folders = classes()
            .that().resideInAPackage(ROOT + "..")
            .and().areTopLevelClasses()
            .and().areAssignableTo(Throwable.class)
            .should().resideInAPackage("..exception");

    @ArchTest
    static final ArchRule adapters_live_in_adapter_folders = classes()
            .that().resideInAPackage(ROOT + ".infrastructure..")
            .and().areNotInterfaces()
            .and().implement(describe("a port out",
                    port -> port.getPackageName().startsWith(ROOT + ".application.")
                            && port.getPackageName().endsWith(".port.out")))
            .should().resideInAPackage("..adapter");

    @ArchTest
    static final ArchRule entities_live_in_the_persistence_adapter = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage(ROOT + ".infrastructure.persistence.*.entity")
            .andShould().haveSimpleNameEndingWith("Entity")
            .because("a JPA entity is a persistence model: the aggregate it maps stays in the domain, "
                    + "free of any framework");

    @ArchTest
    static final ArchRule repositories_live_in_repository_folders = classes()
            .that().areAssignableTo(Repository.class)
            .should().resideInAPackage(ROOT + ".infrastructure.persistence.*.repository");

    @ArchTest
    static final ArchRule adapter_configuration_lives_in_config_folders = classes()
            .that().resideInAPackage(ROOT + ".infrastructure..")
            .and().areAnnotatedWith(Configuration.class)
            .or().areAnnotatedWith(ConfigurationProperties.class)
            .and().resideInAPackage(ROOT + ".infrastructure..")
            .should().resideInAPackage(ROOT + ".infrastructure.*.common.config");

    @ArchTest
    static final ArchRule errors_are_translated_in_one_place = classes()
            .that().areAnnotatedWith(RestControllerAdvice.class)
            .should().resideInAPackage(ROOT + ".infrastructure.web.common.error")
            .because("every error becomes a response in a single place (ApiExceptionHandler)");
}
