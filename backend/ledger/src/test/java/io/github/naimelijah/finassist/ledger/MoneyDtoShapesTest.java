package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds every context's {@code MoneyDto} identical to accounting's, member for member and constraint for
 * constraint.
 *
 * <p>Each web adapter declares its own copy, because the slice rule leaves no legal shared home for one.
 * springdoc names a schema by simple class name and folds classes sharing one into a single {@code
 * MoneyDto} schema without a word, keeping whichever it met first. {@code
 * LedgerArchitectureTest.web_dto_names_are_unique_across_contexts} exempts the name for that reason, so
 * this is what stops a copy drifting — a looser pattern, a dropped {@code @NotNull} — while the contract
 * goes on describing another context's.
 *
 * <p>Read by reflection. Tests may import every context; production code may not.
 */
class MoneyDtoShapesTest {

    private static final Class<?> ACCOUNTING =
            io.github.naimelijah.finassist.ledger.accounting.adapter.in.web.MoneyDto.class;

    /**
     * Every other copy, found in the compiled production classes rather than listed by hand, so a copy a
     * new context adds is compared from the moment it exists — the same static scan {@code
     * WebSliceSecurityImportTest} uses. Scanned once for the class: every parameterised case asks for the
     * copies, and the import is the expensive part.
     */
    private static final List<Class<?>> COPIES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(LedgerArchitectureTest.ROOT).stream()
                    .filter(candidate -> candidate.getSimpleName().equals(ACCOUNTING.getSimpleName()))
                    .filter(candidate -> !candidate.isEquivalentTo(ACCOUNTING))
                    .<Class<?>>map(JavaClass::reflect)
                    .sorted(Comparator.comparing(Class::getName))
                    .toList();

    static Stream<Class<?>> copies() {
        return COPIES.stream();
    }

    /** A scan finding nothing would leave every case below passing over no copy at all. */
    @Test
    void Given_the_production_classes_When_scanned_for_MoneyDto_copies_Then_one_is_found_beyond_accountings() {
        assertThat(copies()).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("copies")
    void
            Given_another_contexts_MoneyDto_When_compared_with_accountings_Then_its_components_match_in_name_type_and_order(
                    Class<?> copy) {
        assertThat(copy.isRecord()).as("%s is a record", copy.getName()).isTrue();
        assertThat(components(copy)).isEqualTo(components(ACCOUNTING));
    }

    /**
     * Compared on the field and on the accessor, the two places a record component's constraints land
     * and bean validation reads them. Annotations compare by type and by every member's value, so a
     * changed {@code regexp} or {@code message} is a difference.
     */
    @ParameterizedTest
    @MethodSource("copies")
    void
            Given_another_contexts_MoneyDto_When_compared_with_accountings_Then_each_component_carries_the_same_constraints(
                    Class<?> copy) throws NoSuchFieldException {
        assertThat(constraints(copy)).isEqualTo(constraints(ACCOUNTING));
    }

    /**
     * Compared on the record itself as well. The copies share one schema and each is serialised by its own
     * annotations, so a class-level {@code @Schema} or {@code @JsonInclude} on one copy would change the
     * shared contract, or one context's wire output, as silently as a differing constraint.
     */
    @ParameterizedTest
    @MethodSource("copies")
    void Given_another_contexts_MoneyDto_When_compared_with_accountings_Then_it_carries_the_same_class_annotations(
            Class<?> copy) {
        assertThat(sorted(copy.getDeclaredAnnotations())).isEqualTo(sorted(ACCOUNTING.getDeclaredAnnotations()));
    }

    /** Each component as {@code name: type}, in declaration order. */
    private static List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(component ->
                        component.getName() + ": " + component.getGenericType().getTypeName())
                .toList();
    }

    /** Each component's annotations on its field and its accessor, keyed and sorted so order is irrelevant. */
    private static Map<String, List<Annotation>> constraints(Class<?> record) throws NoSuchFieldException {
        Map<String, List<Annotation>> byPlacement = new LinkedHashMap<>();
        for (RecordComponent component : record.getRecordComponents()) {
            byPlacement.put(
                    "field " + component.getName(),
                    sorted(record.getDeclaredField(component.getName()).getDeclaredAnnotations()));
            byPlacement.put(
                    "accessor " + component.getName(),
                    sorted(component.getAccessor().getDeclaredAnnotations()));
        }
        return byPlacement;
    }

    private static List<Annotation> sorted(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .sorted(Comparator.comparing(
                        annotation -> annotation.annotationType().getName()))
                .toList();
    }
}
