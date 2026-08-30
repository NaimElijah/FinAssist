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

    /**
     * Injected by Maven as {@code ${project.basedir}/../../contracts}, so it is correct however the
     * build was invoked rather than being a claim about the working directory.
     */
    private static JsonNode load() {
        String directory = System.getProperty("finassist.contracts.dir");
        if (directory == null) {
            throw new IllegalStateException("finassist.contracts.dir is not set; surefire must pass it so"
                    + " this test can find the shared Money vectors");
        }
        Path path = Path.of(directory, "money-vectors.v1.json");
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

    @Test
    void Given_the_vector_file_When_read_Then_every_id_is_unique() {
        List<String> ids = new ArrayList<>();
        for (String name : List.of("construct", "binary", "scalar")) {
            group(name).forEach(vector -> ids.add(vector.get("id").asText()));
        }
        Set<String> unique = new HashSet<>(ids);

        assertThat(unique)
                .as("vector ids must be unique so a failure names exactly one case")
                .hasSameSizeAs(ids);
    }

    @ParameterizedTest
    @MethodSource("currencies")
    void Given_a_currency_vector_When_looked_up_Then_the_minor_units_agree(JsonNode vector) {
        assertThat(Currency.valueOf(vector.get("code").asText()).minorUnits())
                .isEqualTo(vector.get("minorUnits").asInt());
    }

    @ParameterizedTest
    @MethodSource("construct")
    void Given_a_construction_vector_When_applied_Then_it_matches(JsonNode vector) {
        String amount = vector.get("amount").asText();
        String currency = vector.get("currency").asText();

        if (vector.has("error")) {
            assertThatThrownBy(() -> money(amount, currency))
                    .isInstanceOf(exceptionFor(vector.get("error").asText()));
            return;
        }
        assertThat(money(amount, currency).toString())
                .isEqualTo(vector.get("expect").asText());
    }

    @ParameterizedTest
    @MethodSource("binary")
    void Given_a_binary_vector_When_applied_Then_it_matches(JsonNode vector) {
        String currency = vector.get("currency").asText();
        Money left = money(vector.get("left").asText(), currency);
        Money right = money(
                vector.get("right").asText(),
                vector.has("rightCurrency") ? vector.get("rightCurrency").asText() : currency);
        String operation = vector.get("op").asText();

        if (vector.has("error")) {
            assertThatThrownBy(() -> apply(operation, left, right))
                    .isInstanceOf(exceptionFor(vector.get("error").asText()));
            return;
        }
        assertThat(apply(operation, left, right).toString())
                .isEqualTo(vector.get("expect").asText());
    }

    @ParameterizedTest
    @MethodSource("scalar")
    void Given_a_scalar_vector_When_applied_Then_it_matches(JsonNode vector) {
        Money amount =
                money(vector.get("amount").asText(), vector.get("currency").asText());
        BigDecimal multiplier = new BigDecimal(vector.get("multiplier").asText());
        String operation = vector.get("op").asText();

        if (vector.has("error")) {
            assertThatThrownBy(() -> applyScalar(operation, amount, multiplier))
                    .isInstanceOf(exceptionFor(vector.get("error").asText()));
            return;
        }
        assertThat(applyScalar(operation, amount, multiplier).toString())
                .isEqualTo(vector.get("expect").asText());
    }
}
