# FIDE Deviations and Arbiter Policy Encodings

This document tracks where the otb-chess's behaviour intentionally departs from the FIDE Laws of Chess (2023 edition), and where it encodes specific arbiter judgments that FIDE leaves to the human arbiter.

It complements [`SPECIFICATION.md`](../SPECIFICATION.md), which describes what the system does. This file describes how that behaviour relates to FIDE — for the audience of advanced players, arbiters, and programmers who care about the exact gap.

Two sections:

- **Deviations** (D-###) — places we behave differently from FIDE.
- **Arbiter Policy Encodings** (A-###) — places where FIDE delegates to arbiter judgment, and we have encoded a specific policy.

Each deviation entry includes a *Path to compliance* sub-section so the route to closing the gap (if and when worth it) lives next to the rationale for the gap.

---

## Deviations

### D-001 — Clock-press as the move boundary

**FIDE**: Article 4.7 / 6.2.1 — a move is *made* when the piece is released on a legal square. Pressing the clock *completes* the move (a later, distinct event). The opponent's rights ("having the move") begin at *made*, not at *completed*.

**What we do**: The system conflates *made* and *completed* into a single event = clock-press. The window between piece-release and clock-press doesn't exist in our state model.

**Consequences**:

- **C1** — During the gap between piece-release and clock-press, the opponent's board is frozen. Black cannot start their move while White is mid-clock-press.
- **C2** — During the same gap, the opponent cannot make a draw claim (threefold or 50-move). FIDE allows the opponent's "having the move" rights — including these claims — to begin at piece-release. We don't.

**Practical impact**: Matters only in seconds-tight blitz, where the gap is large enough relative to remaining time to be exploited. Negligible in classical and rapid; non-existent in correspondence.

**Rationale**: Implementing the FIDE boundary requires a `pendingClockPress` state between "move made" and "clock pressed", with all of FIDE's "having the move" rights transitioning at the earlier event. The cost — a state-model branch in every relevant component (clock, move handler, draw machinery) — is high relative to the practical benefit.

**Path to compliance**:

1. Introduce a `pendingClockPress` state per side that becomes active when the piece is released on a legal square.
2. All "having the move" rights flip at this state transition (board unfreezes for opponent, claim window opens, etc.).
3. Clock-press remains the time-accounting boundary — no change there.
4. Decide whether to honour FIDE compliance only for legal-pending-moves or also during illegal-move correction windows. The legal-only case is enough to be FIDE-compliant in spirit; the illegal-being-corrected case is genuinely UX-only.

**Beyond practical scope**: Even with full implementation, FIDE's physical-board freedom permits things our digital system never models. Example: Black moving a piece while White is mid-correction of an illegal move — Article 11.5 territory ("annoying the opponent"), no specific rule against. Our system prevents this by construction. **It's a deliberate scope limit, not a deviation we're trying to fix.** Full FIDE faithfulness for OTB *physical* behaviour is out of reach for any digital system.

---

### D-004 — J'adoube / piece adjustment is not modeled

**FIDE**:

- Article 4.2.1 — *"Only the player having the move may adjust one or more pieces on their squares, provided that he/she first expresses his/her intention (for example by saying 'j'adoube' or 'I adjust')."*
- Article 4.2.2 — Any other physical contact (except clearly accidental) is intent-to-move.

**What we do today**: No j'adoube concept exists. Touch-move is enforced via the digital equivalent (selecting a piece commits you to moving it) but no escape hatch is provided for adjustment. There is no way for a player to "touch without committing" the way an OTB arbiter would allow.

**Rationale for not implementing**: Substantial work — UI button, mode tracking, per-piece event recording, side/turn validation, distinguishing adjusting-own-piece vs. adjusting-opponent-piece. The feature exists primarily to mirror physical-board behaviour. Lower priority than D-001.

**Path to compliance (sketch)**:

- "J'adoube" button, must be pressed before each piece adjustment. One press = one adjustment, not a session-wide toggle.
- Adjustment only allowed during the active player's own time.
- Either own piece or opponent piece can be adjusted (per Article 4.2.1).
- Touch-move enforcement (current behaviour) remains the default for all other piece contact.

**Caveat**: Even if implemented, full FIDE fidelity is unlikely. Physical-board ambiguities ("was the contact clearly accidental?") cannot be resolved digitally. Implementation would approximate Article 4.2 rather than match it exactly.

---

## Arbiter Policy Encodings

FIDE's Article 11.5 and 12.9 give the arbiter judgment over distraction, annoying offers, and similar behaviours, with a list of permitted penalties. A digital system has no human arbiter — these judgments must be encoded as policy. This section documents those encodings.

### A-001 — Draw-offer abuse threshold

**FIDE**: Article 11.5 forbids "unreasonable offers of a draw"; Article 12.9 lists penalties (warning → time penalty → opponent point increase → game lost), with the choice and threshold left to the arbiter.

**Our policy**: Three presses of the draw-offer button at a non-recommended moment (i.e., outside the gap between making one's own move and pressing the clock) result in **loss of the game** for the offending player.

The first two presses trigger an inline message to the offender ("offered out of protocol — repeated infractions count as annoyance under Art. 11.5") and a corresponding note to the opponent. The third triggers the loss.

**Rationale**: Arbiter judgment must be encoded into a digital system; an explicit threshold is more transparent than a fuzzy heuristic. Three is small enough to feel like a real limit, large enough to forgive a single mistake.

**Note**: This policy applies only to wrong-time offers. Repeated offers at the recommended time are not currently penalized — see [E-002](enhancements.md#e-002) for the open question of whether they should be.

---

### A-002 — Draw-offer invalidation: asymmetric policy for correct-time vs. wrong-time offers

**FIDE**: Article 9.1.2.1 — *"the offer cannot be withdrawn and remains valid until the opponent accepts it, rejects it orally, rejects it by touching a piece with the intention of moving or capturing it, or the game is concluded in some other way."*

The trigger is **"touching a piece with the intention of moving or capturing it."** Intention is unknowable to a digital system, so we encode two heuristics — one for each offer-time class.

**Our policy (asymmetric)**:

| Offer made at | Invalidated by | Rationale |
|---|---|---|
| **Correct time** (after opponent's own move, before clock-press) | First TOUCH (CLICK or DRAG_*) on any piece | The recipient was waiting on a clean board state. Any piece interaction marks the resumption of their own move-formation, which FIDE treats as rejection. |
| **Wrong time** (anytime else, e.g. mid-thinking on the recipient's own move) | Move MADE — i.e. a legal release per FIDE 4.7 | The recipient was already mid-deliberation when the offer arrived; touching pieces while deciding is normal play, not rejection. Only completing a move actually constitutes a response. |

**Where in code**: [`GameWebSocketServer.java`](../src/main/java/io/github/dlbbld/otbchess/server/GameWebSocketServer.java) (search "Correct-time offer" / "Wrong-time offer").

**Rationale**: The asymmetry encodes "intention to move" reasonably for the two contexts. A correct-time offer interrupts a moment of stillness; the recipient's first touch is intentional and committal. A wrong-time offer arrives during active play; the recipient may touch many pieces while thinking before committing, and only a *completed* move signals decision.

**Acknowledged tension**: A wrong-time offer plus a recipient who has already released a move (per FIDE 4.7) but hasn't pressed the clock is currently rejected by the system as "too late to accept." This is a side effect of [D-001](#d-001-clock-press-as-the-move-boundary): in our model the move is "made" only at clock-press, so the post-release / pre-clock-press window is unreachable for offer acceptance. A FIDE-strict implementation would allow acceptance up to clock-press.
