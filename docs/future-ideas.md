# Future Ideas (Speculative)

Directions the dumb-chessboard could grow beyond currently-planned scope. Distinct from [`enhancements.md`](enhancements.md) (smaller, more committed improvements) and from [`SPECIFICATION.md`](../SPECIFICATION.md)'s "Spec-driven implementation follow-ups" / "Open Items (future modes / nice-to-haves)" sections (concrete, scheduled work). This file is where ideas live before they earn a "yes, let's do that" decision.

Companion: [`SPECIFICATION.md`](../SPECIFICATION.md) for current behaviour, [`design-principles.md`](design-principles.md) for the project values these ideas express.

---

## Naming evolution: "crazy chessboard"

The current name "dumb chessboard" describes the technical posture: the board observes silently and doesn't enforce rules during play. Accurate but understated. As scope grows toward modelling more of OTB chess's untidy social reality (distraction, complaint, arbiter judgement, freak cases), an alternative worth considering is **"crazy chessboard"** — a board that lets players do everything, including the weird and unwise things that physical chess silently permits and FIDE only loosely constrains.

The rename would mark a shift in framing: from "minimal enforcement" toward "celebrated freedom + structured consequence." Not decided, just on the table.

---

## Distraction and complaint mechanics

Today the board handles distraction passively: FIDE Article 11.5 ("It is forbidden to distract or annoy the opponent") is encoded only via the wrong-time-draw-offer escalation ([A-001](fide-deviations.md#a-001)). A richer model would put distraction itself into the player's hands — and matching counter-tools into the opponent's.

### Distraction actions (offender side)

- **Knock piece on table** — button plays a knock sound on the opponent's audio output.
- **Repeated knocking** — rhythmic action with accumulating audible loudness.
- **Foot-tapping** — alternative sound, same shape.
- Each action carries (or accumulates) a **loudness** value.

### Complaint (target side)

- **"I'm being distracted"** button summons the virtual arbiter.
- The arbiter reads the offender's recent loudness history and decides:
  - Below threshold: complaint dismissed ("the arbiter doesn't see anything wrong").
  - At threshold: warning to the offender.
  - Above threshold: escalating Article 12.9 penalties — time penalty, point loss, game forfeit.

### Configurability

- Thresholds adjustable per game: "strict arbitration" vs "tolerant."
- Off by default in tournament-style play; opt-in for "crazy chessboard" mode.

### Rationale

Most chess software treats the player as a move-input device; rules happen *to* them. Modelling distraction-and-complaint as first-class actions surfaces the social fabric of OTB play and gives the player explicit standing (Core Principle #9 in [`SPECIFICATION.md`](../SPECIFICATION.md)) — the right to push back, to dispute, to call the arbiter. Whether or not this ships, the framing is what distinguishes the dumb-chessboard from every other digital chess UI.

If injustice happens, the player has standing to insist on redress. That's the underlying value.
