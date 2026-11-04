package io.github.naimelijah.finassist.ledger.planning.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.naimelijah.finassist.ledger.PostgresFixture;
import io.github.naimelijah.finassist.ledger.RivalWriter;
import io.github.naimelijah.finassist.ledger.planning.application.port.in.ConcurrentPlanTransitionException;
import io.github.naimelijah.finassist.ledger.planning.application.port.out.PlannedFlowRepository;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Direction;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanId;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlanState;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.PlannedFlow;
import io.github.naimelijah.finassist.ledger.planning.domain.plan.Recurrence;
import io.github.naimelijah.finassist.ledger.shared.Currency;
import io.github.naimelijah.finassist.ledger.shared.Money;
import java.time.LocalDate;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A plan's lifecycle move is stored only against the state it was validated on (#370).
 *
 * <p>A move is decided on one read of a flow and written later. Planning has no trigger behind that write,
 * and {@code planning.planned_flow} holds only the current state, so a stale write that lands leaves no
 * trace: a commit decided before a cancel returns the cancelled plan to every projection, and nothing
 * records that it was ever cancelled. Accounting's journal, by contrast, would at least keep both rows.
 *
 * <p>The races are forced, never hoped for. A move can lose in two places, and each has its own case: after
 * the rival has committed, and while the rival's update is uncommitted. {@link RivalWriter} holds the rival
 * open until the worker is seen blocked on it, so every interleaving here happens on every run.
 *
 * <p>{@code JournalTransitionRaceIT} has a third case, a rival committing between the worker's read of the
 * state and its write, and it has no equivalent here on purpose. {@code saveTransition} is a single
 * {@code UPDATE} whose {@code WHERE} holds the state, so there is no gap between a read and a write for a
 * rival to land in.
 */
@SpringBootTest
class PlanTransitionRaceIT extends PostgresFixture {

    private static final LocalDate FEB_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEB_3 = LocalDate.of(2026, 2, 3);
    private static final LocalDate FEB_5 = LocalDate.of(2026, 2, 5);

    private static final String CANCEL_REASON = "too expensive";

    /** Outlasts {@link RivalWriter}'s own wait, so a worker that never returns fails here, by name. */
    private static final long WORKER_BOUND_SECONDS = 15;

    private static final String RIVAL_CANCELS = """
            UPDATE planning.planned_flow
               SET state = 'CANCELLED', state_on = :on, state_reason = :reason
             WHERE id = :id
            """;

    @Autowired
    private PlannedFlowRepository flows;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /**
     * Two moves made from one read, in each order that matters. A pair is listed in both orders because a
     * stale cancel that lands drops a live plan from projections, and a stale commit or realise that lands
     * brings a dead one back or makes a cancelled one look spent. Committing twice on one date writes the
     * very row already stored, so only the state validated against can tell it from a move that is fresh.
     */
    enum TwoMoves {
        COMMIT_THEN_CANCEL(
                false,
                read -> read.commit(FEB_3),
                read -> read.cancel(FEB_3, CANCEL_REASON),
                new PlanState.Committed(FEB_3),
                true),
        CANCEL_THEN_COMMIT(
                false,
                read -> read.cancel(FEB_3, CANCEL_REASON),
                read -> read.commit(FEB_3),
                new PlanState.Cancelled(FEB_3, CANCEL_REASON),
                false),
        COMMIT_THEN_COMMIT_ON_THE_SAME_DATE(
                false, read -> read.commit(FEB_3), read -> read.commit(FEB_3), new PlanState.Committed(FEB_3), true),
        COMMIT_THEN_COMMIT_ON_A_LATER_DATE(
                false, read -> read.commit(FEB_3), read -> read.commit(FEB_5), new PlanState.Committed(FEB_3), true),
        REALISE_THEN_CANCEL(
                true,
                read -> read.realise(FEB_5),
                read -> read.cancel(FEB_5, CANCEL_REASON),
                new PlanState.Realised(FEB_5),
                false),
        CANCEL_THEN_REALISE(
                true,
                read -> read.cancel(FEB_5, CANCEL_REASON),
                read -> read.realise(FEB_5),
                new PlanState.Cancelled(FEB_5, CANCEL_REASON),
                false);

        private final boolean startsCommitted;
        private final UnaryOperator<PlannedFlow> first;
        private final UnaryOperator<PlannedFlow> second;
        private final PlanState stateOfOnlyTheFirst;
        private final boolean countsTowardsProjection;

        TwoMoves(
                boolean startsCommitted,
                UnaryOperator<PlannedFlow> first,
                UnaryOperator<PlannedFlow> second,
                PlanState stateOfOnlyTheFirst,
                boolean countsTowardsProjection) {
            this.startsCommitted = startsCommitted;
            this.first = first;
            this.second = second;
            this.stateOfOnlyTheFirst = stateOfOnlyTheFirst;
            this.countsTowardsProjection = countsTowardsProjection;
        }
    }

    /** Each test has ended its rival by now, so the worker has nothing left to wait for. */
    @AfterEach
    void shutTheWorkerDown() throws InterruptedException {
        worker.shutdownNow();
        assertThat(worker.awaitTermination(WORKER_BOUND_SECONDS, TimeUnit.SECONDS))
                .as("the worker finished once its rival had ended")
                .isTrue();
    }

    /**
     * The whole bug with no thread in sight: by the time the second move is written, the first has been
     * committed for as long as anyone likes, and only the state the second was validated against can tell
     * the store that its decision is stale.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TwoMoves.class)
    void
            Given_two_moves_validated_against_one_read_When_the_second_is_saved_Then_it_is_refused_and_only_the_first_is_stored(
                    TwoMoves moves) {
        PlanId id = storedFor(moves);
        PlannedFlow read = flows.findById(id).orElseThrow();

        flows.saveTransition(read.state(), moves.first.apply(read));
        Throwable refusal = catchThrowable(() -> flows.saveTransition(read.state(), moves.second.apply(read)));

        PlannedFlow stored = flows.findById(id).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(refusal)
                    .as("what the stale move threw")
                    .isInstanceOf(ConcurrentPlanTransitionException.class);
            softly.assertThat(stored.state()).as("the stored state and date").isEqualTo(moves.stateOfOnlyTheFirst);
            softly.assertThat(stored.countsTowardsProjection())
                    .as("whether the stored flow counts towards a projection")
                    .isEqualTo(moves.countsTowardsProjection);
        });
    }

    /**
     * The worker read {@code Planned} and is queued on the row the rival's uncommitted cancel holds. Once
     * the rival commits, the row no longer says what the worker validated against, so the commit must be
     * refused rather than written over the cancel, which would put the plan back into projections.
     */
    @Test
    void Given_a_rivals_uncommitted_cancel_When_it_commits_under_a_blocked_transition_Then_the_transition_is_refused()
            throws Exception {
        PlannedFlow read = stored();

        Throwable outcome;
        try (RivalWriter rival = rivalWriter()) {
            rivalCancels(rival, read.id());
            Future<?> committing = commitInTheBackground(read);
            rival.awaitBlockingAnotherBackend();

            rival.commit();
            outcome = outcomeOf(committing);
        }

        PlannedFlow stored = flows.findById(read.id()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(outcome)
                    .as("what the stale move threw")
                    .isInstanceOf(ConcurrentPlanTransitionException.class);
            softly.assertThat(stored.state())
                    .as("the stored state and date")
                    .isEqualTo(new PlanState.Cancelled(FEB_3, CANCEL_REASON));
            softly.assertThat(stored.countsTowardsProjection())
                    .as("whether the cancelled flow counts towards a projection")
                    .isFalse();
        });
    }

    /**
     * A rival that gives up changed nothing, so the state the worker validated against is still the stored
     * one and its move is good. A store that refused on sight of a lock would turn every aborted neighbour
     * into a spurious conflict for the owner to retry.
     */
    @Test
    void Given_a_rivals_uncommitted_cancel_When_it_rolls_back_under_a_blocked_transition_Then_the_transition_is_stored()
            throws Exception {
        PlannedFlow read = stored();

        Throwable outcome;
        try (RivalWriter rival = rivalWriter()) {
            rivalCancels(rival, read.id());
            Future<?> committing = commitInTheBackground(read);
            rival.awaitBlockingAnotherBackend();

            rival.rollback();
            outcome = outcomeOf(committing);
        }

        PlannedFlow stored = flows.findById(read.id()).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(outcome).as("what the worker threw").isNull();
            softly.assertThat(stored.state()).as("the stored state and date").isEqualTo(new PlanState.Committed(FEB_5));
        });
    }

    /** Commits on the worker's thread, validated against what {@code read} says, as the use case would. */
    private Future<?> commitInTheBackground(PlannedFlow read) {
        return worker.submit(() -> flows.saveTransition(read.state(), read.commit(FEB_5)));
    }

    /** What the worker threw, or {@code null} if it returned; a timeout is itself a failure to report. */
    private static Throwable outcomeOf(Future<?> running) throws Exception {
        try {
            running.get(WORKER_BOUND_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException thrown) {
            return thrown.getCause();
        }
    }

    private static void rivalCancels(RivalWriter rival, PlanId id) {
        rival.jdbc()
                .sql(RIVAL_CANCELS)
                .param("id", id.value())
                .param("on", FEB_3)
                .param("reason", CANCEL_REASON)
                .update();
    }

    private PlanId storedFor(TwoMoves moves) {
        PlannedFlow planned = stored();
        if (moves.startsCommitted) {
            flows.saveTransition(planned.state(), planned.commit(FEB_3));
        }
        return planned.id();
    }

    private PlannedFlow stored() {
        PlannedFlow planned = PlannedFlow.plan(
                "New car", Money.of("40000.00", Currency.ILS), Direction.OUTFLOW, FEB_1, new Recurrence.Once());
        flows.save(planned);
        return planned;
    }
}
