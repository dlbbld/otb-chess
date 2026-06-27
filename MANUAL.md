# Manual

OTB Chess is played by two people in a browser. One player creates a game and shares the game code; the other player opens the same website and joins with that code.

## Create a Game

1. Open the OTB Chess page.
2. Choose the game settings.
3. Select **Create Game**.
4. Share the displayed game code with the second player.
5. Wait until the second player joins.

## Settings

**Play as**

Choose whether the creating player should play White or Black. If a custom FEN is used, the side to move in the FEN decides who moves first, and the creating player is assigned that side automatically.

**Time control**

Choose a preset such as `3+0`, `5+3`, or `30+0`, or enter custom minutes and increment. The clock behaves like a chess clock: after making a move, press your clock manually.

**Number of illegal moves causing game loss**

Choose which illegal move loses the game for a player. `2` is the FIDE default: the second illegal move loses. `Unlimited` removes automatic loss by illegal-move count.

**Continue clock without confirmation after restored position**

When an illegal or incomplete action requires the position to be restored, this controls whether play resumes automatically after restoration or waits for explicit confirmation.

**Starting position FEN**

Leave empty for the normal starting position. Enter a FEN to start from a custom position. The FEN is validated before the game starts.

## Join a Game

1. Open the same OTB Chess page.
2. Enter the game code shown on the first player's board.
3. Select **Join Game**.

The game starts when both players are connected.

## Playing

Move pieces on the board as if using a physical chessboard. Your opponent sees your board actions in real time.

Important differences from most online chess sites:

- Legal moves are not pre-filtered by the board.
- Touch-move matters.
- Illegal moves can be made physically, then ruled on by the arbiter.
- The clock must be pressed manually.
- Draw offers, draw claims, resignations, and clock presses are part of the procedure, not just UI shortcuts.

The arbiter intervenes when a supported rule requires it. If the position must be restored, follow the arbiter message and restore the board.
