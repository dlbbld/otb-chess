import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';
import {
  dragPiece,
  dragFromSideArea,
  pressClock,
  pressOpponentClock,
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

  await expectPiece(white, 'a8', 'WHITE_QUEEN');
  await expectEmpty(white, 'a7');
  await expectPiece(black, 'a8', 'WHITE_QUEEN');
});

test('queenside castling is accepted', async ({ browser }) => {
  // White: Ke1, Ra1 with O-O-O rights. Physically castle: king e1->c1, rook a1->d1.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/R3K3 w Q - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'e1', 'c1');
  await dragPiece(white, 'a1', 'd1');
  await pressClock(white);

  await expectPiece(white, 'c1', 'WHITE_KING');
  await expectPiece(white, 'd1', 'WHITE_ROOK');
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

test('pressing the opponent clock does not commit the move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { timeMs: 300_000, incMs: 0 });
  const { white } = game;

  await dragPiece(white, 'e2', 'e4');
  await pressOpponentClock(white); // wrong lever -> no-op, move not committed
  await expect(white.locator('#arbiterMessage')).not.toContainText('Move accepted');

  await pressClock(white); // own lever -> commits
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
});
