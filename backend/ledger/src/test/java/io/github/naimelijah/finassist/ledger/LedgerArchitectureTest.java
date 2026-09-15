package io.github.naimelijah.finassist.ledger;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * <p>These rules are live. The {@code archRule.failOnEmptyShould=false} escape hatch that kept them
 * dormant over an empty scaffold was deleted with the first domain class, so a rule that matches
 * nothing now fails rather than passing quietly.
 */
@AnalyzeClasses(packages = LedgerArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class LedgerArchitectureTest {

    static final String ROOT = "io.github.naimelijah.finassist.ledger";

    /** The in-language kernel every context may depend on. Nothing else is shared. */
    private static final String SHARED = ROOT + ".shared..";

    /**
     * The composition root: the one package that may see two contexts at once, because assembling
     * them is what it is for. Mirrors the assistant's {@code adapters/} package. See ADR-022.
     */
    private static final String COMPOSITION = ROOT + ".composition..";

    /**
     * What {@code domain_depends_on_no_framework} and {@code
     * the_application_layer_depends_on_no_framework} both forbid.
     *
     * <p>{@code tools.jackson..} sits beside {@code com.fasterxml.jackson..} because Spring Boot 4
     * moved Jackson 3's databind and core packages to {@code tools.jackson}, while its annotations
     * stayed at {@code com.fasterxml.jackson.annotation}. Naming only the old package would let a
     * {@code tools.jackson.databind.ObjectMapper} field into either layer undetected. {@code
     * io.swagger..} and {@code org.springdoc..} close the same gap for the OpenAPI annotations and
     * generator the build gained alongside it (ADR-028).
     */
    private static final String[] FORBIDDEN_FRAMEWORK_PACKAGES = {
        "org.springframework..",
        "jakarta..",
        "javax..",
        "com.fasterxml.jackson..",
        "tools.jackson..",
        "io.swagger..",
        "org.springdoc.."
    };

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
            // One way only. The companion rule below forbids the reverse, so this exemption is a door
            // rather than a hole: without it, a context could reach the wiring that assembles it.
            // Removing this line fails the rule three times over, once per context the root wires.
            .ignoreDependency(resideInAPackage(COMPOSITION), alwaysTrue())
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
            .resideInAnyPackage(FORBIDDEN_FRAMEWORK_PACKAGES)
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

    /**
     * A cycle between aggregates inside one context means an aggregate boundary is wrong.
     *
     * <p>Both the context and the aggregate are captured. Matching on the aggregate alone would
     * merge {@code accounting.domain.event} and {@code budgeting.domain.event} into one slice
     * named "event", which can hide a real intra-context cycle behind the merge and report a
     * cross-context dependency as an aggregate cycle.
     */
    @ArchTest
    static final ArchRule aggregates_are_free_of_cycles = slices().matching(ROOT + ".(*).domain.(*)..")
            .namingSlices("$1.$2")
            .should()
            .beFreeOfCycles();

    /**
     * The composition root sees everything and is seen by nothing.
     *
     * <p>The other half of the exemption above. {@code contexts_do_not_depend_on_each_other} now
     * ignores dependencies originating in {@code composition}, which on its own would leave the door
     * open in both directions. This is what keeps it one-way, and it is the same rule the assistant
     * already enforces on its own composition root.
     */
    @ArchTest
    static final ArchRule no_context_depends_on_the_composition_root = noClasses()
            .that()
            .resideOutsideOfPackage(COMPOSITION)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(COMPOSITION)
            .because("frameworks and cross-context translation travel inward only through declared ports");

    /**
     * The application layer is wired by constructor, not by the container.
     *
     * <p>{@code LedgerApplication} has promised this since the scaffold and nothing checked it, so a
     * single {@code @Service} would have quietly made the layer untestable without Spring. The
     * annotations live in {@code composition}; a use case is a plain object taking its ports as
     * constructor arguments, which is what lets every service test run with no context at all.
     */
    @ArchTest
    static final ArchRule the_application_layer_depends_on_no_framework = noClasses()
            .that()
            .resideInAPackage(ROOT + "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN_FRAMEWORK_PACKAGES)
            .because("a use case that needs a container to construct cannot be tested by calling it");

    /**
     * An inbound adapter drives the application through its in-ports and nothing else.
     *
     * <p>{@code layers_point_inward} treats every adapter as one layer that may reach the whole
     * application layer, so on its own it lets a controller depend on three things it must not:
     *
     * <ul>
     *   <li>an outbound adapter, or an out-port such as a repository, which skips the use case and every
     *       rule the use case enforces;
     *   <li>a concrete service in {@code application.service}. {@code LedgerConfiguration} registers each
     *       service as a bean, so a controller injecting {@code TransactionService} starts and passes
     *       every other rule. It is then bound to one implementation and to every port that
     *       implementation also serves, rather than to the use case it calls.
     * </ul>
     *
     * <p>A controller may still name what it translates: the in-ports with their commands and exceptions,
     * the domain types they return, and {@code shared}. Only the <em>interfaces</em> in {@code port.out}
     * are forbidden. The records there stay reachable, because an in-port's command may carry one:
     * planning's {@code ProjectScenarioCommand} holds {@code AccountRef}, which lives in {@code port.out}
     * beside the port that consumes it, so a controller building that command has to name it.
     */
    @ArchTest
    static final ArchRule inbound_adapters_reach_only_in_ports = noClasses()
            .that()
            .resideInAPackage(ROOT + "..adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + "..adapter.out..")
            .orShould()
            .dependOnClassesThat(
                    resideInAPackage(ROOT + "..application.port.out..").and(INTERFACES))
            .orShould()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + "..application.service..")
            .because("a controller reaches the application only through the in-ports it drives");

    /**
     * An outbound adapter serves the application and knows nothing of how the application was called.
     *
     * <p>A store that named a controller's transport type would tie the schema mapping to the wire
     * shape, so a change to the contract would reach the database.
     */
    @ArchTest
    static final ArchRule outbound_adapters_do_not_reach_inbound = noClasses()
            .that()
            .resideInAPackage(ROOT + "..adapter.out..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + "..adapter.in..")
            .because("the wire shape must not reach the store");

    /**
     * springdoc names a schema by simple class name and silently keeps only one of two classes that
     * share one, so a second context's DTO can shadow the first's without either build failing on its
     * own. {@code MoneyDto} is the one deliberate exception: each context declares an
     * identical copy, on purpose, because the slice rule leaves no legal shared home for it. {@code
     * MoneyDtoShapesTest} is what holds the copies identical.
     */
    private static final String SHARED_DTO_SIMPLE_NAME = "MoneyDto";

    /**
     * Anonymous classes are skipped. springdoc has no name to key a schema by, and ArchUnit gives every one
     * the same empty simple name. javac emits one wherever a class switches over an enum declared elsewhere,
     * the synthetic {@code $SwitchMap$} holder such as {@code RecurrenceRequest$1}, so without the skip a
     * second such switch in any web adapter would fail this rule on two classes that share nothing but the
     * absence of a name.
     */
    @ArchTest
    static final ArchRule web_dto_names_are_unique_across_contexts = classes()
            .that()
            .resideInAPackage(ROOT + "..adapter.in.web..")
            .and()
            .areNotAnonymousClasses()
            .should(haveASimpleNameUniqueAcrossContexts())
            .because("springdoc silently drops one schema when two classes share a simple name");

    /**
     * {@link ArchCondition#init} sees every matched class before {@link ArchCondition#check} is asked
     * about any one of them, which is what makes a cross-class check like "no two classes share a
     * name" possible without a second import pass.
     */
    private static ArchCondition<JavaClass> haveASimpleNameUniqueAcrossContexts() {
        return new ArchCondition<>("have a simple name unique across every context, except MoneyDto") {

            private final Map<String, List<JavaClass>> classesByName = new HashMap<>();

            @Override
            public void init(Collection<JavaClass> allClasses) {
                classesByName.clear();
                for (JavaClass clazz : allClasses) {
                    classesByName
                            .computeIfAbsent(clazz.getSimpleName(), name -> new ArrayList<>())
                            .add(clazz);
                }
            }

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String name = item.getSimpleName();
                List<JavaClass> sharingTheName = classesByName.getOrDefault(name, List.of());
                if (!SHARED_DTO_SIMPLE_NAME.equals(name) && sharingTheName.size() > 1) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            item.getFullName() + " shares the simple name " + name + " with "
                                    + otherFullNames(sharingTheName, item)));
                }
            }

            private String otherFullNames(List<JavaClass> sharingTheName, JavaClass self) {
                return sharingTheName.stream()
                        .filter(other -> !other.equals(self))
                        .map(JavaClass::getFullName)
                        .collect(Collectors.joining(", "));
            }
        };
    }
}
