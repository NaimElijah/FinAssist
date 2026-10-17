# ADR-042: Agent instructions are split per codebase, one `CLAUDE.md` beside each

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

**`.claude/CLAUDE.md` had grown to 602 lines, and Claude Code loads all of it into every
session.** It held the commands, the layout, about 75 build-enforced conventions and 13 review
conventions for four codebases and `ops/`, all in one file. A session editing a React form
carried the ledger's Flyway and journal rules. A session writing a migration carried the web
client's query-key conventions.

**Length costs adherence, not only context.** Claude Code's memory documentation sets a target of
"under 200 lines per CLAUDE.md file". It says "longer files consume more context and reduce
adherence", and it advises moving "instructions that matter for only part of the codebase" out of
the file that always loads. The `project-context-init` scaffold that created this file sets the
same bar.

**Most of the rules belong to one codebase.** A minority span codebases: context independence,
money, the clock, the wire conventions, and the symmetry of the two resource servers. The rest
concern one codebase:

- the ledger's journal and Flyway;
- the gateway's relay;
- the assistant's middleware order;
- the web client's mutations;
- the backup CLI.

**Claude Code offers three ways to load less.** The memory documentation describes all three, and
Claude Code 2.1.302, the version installed, supports them:

- **A `CLAUDE.md` in a subdirectory** loads "once Claude reads, writes, or edits another file in
  that subdirectory".
- **A rule in `.claude/rules/`** has `paths:` frontmatter naming globs, and loads when a matching
  file is read or written.
- **An `@path` import** organises a file, but it does not "reduce its context cost, because
  imported files also load at launch".

## Decision

**The root `.claude/CLAUDE.md` holds only what applies everywhere, in under 200 lines.** That
covers:

- the orientation;
- the commands;
- a shallow layout;
- the invariants that span codebases;
- the rules for root-level files, such as `docker-compose.yml`;
- the working practices.

**Each codebase has its own `CLAUDE.md` at its root:** `backend/ledger/`, `backend/gateway/`,
`backend/assistant/`, `frontend/web/` and `ops/`. Each one holds that codebase's layout, how to test
it, and every convention that binds there alone.

**A rule lives in exactly one file.** A rule that spans codebases goes in the root, never copied
into each one.

**The root lists every nested file.** It tells a session to read a codebase's file before planning
work there, since that file would otherwise load only once a file under it is touched.

## Consequences

**Makes easy**

- **A session pays only for the rules of the code it touches.** Editing the web client loads the
  root and `frontend/web/CLAUDE.md`. The ledger's persistence rules stay out.
- **A codebase's rules sit beside its code,** so one review covers a change to the code and to the
  rules that govern it.

**Makes hard**

- **A codebase's rules stay unseen until a session reads a file there.** A session planning from
  the root alone can propose something that codebase forbids. The root's index is the mitigation,
  and a build-enforced rule still fails CI regardless.
- **`/compact` drops a nested file until a session touches a file under it again.** The root, by
  contrast, is re-read from disk.
- **A nested file cannot scope two kinds of rule:** one that spans codebases, and one that binds a
  root-level file. Both stay in the root, which must hold them within its budget.
- **`frontend/web/CLAUDE.md` falls under Prettier.** `npm run format:check` runs
  `prettier --check .` in `frontend/web`, so the file must be formatted like the code beside it.

**Accepted cost**

- **There are six files to keep consistent instead of one.** When two of them contradict each
  other, a session may follow either. `/doctor prompt-audit` reads all six and reports the
  conflict.

## Alternatives considered

- **Path-scoped rules in `.claude/rules/`.** Rejected, because the owner chose to keep each
  codebase's rules beside its code. These rules load the same way, and they would keep all agent
  configuration under `.claude/`. They can also scope root-level files and rules that span two
  codebases, which a nested file cannot. That makes them the move named under Revisit when.
- **Trim the single file in place.** Rejected. Cutting the narrative and the repeated rationale
  still leaves about 350 lines of rules, well past the target, and every session would still pay
  for all of them.
- **Split it with `@` imports.** Rejected. The documentation says imported files load at launch,
  so splitting this way saves nothing.

## Revisit when

- **A session acts against a codebase's rule because that codebase's file had not loaded.** That is
  the cost under Makes hard turning real. Move the missed rule into the root, or reconsider this
  record.
- **The root passes 200 lines,** or the rules for root-level files and the rules that span
  codebases outgrow a short section. Move them to `.claude/rules/` with `paths:`, which can scope
  them.
