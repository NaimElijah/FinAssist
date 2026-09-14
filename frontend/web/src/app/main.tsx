// Tokens first: the base sheet and every component module read the custom properties it defines.
import '../shared/ui/tokens.css';
import './global.css';

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from './App';
import { ErrorBoundary } from './ErrorBoundary';
import { createQueryClient } from './queryClient';
import { createAppRouter } from './routes';

const container = document.getElementById('root');
if (!container) {
    // Fail loudly: a missing mount point is a broken build, not a condition to recover from.
    throw new Error('Root element #root is missing from index.html');
}

// Created once, outside React: the router must not be recreated on render, and one query cache
// serves the whole page.
const router = createAppRouter();
const queryClient = createQueryClient();

createRoot(container).render(
    <StrictMode>
        <ErrorBoundary>
            <App router={router} queryClient={queryClient} />
        </ErrorBoundary>
    </StrictMode>,
);
