package io.github.naimelijah.finassist.ledger.composition;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Refuses a JSON number wherever the ledger reads text, so that a decimal sent as a number is a 400
 * (ADR-028).
 *
 * <p>Every decimal on the wire is a string, and the contract types each one so. Jackson's default coercion
 * reads {@code "amount": 12.50} into a {@code String} member as the text {@code "12.50"}, which then passes
 * the decimal pattern: a client that serialises a binary float would work until a value such as {@code
 * 0.30000000000000004} arrived, and fail far from its cause. Refused here, a number is a malformed body
 * like any other wrong type. It applies to every text member, not only decimals, because the contract
 * types every one of them as a string too.
 *
 * <p>Spring Boot applies every {@link JsonMapperBuilderCustomizer} bean to the mapper Spring MVC reads
 * request bodies with, and {@link LedgerConfiguration} registers this one. It is a class of its own so that a
 * {@code @WebMvcTest} slice, which loads no scanned configuration, can import it without the rest of the
 * composition root.
 */
public class StrictTextCoercion implements JsonMapperBuilderCustomizer {

    @Override
    public void customize(JsonMapper.Builder builder) {
        builder.withCoercionConfig(
                LogicalType.Textual,
                config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
