package io.github.naimelijah.finassist.ledger;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the structural rules the ledger is built on, so that they fail a build rather than a
 * review.
 *
 * <p>The ledger is organised context first: each bounded context owns a complete hexagon, so a
 * context is one directory and can be extracted by moving it. Two rules protect that shape - layers
 * point inward within a context, and contexts do not reach into each other. Without the second one,
 * context-first packaging is only folders. See {@code
 * docs/ADRs/ADR-010-bounded-context-map.md}.
 *
 * <p>These rules are dormant while the context packages are empty: {@code archunit.properties} sets
 * {@code archRule.failOnEmptyShould=false} so a scaffold does not fail the build. Remove that
 * setting with the first domain class.
 */
@AnalyzeClasses(packages = LedgerArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class LedgerArchitectureTest {

    static final String ROOT = "io.github.naimelijah.finassist.ledger";

    /** The in-language kernel every context may depend on. Nothing else is shared. */
    private static final String SHARED = ROOT + ".shared..";

    /**
     * Dependencies point inward only, in every context at once. The patterns span contexts
     * deliberately: {@code ..domain..} matches {@code accounting.domain} and {@code
     * budgeting.domain} alike, so a new context inherits the rule without editing this test.
     */
    @ArchTest
    static final ArchRule layers_point_inward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            // A layer may legitimately be empty; the direction is what this rule protects.
            .withOptionalLayers(true)
            .layer("Domain")
            .definedBy(ROOT + "..domain..")
            .layer("Application")
            .definedBy(ROOT + "..application..")
            .layer("Adapter")
            .definedBy(ROOT + "..adapter..")
            .whereLayer("Adapter")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapter")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapter");

    /**
     * Bounded contexts are isolated. Accounting may not reach into budgeting, in either direction
     * and at any layer; the only common ground is {@code shared}.
     *
     * <p>This is the rule that makes context-first packaging worth the nesting. Where two contexts
     * genuinely need to collaborate, the answer is a published port and an explicit translation,
     * not an import.
     */
    @ArchTest
    static final ArchRule contexts_do_not_depend_on_each_other = slices().matching(ROOT + ".(*)..")
            .namingSlices("context $1")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAPackage(SHARED))
            .because("a context must be extractable by moving its directory");

    /**
     * The domain stays framework-free, which is what lets it be tested without Spring and what keeps
     * persistence concerns out of the aggregates. JPA entities belong in the outbound adapter and
     * are mapped; an entity is not an aggregate.
     */
    @ArchTest
    static final ArchRule domain_depends_on_no_framework = noClasses()
            .that()
            .resideInAPackage(ROOT + "..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..", "javax..", "com.fasterxml.jackson..")
            .because("the domain is the product; frameworks are replaceable detail");

    /**
     * Money is exact or it is wrong. A floating-point field in any context's domain is a defect, not
     * a performance choice.
     */
    @ArchTest
    static final ArchRule money_is_never_floating_point = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(ROOT + "..domain..")
            .should()
            .haveRawType(double.class)
            .orShould()
            .haveRawType(float.class)
            .orShould()
            .haveRawType(Double.class)
            .orShould()
            .haveRawType(Float.class)
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    /** A cycle between aggregates inside one context means an aggregate boundary is wrong. */
    @ArchTest
    static final ArchRule aggregates_are_free_of_cycles =
            slices().matching(ROOT + "..domain.(*)..").should().beFreeOfCycles();
}
