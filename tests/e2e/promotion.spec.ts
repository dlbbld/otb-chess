import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  clickRestore,
  expectGameResumed,
} from './helpers/app';
import {
  dragPiece,
  pressClock,
  expectPiece,
  removePiece,
  dragFromSideArea,
} from './helpers/board';

// Black pawn b2, white rook a1, black rook a8: b2xa1 promotes. Black is to move (the creator
// gets the FEN's side to move).
const PROMOTION_FEN = 'r3k3/8/8/8/8/8/1p6/R3K3 b - - 0 1';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('dragging a board piece onto the pending promotion square is an illegal move, not a released-piece deadlock', async ({
  browser,
}) => {
  // The reported journey: b2 captures a1 (pawn parked, promotion pending), then the a8 rook is
  // dragged onto a1 and the clock pressed. This used to be misread as a released-piece
  // commitment whose "restore" target was the tampered position itself — Revert did nothing and
  // the violation looped forever. It is simply an illegal move.
  game = await startTwoPlayerGame(browser, { fen: PROMOTION_FEN });
  const { white, black } = game;

  await dragPiece(black, 'b2', 'a1'); // pawn captures the rook — promotion pending
  await dragPiece(black, 'a8', 'a1'); // tampering: own rook onto the parked pawn
  await pressClock(black);

  await expect(black.locator('#arbiterMessage')).toContainText('Illegal move');
  await expect(black.locator('#arbiterMessage')).not.toContainText('Released-piece');

  // Revert restores the REAL turn-start position (this was the deadlock before the fix)...
  await clickRestore(black);
  await expectGameResumed(black);
  await expectPiece(black, 'a1', 'WHITE_ROOK');
  await expectPiece(black, 'a8', 'BLACK_ROOK');
  await expectPiece(black, 'b2', 'BLACK_PAWN');

  // ...and the game continues. The touched pawn is bound (touch-move), so Black completes the
  // promotion properly: capture, lift the pawn off, place the promoted piece from the side area.
  await dragPiece(black, 'b2', 'a1');
  await removePiece(black, 'a1');
  await dragFromSideArea(black, 'BLACK_QUEEN', 'a1');
  await pressClock(black);

  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'a1', 'BLACK_QUEEN');
});

test('completing the promotion with a rook from the side area is accepted (b2xa1=R)', async ({ browser }) => {
  // The user's intended move, done properly: the promoted ROOK arrives from the side area
  // (the second black rook is off-board in this position, so it sits there).
  game = await startTwoPlayerGame(browser, { fen: PROMOTION_FEN });
  const { white, black } = game;

  await dragPiece(black, 'b2', 'a1');
  await removePiece(black, 'a1');
  await dragFromSideArea(black, 'BLACK_ROOK', 'a1');
  await pressClock(black);

  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'a1', 'BLACK_ROOK');
  await expectPiece(white, 'a8', 'BLACK_ROOK'); // the a8 rook never moved
});
