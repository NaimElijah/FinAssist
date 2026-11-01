/**
 * The one place the web client writes a log line.
 *
 * It exists for the same reason the assistant's does: redaction has to be a property of the
 * boundary, not a duty at every call site. `no-console` is enabled in `eslint.config.js` so
 * this module is the only way out, which mirrors the backend's ban on `print`. See ADR-019.
 *
 * Records are JSON on the console. A browser has nowhere else to put them, and shipping them
 * anywhere is a decision that needs a collector endpoint the client does not have yet.
 */

/**
 * Severity levels, ordered. `debug` is meant for the developer, but nothing filters by level: it is
 * written in every build, a production one included, and redacted like any other record.
 */
export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/** Structured fields attached to a record. Values are redacted before they are written. */
export type LogFields = Record<string, unknown>;

export interface LogRecord {
    readonly timestamp: string;
    readonly level: LogLevel;
    readonly event: string;
    readonly [field: string]: unknown;
}

/** A sink a record is handed to. Swapped in tests; the default writes to the console. */
export type LogSink = (record: LogRecord) => void;

export const REDACTED = '[redacted]';

/**
 * Key fragments whose values never reach the output. Matched as substrings on a
 * separator-insensitive form, so `apiKey`, `api_key` and `X-Api-Key` are all caught.
 */
// Deliberately no bare 'token': the assistance feature's whole job is to report LLM usage,
// and `inputTokens` is the natural way to log it. A fragment that redacted a token count would
// destroy the measurement while protecting nothing. None of the fragments below is a substring
// of 'inputtokens' or 'outputtokens' either, so that measurement survives this list too.
const SECRET_KEY_FRAGMENTS = [
    'apikey',
    'authtoken',
    'authorization',
    'accesstoken',
    'credential',
    'idtoken',
    'password',
    'refreshtoken',
    'secret',
    // The gateway (ADR-030) keeps the browser's tokens server-side: the session and CSRF
    // cookies it sets, the header that echoes the CSRF one back, and the bearer/JWT it relays
    // to the ledger and the assistant are exactly as credential-shaped as the fragments above.
    'cookie',
    'xsrf',
    'csrf',
    'session',
    'bearer',
    'jwt',
] as const;

/** Guards against a cyclic structure turning redaction into an infinite descent. */
const MAX_DEPTH = 6;

function isSecretKey(key: string): boolean {
    const normalised = key.toLowerCase().replaceAll(/[-_]/g, '');
    return SECRET_KEY_FRAGMENTS.some((fragment) => normalised.includes(fragment));
}

function redact(value: unknown, depth = 0): unknown {
    if (depth >= MAX_DEPTH) {
        return REDACTED;
    }
    if (Array.isArray(value)) {
        return value.map((item) => redact(item, depth + 1));
    }
    if (value !== null && typeof value === 'object') {
        return Object.fromEntries(
            Object.entries(value).map(([key, item]) => [
                key,
                isSecretKey(key) ? REDACTED : redact(item, depth + 1),
            ]),
        );
    }
    return value;
}

/**
 * Redact a set of structured fields.
 *
 * @param fields - the fields as the caller supplied them.
 * @returns a new object with secret-named keys replaced, recursively.
 */
export function redactFields(fields: LogFields): LogFields {
    return redact(fields) as LogFields;
}

/* eslint-disable no-console -- this is the boundary the rule exists to funnel everything into */
const consoleSink: LogSink = (record) => {
    const line = JSON.stringify(record);
    if (record.level === 'error') {
        console.error(line);
    } else if (record.level === 'warn') {
        console.warn(line);
    } else {
        console.log(line);
    }
};
/* eslint-enable no-console */

let sink: LogSink = consoleSink;

/**
 * Replace the sink. Returns a function restoring the previous one.
 *
 * @param replacement - where records should go instead.
 * @returns a restore function, so a test cannot leave the sink swapped for the next one.
 */
export function setLogSink(replacement: LogSink): () => void {
    const previous = sink;
    sink = replacement;
    return () => {
        sink = previous;
    };
}

function write(level: LogLevel, event: string, fields: LogFields = {}): void {
    sink({
        // Caller fields are spread first so the record's own metadata always wins. Spreading
        // them last let `log.error('http failed', { event: 'click' })` replace the stable event
        // name every filter and alert keys on.
        ...redactFields(fields),
        timestamp: new Date().toISOString(),
        level,
        event,
    });
}

/**
 * Write a structured record.
 *
 * @param event - a short, stable description. Variable parts belong in `fields`, so records
 *   stay groupable rather than each one being unique prose.
 * @param fields - structured context, redacted before writing.
 */
export const log = {
    debug: (event: string, fields?: LogFields) => {
        write('debug', event, fields);
    },
    info: (event: string, fields?: LogFields) => {
        write('info', event, fields);
    },
    warn: (event: string, fields?: LogFields) => {
        write('warn', event, fields);
    },
    error: (event: string, fields?: LogFields) => {
        write('error', event, fields);
    },
};
