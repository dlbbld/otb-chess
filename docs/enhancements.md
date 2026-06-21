# Enhancements (non-deviation)

This document tracks improvements to the otb-chess that are *not* fixes for FIDE deviations. Deviation-related improvements live alongside their parent entry in [`fide-deviations.md`](fide-deviations.md) under "Path to compliance" sections.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) for current behaviour, [`fide-deviations.md`](fide-deviations.md) for FIDE relationship, [`design-principles.md`](design-principles.md) for cross-cutting design rules, [`future-ideas.md`](future-ideas.md) for speculative directions.

---

## E-001 — Configurable auto-resume timeout after a paused claim resolution

**Background**: This enhancement only becomes relevant if [E-003](#e-003) (interactive wrong-side claim resolution) is implemented. Today, wrong-side claims are simply rejected with a private error to the claimant — no clock pause, no resume step, so no resume timeout to configure.

**Proposed**: A start-screen setting "auto-resume after paused claim: N seconds" (e.g. default 5s). When set, after a paused claim resolution the game resumes automatically after the timeout instead of requiring dual consent.

**Rationale**: If the system ever moves to interactive wrong-side claim resolution (E-003), the dual-consent step might be unnecessary friction in casual play. Make it configurable.

**Status**: Conditional on E-003 — without it, no resume to time out.

---

## E-002 — Cap on draw offers at the recommended time

**Today**: [A-001](fide-deviations.md#a-001) caps wrong-time draw offers at three (third = loss). Offers made at the recommended time (after own move, before clock-press) face no per-game limit — only whatever annoyance threshold the opponent personally tolerates.

**Question to think about**: Should the system also cap correctly-timed offers? Options:

- A per-game cap ("you may offer N times").
- A cooldown ("at most once every X moves").
- A cap that engages only after the opponent has rejected once or twice.
- No cap at all — leave it to opponent acceptance/rejection alone.

**Hard part**: Distinguishing "annoying" from "legitimate change of mind after position changes meaningfully" has no clean signal from the position alone.

**Status**: Think-about-it, not decide-now. Deferred until usage patterns motivate a specific shape.

---

## E-003 — Interactive wrong-side claim resolution

**Today**: When a player who does *not* have the move clicks a claim button, the system simply returns a private error to the claimant ("You cannot claim a draw when not having the move."). The opponent (who has the move) is not notified. The clock is not paused. This is FIDE-aligned: in OTB chess an arbiter would tell the offender "it's not your move" and play would continue without interruption.

**Idea on the table**: A more interactive resolution where wrong-side claims are visible to both players:

- Pause the clock when a wrong-side claim arrives.
- Show the offender: "You cannot claim a draw when not having the move."
- Show the having-the-move player: "[opponent] tried to claim a draw, but it isn't their move."
- Resume by both-player consent (or after [E-001](#e-001)'s timeout if implemented).

**Why might this be wanted**: It surfaces the social-fabric layer that the [`future-ideas.md`](future-ideas.md) distraction-and-complaint mechanics also speak to — making the system's events feel less mechanical. A wrong-side claim is, in OTB terms, a small drama; rendering it as a beat in the game (rather than swallowing it silently) might fit "crazy chessboard" better than "minimal-intervention chessboard."

**Why it's not the default**: It's MORE intrusive than FIDE rather than less. It also opens questions about timing manipulation (could the wrong-side player exploit pauses to disrupt the opponent's thinking?) that the simple-rejection model avoids by construction.

**Status**: Speculative. If pursued, it pairs with E-001 as a configurability layer.
