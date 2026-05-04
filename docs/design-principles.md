# Design Principles

Cross-cutting rules that don't fit into [`SPECIFICATION.md`](../SPECIFICATION.md)'s numbered Core Principles list (which is about the operational relationships between board, arbiter, and player). The principles here are looser — UX defaults, scope statements, project values.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) Core Principles for operational rules, [`fide-deviations.md`](fide-deviations.md) for FIDE relationship, [`enhancements.md`](enhancements.md) for non-deviation improvements, [`future-ideas.md`](future-ideas.md) for speculative directions.

Cross-reference: SPECIFICATION.md's **Core Principle #9 — "The player has standing"** is the operational counterpart to several principles here, especially the rationale for the distraction/complaint mechanics in [`future-ideas.md`](future-ideas.md).

---

## P-001 — No "are you sure?" confirmation dialogs

**Rule**: Action buttons commit when pressed. The system never asks "are you sure?" before performing an irrevocable action.

**Why**: An OTB arbiter does not ask "do you really want to claim threefold?" — they record the claim and apply the rules. If the claim was wrong, the consequences (under [A-001](fide-deviations.md#a-001), Article 9.5.3 time penalty, etc.) follow. Confirmation dialogs are an artefact of "undo culture" software UX; chess plays under physical-act rules.

**Applies to**: Resign, draw offer, draw claim (after [D-003](fide-deviations.md#d-003)'s GUI redesign), and any other irrevocable action.

**Does not apply to**: Reversible actions (e.g. moving a piece on the board before clock-press — undo is *part of* the model, not an exception).

---

## P-002 — Bullet chess is a non-goal

**Rule**: The dumb-chessboard's clock and UX are designed for OTB-style time controls — classical, rapid, standard blitz (≥ 3 minutes per side). Bullet (e.g. 1+0) is **not a target use case**.

**Why**: At bullet speeds, chess becomes a reaction-speed game more than a chess game. The deliberate UX (separate buttons for claim variants, no confirmation dialogs, accurate touch-move handling, j'adoube path under [D-004](fide-deviations.md#d-004)) is the wrong shape for that. Players who want bullet should reach for tools built for it.

**Implications**:

- The start-screen time-control presets stop at 3+0 / 3+2 on the low end (per current `SPECIFICATION.md`).
- "Custom" time control allows lower values, but with no commitment that the UX behaves well there.
- This is a personal stance, not a universal claim about chess software.
