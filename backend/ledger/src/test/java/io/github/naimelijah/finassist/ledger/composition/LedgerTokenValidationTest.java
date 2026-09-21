package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@code LedgerSecurityConfiguration.tokenValidator}, against a fixed clock rather than the system
 * one, so {@code JwtTimestampValidator}'s 30 s skew is proven at the boundary rather than by luck.
 */
class LedgerTokenValidationTest {

    private static final String ISSUER = "http://localhost:8081/realms/finassist";
    private static final String AUDIENCE = "finassist-ledger";

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** One second past the 30 s skew {@code LedgerSecurityConfiguration} configures. */
    private static final Duration BEYOND_SKEW = Duration.ofSeconds(31);

    /** One second inside it. */
    private static final Duration WITHIN_SKEW = Duration.ofSeconds(29);

    private final OAuth2TokenValidator<Jwt> validator =
            LedgerSecurityConfiguration.tokenValidator(ISSUER, AUDIENCE, FIXED_CLOCK);

    @Test
    void Given_a_token_expired_beyond_the_skew_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.expiresAt(NOW.minus(BEYOND_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_expired_within_the_skew_When_validated_Then_it_is_accepted() {
        Jwt jwt = jwt(builder -> builder.expiresAt(NOW.minus(WITHIN_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void Given_a_token_not_yet_valid_beyond_the_skew_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.notBefore(NOW.plus(BEYOND_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_not_yet_valid_within_the_skew_When_validated_Then_it_is_accepted() {
        Jwt jwt = jwt(builder -> builder.notBefore(NOW.plus(WITHIN_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void Given_a_token_naming_a_different_audience_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.claim("aud", List.of("some-other-service")));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_naming_both_the_expected_audience_and_another_When_validated_Then_it_is_accepted() {
        Jwt jwt = jwt(builder -> builder.claim("aud", List.of(AUDIENCE, "some-other-service")));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void Given_a_token_with_the_wrong_issuer_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.issuer("http://attacker.example/realms/finassist"));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_with_no_subject_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(UnaryOperator.identity(), false);

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_an_otherwise_valid_token_When_validated_Then_it_is_accepted() {
        assertThat(validator.validate(jwt(UnaryOperator.identity())).hasErrors())
                .isFalse();
    }

    private static Jwt jwt(UnaryOperator<Jwt.Builder> customizer) {
        return jwt(customizer, true);
    }

    private static Jwt jwt(UnaryOperator<Jwt.Builder> customizer, boolean withSubject) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .expiresAt(NOW.plus(Duration.ofHours(1)));
        if (withSubject) {
            builder = builder.subject("owner-user");
        }
        return customizer.apply(builder).build();
    }
}
