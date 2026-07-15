import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  requestPiece,
  clickRestore,
  SCORE_DRAW,
} from './helpers/app';
import {
  dragPiece,
  dragFromSideArea,
  removePiece,
  pressClock,
  pressOpponentClock,
  pressOwnClock,
  flipBoard,
  clickSquare,
  expectPiece,
  expectEmpty,
} from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('pawn promotion to a queen appears on both boards', async ({ browser }) => {
  // White Pa7 about to promote. Physically: move the pawn to a8, then replace it with a queen
  // taken from the side area.
  game = await startTwoPlayerGame(browser, { fen: '4k3/P7/8/8/8/8/8/4K3 w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'a7', 'a8');
  await dragFromSideArea(white, 'WHITE_QUEEN', 'a8');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'a8', 'WHITE_QUEEN');
  await expectEmpty(white, 'a7');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'a8', 'WHITE_QUEEN');
});

test('queenside castling is accepted', async ({ browser }) => {
  // White: Ke1, Ra1 with O-O-O rights. Physically castle: king e1->c1, rook a1->d1.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/R3K3 w Q - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'e1', 'c1');
  await dragPiece(white, 'a1', 'd1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'c1', 'WHITE_KING');
  await expectPiece(white, 'd1', 'WHITE_ROOK');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'c1', 'WHITE_KING');
  await expectPiece(black, 'd1', 'WHITE_ROOK');
});

test('released-piece: moving a piece again after a legal release is rejected', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // e2-e3 is a legal move: the pawn is released on e3. Moving it again (e3-e4) is a violation.
  await dragPiece(white, 'e2', 'e3');
  await dragPiece(white, 'e3', 'e4');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

test('flag fall ends the game for the player who ran out of time', async ({ browser }) => {
  // Tiny clock: White is to move and nobody moves, so White's clock runs out and Black wins.
  game = await startTwoPlayerGame(browser, { timeMs: 3000, incMs: 0 });
  const { white, black } = game;

  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
});

test('flag fall is a draw when the opponent has only a lone king (insufficient material)', async ({
  browser,
}) => {
  // White is to move and lets the clock run out; Black has a lone king and cannot mate -> draw.
  game = await startTwoPlayerGame(browser, {
    fen: '8/8/4k3/3R4/2K5/8/8/8 w - - 0 50',
    timeMs: 2000,
    incMs: 0,
  });
  const { white, black } = game;

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  // Personalised per player: the flagger reads "you"; the opponent reads "your opponent".
  await expect(white.locator('#gameResultReason')).toContainText(
    'You flagged, but because your opponent has insufficient material to mate',
  );
  await expect(black.locator('#gameResultReason')).toContainText(
    'Your opponent flagged, but because you have insufficient material to mate',
  );
});

test('flag fall is a draw in a blocked position with material (no potential mate)', async ({ browser }) => {
  // White flags in a fully blocked pawn wall: Black has pawns but can never break through -> draw.
  game = await startTwoPlayerGame(browser, {
    fen: '8/8/3k4/1p2p1p1/pP1pP1P1/P2P4/1K6/8 w - - 32 62',
    timeMs: 2000,
    incMs: 0,
  });
  const { white, black } = game;

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#gameResultReason')).toContainText(
    'You flagged, but because your opponent has no potential mate',
  );
  await expect(black.locator('#gameResultReason')).toContainText(
    'Your opponent flagged, but because you have no potential mate',
  );
});

test('pressing the opponent clock does not commit the move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { timeMs: 300_000, incMs: 0 });
  const { white, black } = game;

  await dragPiece(white, 'e2', 'e4');
  await pressOpponentClock(white); // wrong lever -> should be a no-op

  // Give any (erroneous) commit time to propagate, then confirm it did not happen: the move was
  // not committed, so it never becomes Black's turn. (Black sees the drag mirrored live, so we
  // assert on turn state, not on the piece.)
  await black.waitForTimeout(500);
  await expect(black.locator('#arbiterMessage')).not.toContainText('Your turn');

  await pressClock(white); // own lever -> commits, now it is Black's turn
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('requesting a knight and underpromoting to it is accepted', async ({ browser }) => {
  // Both white knights are on the board, so a knight is not in the side area until requested.
  game = await startTwoPlayerGame(browser, { fen: '4k3/P7/8/8/8/8/8/1N2K1N1 w - - 0 1' });
  const { white, black } = game;

  await requestPiece(white, 'KNIGHT'); // adds a knight to the side area
  await dragPiece(white, 'a7', 'a8'); // pawn to the last rank
  await dragFromSideArea(white, 'WHITE_KNIGHT', 'a8'); // underpromote with the requested knight
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'a8', 'WHITE_KNIGHT');
  await expectPiece(black, 'a8', 'WHITE_KNIGHT');
});

test('en passant that would expose the own king is rejected', async ({ browser }) => {
  // White Pe5 and King g5; Black Pf5 (e.p. target f6) and Ra5. exf6 e.p. clears the 5th rank and
  // leaves the white king in check from the rook, so it is illegal.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/r3PpK1/8/8/8/8 w - f6 0 1' });
  const { white } = game;

  await dragPiece(white, 'e5', 'f6'); // attempt exf6 e.p.
  await removePiece(white, 'f5'); // lift the en-passant-captured pawn
  await pressClock(white);

  // No legal move produces this position -> the arbiter requires a restore.
  await clickRestore(white); // the "Revert" button only exists if the move was rejected
  await expectPiece(white, 'e5', 'WHITE_PAWN'); // position restored
});

test('illegal move while in check says it leaves the own king in check', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: 'k3r3/8/8/8/8/8/8/4K2R w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'h1', 'h2'); // does not answer the check from the black rook on e8
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText(
    'Illegal move because it leaves the own king in check.');
  await expect(white.locator('#arbiterMessage')).not.toContainText('would leave');
  await expect(black.locator('#opponentInfoPanel')).toContainText('it leaves the own king in check');
});

test('moving an opponent piece is rejected immediately', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await dragPiece(white, 'a7', 'a6'); // drag a BLACK pawn -> mid-play intervention (no clock needed)

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
  await expect(white.locator('#arbiterMessage')).toContainText(/opponent/i);
});

test('flipping the board, then making a legal move, still works', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await flipBoard(white); // white flips their own view
  await dragPiece(white, 'e2', 'e4'); // squares still resolve by name after the flip
  await pressOwnClock(white); // own lever is on the other side now

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
});

test('changing a completed promotion is a released-piece violation, not an illegal move', async ({ browser }) => {
  // bxa8: pawn captures the rook and promotes. Once the queen is placed on a8 the move is complete;
  // swapping it back for the pawn is a released-piece violation naming the queen.
  game = await startTwoPlayerGame(browser, { fen: 'r3k3/1P6/8/8/8/8/8/4K3 w - - 0 1' });
  const { white } = game;

  await dragPiece(white, 'b7', 'a8'); // pawn captures the rook (lands as a pawn on a8)
  await removePiece(white, 'a8'); // lift the pawn off
  await dragFromSideArea(white, 'WHITE_QUEEN', 'a8'); // place the queen -> promotion complete
  await removePiece(white, 'a8'); // lift the queen off
  await dragFromSideArea(white, 'WHITE_PAWN', 'a8'); // put the pawn back
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText(/released-piece/i);
  await expect(white.locator('#arbiterMessage')).toContainText(/queen/i);
});

test('castling by two king moves (king released on g1, then moved on) asks to complete castling', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white } = game;

  await dragPiece(white, 'e1', 'g1'); // king released on g1 -> commits to castling
  await dragPiece(white, 'g1', 'f1'); // then moved on to f1
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Castling has been started. Because the king was released on g1 and kingside castling is legal,'
      + ' you must complete the castling move by moving the rook from h1 to f1.');
  await expect(white.locator('#arbiterMessage')).not.toContainText(/released-piece/i);
});

test('incomplete castling has no Revert button and informs the opponent passively', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'e1', 'g1'); // king released on g1 -> starts legal kingside castling
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Castling has been started. Because the king was released on g1 and kingside castling is legal,'
      + ' you must complete the castling move by moving the rook from h1 to f1.');
  await expect(white.getByRole('button', { name: 'Revert' })).toBeHidden();

  await expect(black.locator('#arbiterMessage')).not.toContainText(/started castling/i);
  await expect(black.locator('#opponentInfoPanel')).toContainText('Your opponent started castling');

  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('king-then-rook touch before incomplete castling uses the touch-castling reason', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white, black } = game;

  await clickSquare(white, 'e1');
  await clickSquare(white, 'h1');
  await dragPiece(white, 'e1', 'g1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Because you touched the king and the rook, and castling is legal, please perform the castling move.');
  await expect(white.getByRole('button', { name: 'Revert' })).toBeHidden();
  await expect(black.locator('#arbiterMessage')).not.toContainText(/touched the king and the rook/i);
  await expect(black.locator('#opponentInfoPanel')).toContainText(
    'Your opponent touched the king and the rook, and castling is legal.');

  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('rook-first castling attempt keeps the rook move and restores the king', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'h1', 'f1'); // legal rook move; rook-first castling is not allowed
  await dragPiece(white, 'e1', 'g1'); // king displacement after the rook move
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Castling cannot be performed rook first. Because the rook was released on f1 as a legal move,'
      + ' the move is the rook move from h1 to f1. Please put the king back on e1 and press the clock.');
  await expect(white.locator('#arbiterMessage')).not.toContainText(/illegal move/i);
  await expect(white.locator('#arbiterMessage')).not.toContainText(/put the rook back/i);

  await clickRestore(white);
  await expectPiece(white, 'e1', 'WHITE_KING');
  await expectPiece(white, 'f1', 'WHITE_ROOK');
  await expectEmpty(white, 'g1');
  await expectEmpty(white, 'h1');
  await expect(white.locator('#arbiterMessage')).toContainText('Clock restarted', { timeout: 15_000 });

  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

test('illegal castling without the side right obliges the first-touched king when it can move', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: '2k5/8/8/8/8/8/8/R3K2R w Q - 0 1' });
  const { white } = game;

  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Illegal move: castling is not possible');
  await expect(white.locator('#arbiterMessage')).toContainText('there is no castling right anymore on this side');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Because you touched your king first, and the king has legal moves');
  await expect(white.locator('#arbiterMessage')).toContainText('you must make a legal move with the king');
  await expect(white.locator('#arbiterMessage')).not.toContainText('Castling counts as a king move');
});

test('illegal castling with no king moves obliges the touched rook when it can move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: 'k7/8/8/8/8/7b/3PPP2/3QK2R w - - 0 1' });
  const { white } = game;

  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Illegal move: castling is not possible');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'you first touched your king, which has no legal moves');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'then touched your rook, which has legal moves');
  await expect(white.locator('#arbiterMessage')).toContainText('you must make a legal move with the rook');
  await expect(white.locator('#arbiterMessage')).not.toContainText('Castling counts as a king move');
});

test('illegal castling with no king or rook moves leaves any other legal move free', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: 'k3r3/8/8/8/8/7b/3P1P2/3QK2R w - - 0 1' });
  const { white } = game;

  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Illegal move: castling is not possible');
  await expect(white.locator('#arbiterMessage')).toContainText('you touched your king and then your rook');
  await expect(white.locator('#arbiterMessage')).toContainText('neither piece has legal moves');
  await expect(white.locator('#arbiterMessage')).toContainText('you may make any other legal move');
  await expect(white.locator('#arbiterMessage')).not.toContainText('Castling counts as a king move');
});

test('moving two pieces (a knight shuffle around a pin) is an illegal move', async ({ browser }) => {
  // Black Nc6 is pinned (blocks Qb5 -> Ke8). Moving Nc6->d4 and then Ne5->c6 to re-block is two
  // moves; no single legal move produces it.
  game = await startTwoPlayerGame(browser, {
    fen: '2bqkb1r/pQp1ppp1/2np4/1Q2n3/8/7p/PP1PPPPP/RNB1KBNR b KQk - 9 12',
  });
  const { black } = game; // FEN is black-to-move, so the creator plays black

  await dragPiece(black, 'c6', 'd4');
  await dragPiece(black, 'e5', 'c6');
  await pressClock(black);

  await expect(black.locator('#arbiterMessage')).toContainText(/illegal move/i);
});
