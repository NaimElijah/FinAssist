package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EmptyKeySetIsAnOutage} refuses to wrap a selector whose key source it cannot ask.
 *
 * <p>What it does with the selector it does wrap is proved against the real decoder, in {@code
 * LedgerJwksBodyTest}. This class pins the one guard that test cannot reach: a Spring upgrade that
 * installed another selector type must stop the ledger at start-up, not leave the empty-set check
 * silently switched off.
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
    void Given_no_selector_When_wrapped_Then_it_is_refused() {
        assertThatThrownBy(() -> EmptyKeySetIsAnOutage.around(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none");
    }
}
