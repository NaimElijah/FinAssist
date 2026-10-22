<!--
Every pull request states the six sections below (README, "Conventions").
Into dev, cite each issue with "Refs #N"; into main, a promotion closes them with "Closes #N".
-->

## Problem

<!-- What is wrong or missing, and the issue that records it. -->

## Approach

<!-- How this change solves it, and why this way. -->

## Trade-offs accepted

<!-- What this makes harder or leaves out, and every default chosen where the issue left a gap. -->

## What changed

<!-- One line per commit, or a table of commit and files. -->

## How it was verified

<!-- What you ran and what it printed. Say plainly what was not verified. -->

## ADR

<!-- The ADR this follows, adds or supersedes, or "None" and why. -->

## Checklist

<!-- Tick an item that does not apply, and say so beside it. -->

- [ ] Build, tests, format and lint run locally, and the result is stated above.
- [ ] `docs/requirements/requirements.md`, `docs/architecture/architecture.md` and the README
      change in the same commit as the behaviour they describe.
- [ ] After an endpoint change, the service's contract is regenerated (`LedgerContractIT`,
      `tests/test_contract.py` or `GatewayContractIT`), then the web types
      (`make generate-web-api`).
- [ ] A change to code adds a bold-lead bullet to `CHANGELOG.md` under `[Unreleased]`.
- [ ] A new or superseded ADR is linked above and indexed in `docs/ADRs/README.md`.
