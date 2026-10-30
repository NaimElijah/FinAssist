package io.github.naimelijah.finassist.ledger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.naimelijah.finassist.ledger.composition.LedgerPermissions;
import io.github.naimelijah.finassist.ledger.composition.LedgerSecurityConfiguration;
import java.util.List;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Wires {@code LedgerSecurityConfiguration} into a {@code @WebMvcTest} slice, and pre-authenticates
 * every request as the owner, so a slice that exists to assert a controller's wire shape does not
 * also have to spell out a token on every {@code mvc.perform(...)} call.
 *
 * <p>A slice loads no scanned {@code @Configuration} (ADR-028's convention, which ADR-030's
 * security chain now has to follow too), so without this import a slice would boot with no
 * security at all — every request admitted, every permission moot — which is a false pass, not a
 * fast one. {@code @Import} rather than component scanning is exactly what a slice test is for.
 *
 * <p>The chain's {@code RequestRejectedHandler} marks the current observation, so the slice also
 * imports Boot's own {@code ObservationAutoConfiguration}, which the running ledger applies and a slice
 * does not; the registry is the real auto-configured one, not a stand-in.
 *
 * <p>The default authentication is deliberately the most permissive role: a slice's own tests
 * assert what the controller answers for a given input, not authorization, which {@code
 * LedgerAuthorizationIT} covers end to end against real tokens. A test that needs to assert a 403
 * still can, by passing its own {@code jwt()} post-processor to {@code mvc.perform(...)}, which
 * overrides the default this class sets.
 */
@TestConfiguration
@Import(LedgerSecurityConfiguration.class)
@ImportAutoConfiguration(ObservationAutoConfiguration.class)
public class WebSliceSecurity {

    private static final String OWNER_SUBJECT = "owner-user";

    @Bean
    MockMvcBuilderCustomizer defaultOwnerAuthentication() {
        return builder -> builder.defaultRequest(get("/").with(
                        jwt().jwt(token -> token.subject(OWNER_SUBJECT).claim("roles", List.of("owner")))
                                .authorities(new LedgerPermissions())));
    }
}
