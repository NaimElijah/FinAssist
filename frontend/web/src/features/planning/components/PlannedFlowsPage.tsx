import { useCallback, useEffect, useId, useRef, useState } from 'react';

import { clearSettledOutcome } from '@/shared/lib/settledMutation';
import { todayIsoDate } from '@/shared/lib/today';
import { useWriteAccess } from '@/shared/lib/writeAccess';
import {
    ActionPanel,
    Button,
    type Column,
    DataTable,
    DateField,
    DateText,
    EmptyState,
    ErrorState,
    labelsByField,
    formatCalendarDate,
    LoadingNote,
    MoneyText,
    QueryErrorState,
    StateBadge,
    TextField,
    type Tone,
    useRetryFocus,
} from '@/shared/ui';

import {
    useCancelFlow,
    useCommitFlow,
    usePlannedFlows,
    useRealiseFlow,
} from '../hooks/planQueries';
import {
    DIRECTION_LABELS,
    PLAN_STATUS_LABELS,
    type PlannedFlow,
    type PlanStatus,
} from '../types/flow';
import { recurrenceSummary } from '../types/recurrence';
import { PlanFlowForm } from './PlanFlowForm';
import styles from './PlannedFlowsPage.module.css';

const EMPTY_MESSAGE = 'No planned flows yet';

/** A lifecycle panel's labels, keyed by the request member each field fills. */
const PANEL_LABELS = { on: 'On', reason: 'Reason' } as const;

/** A failed request's field errors, listed under the form's own labels (WCAG 3.3.1). */
const PANEL_FIELD_LABEL = labelsByField(PANEL_LABELS);

/** Each direction's colour role. The label always carries the meaning; tone only reinforces it. */
const DIRECTION_TONES: Readonly<Record<PlannedFlow['direction'], Tone>> = {
    INFLOW: 'positive',
    OUTFLOW: 'neutral',
    INVESTMENT: 'neutral',
};

/** Each status's colour role, mirroring `TransactionState`'s own four-state mapping. */
const STATUS_TONES: Readonly<Record<PlanStatus, Tone>> = {
    PLANNED: 'warning',
    COMMITTED: 'neutral',
    REALISED: 'positive',
    CANCELLED: 'negative',
};

/** The three lifecycle actions a planned flow can move through, each opening its own panel. */
type LifecycleKind = 'COMMIT' | 'REALISE' | 'CANCEL';

/** Which row's panel is open, and for which action; `undefined` when none is. */
interface ActiveAction {
    readonly flowId: string;
    readonly kind: LifecycleKind;
}

/**
 * A just-closed panel's action, for restoring focus once it closes.
 *
 * `newStatus` is the flow's status straight from the mutation's own response - known the instant
 * it succeeds, unlike the separate flows-list query, whose refetch can still be in flight when
 * the panel closes. `undefined` means nothing changed: the panel itself was cancelled.
 */
interface ClosedAction extends ActiveAction {
    readonly newStatus?: PlanStatus | undefined;
}

/**
 * The lifecycle actions each status legally admits, mirroring the ledger's own sealed
 * `PlanState` transitions: `PLANNED` commits or cancels, `COMMITTED` realises or cancels, and a
 * `REALISED` or `CANCELLED` flow admits none. The server stays authoritative regardless: a stale
 * row offering an action it no longer allows still meets a 409 `ILLEGAL_TRANSITION`.
 */
const LEGAL_ACTIONS: Readonly<Record<PlanStatus, readonly LifecycleKind[]>> = {
    PLANNED: ['COMMIT', 'CANCEL'],
    COMMITTED: ['REALISE', 'CANCEL'],
    REALISED: [],
    CANCELLED: [],
};

const ACTION_LABELS: Readonly<Record<LifecycleKind, string>> = {
    COMMIT: 'Commit',
    REALISE: 'Realise',
    CANCEL: 'Cancel',
};

/**
 * The panel's own submit label. `CANCEL`'s differs from `ACTION_LABELS.CANCEL`: `ActionPanel`
 * always renders its own "Cancel" button to close the panel, and a submit button reading the
 * same word would be indistinguishable from it once the panel is open.
 */
const SUBMIT_LABELS: Readonly<Record<LifecycleKind, string>> = {
    COMMIT: 'Commit',
    REALISE: 'Realise',
    CANCEL: 'Cancel flow',
};

const ACTION_TITLES: Readonly<Record<LifecycleKind, string>> = {
    COMMIT: 'Commit planned flow',
    REALISE: 'Realise planned flow',
    CANCEL: 'Cancel planned flow',
};

/** The id of one flow's actions cell, so focus can be restored to it after its panel closes. */
function actionsCellId(flowId: string): string {
    return `flow-actions-${flowId}`;
}

/**
 * A flow as the row shows it - its description and start - for the accessible names of the
 * controls acting on it, so each says which row it acts on out of the table's context. Two flows
 * sharing both share it.
 */
function flowName(flow: PlannedFlow): string {
    return `${flow.description}, starting ${formatCalendarDate(flow.startsOn)}`;
}

/** The id of one flow's state cell, the fallback focus target when its own action is gone. */
function stateCellId(flowId: string): string {
    return `flow-state-${flowId}`;
}

/**
 * The buttons offering this status's legal actions, or nothing for a frozen flow or a read-only
 * session (`useWriteAccess`) - Commit, Realise and Cancel all change data, so none is offered to
 * a viewer (ADR-032).
 */
function LifecycleButtons({
    status,
    rowName,
    onOpen,
}: {
    readonly status: PlanStatus;
    /** The row the buttons act on, from `flowName`, after each button's own word. */
    readonly rowName: string;
    readonly onOpen: (kind: LifecycleKind) => void;
}) {
    const { canWrite } = useWriteAccess();
    const actions = LEGAL_ACTIONS[status];
    if (!canWrite || actions.length === 0) {
        return null;
    }
    return (
        <span className={styles.actions}>
            {actions.map((kind) => (
                <Button
                    key={kind}
                    data-action={kind}
                    // The visible word comes first, so a voice command naming it still matches
                    // (WCAG 2.5.3).
                    aria-label={`${ACTION_LABELS[kind]} ${rowName}`}
                    variant={kind === 'CANCEL' ? 'danger' : 'secondary'}
                    onClick={() => {
                        onOpen(kind);
                    }}
                >
                    {ACTION_LABELS[kind]}
                </Button>
            ))}
        </span>
    );
}

/**
 * The inline panel for one lifecycle action: a date defaulting to today, and a reason when
 * cancelling. Mirrors `AccountDetailPage`'s own `LifecycleActionPanel` exactly.
 *
 * On success it refetches what the transition can change (see `planQueries.ts`) and closes. On
 * failure - including a 409 `ILLEGAL_TRANSITION` when the server no longer agrees this row's
 * action is legal - it shows the server's detail and stays open, so the owner can see why and try
 * something else. Editing the date or the reason clears that failure, since it answered the action
 * as it was sent.
 *
 * The Reason field's own `required` is the whole of its client-side validation: the browser
 * refuses to fire the form's submit event while it is empty, so no duplicate check is needed here.
 */
function LifecycleActionPanel({
    kind,
    flowId,
    rowName,
    onClose,
}: {
    readonly kind: LifecycleKind;
    readonly flowId: string;
    /** The row the panel acts on, from `flowName`, so its title says which. */
    readonly rowName: string;
    /** Called with the flow's new status on success, or with none on cancel. */
    readonly onClose: (newStatus?: PlanStatus) => void;
}) {
    const commit = useCommitFlow();
    const realise = useRealiseFlow();
    const cancel = useCancelFlow();
    const [on, setOn] = useState(() => todayIsoDate(new Date()));
    const [reason, setReason] = useState('');
    const submitting = useRef(false);
    const errorStateRef = useRef<HTMLDivElement>(null);

    const mutation = kind === 'COMMIT' ? commit : kind === 'REALISE' ? realise : cancel;
    const failure = mutation.error;

    // Runs after the render that shows the failure, so the error state is already on the page.
    useEffect(() => {
        if (failure === null) {
            return;
        }
        errorStateRef.current?.focus();
    }, [failure]);

    function submit() {
        // The disabled submit stops a click, not a submit event from elsewhere.
        if (submitting.current) {
            return;
        }
        submitting.current = true;
        // The mutation's own response carries the flow's new status; passed to onClose so it can
        // decide where focus goes without waiting on the separate flows query.
        const onSuccess = (flow: PlannedFlow) => {
            onClose(flow.state.status);
        };
        const onSettled = () => {
            submitting.current = false;
        };
        if (kind === 'COMMIT') {
            commit.mutate({ flowId, on }, { onSuccess, onSettled });
        } else if (kind === 'REALISE') {
            realise.mutate({ flowId, on }, { onSuccess, onSettled });
        } else {
            cancel.mutate({ flowId, on, reason }, { onSuccess, onSettled });
        }
    }

    return (
        <ActionPanel
            title={`${ACTION_TITLES[kind]}: ${rowName}`}
            headingLevel={2}
            submitLabel={SUBMIT_LABELS[kind]}
            onSubmit={submit}
            onCancel={() => {
                // Never onClose directly: ActionPanel's Cancel button calls this as a click
                // handler, and passing onClose straight through would read the click event as
                // the newStatus parameter.
                onClose();
            }}
            pending={mutation.isPending}
        >
            <div className={styles.panelFields}>
                {failure === null ? null : (
                    <ErrorState
                        ref={errorStateRef}
                        error={failure}
                        fieldLabel={PANEL_FIELD_LABEL}
                    />
                )}
                <DateField
                    label={PANEL_LABELS.on}
                    required
                    value={on}
                    onChange={(event) => {
                        clearSettledOutcome(mutation, submitting);
                        setOn(event.target.value);
                    }}
                />
                {kind === 'CANCEL' ? (
                    <TextField
                        label={PANEL_LABELS.reason}
                        required
                        value={reason}
                        onChange={(event) => {
                            clearSettledOutcome(mutation, submitting);
                            setReason(event.target.value);
                        }}
                    />
                ) : null}
            </div>
        </ActionPanel>
    );
}

/** This row's actions cell: buttons for its legal transitions, or the one open panel. */
function FlowActionsCell({
    flow,
    active,
    onOpen,
    onClose,
}: {
    readonly flow: PlannedFlow;
    readonly active: ActiveAction | undefined;
    readonly onOpen: (flowId: string, kind: LifecycleKind) => void;
    readonly onClose: (newStatus?: PlanStatus) => void;
}) {
    return (
        <div id={actionsCellId(flow.id)}>
            {active?.flowId === flow.id ? (
                <LifecycleActionPanel
                    kind={active.kind}
                    flowId={flow.id}
                    rowName={flowName(flow)}
                    onClose={onClose}
                />
            ) : (
                <LifecycleButtons
                    status={flow.state.status}
                    rowName={flowName(flow)}
                    onOpen={(kind) => {
                        onOpen(flow.id, kind);
                    }}
                />
            )}
        </div>
    );
}

/**
 * A status badge, followed by the reason when the flow was cancelled.
 *
 * `id` and `tabIndex={-1}` let a lifecycle panel that closed after changing this row's status
 * move focus here: a script can focus an off-Tab-order element, but never a plain `<span>`.
 */
function StateCell({ state, id }: { readonly state: PlannedFlow['state']; readonly id: string }) {
    return (
        <span id={id} tabIndex={-1} className={styles.state}>
            <StateBadge
                status={state.status}
                tones={STATUS_TONES}
                label={PLAN_STATUS_LABELS[state.status]}
            />
            {state.status === 'CANCELLED' ? (
                <span className={styles.reason}>{state.reason}</span>
            ) : null}
        </span>
    );
}

/**
 * One flow's description, taking focus once when it was just planned - the same signal
 * `AccountsPage`'s `AccountLink` gives its new row, without a link to carry it: a planned flow
 * has no detail page.
 */
function DescriptionCell({
    flow,
    focused,
    onFocused,
}: {
    readonly flow: PlannedFlow;
    readonly focused: boolean;
    /** Called once focus has been applied, so the request to focus can be cleared. */
    readonly onFocused: () => void;
}) {
    const ref = useRef<HTMLSpanElement>(null);

    useEffect(() => {
        if (focused) {
            ref.current?.focus();
            onFocused();
        }
    }, [focused, onFocused]);

    return (
        <span ref={ref} tabIndex={-1}>
            {flow.description}
        </span>
    );
}

/** The flows table's columns. The description heads each row. */
function flowColumns(
    focusedFlowId: string | undefined,
    onFocused: () => void,
    active: ActiveAction | undefined,
    onOpen: (flowId: string, kind: LifecycleKind) => void,
    onClose: (newStatus?: PlanStatus) => void,
): readonly Column<PlannedFlow>[] {
    return [
        {
            key: 'description',
            header: 'Description',
            rowHeader: true,
            render: (flow) => (
                <DescriptionCell
                    flow={flow}
                    focused={flow.id === focusedFlowId}
                    onFocused={onFocused}
                />
            ),
        },
        {
            key: 'amount',
            header: 'Amount',
            align: 'end',
            render: (flow) => (
                <MoneyText amount={flow.amount.amount} currency={flow.amount.currency} />
            ),
        },
        {
            key: 'direction',
            header: 'Direction',
            render: (flow) => (
                <StateBadge
                    status={flow.direction}
                    tones={DIRECTION_TONES}
                    label={DIRECTION_LABELS[flow.direction]}
                />
            ),
        },
        {
            key: 'starts',
            header: 'Starts',
            align: 'end',
            render: (flow) => <DateText value={flow.startsOn} />,
        },
        {
            key: 'recurrence',
            header: 'Recurrence',
            render: (flow) => recurrenceSummary(flow.recurrence),
        },
        {
            key: 'state',
            header: 'State',
            render: (flow) => <StateCell state={flow.state} id={stateCellId(flow.id)} />,
        },
        {
            key: 'actions',
            header: 'Actions',
            render: (flow) => (
                <FlowActionsCell flow={flow} active={active} onOpen={onOpen} onClose={onClose} />
            ),
        },
    ];
}

/**
 * The planned flows list in whichever state its request is in.
 *
 * Flows already on screen stay there when a later refetch fails: TanStack Query keeps the data it
 * holds, so the failure and its retry are shown above the rows rather than in their place.
 */
function PlannedFlowsList({
    focusedFlowId,
    onFocused,
}: {
    readonly focusedFlowId: string | undefined;
    readonly onFocused: () => void;
}) {
    // At most one row's panel is open at a time, across the whole table.
    const [active, setActive] = useState<ActiveAction | undefined>(undefined);
    // Which row and action a just-closed panel was for, so focus can return there once the DOM
    // reflects the close; state, not a ref, so it is never read or written during render.
    const [closedAction, setClosedAction] = useState<ClosedAction | undefined>(undefined);
    const flows = usePlannedFlows();
    const { targetRef, onRetry } = useRetryFocus<HTMLDivElement>(
        flows.error,
        flows.isFetching,
        () => {
            void flows.refetch();
        },
    );

    // Runs after a panel closes, whether by cancel or by success - both go through closeAction
    // below, and a failure never does (the panel stays open and keeps its own focus). Prefers the
    // button for the acted-on action, still present after a cancel because the status has not
    // changed; falls back to the row's state cell, which is what actually changed after a
    // successful transition removed that action from the row.
    useEffect(() => {
        if (closedAction === undefined) {
            return;
        }
        const stillOffered =
            closedAction.newStatus === undefined ||
            LEGAL_ACTIONS[closedAction.newStatus].includes(closedAction.kind);
        const target = stillOffered
            ? document
                  .getElementById(actionsCellId(closedAction.flowId))
                  ?.querySelector<HTMLElement>(`[data-action="${closedAction.kind}"]`)
            : document.getElementById(stateCellId(closedAction.flowId));
        target?.focus();
    }, [closedAction]);

    function openAction(flowId: string, kind: LifecycleKind) {
        setActive({ flowId, kind });
    }

    function closeAction(newStatus?: PlanStatus) {
        if (active !== undefined) {
            setClosedAction({ ...active, newStatus });
        }
        setActive(undefined);
    }

    if (flows.isPending) {
        return <LoadingNote message="Loading planned flows…" />;
    }
    const failure = (
        <QueryErrorState error={flows.error} retrying={flows.isFetching} onRetry={onRetry} />
    );
    if (flows.data === undefined) {
        return failure;
    }
    return (
        <div ref={targetRef} tabIndex={-1} className={styles.stack}>
            {failure}
            {flows.data.length === 0 ? (
                <EmptyState message={EMPTY_MESSAGE} />
            ) : (
                <DataTable
                    caption="Planned flows"
                    captionHidden
                    columns={flowColumns(focusedFlowId, onFocused, active, openAction, closeAction)}
                    rows={flows.data}
                    rowKey={(flow) => flow.id}
                />
            )}
        </div>
    );
}

/**
 * `/planning/flows`: every planned flow with its amount, direction, start date and recurrence
 * summary, whatever its state, and, for a session with write access, the form that plans another.
 *
 * Each flow's legal lifecycle actions are offered per its current status; opening one moves the
 * flow through `PLANNED → COMMITTED → REALISED`, with `CANCELLED` reachable from the first two.
 * Planning a flow moves focus to its new row's description, once - the one success signal,
 * mirroring `AccountsPage`'s own pattern.
 *
 * A read-only session (`useWriteAccess`) sees neither the form nor a lifecycle action (ADR-032).
 */
export function PlannedFlowsPage() {
    const { canWrite } = useWriteAccess();
    const listHeadingId = useId();
    const [plannedFlowId, setPlannedFlowId] = useState<string | undefined>(undefined);
    // Stable, so the row's focus effect does not run again on every render of the page.
    const clearPlannedFlow = useCallback(() => {
        setPlannedFlowId(undefined);
    }, []);

    return (
        <>
            <section aria-labelledby={listHeadingId} className={styles.list}>
                <h2 id={listHeadingId} className={styles.listHeading}>
                    All planned flows
                </h2>
                <PlannedFlowsList focusedFlowId={plannedFlowId} onFocused={clearPlannedFlow} />
            </section>
            {canWrite ? (
                <PlanFlowForm
                    onPlanned={(flow) => {
                        setPlannedFlowId(flow.id);
                    }}
                />
            ) : null}
        </>
    );
}
