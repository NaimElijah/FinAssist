/**
 * Catches a render error so one broken component does not blank the whole page silently.
 *
 * React has no hook equivalent: `getDerivedStateFromError` and `componentDidCatch` exist only
 * on class components, which is why this one is a class in a codebase that is otherwise
 * functions. Without it a throw during render unmounts the tree and leaves an empty document
 * with nothing written anywhere — the definition of a silent failure. See ADR-019.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

import { log } from '@/shared/lib/logger';

interface Props {
    readonly children: ReactNode;
    /** Rendered in place of the failed subtree. Defaults to a plain, unstyled message. */
    readonly fallback?: ReactNode;
}

interface State {
    readonly failed: boolean;
}

export class ErrorBoundary extends Component<Props, State> {
    override state: State = { failed: false };

    static getDerivedStateFromError(): State {
        return { failed: true };
    }

    override componentDidCatch(error: Error, info: ErrorInfo): void {
        log.error('render failed', {
            reason: error.message,
            // The component stack is what makes this actionable; the message alone rarely says
            // which subtree threw.
            componentStack: info.componentStack,
        });
    }

    override render(): ReactNode {
        if (this.state.failed) {
            return this.props.fallback ?? <p role="alert">Something went wrong.</p>;
        }
        return this.props.children;
    }
}
