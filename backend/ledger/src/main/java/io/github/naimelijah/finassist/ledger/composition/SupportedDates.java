package io.github.naimelijah.finassist.ledger.composition;

import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.format.Formatter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Refuses, wherever the ledger reads a date, one outside the years {@value #EARLIEST_YEAR} to {@value
 * #LATEST_YEAR}: ISO 8601's four-digit years, without a sign.
 *
 * <p>{@code java.time} and PostgreSQL each accept dates hundreds of thousands of years away, and not the
 * same ones, so a date the ledger never means would otherwise reach the store or the projection's
 * arithmetic and fail there as a 500. Refused here, it is a malformed request like any date that does not
 * parse: 400 {@code MALFORMED_REQUEST} (ADR-028).
 *
 * <p>One bound, applied at both places a date enters. A query parameter is parsed by a {@link Formatter}
 * registered with Spring MVC for every {@code LocalDate}, unconditionally: a formatter registered by a
 * {@link WebMvcConfigurer} is consulted before the {@code @DateTimeFormat} annotation's, so it parses
 * ISO dates and overrides any pattern such an annotation names. Every query date the ledger takes is an
 * ISO date today, so nothing is lost; a parameter that needs another pattern would have to be bounded
 * another way. A body member is read by Jackson's own {@code LocalDate} deserializer, wrapped so that
 * its parsing is kept and only its result is bounded.
 *
 * <p>{@link LedgerConfiguration} registers it. It is a class of its own, as {@link StrictTextCoercion} is,
 * so that a {@code @WebMvcTest} slice can import it.
 */
public class SupportedDates implements JsonMapperBuilderCustomizer, WebMvcConfigurer {

    /** The earliest year a date may fall in: ISO 8601's year 0000 is 1 BC, which no record means. */
    static final int EARLIEST_YEAR = 1;

    /** The latest year a date may fall in: the last that ISO 8601 writes in four digits. */
    static final int LATEST_YEAR = 9999;

    private static final String REFUSAL = "A date must fall in the years " + EARLIEST_YEAR + " to " + LATEST_YEAR;

    @Override
    public void customize(JsonMapper.Builder builder) {
        builder.addModule(new SimpleModule(SupportedDates.class.getSimpleName())
                .addDeserializer(LocalDate.class, boundedLocalDateDeserializer()));
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addFormatter(new BoundedIsoDateFormatter());
    }

    /**
     * Jackson's {@link DelegatingDeserializer} is typed for {@code Object} whatever it delegates to, so the
     * module's {@code LocalDate} slot needs this cast. It is safe: the delegate is Jackson's {@code
     * LocalDate} deserializer, and the wrapper returns only what the delegate produced.
     */
    @SuppressWarnings("unchecked")
    private static ValueDeserializer<LocalDate> boundedLocalDateDeserializer() {
        ValueDeserializer<?> bounded = new BoundedLocalDateDeserializer(LocalDateDeserializer.INSTANCE);
        return (ValueDeserializer<LocalDate>) bounded;
    }

    private static boolean isSupported(LocalDate date) {
        return date.getYear() >= EARLIEST_YEAR && date.getYear() <= LATEST_YEAR;
    }

    /**
     * A query date, parsed as {@code @DateTimeFormat(iso = DATE)} parses one. A failure here reaches the
     * client as a type mismatch, which is 400.
     */
    private static final class BoundedIsoDateFormatter implements Formatter<LocalDate> {

        @Override
        public LocalDate parse(String text, Locale locale) throws ParseException {
            LocalDate date = LocalDate.parse(text, DateTimeFormatter.ISO_DATE);
            if (!isSupported(date)) {
                throw new ParseException(REFUSAL, 0);
            }
            return date;
        }

        @Override
        public String print(LocalDate date, Locale locale) {
            return DateTimeFormatter.ISO_LOCAL_DATE.format(date);
        }
    }

    /** Jackson's own deserializer, contextualised as Jackson would, with its result bounded. */
    private static final class BoundedLocalDateDeserializer extends DelegatingDeserializer {

        BoundedLocalDateDeserializer(ValueDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> delegate) {
            return new BoundedLocalDateDeserializer(delegate);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) {
            Object value = super.deserialize(parser, context);
            if (value instanceof LocalDate date && !isSupported(date)) {
                return context.reportInputMismatch(LocalDate.class, REFUSAL);
            }
            return value;
        }
    }
}
