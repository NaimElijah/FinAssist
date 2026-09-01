# ADR-021: The application layer — use-case ports, consumer-owned out-ports, constructor wiring

- **Status:** Accepted
- **Date:** 2026-09-01

## Context

Every one of the six contexts had a complete, tested domain layer and nothing above it. The
`application/port/{in,out}` and `application/service` directories existed and were empty in the
ledger; `application/{port,usecase}` were empty in the assistant, their `__init__.py` docstrings
naming ports that did not exist.

That gap was not cosmetic. Three requirements were recorded as `TODO` with the explicit note that
the domain *cannot* satisfy them:

- an entry's currency must match the account it is posted to — an `Entry` holds an `AccountId`,
  not an `Account`, so nothing in the domain can look the currency up;
- an account name must be unique within the chart of accounts — the same problem;
- `Budget.remaining` takes spend and `Projection.of` takes an opening balance, both of which live
  in `accounting`, which neither `budgeting` nor `planning` may name.

ADR-007 named the packages and their semantics. It did not say what a use-case port looks like,
who owns an out-port, how a service is wired, or what happens at the seam between two contexts.
Those questions all arrive at once with the first service, and answering them by precedent —
whatever the first file happens to do — is how a layer acquires four shapes for one idea.

Two constraints bound the answer before it starts. `LedgerApplication`'s Javadoc committed to the
application layer being "wired by constructor, not by the container", and nothing enforced it. And
the assistant's architecture rules forbid every inward module from importing a declared runtime
dependency, so a Python port may use `typing.Protocol`, frozen `dataclass` and the standard
library, and nothing else — pydantic included.

## Decision

**One use-case interface per capability, named for what a caller wants to do.**
`OpenAccountUseCase`, `RecordTransactionUseCase`, `ReviewBudgetUseCase`, `ProjectScenarioUseCase`.
Where several transitions share a subject and a service they share an interface —
`TransitionTransactionUseCase` carries `clear`, `reconcile` and `void` — because splitting them
would produce three files that are never used apart.

**A command is a record, and it carries only what the caller supplies.** Identity is generated
inside the service, never passed in. A transition taking two or three scalars takes them as
parameters rather than acquiring a wrapper record that says nothing.

**An out-port is owned by the context that consumes it, and speaks that context's language.**
`budgeting` declares `SpendJournal` returning `SpendRecord`; `planning` declares
`OpeningPositionProvider` returning `OpeningPosition`, addressed by a local `AccountRef`. Neither
names an accounting type, so `contexts_do_not_depend_on_each_other` stays satisfied without an
exemption. This is ADR-010's "a published port and an explicit translation" applied in-process,
and it is what makes the third stranded requirement above implementable. Where the translation
itself lives is [ADR-022](ADR-022-ledger-composition-root.md).

The `AccountRef` in `planning` is the local reference type
[ADR-015](ADR-015-planning-as-a-sixth-bounded-context.md) predicted and deferred until "the first
time the application layer genuinely needs them". That time is now: `ProjectScenarioCommand`
carries the accounts whose balances open the projection, so the caller names which are cash and
which are invested and **no policy about that is invented in code**.

**Application services are framework-free and wired by constructor**, in both languages. No Spring
annotation, no `jakarta`, no FastAPI, no settings object. A new ArchUnit rule,
`the_application_layer_depends_on_no_framework`, turns the Javadoc's claim into a build failure;
the assistant's existing framework rule already covered its side.

**Ports in the assistant are synchronous.** `def`, not `async def`, on both the `Protocol` and the
use case. FastAPI runs a synchronous dependency in a threadpool, nothing in the system fans out
concurrent I/O, performance is explicitly likely a non-requirement for a single owner, and async
would add `pytest-asyncio`, lockfile churn and `await` ceremony to code that does no concurrent
work. A Python use case is a frozen dataclass holding its ports with one named method, which is
the constructor injection the ledger uses, spelled the way Python spells it.

**Failures are typed and specific.** `AccountNotFoundException`, `DuplicateAccountNameException`,
`EntryCurrencyMismatchException`, `StaleQuoteError`, `PriceUnavailableError`. They live beside the
in-port, because a caller catching one is using the published contract. Nothing returns a
sentinel, and nothing catches broadly to keep going.

**In-memory adapters are a development seam, not the store.**
[ADR-006](ADR-006-postgresql-for-the-ledger-store.md) still governs the durable one and is still
Proposed; nothing here adds a driver, a schema or a line of SQL. The obligation
[ADR-020](ADR-020-audit-trail-follows-persistence.md) attaches to the first repository is honoured
**in the port shape** rather than deferred: no repository port exposes a delete, no entry is ever
mutated in place, and a lifecycle change is stored as a new aggregate state. The durable adapter
inherits a contract that cannot express a destructive write.

**Mutation testing extends to the application services.** The PIT `targetClasses` list gains
`…ledger.*.application.service.*`. Still gated on nothing, per
[ADR-017](ADR-017-test-strategy.md) — the scope grows, the decision does not change.

## Consequences

**Makes easy**

- The three stranded requirements become enforceable, and their tests name the service that holds
  them.
- A use case is tested by calling it with fake out-ports: no Spring context, no database, no HTTP,
  no mock framework. Neither suite gains a dependency.
- The seam between two contexts is a declared interface with a name, so "where does budgeting get
  spend from" has one answer instead of an import.
- `DisposeUnits` sorts lots before calling `consume_fifo`, closing the sharpest edge the README
  names: `consume_fifo` trusts its caller's ordering and silently does LIFO if given the wrong one.

**Makes hard**

- Two more layers of indirection between a request and a domain call, in a system that still has
  no request. A port is paid for now and collected when the second adapter exists.
- Synchronous ports mean the first genuinely concurrent use case pays a mechanical migration
  across every port, use case and test in the assistant.
- An out-port declared by its consumer is a shape guessed before its second implementation exists.
  `SpendJournal` fits the in-memory translation; whether it fits an HTTP one is unproven.

**Accepted cost**

- The in-memory adapters are wired and reachable, and nothing calls them, because no endpoint
  exists. They exist to make the layer runnable and testable, and they will be replaced rather
  than grown.
- `AnswerQuestion` is defined and deliberately **not** wired. An LLM adapter would answer the
  project's central open privacy question by implication, which the requirements reserve for its
  own decision.

## Alternatives considered

**Spend and opening balance passed in on the command.** Simplest, and strictly YAGNI: no port is
declared that nothing implements. Rejected because it moves the unanswered question up to the
caller instead of answering it, and the caller it moves to does not exist either — the use case
would stop short of the thing it exists to do.

**One `@Service`-annotated class per use case, wired by component scan.** The idiomatic Spring
shape, and it would need no configuration class. Rejected because it contradicts a commitment
already written into `LedgerApplication`, and it makes the application layer untestable without
the container. The new ArchUnit rule now makes reversing this a deliberate act rather than a
convenience.

**Asynchronous ports in the assistant.** Matches `httpx.AsyncClient`, the Anthropic async client
and FastAPI's native shape, and avoids a later migration. Rejected on cost now versus cost later:
the migration is mechanical, and the code has no concurrency to express yet.

**A generic `Repository<T, Id>` in `shared`.** Rejected: `shared` is exempt from the context
isolation rule and is therefore the one place coupling can accumulate unchallenged. Three small
purpose-named interfaces cost less than one shared abstraction every context must agree on.

## Revisit when

- **A use case must fan out concurrent I/O** — quoting a whole portfolio, or asking the ledger and
  the LLM at once. That is the trigger to move the assistant's ports to `async`, and it supersedes
  the synchronous half of this record rather than amending it.
- **ADR-006 is ratified**, at which point the in-memory adapters are replaced and ADR-020's
  append-only obligation moves from the port shape into the schema and its migrations.
- **A second implementation of `SpendJournal` or `OpeningPositionProvider` appears** — an HTTP one,
  most likely. If the shape does not survive it, the port was drawn around one caller.
- **A use case starts holding a rule** rather than orchestrating one. That means an invariant is
  homeless and belongs in the domain, and this record should say so.
