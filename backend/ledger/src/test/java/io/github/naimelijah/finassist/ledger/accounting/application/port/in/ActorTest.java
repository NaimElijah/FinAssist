package io.github.naimelijah.finassist.ledger.accounting.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@code Actor}'s own validation, kept apart from any use case that carries one. */
class ActorTest {

    @Test
    void Given_a_blank_subject_When_an_actor_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Actor("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void Given_an_empty_subject_When_an_actor_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Actor(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void Given_a_subject_over_the_limit_When_an_actor_is_built_Then_it_is_refused() {
        String tooLong = "s".repeat(Actor.MAX_SUBJECT_LENGTH + 1);

        assertThatThrownBy(() -> new Actor(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
    }

    @Test
    void Given_a_subject_at_the_limit_When_an_actor_is_built_Then_it_is_accepted() {
        String atLimit = "s".repeat(Actor.MAX_SUBJECT_LENGTH);

        Actor actor = new Actor(atLimit);

        assertThat(actor.subject()).isEqualTo(atLimit);
    }

    @Test
    void Given_a_null_subject_When_an_actor_is_built_Then_it_is_refused() {
        assertThatThrownBy(() -> new Actor(null)).isInstanceOf(NullPointerException.class);
    }
}
