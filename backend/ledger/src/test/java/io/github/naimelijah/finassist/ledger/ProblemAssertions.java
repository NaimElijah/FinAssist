package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The members every ledger problem must carry, whichever writer built it (ADR-068): a {@code type}, a
 * non-empty {@code title}, a {@code status} equal to the response's own, and a {@code code}.
 *
 * <p>One check shared by every writer's test, so a writer that drops a member fails its own test rather
 * than only a contract a client reads later. The ledger has several writers that never meet: the
 * composition fallback, each context's advice, the security chain's two handlers, and the filters that
 * answer before the dispatcher. Each takes the response in the form its test already holds.
 */
public final class ProblemAssertions {

    private static final JsonMapper JSON = JsonMapper.shared();

    private ProblemAssertions() {}

    /**
     * Asserts that a problem body carries every required member.
     *
     * @param responseStatus the status the response was sent with, which the body's {@code status} must
     *     repeat
     * @param body the response body, expected to be a problem+json object
     * @throws AssertionError naming the first member that is missing, empty or wrong
     */
    public static void assertRequiredMembers(int responseStatus, String body) {
        JsonNode problem = JSON.readTree(body);
        assertThat(textOf(problem, "type")).as("the type of %s", body).isNotBlank();
        assertThat(textOf(problem, "title")).as("the title of %s", body).isNotBlank();
        assertThat(problem.path("status").isInt())
                .as("the status of %s is a JSON integer", body)
                .isTrue();
        assertThat(problem.path("status").asInt()).as("the status of %s", body).isEqualTo(responseStatus);
        assertThat(textOf(problem, "code")).as("the code of %s", body).isNotBlank();
    }

    /**
     * Asserts that a written response's problem body carries every required member, for a writer called
     * directly with a mock response.
     *
     * @throws AssertionError as {@link #assertRequiredMembers(int, String)} does
     */
    public static void assertRequiredMembers(MockHttpServletResponse response) throws Exception {
        assertRequiredMembers(response.getStatus(), response.getContentAsString());
    }

    /** The same check as a MockMvc matcher, for a writer reached through a dispatched request. */
    public static ResultMatcher requiredProblemMembers() {
        return result -> assertRequiredMembers(result.getResponse());
    }

    /** A member's text, or {@code null} when it is absent or not a JSON string, so that fails as blank. */
    private static String textOf(JsonNode problem, String member) {
        JsonNode value = problem.path(member);
        return value.isString() ? value.asString() : null;
    }
}
