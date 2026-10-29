package io.github.naimelijah.finassist.ledger;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.naimelijah.finassist.ledger.accounting.domain.account.AccountType;
import io.github.naimelijah.finassist.ledger.planning.domain.forecast.Comparison;
import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     *
     * <p>{@code org.slf4j..}, {@code io.opentelemetry..} and {@code io.micrometer..} are the logging,
     * tracing and metrics libraries the compile classpath carries for ADR-019. Observability is an
     * edge concern: a logger or a meter in an aggregate or a use case puts output in the core, which
     * "nothing writes output except the logging boundary" (root {@code CLAUDE.md}) keeps out. {@code
     * org.flywaydb..} is the migration engine and {@code com.nimbusds..} the token library the
     * resource server pulls in; either in the core is persistence or security arriving without a
     * Spring type the entries above would see.
     *
     * <p>{@code org.slf4j..} is not the only logging API on that classpath. {@code
     * spring-boot-starter-logging} also brings Logback ({@code ch.qos.logback..}), the Log4j 2 API
     * through {@code log4j-to-slf4j} ({@code org.apache.logging..}), and the bridge for the JDK's own
     * {@code java.util.logging..}; a {@code Logger} from any of them writes output from the core as
     * surely as an SLF4J one, so each is listed. {@code com.zaxxer.hikari..} is the connection pool
     * {@code spring-boot-starter-jdbc} brings: a pool in the core is persistence without a {@code
     * java.sql} type in sight. Commons Logging ({@code org.apache.commons.logging..}) arrives
     * another way: {@code spring-core} declares it at compile scope, so a {@code LogFactory.getLog}
     * in an aggregate compiles, and {@code org.apache.logging..} names Log4j alone.
     *
     * <p>{@code java.sql..}, {@code java.net..}, {@code java.io..}, {@code java.nio.file..} and {@code
     * java.nio.channels..} are the JDK's own I/O seams: a {@code Connection}, an {@code HttpClient}, a
     * {@code Reader}, a {@code Path} or a {@code FileChannel} in the domain or a use case is
     * persistence, transport or file access arriving without a framework, which the other entries
     * cannot see because it imports none. The rest of {@code java.nio} is not I/O - a {@code
     * StandardCharsets} constant or a {@code ByteBuffer} touches nothing outside the process - so it
     * stays allowed.
     */
    private static final String[] FORBIDDEN_FRAMEWORK_PACKAGES = {
        "org.springframework..",
        "jakarta..",
        "javax..",
        "com.fasterxml.jackson..",
        "tools.jackson..",
        "io.swagger..",
        "org.springdoc..",
        "org.slf4j..",
        "ch.qos.logback..",
        "org.apache.logging..",
        "org.apache.commons.logging..",
        "java.util.logging..",
        "io.opentelemetry..",
        "io.micrometer..",
        "org.flywaydb..",
        "com.nimbusds..",
        "com.zaxxer.hikari..",
        "java.sql..",
        "java.net..",
        "java.io..",
        "java.nio.file..",
        "java.nio.channels.."
    };

    /**
     * Where a monetary amount can live: every context's domain, and the {@code shared} kernel that
     * holds {@code Money} itself.
     */
    private static final String[] MONEY_HOLDING_PACKAGES = {ROOT + "..domain..", SHARED};

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
     *
     * <p>The {@code shared} kernel is held to the same rule. Every domain depends on {@code Money}, so
     * a framework type in {@code shared} would reach each of them through a dependency this rule,
     * scoped to {@code ..domain..} alone, never sees.
     */
    @ArchTest
    static final ArchRule domain_depends_on_no_framework = noClasses()
            .that()
            .resideInAnyPackage(ROOT + "..domain..", SHARED)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN_FRAMEWORK_PACKAGES)
            .because("the domain is the product; frameworks are replaceable detail");

    /**
     * Money is exact or it is wrong. A floating-point field in any context's domain, or in the kernel
     * that holds {@code Money}, is a defect, not a performance choice. An array of them is one too,
     * at any depth, which is why the raw type's base component is what is checked.
     */
    @ArchTest
    static final ArchRule money_is_never_floating_point = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(MONEY_HOLDING_PACKAGES)
            .should()
            .haveRawType(floatingPoint())
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    /**
     * The field rule sees only a field's raw type, so a {@code List<Double>} field, or a {@code Double}
     * handed to or returned from a method, passes it. A dependency on the boxed types catches those,
     * generic type arguments included, and any boxing or unboxing, which javac compiles to a call on
     * {@code Double} or {@code Float}.
     */
    @ArchTest
    static final ArchRule money_never_depends_on_a_boxed_floating_point_type = noClasses()
            .that()
            .resideInAnyPackage(MONEY_HOLDING_PACKAGES)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(Double.class, Float.class)
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    /**
     * ArchUnit records no dependency on a primitive, so {@code static BigDecimal fromDouble(double)}
     * passes both rules above. A method or constructor taking or returning a floating-point type is
     * the door a binary approximation walks in through.
     */
    @ArchTest
    static final ArchRule money_is_never_passed_or_returned_as_floating_point = noCodeUnits()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage(MONEY_HOLDING_PACKAGES)
            .should()
            .haveRawReturnType(floatingPoint())
            .orShould()
            .haveRawParameterTypes(anyElement(floatingPoint()))
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    /**
     * A primitive {@code double} local leaves no type in the bytecode ArchUnit reads, and none of the
     * rules above sees a call to code outside the ledger. So what is checked is the call through which
     * a binary approximation reaches money or leaves it: no call, and no method or constructor
     * reference, whose target takes or returns a floating-point type, such as {@code
     * BigDecimal.valueOf(cents * 0.1)}, {@code new BigDecimal(double)} or {@code
     * BigDecimal.doubleValue()}. Arithmetic on a {@code double} that never crosses a call, such as
     * {@code (long) (cents * 1.1)}, still passes: no bytecode rule sees it.
     */
    @ArchTest
    static final ArchRule money_never_calls_through_floating_point = noClasses()
            .that()
            .resideInAnyPackage(MONEY_HOLDING_PACKAGES)
            .should()
            .accessTargetWhere(takesOrReturnsFloatingPoint())
            .because("monetary amounts use BigDecimal; see docs/requirements/requirements.md");

    private static DescribedPredicate<JavaAccess<?>> takesOrReturnsFloatingPoint() {
        DescribedPredicate<JavaClass> floatingPoint = floatingPoint();
        return new DescribedPredicate<>(
                "call or reference code taking or returning " + floatingPoint.getDescription()) {
            @Override
            public boolean test(JavaAccess<?> access) {
                return access.getTarget() instanceof CodeUnitAccessTarget codeUnit
                        && (floatingPoint.test(codeUnit.getRawReturnType())
                                || codeUnit.getRawParameterTypes().stream().anyMatch(floatingPoint));
            }
        };
    }

    private static final Set<Class<?>> FLOATING_POINT_TYPES =
            Set.of(double.class, float.class, Double.class, Float.class);

    private static DescribedPredicate<JavaClass> floatingPoint() {
        return new DescribedPredicate<>("a floating-point type, or an array of one") {
            @Override
            public boolean test(JavaClass type) {
                JavaClass base = type.getBaseComponentType();
                return FLOATING_POINT_TYPES.stream().anyMatch(base::isEquivalentTo);
            }
        };
    }

    private static DescribedPredicate<List<JavaClass>> anyElement(DescribedPredicate<JavaClass> predicate) {
        return new DescribedPredicate<>("any of which is " + predicate.getDescription()) {
            @Override
            public boolean test(List<JavaClass> types) {
                return types.stream().anyMatch(predicate);
            }
        };
    }

    /**
     * The quantities "Derived, never stored" forbids a domain field or a column to be named after, as a
     * regular-expression alternation both Java and PostgreSQL read. {@code SchemaBoundariesIT} matches
     * columns against this same constant, so the two halves of the rule cannot drift apart.
     */
    static final String DERIVED_QUANTITIES = "balance|spent|remaining|projection";

    /** The names "Derived, never stored" forbids a domain field to carry, in any case. */
    private static final String DERIVED_QUANTITY_NAME = "(?i).*(" + DERIVED_QUANTITIES + ").*";

    /**
     * Derived, never stored (root {@code CLAUDE.md}, "The domain"): a balance is summed from entries,
     * a budget's spend passed in and its remainder computed, a projection computed from its scenario.
     * A domain field named after one of those quantities is the bug that rule exists to prevent — a
     * value that can drift from the facts it was derived from.
     *
     * <p>Scoped by name, with two exempt fields that hold no stored quantity: {@code
     * AccountType.normalBalance} is the side a balance sits on, a constant of the type rather than an
     * amount; {@code Comparison.projections} is held by a value that is itself derived, computed per
     * request from its projections and never persisted. Any other field of {@code Comparison} is held
     * to the rule. Its storage-side half is {@code SchemaBoundariesIT}'s refusal of a column with such a
     * name.
     */
    @ArchTest
    static final ArchRule derived_quantities_are_never_stored = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(ROOT + "..domain..")
            .and()
            .doNotHaveFullName(AccountType.class.getName() + ".normalBalance")
            .and()
            .doNotHaveFullName(Comparison.class.getName() + ".projections")
            .should()
            .haveNameMatching(DERIVED_QUANTITY_NAME)
            .because("a balance, a spend, a remainder or a projection is derived on every read, never stored");

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

    /** Where each context declares the ports it consumes, a translation's own port among them. */
    private static final String OUT_PORTS = ROOT + "..application.port.out..";

    /**
     * A translation calls the upstream context's in-port, never its repositories (root {@code
     * CLAUDE.md}, Architecture). A translation is a class in {@code composition} that implements a
     * consumer's out-port, as {@code AccountingSpendJournal} implements budgeting's {@code SpendJournal}
     * through accounting's {@code ViewJournalUseCase}. One that read {@code TransactionRepository}
     * instead would skip every rule the upstream's use case enforces - {@code postedBetween} is what
     * drops a voided transaction - and would tie the consumer to the upstream's storage, so extracting
     * either context would become a change of design rather than of transport (ADR-022).
     *
     * <p>{@code contexts_do_not_depend_on_each_other} ignores every dependency that starts in {@code
     * composition}, because {@code LedgerConfiguration} has to name repositories and services to wire
     * them, so nothing else stops a translation reaching them too. Forbidden: a concrete {@code
     * application.service} class, which binds it to one implementation rather than the use case; any
     * adapter; and any out-port interface other than those it implements. The records in {@code
     * port.out} that it translates into, such as {@code SpendRecord} and {@code AccountRef}, stay
     * reachable: they are the consumer's vocabulary, which a translation exists to speak.
     *
     * <p>The root rule's other half, converting the upstream's exceptions at the boundary, is not
     * checked here, since no bytecode rule sees which unchecked exception a call can raise. {@code
     * AccountingOpeningPositionsTest} pins it for the one translation that has one to convert.
     */
    @ArchTest
    static final ArchRule translations_reach_the_upstream_context_only_through_its_in_ports = classes()
            .that(resideInAPackage(COMPOSITION).and(implementAnOutPort()))
            .should(reachNoServiceAdapterOrOtherOutPort())
            .because("a translation that reads a repository skips every rule the upstream's use case enforces");

    private static DescribedPredicate<JavaClass> implementAnOutPort() {
        return new DescribedPredicate<>("implement an interface in " + OUT_PORTS) {
            @Override
            public boolean test(JavaClass type) {
                return type.getAllRawInterfaces().stream().anyMatch(resideInAPackage(OUT_PORTS));
            }
        };
    }

    private static ArchCondition<JavaClass> reachNoServiceAdapterOrOtherOutPort() {
        DescribedPredicate<JavaClass> serviceOrAdapter =
                resideInAPackage(ROOT + "..application.service..").or(resideInAPackage(ROOT + "..adapter.."));
        DescribedPredicate<JavaClass> outPort = resideInAPackage(OUT_PORTS).and(INTERFACES);
        return new ArchCondition<>("depend on no application service, no adapter, and no foreign out-port") {
            @Override
            public void check(JavaClass translation, ConditionEvents events) {
                Set<JavaClass> implemented = translation.getAllRawInterfaces();
                for (Dependency dependency : translation.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    boolean foreignOutPort = outPort.test(target) && !implemented.contains(target);
                    if (serviceOrAdapter.test(target) || foreignOutPort) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * The call-shaped half of the rule above, which sees only classes that implement an out-port and
     * so misses the two natural ways to write a translation without one. {@code SpendJournal} and
     * {@code OpeningPositionProvider} each have a single method, so a {@code @Bean} returning {@code
     * period -> transactions.postedBetween(...)} is a translation whose body javac compiles into
     * {@code LedgerConfiguration}, which implements nothing. And a translation that hands the read to
     * a helper in {@code composition}, nested or top-level, moves the repository into a class that
     * implements nothing either.
     *
     * <p>What separates wiring from translating is the kind of access, not the class. The wiring only
     * <em>constructs</em> repositories, adapters and services, handing each to the next constructor;
     * a translation <em>calls</em> the upstream's in-port. So no class in {@code composition} may call
     * a method, or take a method reference, whose owner is an out-port interface, an {@code
     * application.service} class or an adapter, and constructor calls stay allowed. No composition
     * class makes such a call today: {@code LedgerConfiguration} only constructs, and the security,
     * problem-handling and OpenAPI classes name no out-port, service or adapter at all. A
     * {@code @Transactional} decorator in {@code composition} that delegates to a service, the shape
     * {@code backend/ledger/CLAUDE.md} prescribes for a write across two aggregates, would trip this
     * rule; it should delegate through the in-port it decorates, which is what keeps it a decorator.
     *
     * <p>Still held in review: an in-port call is allowed wherever it is made, so a translation that
     * calls the right in-port but drops what that in-port returns is outside any bytecode rule; and
     * the owner a call names is its static type, so a repository reached through a type outside the
     * ledger, such as an {@code Object} cast or reflection, is not seen.
     */
    @ArchTest
    static final ArchRule composition_calls_no_out_port_service_or_adapter = noClasses()
            .that()
            .resideInAPackage(COMPOSITION)
            .should()
            .accessTargetWhere(callsAMethodOnAnOutPortServiceOrAdapter())
            .because("composition constructs the upstream's repositories and services; only a translation"
                    + " calls the upstream, and it calls its in-ports");

    private static DescribedPredicate<JavaAccess<?>> callsAMethodOnAnOutPortServiceOrAdapter() {
        DescribedPredicate<JavaClass> outPortServiceOrAdapter = resideInAPackage(OUT_PORTS)
                .and(INTERFACES)
                .or(resideInAPackage(ROOT + "..application.service.."))
                .or(resideInAPackage(ROOT + "..adapter.."));
        return new DescribedPredicate<>(
                "call or reference a method of an out-port interface, an application service or an adapter") {
            @Override
            public boolean test(JavaAccess<?> access) {
                return access.getTarget() instanceof CodeUnitAccessTarget codeUnit
                        && !codeUnit.getName().equals(JavaConstructor.CONSTRUCTOR_NAME)
                        && outPortServiceOrAdapter.test(codeUnit.getOwner());
            }
        };
    }

    /**
     * The clock is read once, in the composition root, and passed down (root {@code CLAUDE.md}, "Pass
     * the clock in"). A domain transition takes a date, a projection its horizon, a use case its dates
     * as arguments; a {@code now()} anywhere else makes that code depend on when it runs, which no
     * test can pin.
     *
     * <p>A method reference counts as a read, not only a call: {@code Instant::now} handed to a
     * supplier reads the clock as surely as calling it, and is the form a call-only rule would miss.
     * Every {@code now} overload is refused, the {@code Clock}-taking ones too, because the place to
     * turn a {@code Clock} into a date is the composition root, which is the one package exempt.
     *
     * <p>{@code java.util} reads the clock as well, without saying {@code now}: {@code new Date()}, a
     * {@code Calendar.getInstance} overload and {@code new GregorianCalendar()} each start from the
     * current time, called or as a {@code Date::new} reference. Their other constructors and factories
     * take the instant they hold, so only these forms are refused.
     */
    @ArchTest
    static final ArchRule clock_is_read_only_in_the_composition_root = noClasses()
            .that()
            .resideOutsideOfPackage(COMPOSITION)
            .should()
            .accessTargetWhere(readsTheClock())
            .because("the clock is read once, in the composition root, and passed down as a value");

    /**
     * The read above names its method, so a class that is handed a {@code Clock} or an {@code
     * InstantSource} and calls {@code instant()} or {@code millis()} on it passes that rule while
     * depending on when it runs just the same. Outside the composition root nothing may hold either:
     * what a use case or a domain transition receives is the date the root read from it.
     */
    @ArchTest
    static final ArchRule no_clock_is_held_outside_the_composition_root = noClasses()
            .that()
            .resideOutsideOfPackage(COMPOSITION)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(Clock.class, InstantSource.class)
            .because("the clock is read once, in the composition root, and passed down as a value");

    private static final Set<String> SYSTEM_CLOCK_METHODS = Set.of("currentTimeMillis", "nanoTime");

    private static DescribedPredicate<JavaAccess<?>> readsTheClock() {
        return new DescribedPredicate<>("read the clock: java.time *.now, Clock.system*, InstantSource.system,"
                + " System.currentTimeMillis, System.nanoTime, new Date(), Calendar.getInstance*"
                + " or new GregorianCalendar()") {
            @Override
            public boolean test(JavaAccess<?> access) {
                AccessTarget target = access.getTarget();
                JavaClass owner = target.getOwner();
                String name = target.getName();
                boolean timeNow = owner.getPackageName().startsWith("java.time") && name.equals("now");
                boolean systemClock = (owner.isEquivalentTo(Clock.class) || owner.isEquivalentTo(InstantSource.class))
                        && name.startsWith("system");
                boolean systemTime = owner.isEquivalentTo(System.class) && SYSTEM_CLOCK_METHODS.contains(name);
                boolean calendarNow = owner.isAssignableTo(Calendar.class) && name.startsWith("getInstance");
                boolean currentInstantConstructed =
                        (owner.isEquivalentTo(Date.class) || owner.isEquivalentTo(GregorianCalendar.class))
                                && name.equals(JavaConstructor.CONSTRUCTOR_NAME)
                                && target instanceof CodeUnitAccessTarget constructor
                                && constructor.getRawParameterTypes().isEmpty();
                return timeNow || systemClock || systemTime || calendarNow || currentInstantConstructed;
            }
        };
    }

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
