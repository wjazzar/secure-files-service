package com.praxedo.securefiles.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * The architecture rules the build refuses to let anyone break.
 *
 * <p><strong>These rules are the boundary.</strong> The project is a single
 * Maven module — the layers are packages, not artefacts — so nothing else
 * stops an import from pointing the wrong way. Weakening a rule here silently
 * removes a guarantee; each one therefore states what it protects.
 *
 * <p>They are checked on production classes only: tests legitimately reach
 * across layers to set a situation up.
 */
@AnalyzeClasses(
        packages = "com.praxedo.securefiles",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringRulesTest {

    @ArchTest
    static final ArchRule dependencies_point_inwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("domain").definedBy("com.praxedo.securefiles.domain..")
            .layer("application").definedBy("com.praxedo.securefiles.application..")
            .layer("infrastructure").definedBy("com.praxedo.securefiles.infrastructure..")
            // The root holds main() only; the wiring is in config. Both belong to
            // the composition: a package outside every layer would escape this rule.
            .layer("composition").definedBy("com.praxedo.securefiles", "com.praxedo.securefiles.config..")

            .whereLayer("composition").mayNotBeAccessedByAnyLayer()
            .whereLayer("infrastructure").mayOnlyBeAccessedByLayers("composition")
            .whereLayer("application").mayOnlyBeAccessedByLayers("infrastructure", "composition")
            .whereLayer("domain").mayOnlyBeAccessedByLayers("application", "infrastructure", "composition")

            .as("dependencies point inwards: composition -> infrastructure -> application -> domain");

    /**
     * What the removed {@code domain} Maven module used to guarantee by simply
     * not having these libraries on its classpath.
     *
     * <p>No exception, persistence included: the aggregate is a plain class, and
     * its JPA mapping is {@code StoredFileEntity}, in the persistence adapter.
     */
    @ArchTest
    static final ArchRule the_domain_knows_no_framework = noClasses()
            .that().resideInAPackage("com.praxedo.securefiles.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax..",
                    "org.hibernate..",
                    "software.amazon..",
                    "org.flywaydb..",
                    "com.fasterxml..",
                    "org.slf4j..",
                    "java.sql..",
                    "javax.sql..")
            .because("the domain must stay testable without any infrastructure, and must not "
                    + "be able to change a status outside its own transitions (ARCHITECTURE.md §3). "
                    + "Its persistence mapping lives in the persistence adapter, not on the aggregate");

    /** Use cases talk to ports. A port is an interface we own. */
    @ArchTest
    static final ArchRule the_application_layer_knows_no_framework = noClasses()
            .that().resideInAPackage("com.praxedo.securefiles.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "software.amazon..",
                    "org.flywaydb..",
                    "java.sql..",
                    "javax.sql..")
            .because("a use case orchestrates ports; it does not know what implements them "
                    + "(ARCHITECTURE.md §5)");

    /** Rule B-6: only the persistence adapter writes a status. */
    @ArchTest
    static final ArchRule only_the_persistence_adapter_speaks_sql = noClasses()
            .that().resideOutsideOfPackage("com.praxedo.securefiles.infrastructure.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..")
            .because("every state transition goes through one reviewable place (rule B-6)");

    /**
     * Rule B-10: no local state. A temporary file on one node is invisible to
     * the next, and the service is meant to run on several.
     */
    @ArchTest
    static final ArchRule no_local_filesystem = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File")
            .because("no local state, no shared temporary file (rule B-10)");

    /** Rule B-1: file content never materialises in memory. */
    @ArchTest
    static final ArchRule no_content_held_in_memory = noClasses()
            .that().resideInAnyPackage(
                    "com.praxedo.securefiles.domain..",
                    "com.praxedo.securefiles.application..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.io.ByteArrayOutputStream")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.ByteArrayInputStream")
            .because("file content is streamed from end to end, never buffered (rule B-1)");
}
