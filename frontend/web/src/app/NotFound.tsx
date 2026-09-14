import { Link } from 'react-router';

import { EmptyState } from '../shared/ui';

/**
 * The page for a path no route matches.
 *
 * Rendered inside the layout, so the navigation stays available, and it offers a way back to
 * Accounts, where the app opens.
 */
export function NotFound() {
    return (
        <>
            <h1>Page not found</h1>
            <EmptyState
                message="Nothing lives at this address."
                action={<Link to="/accounts">Go to Accounts</Link>}
            />
        </>
    );
}
