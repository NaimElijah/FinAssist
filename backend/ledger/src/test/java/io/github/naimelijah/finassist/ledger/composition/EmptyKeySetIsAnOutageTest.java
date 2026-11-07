package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link EmptyKeySetIsAnOutage} refuses to wrap a selector whose key source it cannot ask, and asks
 * nothing of the source for a token whose algorithm the selector does not allow.
 *
 * <p>What it does with the selector it does wrap is proved against the real decoder, in {@code
 * LedgerJwksBodyTest}. This class pins the two guards that test cannot reach. A Spring upgrade that
 * installed another selector type must stop the ledger at start-up, not leave the empty-set check
 * silently switched off. And since {@link RefuseUnexpectedAlgorithm} refuses a disallowed algorithm
 * before the decoder runs (ADR-076), only a test of the selector itself still sees that such a
 * token costs no fetch here.
 */
class EmptyKeySetIsAnOutageTest {

    @Test
    void Given_a_selector_other_than_JWSVerificationKeySelector_When_wrapped_Then_it_is_refused() {
        JWSKeySelector<SecurityContext> another = (header, context) -> List.of();

        assertThatThrownBy(() -> EmptyKeySetIsAnOutage.around(another))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWSVerificationKeySelector");
    }

    @Test
    void
            Given_a_token_with_a_disallowed_algorithm_and_an_empty_set_When_keys_are_selected_Then_the_source_is_never_asked()
                    throws Exception {
        AtomicInteger asked = new AtomicInteger();
        JWKSource<SecurityContext> emptySet = (selector, context) -> {
            asked.incrementAndGet();
            return List.of();
        };
        EmptyKeySetIsAnOutage selector =
                EmptyKeySetIsAnOutage.around(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, emptySet));

        assertThat(selector.selectJWSKeys(new JWSHeader(JWSAlgorithm.HS256), null))
                .isEmpty();
        assertThat(asked.get()).as("requests the key source answered").isZero();
    }

    @Test
    void Given_no_selector_When_wrapped_Then_it_is_refused() {
        assertThatThrownBy(() -> EmptyKeySetIsAnOutage.around(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none");
    }
}
