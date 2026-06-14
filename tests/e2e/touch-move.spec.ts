import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  clickRestore,
  clickReadyToContinue,
  expectGameResumed,
} from './helpers/app';
import { dragPiece, clickSquare, pressClock, expectPiece } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

// Case 1: touching an own piece that has no legal moves creates no obligation.
test('touching an own piece with no legal moves leaves any other move free', async ({ browser }) => {
  game = await startTwoPlayerGame(browser); // standard start: the a1 rook is boxed in
  const { white, black } = game;

  await clickSquare(white, 'a1'); // rook has no legal moves -> no obligation
  await dragPiece(white, 'e2', 'e4'); // a different piece moves freely
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

// Case 2: touching an own piece that has a legal move binds it.
test('touching an own piece with a legal move, then moving another, is a violation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await clickSquare(white, 'g1'); // knight has legal moves
  await dragPiece(white, 'e2', 'e4'); // move a different piece
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

test('moving the touched piece satisfies the touch-move obligation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await clickSquare(white, 'g1');
  await dragPiece(white, 'g1', 'f3'); // move the touched knight
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

// Case 3: touching a capturable opponent piece obliges capturing it.
const CAPTURE_FEN = '4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1'; // White Pe4 can take Black Pd5 (exd5)

test('touching a capturable opponent piece, then moving another, is a violation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: CAPTURE_FEN });
  const { white } = game;

  await clickSquare(white, 'd5'); // the capturable black pawn
  await dragPiece(white, 'e1', 'e2'); // a non-capturing king move
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

test('capturing the touched opponent piece satisfies the obligation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: CAPTURE_FEN });
  const { white, black } = game;

  await clickSquare(white, 'd5');
  await dragPiece(white, 'e4', 'd5'); // exd5 — capture the touched piece
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(white, 'd5', 'WHITE_PAWN');
});

// Cases 5/6: the touch-move obligation survives an illegal move + restore. These also exercise the
// full restore + both-players-ready handshake (autoResume off).
test('after an illegal move and restore, the touched piece stays bound (different piece is a violation)', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { autoResume: false });
  const { white, black } = game;

  await dragPiece(white, 'g1', 'g3'); // illegal knight move -> touches the knight
  await pressClock(white);

  await clickRestore(white); // "Do this for me"
  await expectPiece(white, 'g1', 'WHITE_KNIGHT'); // position is restored
  await clickReadyToContinue(white);
  await clickReadyToContinue(black);
  await expectGameResumed(white);

  // Touch-move persists from the illegal attempt: the knight must move; a pawn move is a violation.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText(/knight/i);
});

test('after an illegal move and restore, moving the touched piece legally is accepted', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { autoResume: false });
  const { white, black } = game;

  await dragPiece(white, 'g1', 'g3');
  await pressClock(white);

  await clickRestore(white);
  await clickReadyToContinue(white);
  await clickReadyToContinue(black);
  await expectGameResumed(white);

  await dragPiece(white, 'g1', 'f3'); // move the touched knight legally
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

// Case 4 (FIDE 4.3.3): touching an own piece and the opponent piece it can capture binds that
// specific capture. White Pe4 can take Black Pd5.
const COMPOUND_FEN = '4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1';

test('touching own piece then the opponent piece it can take binds that capture (another move is a violation)', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: COMPOUND_FEN });
  const { white } = game;

  await clickSquare(white, 'e4'); // own pawn that can capture d5
  await clickSquare(white, 'd5'); // the opponent pawn it can take
  await dragPiece(white, 'e4', 'e5'); // push instead of capturing -> violates the specific capture
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText(/capture/i);
});

test('capturing with the touched piece satisfies the specific-capture obligation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: COMPOUND_FEN });
  const { white, black } = game;

  await clickSquare(white, 'e4');
  await clickSquare(white, 'd5');
  await dragPiece(white, 'e4', 'd5'); // exd5 — the required capture
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});

// FIDE 4.4.2: touching the rook before the king forbids castling with it — the rook must move.
test('clicking the rook before castling is a touch-move violation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white } = game;

  await clickSquare(white, 'h1'); // touch the rook first
  await dragPiece(white, 'e1', 'g1'); // then the castling motion (king, then rook)
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText(/touch-move/i);
});
