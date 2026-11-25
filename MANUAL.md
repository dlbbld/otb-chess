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

## Sounds

Below **Join a Game** there are two options, both switched on at first:

- **Sound for a moved piece** — a sound when a piece is set down on a square of the board.
- **Sound for the clock press** — a sound when a clock lever is pressed down.

You hear your own pieces and clock presses and your opponent's. The options are yours alone: they are saved in your browser, apply to every game you create, join or return to, and do not change what your opponent hears. Set them before you open a game; there is no switch during the game. The volume is that of your device.

A browser plays sounds on a page only after you have clicked or touched that page. If your opponent moves before you have touched the game page, that first move can be silent. On a phone the same can happen after the screen was locked: touch the page once and the sounds are back.

## Join a Game

1. Open the same OTB Chess page.
2. Enter the game code shown on the first player's board.
3. Select **Join Game**.

The game starts when both players are connected.

## Playing both sides yourself

To try OTB Chess alone you need two browser sessions, one for each player:

- a normal window and a **private (incognito) window** of the same browser, or
- two **different browsers**, or two devices.

Two normal tabs or windows of the same browser do not work: they share the stored game, so the start screen refuses to join a game that is already open in that browser and tells you so.

Both boards of a computer use the same loudspeaker. If the sounds are on in one of them, you hear the pieces and clock presses of both players from it.

## Playing

Move pieces on the board as if using a physical chessboard. Your opponent sees your board actions in real time.

Important differences from most online chess sites:

- Legal moves are not pre-filtered by the board.
- Touch-move matters.
- Illegal moves can be made physically, then ruled on by the arbiter.
- The clock must be pressed manually.
- Draw offers, draw claims, resignations, and clock presses are part of the procedure, not just UI shortcuts.

The arbiter intervenes when a supported rule requires it. If the position must be restored, follow the arbiter message and restore the board.

**Pressing the clock without a move** is an illegal move (FIDE 7.5.3). The arbiter stops the clock, tells you so, and restarts the clock after a few seconds; with confirmation switched on, both players confirm that they are ready instead. A double click on the clock counts once. Once the clock runs again, the next press without a move is the next illegal move.

**After an illegal move** you have to move the piece you touched first, if it can move. If you move another piece instead, the arbiter rules a touch-move violation - whether or not that move would have been legal - and asks again for the touched piece. This is not counted as an illegal move.

**Circles on the board** show what the arbiter's ruling is about, on both boards: green for the piece you have to move or capture, red for the start and end square of an illegal move, blue for the start and end square of a move you had already made when you changed the position again.
