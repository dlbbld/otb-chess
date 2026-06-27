# Manifesto

Most online chess software makes chess easier to enter: it prevents illegal moves, highlights legal destinations, hides tentative mouse movement from the opponent, and turns the clock into an automatic detail. That convenience is real and useful. It also changes the game.

OTB Chess starts from a different question: what is lost when online chess removes the physical procedure of over-the-board play?

In over-the-board chess, a move is not only an abstract change in position. A player thinks, decides, deliberately touches a piece, moves it, releases it, and presses the clock. That sequence has weight. It shows respect for the position, for the time spent thinking, and for the opponent who should not be distracted by careless gestures over the board.

Physical chess also allows mistakes that many online boards make impossible. A player can touch the wrong piece. A player can move a pinned piece and leave the king in check. Nobody silently prevents the hand from making an illegal move. The rules do not disappear, but they appear through consequence and arbitration rather than through disabled input.

That is the space this project explores.

## Why It Exists

**Respect for procedure**

The project treats the physical act of moving as part of chess. Touching, releasing, restoring, and pressing the clock are not cosmetic details; they are part of the discipline of play.

**A richer model of chess**

Online chess often reduces the game to legal move selection. OTB Chess keeps more dimensions alive: touch-move, illegal moves, manual clock handling, restoration, claims, and arbiter rulings.

**Arbiter-focused learning**

Many interesting arbiter situations barely exist in ordinary online chess. If the software prevents illegal moves, the player never experiences the ruling. OTB Chess makes those situations visible again, with a virtual arbiter constantly watching the board.

**Just for the fun of it**

This idea existed for a long time, but for me it was out of reach because the implementation was too complex. The recent breakthrough in AI-assisted software development made it possible to actually build it. Part of the reason this project exists is simply the joy of doing it: making the difference between online chess and over-the-board chess tangible, playable, and a little surprising.

## Current Shape

The current app is not a free-for-all board. It is closer to a tournament game observed by a very attentive arbiter. Players can perform physical actions, but the arbiter enforces the supported rules as soon as the game procedure requires a ruling.

A possible future mode would be stricter in a different way: the board could allow more rule violations silently, and the opponent would need to notice, stop the clock, and call the arbiter. That would train another part of over-the-board chess: not only following the rules, but recognizing when the rules have been broken.

For now, OTB Chess focuses on the watched-board model: physical freedom, procedural consequence, and a virtual arbiter that keeps the game within the supported rules.
