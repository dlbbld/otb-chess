# Enhancements (non-deviation)

This document tracks improvements to the dumb-chessboard that are *not* fixes for FIDE deviations. Deviation-related improvements live alongside their parent entry in [`fide-deviations.md`](fide-deviations.md) under "Path to compliance" sections.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) for current behaviour, [`fide-deviations.md`](fide-deviations.md) for FIDE relationship, [`design-principles.md`](design-principles.md) for cross-cutting design rules, [`future-ideas.md`](future-ideas.md) for speculative directions.

---

## E-001 — Configurable auto-resume timeout after invalid claim

**Today**: When a wrong-side draw claim or other invalid claim pauses the game (see [D-002](fide-deviations.md#d-002)), both players must signal agreement to resume. Two clicks required.

**Proposed**: A start-screen setting "auto-resume after invalid claim: N seconds" (e.g. default 5s). When set, the game resumes automatically after the timeout instead of requiring dual consent. The offending player still sees the rejection message; the opponent still sees the notification.

**Rationale**: For most invalid claims, the dual-consent step is unnecessary friction. Tournament-style play might prefer the explicit handshake; casual blitz might prefer auto-resume. Make it configurable rather than choosing one for everyone.

**Status**: Planned but not scheduled.

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
