package io.github.naimelijah.finassist.ledger.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Money checked against the same vectors the Python kernel is checked against.
 *
 * <p>ADR-010 keeps the two implementations honest through "equivalent tests on both sides". That
 * was mirrored prose until now, and it had already drifted: the Python kernel raised on a large
 * product where {@code timesRounded} here returned a value, and no test on either side could have
 * noticed. Both suites now read one shared file, so a behavioural difference fails a build instead
 * of waiting to be spotted. See ADR-018.
 *
 * <p>The file is data — no behaviour, no types, imported by nothing at runtime. It holds string
 * forms rather than expressions, so it creates a shared <em>specification</em> and not the shared
 * kernel ADR-010 forbids.
 *
 * <p>Errors are language-neutral categories, because this side raises {@link
 * IllegalArgumentException} where Python raises its own {@code ValueError}.
 *
 * <p>Parsed with Jackson, already on the test classpath through {@code spring-boot-starter-web}
 * — Spring Boot 4 ships Jackson 3, whose packages are {@code tools.jackson}, not
 * {@code com.fasterxml.jackson}. ArchUnit's framework rule uses {@code DoNotIncludeTests}, so a
 * Jackson import in a test class is not a violation of it.
 */
class MoneyVectorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode VECTORS = load();

    private static final String VECTORS_FILE = "money-vectors.v1.json";

    /**
     * Surefire injects {@code finassist.contracts.dir} as {@code ${project.basedir}/../../contracts},
     * which is correct however Maven was invoked. Other runners do not: PIT executes the suite in
     * its own JVM outside surefire, and an IDE may not pass it either, so the property alone made
     * this class fail everywhere except {@code mvn test} — it broke {@code make mutation-ledger}
     * outright. The search is the fallback, and both paths still fail loudly rather than skipping.
     */
    private static Path locateVectors() {
        String configured = System.getProperty("finassist.contracts.dir");
        if (configured != null) {
            return Path.of(configured, VECTORS_FILE);
        }
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("contracts").resolve(VECTORS_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find contracts/" + VECTORS_FILE + " above "
                + Path.of("").toAbsolutePath()
                + ", and finassist.contracts.dir was not set. Both language suites read this file;"
                + " a missing one must fail loudly rather than silently check nothing.");
    }

    private static JsonNode load() {
        Path path = locateVectors();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("shared Money vectors not found at " + path
                    + ". Both language suites read this file; a missing one must fail loudly rather than"
                    + " silently check nothing.");
        }
        try {
            return MAPPER.readTree(Files.readString(path));
        } catch (IOException cause) {
            throw new IllegalStateException("could not read the shared Money vectors at " + path, cause);
        }
    }

    private static List<JsonNode> group(String name) {
        return StreamSupport.stream(VECTORS.get(name).spliterator(), false).toList();
    }

    private static List<JsonNode> currencies() {
        return group("currencies");
    }

    private static List<JsonNode> construct() {
        return group("construct");
    }

    private static List<JsonNode> binary() {
        return group("binary");
    }

    private static List<JsonNode> scalar() {
        return group("scalar");
    }

    private static Money money(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.valueOf(currency));
    }

    /** Categories to this language's exception types; the file names neither. */
    private static Class<? extends Throwable> exceptionFor(String category) {
        return switch (category) {
            case "PRECISION_EXCEEDED" -> IllegalArgumentException.class;
            case "CURRENCY_MISMATCH" -> CurrencyMismatchException.class;
            default -> throw new IllegalArgumentException("unknown error category: " + category);
        };
    }

    private static Money apply(String operation, Money left, Money right) {
        return switch (operation) {
            case "plus" -> left.plus(right);
            case "minus" -> left.minus(right);
            default -> throw new IllegalArgumentException("unknown binary op: " + operation);
        };
    }

    private static Money applyScalar(String operation, Money amount, BigDecimal multiplier) {
        return switch (operation) {
            case "times" -> amount.times(multiplier);
            case "timesRounded" -> amount.timesRounded(multiplier);
            default -> throw new IllegalArgumentException("unknown scalar op: " + operation);
        };
    }

    /**
     * Every group this suite knows how to read. Pinned, so a group added to the file that neither
     * language reads fails here rather than sitting in the file specifying nothing.
     */
    private static final Set<String> GROUPS = Set.of("currencies", "construct", "binary", "scalar");

    /** Prose and metadata rather than vectors, so not expected to be read as a group. */
    private static final Set<String> NOT_GROUPS = Set.of("about", "version");

    /**
     * The anti-vacuity guard the architecture rules use, for the same reason: a truncated file would
     * otherwise let every case below pass by checking nothing.
     */
    @Test
    void Given_the_vector_file_When_loaded_Then_it_holds_vectors_in_every_group() {
        assertThat(VECTORS.get("version").asInt()).isEqualTo(1);
        assertThat(currencies()).hasSize(Currency.values().length);
        assertThat(construct()).isNotEmpty();
        assertThat(binary()).isNotEmpty();
        assertThat(scalar()).isNotEmpty();
    }

    /**
     * Without this the file guards against being empty and not against growing a section nothing
     * reads — a group only one language knew about would specify something never checked.
     */
    @Test
    void Given_the_vector_file_When_read_Then_it_holds_no_group_this_suite_ignores() {
        Set<String> groups = new HashSet<>();
        VECTORS.propertyNames().forEach(groups::add);
        groups.removeAll(NOT_GROUPS);

        assertThat(groups)
                .as("both languages must read every group, or the file specifies something nothing checks")
                .isEqualTo(GROUPS);
    }

    @Test
    void Given_the_vector_file_When_read_Then_every_id_is_unique() {
        List<String> ids = new ArrayList<>();
        for (String name : List.of("construct", "binary", "scalar")) {
            group(name).forEach(vector -> ids.add(vector.get("id").asString()));
        }
        Set<String> unique = new HashSet<>(ids);

        assertThat(unique)
                .as("vector ids must be unique so a failure names exactly one case")
                .hasSameSizeAs(ids);
    }

    @ParameterizedTest
    @MethodSource("currencies")
    void Given_a_currency_vector_When_looked_up_Then_the_minor_units_agree(JsonNode vector) {
        assertThat(Currency.valueOf(vector.get("code").asString()).minorUnits())
                .isEqualTo(vector.get("minorUnits").asInt());
    }

    @ParameterizedTest
    @MethodSource("construct")
    void Given_a_construction_vector_When_applied_Then_it_matches(JsonNode vector) {
        String amount = vector.get("amount").asString();
        String currency = vector.get("currency").asString();

        if (vector.has("error")) {
            assertThatThrownBy(() -> money(amount, currency))
                    .isInstanceOf(exceptionFor(vector.get("error").asString()));
            return;
        }
        assertThat(money(amount, currency).toString())
                .isEqualTo(vector.get("expect").asString());
    }

    @ParameterizedTest
    @MethodSource("binary")
    void Given_a_binary_vector_When_applied_Then_it_matches(JsonNode vector) {
        String currency = vector.get("currency").asString();
        Money left = money(vector.get("left").asString(), currency);
        Money right = money(
                vector.get("right").asString(),
                vector.has("rightCurrency") ? vector.get("rightCurrency").asString() : currency);
        String operation = vector.get("op").asString();

        if (vector.has("error")) {
            assertThatThrownBy(() -> apply(operation, left, right))
                    .isInstanceOf(exceptionFor(vector.get("error").asString()));
            return;
        }
        assertThat(apply(operation, left, right).toString())
                .isEqualTo(vector.get("expect").asString());
    }

    @ParameterizedTest
    @MethodSource("scalar")
    void Given_a_scalar_vector_When_applied_Then_it_matches(JsonNode vector) {
        Money amount =
                money(vector.get("amount").asString(), vector.get("currency").asString());
        BigDecimal multiplier = new BigDecimal(vector.get("multiplier").asString());
        String operation = vector.get("op").asString();

        if (vector.has("error")) {
            assertThatThrownBy(() -> applyScalar(operation, amount, multiplier))
                    .isInstanceOf(exceptionFor(vector.get("error").asString()));
            return;
        }
        assertThat(applyScalar(operation, amount, multiplier).toString())
                .isEqualTo(vector.get("expect").asString());
    }
}
