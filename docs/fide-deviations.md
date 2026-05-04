# FIDE Deviations and Arbiter Policy Encodings

This document tracks where the dumb-chessboard's behaviour intentionally departs from the FIDE Laws of Chess (2023 edition), and where it encodes specific arbiter judgments that FIDE leaves to the human arbiter.

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

### D-002 — Wrong-side draw claim pauses the game

**FIDE**: Articles 9.2 and 9.3 — only the player having the move can correctly claim threefold or 50-move. A wrong-side attempt would be told off by the arbiter informally; play continues uninterrupted.

**What we do**: The "Claim Threefold" and "Claim 50-Move" buttons stay enabled for both sides at all times. If the side *not* having the move clicks, the system pauses the clock and shows messages: to the offender, "you cannot claim at this time"; to the having-the-move side, "[opponent] tried to claim threefold but it isn't their move." Game resumes only when both players signal agreement to continue (default), or after a configurable timeout (see [E-001](enhancements.md#e-001)).

**Rationale**: In OTB chess, the arbiter resolves wrong-side claims with no game-state change — the claim is informally rejected and play continues. Our digital system has no human arbiter; the same situation has to surface as a state-machine event. We chose explicit interactive resolution over silent rejection so the offender sees feedback and the opponent isn't surprised by a claim notification appearing during their thinking time.

**Path to compliance**: Suppress the wrong-side claim entirely (don't pause the clock, don't notify the opponent — only show a private message to the offender). E-001 is a partial mitigation; full FIDE-style behaviour would also remove the dual-consent step.

---

### D-003 — Claim buttons have a back-out step (current bug, to be fixed)

**FIDE**: Article 9.5.1 — a draw claim cannot be withdrawn once made. The "claim" act is the speech act of telling the arbiter; in our case it should be the button press itself.

**What we do today**: Pressing "Claim Threefold" opens a sub-panel with two options ("on position" / "on whole move"); the user can still cancel out of the sub-panel without committing. So the visible "Claim Threefold" button isn't actually committing — the commitment happens deeper.

**Rationale**: Initial UI choice; doesn't match FIDE's irrevocable-claim semantic. Identified during spec review.

**Path to compliance**: GUI redesign — split into two top-level buttons that each commit immediately on press:

- `Claim threefold on position`
- `Claim threefold on whole move`

Same shape for the 50-move claim. Once a top-level button is pressed, the claim is recorded and cannot be retracted — matching the OTB reality of telling the arbiter.

This is committed work, scheduled when the surrounding GUI work is touched.

---

### D-004 — J'adoube / piece adjustment is not modeled

**FIDE**:

- Article 4.2.1 — *"Only the player having the move may adjust one or more pieces on their squares, provided that he/she first expresses his/her intention (for example by saying 'j'adoube' or 'I adjust')."*
- Article 4.2.2 — Any other physical contact (except clearly accidental) is intent-to-move.

**What we do today**: No j'adoube concept exists. Touch-move is enforced via the digital equivalent (selecting a piece commits you to moving it) but no escape hatch is provided for adjustment. There is no way for a player to "touch without committing" the way an OTB arbiter would allow.

**Rationale for not implementing**: Substantial work — UI button, mode tracking, per-piece event recording, side/turn validation, distinguishing adjusting-own-piece vs. adjusting-opponent-piece. The feature exists primarily to mirror physical-board behaviour. Lower priority than D-001/D-002/D-003.

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
