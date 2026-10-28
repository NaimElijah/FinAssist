package io.github.naimelijah.finassist.ledger.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * {@code LedgerSecurityConfiguration.tokenValidator}, against a fixed clock rather than the system
 * one, so {@code JwtTimestampValidator}'s 30 s skew is proven at the boundary rather than by luck;
 * and the clock the shipped configuration hands it, which every {@code *IT} replaces with a fixed one
 * and so cannot see.
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

    /** How long after {@link #NOW} the {@code jwt()} helper's token expires. */
    private static final Duration LIFETIME = Duration.ofHours(1);

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

    /**
     * {@code JwtTimestampValidator} treats a missing {@code exp} as acceptable unless told
     * otherwise, so without {@code setAllowEmptyExpiryClaim(false)} a signed token with no lifetime
     * at all is valid forever. ADR-030 requires {@code exp} of both resource servers, and the
     * assistant already refuses this shape.
     *
     * <p>The claim is removed from the built token rather than left unset by the {@link #jwt}
     * helper: {@code Jwt.Builder#build} derives {@code expiresAt} from the {@code exp} claim it
     * holds, so removing the entry is what produces a token with no expiry — and it keeps this the
     * only test that has to know the helper sets one.
     */
    @Test
    void Given_a_token_with_no_expiry_When_validated_Then_it_is_refused() {
        Jwt jwt = jwt(builder -> builder.claims(claims -> claims.remove(JwtClaimNames.EXP)));

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
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

    /**
     * One validator lives as long as the ledger does, so it must read its clock on every call: a
     * validator that took the time once, when it was built, would admit a token for ever after. The
     * same token, accepted, is refused once the clock passes its {@code exp} and the skew.
     */
    @Test
    void Given_an_accepted_token_When_the_clock_passes_its_expiry_and_the_skew_Then_the_same_token_is_refused() {
        MovableClock clock = new MovableClock(NOW);
        OAuth2TokenValidator<Jwt> validating = LedgerSecurityConfiguration.tokenValidator(ISSUER, AUDIENCE, clock);
        Jwt jwt = jwt(UnaryOperator.identity());

        boolean refusedBefore = validating.validate(jwt).hasErrors();
        clock.advance(LIFETIME.plus(BEYOND_SKEW));
        boolean refusedAfter = validating.validate(jwt).hasErrors();

        assertThat(refusedBefore).as("refused while valid").isFalse();
        assertThat(refusedAfter).as("refused once expired beyond the skew").isTrue();
    }

    /**
     * Every {@code *IT} replaces the {@code Clock} bean with a fixed one, so this is the only test of
     * the clock the ledger ships with. A lagging, offset or fixed clock there would admit expired tokens
     * or refuse fresh ones in production while every suite stayed green.
     */
    @Test
    void Given_the_shipped_configuration_When_its_clock_bean_is_built_Then_it_is_the_system_utc_clock() {
        assertThat(new LedgerSecurityConfiguration().clock()).isEqualTo(Clock.systemUTC());
    }

    /** A clock the test moves, standing for time passing between two requests carrying one token. */
    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the token validators read only instants, never a zone");
        }
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
                .expiresAt(NOW.plus(LIFETIME));
        if (withSubject) {
            builder = builder.subject("owner-user");
        }
        if (withIssuedAt) {
            builder = builder.issuedAt(NOW);
        }
        return customizer.apply(builder).build();
    }
}
