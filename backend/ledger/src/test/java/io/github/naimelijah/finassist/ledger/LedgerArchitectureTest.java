package io.github.naimelijah.finassist.ledger;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the structural rules the ledger is built on, so that they fail a build rather than
 * a review.
 *
 * <p>The ledger is the invariant-carrying half of FinAssist and uses tactical DDD in full; the
 * assistant deliberately does not. That asymmetry, and the reasoning behind it, is recorded in
 * {@code docs/ADRs/ADR-007-asymmetric-hexagonal-architecture.md}.
 *
 * <p>These rules are dormant while the production packages are empty: {@code archunit.properties}
 * sets {@code archRule.failOnEmptyShould=false} so a scaffold does not fail the build. Remove that setting
 * with the first domain class.
 */
@AnalyzeClasses(packages = LedgerArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class LedgerArchitectureTest {

    static final String ROOT = "io.github.naimelijah.finassist.ledger";

    /** Dependencies point inward only. An adapter may be depended on by nothing. */
    @ArchTest
    static final ArchRule layers_point_inward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            // A layer may legitimately be empty; the direction is what this rule protects.
            .withOptionalLayers(true)
            .layer("Domain")
            .definedBy(ROOT + ".domain..")
            .layer("Application")
            .definedBy(ROOT + ".application..")
            .layer("Adapter")
            .definedBy(ROOT + ".adapter..")
            .whereLayer("Adapter")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapter")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapter");

    /**
     * The domain stays framework-free, which is what lets it be tested without Spring and what
     * keeps persistence concerns out of the aggregates. JPA entities belong in the outbound
     * adapter and are mapped; an entity is not an aggregate.
     */
    @ArchTest
    static final ArchRule domain_depends_on_no_framework = noClasses()
            .that()
            .resideInAPackage(ROOT + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "javax..", "com.fasterxml.jackson..")
            .because("the domain is the product; frameworks are replaceable detail");

    /**
     * Money is exact or it is wrong. A floating-point field in the domain is a defect, not a
     * performance choice.
     */
    @ArchTest
    static final ArchRule money_is_never_floating_point = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(ROOT + ".domain..")
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    /** A cycle between top-level packages means a boundary is in the wrong place. */
    @ArchTest
    static final ArchRule packages_are_free_of_cycles =
            slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();
}
