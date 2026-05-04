# Design Principles

Cross-cutting rules that don't fit into [`SPECIFICATION.md`](../SPECIFICATION.md)'s numbered Core Principles list (which is about the operational relationships between board, arbiter, and player). The principles here are looser — UX defaults, scope statements, project values.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) Core Principles for operational rules, [`fide-deviations.md`](fide-deviations.md) for FIDE relationship, [`enhancements.md`](enhancements.md) for non-deviation improvements, [`future-ideas.md`](future-ideas.md) for speculative directions.

Cross-reference: SPECIFICATION.md's **Core Principle #9 — "The player has standing"** is the operational counterpart to several principles here, especially the rationale for the distraction/complaint mechanics in [`future-ideas.md`](future-ideas.md).

---

## P-001 — No "are you sure?" confirmation dialogs

**Rule**: Action buttons commit when pressed. The system never asks "are you sure?" before performing an irrevocable action.

**Why**: An OTB arbiter does not ask "do you really want to claim threefold?" — they record the claim and apply the rules. If the claim was wrong, the consequences (under [A-001](fide-deviations.md#a-001), Article 9.5.3 time penalty, etc.) follow. Confirmation dialogs are an artefact of "undo culture" software UX; chess plays under physical-act rules.

**Applies to**: Resign, draw offer, draw claim, and any other irrevocable action.

**Does not apply to**: Reversible actions (e.g. moving a piece on the board before clock-press — undo is *part of* the model, not an exception).

---

## P-002 — Bullet chess is a non-goal

**Rule**: The dumb-chessboard's clock and UX are designed for OTB-style time controls — classical, rapid, standard blitz (≥ 3 minutes per side). Bullet (e.g. 1+0) is **not a target use case**.

**Why**: At bullet speeds, chess becomes a reaction-speed game more than a chess game. The deliberate UX (separate buttons for claim variants, no confirmation dialogs, accurate touch-move handling, j'adoube path under [D-004](fide-deviations.md#d-004)) is the wrong shape for that. Players who want bullet should reach for tools built for it.

**Implications**:

- The start-screen time-control presets stop at 3+0 / 3+2 on the low end (per current `SPECIFICATION.md`).
- "Custom" time control allows lower values, but with no commitment that the UX behaves well there.
- This is a personal stance, not a universal claim about chess software.

---

## P-003 — Board never gives procedural instructions

**Rule**: After a player's action, the system does not tell the player what to do next. It may *acknowledge* what just happened, but it does not instruct ("Now press X", "Don't forget to Y").

**Why**: An OTB arbiter does not coach a player through the procedure of their own move — they observe and adjudicate. Telling a player to "press the clock" after they offer a draw, for instance, would shield them from a consequence the rules already account for: if they fail to press, their own time keeps running while the opponent considers the offer. That cost is part of the procedure; surfacing it as an instruction removes a learning surface and makes the system more paternal than an arbiter would be.

The same logic applies to other "next step" moments: after a successful claim, after a resignation acknowledgment, after restoration completes. The system can confirm that the action was registered, but it does not narrate the procedure.

**Applies to**: Acknowledgments after irreversible actions (draw offer sent, claim accepted, resignation registered), confirmation messages after restoration is complete, and any moment where a "next step in the procedure" exists for the player.

**Does not apply to**: Arbiter intervention messages that *describe a violation* and direct restoration of the position (e.g. *"Please restore the position to the beginning of the move."*). These are reactive — they exist because Core Principle #2 requires the arbiter to react to violations, and the player is not yet in a position to know what to do without being told.

**Cross-reference**: Operational expression of [`SPECIFICATION.md`](../SPECIFICATION.md) Core Principle #2 (*"The arbiter is reactive, not proactive"*) and #3 (*"The arbiter escalates one step at a time. Minimum information at each intervention."*).

**Known applications**:

- Correct-time draw-offer acknowledgment: bare *"Draw offer sent."* (no clock-press reminder). See [`SPECIFICATION.md`](../SPECIFICATION.md) §Draw offer.

**Known tension to revisit**: The released-piece restoration messages currently end with *"... and press the clock."* (see `messages.properties` `arbiter.released_piece.*`). Whether the trailing instruction belongs there or violates P-003 is an open question — the arbiter is reacting to a violation (so the *what to restore* part is in-scope per "Does not apply to"), but the *and press the clock* tail is the same kind of procedural coaching this principle pushes back on. Flagged for review when the released-piece UX is next touched.
