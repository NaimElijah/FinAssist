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

    /**
     * Comfortably before either expired token's {@code exp} below. {@code Jwt}'s own constructor
     * requires {@code expiresAt} to be after {@code issuedAt} whenever both are present, so moving
     * only {@code exp} into the past — without also moving {@code iat} — throws before validation
     * ever runs; both expired-token tests move {@code iat} back to this instead of the default
     * {@code NOW} the {@code jwt()} helper otherwise sets.
     */
    private static final Instant WELL_BEFORE_EXPIRY = NOW.minus(Duration.ofHours(1));

    private final OAuth2TokenValidator<Jwt> validator =
            LedgerSecurityConfiguration.tokenValidator(ISSUER, AUDIENCE, FIXED_CLOCK);

    @Test
    void Given_a_token_expired_beyond_the_skew_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.issuedAt(WELL_BEFORE_EXPIRY).expiresAt(NOW.minus(BEYOND_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_expired_within_the_skew_When_validated_Then_it_is_accepted() {
        Jwt jwt = jwt(builder -> builder.issuedAt(WELL_BEFORE_EXPIRY).expiresAt(NOW.minus(WITHIN_SKEW)));

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
    void Given_a_token_issued_beyond_the_skew_in_the_future_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.issuedAt(NOW.plus(BEYOND_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_issued_within_the_skew_in_the_future_When_validated_Then_it_is_accepted() {
        Jwt jwt = jwt(builder -> builder.issuedAt(NOW.plus(WITHIN_SKEW)));

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void Given_a_token_with_no_issued_at_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(UnaryOperator.identity(), true, false);

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
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

    /**
     * Pins the audience check to an exact match rather than a "starts with" or "contains" one: a
     * future refactor toward a looser comparison must fail this before it ever ships.
     */
    @Test
    void Given_a_token_whose_only_audience_is_the_assistant_audience_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.claim("aud", List.of("finassist-assistant")));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void Given_a_token_with_the_wrong_issuer_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.issuer("http://attacker.example/realms/finassist"));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    /**
     * Pins the issuer check to an exact match rather than a prefix one: {@code
     * .../realms/finassist-evil} extends the real issuer byte-for-byte, and must still be refused.
     */
    @Test
    void Given_a_token_whose_issuer_is_a_prefix_extension_of_the_real_one_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.issuer("http://localhost:8081/realms/finassist-evil"));

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
        return jwt(customizer, true, true);
    }

    private static Jwt jwt(UnaryOperator<Jwt.Builder> customizer, boolean withSubject) {
        return jwt(customizer, withSubject, true);
    }

    /**
     * Builds a token that is valid on every check but the one its caller means to exercise.
     * {@code withIssuedAt} is {@code false} only for the missing-{@code iat} case; every other
     * test in this class needs a genuinely valid {@code iat} so it is isolating the one check it
     * actually names.
     */
    private static Jwt jwt(UnaryOperator<Jwt.Builder> customizer, boolean withSubject, boolean withIssuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .expiresAt(NOW.plus(Duration.ofHours(1)));
        if (withSubject) {
            builder = builder.subject("owner-user");
        }
        if (withIssuedAt) {
            builder = builder.issuedAt(NOW);
        }
        return customizer.apply(builder).build();
    }
}
