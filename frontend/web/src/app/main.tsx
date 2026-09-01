import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { ErrorBoundary } from './ErrorBoundary';

const container = document.getElementById('root');
if (!container) {
    // Fail loudly: a missing mount point is a broken build, not a condition to recover from.
    throw new Error('Root element #root is missing from index.html');
}

createRoot(container).render(
    <StrictMode>
        <ErrorBoundary>
            <App />
        </ErrorBoundary>
    </StrictMode>,
);
