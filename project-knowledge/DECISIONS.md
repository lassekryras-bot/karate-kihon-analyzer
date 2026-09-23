# Decisions

This file records durable decisions that do not already have a clearer authoritative owner. Keep entries concise and append new decisions; do not use it as a task log.

The seed records below preserve the reason and consequence of the initial choices. Current policy lives in the linked owning files. Source and date interpretation: [seed provenance](README.md#seed-provenance).

## 2026-09-23 — Product development order

**Status:** DECIDED

Develop the shared Karate Analysis Platform backend and the free/lightweight Karate Kihon Analyzer together first. Build Karate Coach later on the proven platform.

**Consequence:** A hypothetical future Coach requirement alone does not justify current complexity.

**Current policy:** [Product](PRODUCT.md).

## 2026-09-23 — Analytical ownership

**Status:** DECIDED

Karate and movement-analysis knowledge belongs in the shared platform; product experience belongs in applications. Analytical concepts have one authoritative implementation.

**Consequence:** Applications consume analysis and may present it differently, but do not fork analytical calculations.

**Current policy:** [Domain](DOMAIN.md) and [Architecture](ARCHITECTURE.md).

## 2026-09-23 — Fresh knowledge baseline

**Status:** DECIDED

Begin with a fresh project-knowledge layer. Existing implementation and old documentation are evidence, not automatic current authority.

**Consequence:** Repository structure and implementation facts must be established by focused discovery. Material conflicts with current intent are surfaced rather than silently reconciled.

**Current policy:** [AGENTS.md](../AGENTS.md#knowledge-policy).
