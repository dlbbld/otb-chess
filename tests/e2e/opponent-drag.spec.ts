import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame } from './helpers/app';
import { squareLocator, dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

/** Presses the mouse on a square and keeps it pressed: the piece is in the player's hand. */
async function holdPiece(page: Page, square: string): Promise<void> {
  const b = await squareLocator(page, square).boundingBox();
  if (!b) throw new Error(`Cannot locate square ${square}`);
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
}

function sidePieces(page: Page, piece: string) {
  return page.locator(`.side-piece[title="${piece}"]`);
}

// User-reported: while the player on move holds a piece, the opponent's screen listed it among
// the captured material beside the board until it was released.
test('a piece held by the player on move is not shown as captured on the opponent screen', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await holdPiece(white, 'a2');
  await expectEmpty(black, 'a2'); // Black sees the pawn lifted into White's hand
  await expect(sidePieces(black, 'WHITE_PAWN')).toHaveCount(0);
  await expect(sidePieces(black, 'WHITE_QUEEN')).toHaveCount(1); // the spare promotion queen only
  await white.mouse.up();
  await expectPiece(black, 'a2', 'WHITE_PAWN');
  await expect(sidePieces(black, 'WHITE_PAWN')).toHaveCount(0);

  await dragPiece(white, 'a2', 'a4'); // touch-move: the touched pawn must move
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');

  await holdPiece(black, 'h7');
  await expectEmpty(white, 'h7');
  await expect(sidePieces(white, 'BLACK_PAWN')).toHaveCount(0);
  await expect(sidePieces(white, 'BLACK_QUEEN')).toHaveCount(1);
  await black.mouse.up();
  await expectPiece(white, 'h7', 'BLACK_PAWN');
  await expect(sidePieces(white, 'BLACK_PAWN')).toHaveCount(0);
});
