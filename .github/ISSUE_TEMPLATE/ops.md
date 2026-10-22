---
name: Operations or infrastructure
about: The local stack, CI, backups, Keycloak, or the AWS deployment
title: "ops(<area>): "
labels: "type: chore"
assignees: NaimElijah
---

<!--
Before filing, add what a template cannot: one "priority:" label, one "size:" label, at least one
"area:" label ("area: ops" for the local stack, "area: infra" for AWS, "area: ci" for .github/), a
milestone, and a place on project board #2 at Backlog. Title a CI change "ci: …" rather than
"ops(<area>): …".
-->

## Problem

<!-- What is missing or at risk, and what it would cost the owner's books or the pipeline. -->

## Constraints already decided

<!-- The ADRs and rules this must respect, each cited. -->

## What should change

<!-- The files, services or resources, and the order they change in. -->

## Acceptance

- [ ] Proven by running it in an isolated Compose project or environment, never in the owner's
      `finassist` project.

## Runbook

<!-- The runbook in docs/operations/ this adds or changes, or "None" and why. -->
