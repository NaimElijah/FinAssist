package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;

/**
 * Fails the build for a {@code @WebMvcTest} slice that does not {@code @Import} {@link
 * WebSliceSecurity} — until this rule existed, a convention {@code .claude/CLAUDE.md} listed under
 * "enforced by the build, not by review" that nothing enforced.
 *
 * <p>The omission is a false pass, not a loud failure. {@code spring-boot-webmvc-test}'s {@code
 * AutoConfigureWebMvc.imports} names no security auto-configuration at all, so a slice that forgets
 * the import boots with no {@code SecurityFilterChain} and no security filter: every request
 * admitted, every permission moot, and every assertion in that slice green (ADR-030, and {@link
 * WebSliceSecurity}'s own javadoc, which warns of exactly this).
 *
 * <p>Static, like the ledger's other architecture rules: {@link ClassFileImporter} reads the
 * compiled test classes rather than loading them, and both annotations are read as ArchUnit's own
 * {@link JavaClass}, so nothing here depends on a class being loadable. It lives in its own class
 * rather than in {@code LedgerArchitectureTest}, whose {@code @AnalyzeClasses} deliberately
 * excludes the test tree this rule is about.
 *
 * <p>Both checks use {@code isMetaAnnotatedWith}, not {@code isAnnotatedWith}: Spring resolves
 * {@code @WebMvcTest} and {@code @Import} through the meta-annotation graph, so a slice that used
 * a composed annotation — one itself meta-annotated {@code @WebMvcTest}, with no direct {@code
 * @Import} of its own — would be invisible to a check that only reads direct annotations. It
 * would then boot with no {@code SecurityFilterChain} while the scan below found nothing to flag,
 * the exact false pass this rule exists to stop.
 */
class WebSliceSecurityImportTest {

    private static final String ROOT = "io.github.naimelijah.finassist.ledger";

    /** The member {@code @Import} carries: the configuration classes to register. */
    private static final String IMPORTED_CLASSES = "value";

    /**
     * Computed once for the whole class, for the reason {@code ApplicationYmlPlaceholdersTest}
     * gives: importing bytecode is the expensive part, both tests below want the same deterministic
     * scan, and a {@code static final} field leaves neither test depending on the other.
     */
    private static final List<JavaClass> WEB_MVC_SLICES = webMvcSlices();

    private static List<JavaClass> webMvcSlices() {
        JavaClasses tests = new ClassFileImporter()
                .withImportOption(new ImportOption.OnlyIncludeTests())
                .importPackages(ROOT);

        List<JavaClass> slices = new ArrayList<>();
        for (JavaClass candidate : tests) {
            if (candidate.isMetaAnnotatedWith(WebMvcTest.class)) {
                slices.add(candidate);
            }
        }
        return slices;
    }

    /**
     * A scan that finds nothing proves nothing — the same guard {@code
     * ApplicationYmlPlaceholdersTest} carries, and the same reason {@code
     * archRule.failOnEmptyShould=false} was deleted. Boot has already moved {@code @WebMvcTest}
     * once (it is {@code org.springframework.boot.webmvc.test.autoconfigure} now), and a move that
     * left this scan matching nothing would turn the rule below into a test that always passes.
     */
    @Test
    void Given_the_ledgers_test_code_When_scanned_for_web_mvc_slices_Then_at_least_one_is_found() {
        assertThat(WEB_MVC_SLICES).isNotEmpty();
    }

    @Test
    void Given_every_web_mvc_slice_When_its_imports_are_read_Then_each_one_imports_WebSliceSecurity() {
        List<String> withoutSecurity = WEB_MVC_SLICES.stream()
                .filter(slice -> !importsWebSliceSecurity(slice))
                .map(JavaClass::getFullName)
                .toList();

        assertThat(withoutSecurity)
                .as("@WebMvcTest slices that would boot with no security filter chain at all")
                .isEmpty();
    }

    /**
     * Importing {@link WebSliceSecurity} is not enough if the slice then keeps its filters off MockMvc:
     * {@code @AutoConfigureMockMvc(addFilters = false)} registers no servlet filter, the security chain
     * included, so the slice boots with security configured and never consulted — the same false pass
     * as forgetting the import. Read through the meta-annotation graph for the reason the class comment
     * gives.
     */
    @Test
    void Given_every_web_mvc_slice_When_its_mock_mvc_configuration_is_read_Then_none_turns_the_filters_off() {
        List<String> withoutFilters = WEB_MVC_SLICES.stream()
                .filter(slice -> slice.isMetaAnnotatedWith(TURNS_MOCK_MVC_FILTERS_OFF))
                .map(JavaClass::getFullName)
                .toList();

        assertThat(withoutFilters)
                .as("@WebMvcTest slices whose MockMvc skips every filter, the security chain included")
                .isEmpty();
    }

    /** The member of {@code @AutoConfigureMockMvc} that decides whether servlet filters are applied. */
    private static final String ADD_FILTERS = "addFilters";

    private static final DescribedPredicate<JavaAnnotation<?>> TURNS_MOCK_MVC_FILTERS_OFF = DescribedPredicate.describe(
            "@AutoConfigureMockMvc(addFilters = false)",
            annotation -> annotation.getRawType().isEquivalentTo(AutoConfigureMockMvc.class)
                    && annotation.get(ADD_FILTERS).map(Boolean.FALSE::equals).orElse(false));

    /**
     * Matches an {@code @Import} annotation, wherever it sits in the meta-annotation graph, that
     * names {@link WebSliceSecurity} among the classes it imports. Handed to {@code
     * isMetaAnnotatedWith} rather than walked by hand, so ArchUnit's own cycle-safe traversal
     * covers a composed annotation at any depth.
     */
    private static final DescribedPredicate<JavaAnnotation<?>> IMPORTS_WEB_SLICE_SECURITY = DescribedPredicate.describe(
            "imports " + WebSliceSecurity.class.getSimpleName(), WebSliceSecurityImportTest::namesWebSliceSecurity);

    private static boolean namesWebSliceSecurity(JavaAnnotation<?> annotation) {
        if (!annotation.getRawType().isEquivalentTo(Import.class)) {
            return false;
        }
        Object imported = annotation.get(IMPORTED_CLASSES).orElse(new JavaClass[0]);
        return Arrays.stream((JavaClass[]) imported)
                .anyMatch(configuration -> configuration.getName().equals(WebSliceSecurity.class.getName()));
    }

    private static boolean importsWebSliceSecurity(JavaClass slice) {
        return slice.isMetaAnnotatedWith(IMPORTS_WEB_SLICE_SECURITY);
    }
}
