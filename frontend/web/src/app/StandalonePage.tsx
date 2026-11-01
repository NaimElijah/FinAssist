import { type ReactNode, useEffect } from 'react';

import { documentTitleFor } from './productName';
import styles from './StandalonePage.module.css';

/** The screen's title, and what it shows beneath its heading. */
export interface StandalonePageProps {
    /** Heads the screen and titles the document, without the product name. */
    readonly title: string;
    readonly children?: ReactNode;
}

/**
 * A whole screen shown where the app's layout is not: the session check, signing in, an outage
 * reaching the gateway, a render failure that took the app down, and a failure of `Layout` itself
 * (`RootRouteError`).
 *
 * The first four render above `RouterProvider` (see `App.tsx` and `main.tsx`); the last renders
 * inside it, as the root route's boundary, but replaces `Layout`. Either way `Layout` - with its
 * `<main>`, its document title and its heading convention - is not there to provide them. This
 * gives such a screen the same three: a `<main>` landmark, an h1 naming the screen, and a document
 * title built the way `Layout` builds one (WCAG 1.3.1, 2.4.2, 2.4.6).
 *
 * @param props - see `StandalonePageProps`.
 */
export function StandalonePage({ title, children }: StandalonePageProps) {
    useEffect(() => {
        document.title = documentTitleFor(title);
    }, [title]);

    return (
        <main className={styles.main}>
            <h1>{title}</h1>
            {children}
        </main>
    );
}
