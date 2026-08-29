/**
 * Application shell.
 *
 * The only place allowed to know about every feature at once. A feature never imports another
 * feature; composition happens here, which is what keeps the boundaries in `src/features`
 * meaningful. See `tests/architecture.test.ts`.
 */
export function App() {
    return (
        <main>
            <h1>FinAssist</h1>
            <p>No feature is implemented yet.</p>
        </main>
    );
}
