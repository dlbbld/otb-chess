import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, clickRestore, expectGameResult } from './helpers/app';
import { dragPiece, pressClock, expectPiece, squareLocator } from './helpers/board';

// Reported journey (2026-11-25): 1. e4, then Black plays the knight from g8 to g6 - an illegal
// move - and reverts. The knight from b8 to b6 with the clock was then ruled a second illegal
// move, which lost the game. The knight on g8 was touched first and can move, so the move that
// replaces the illegal one has to be made with it: the ruling is a touch-move violation.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');

async function playE4ThenTheIllegalKnightMoveAndRevert(): Promise<void> {
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');

  await dragPiece(black, 'g8', 'g6');
  await pressClock(black);
  await expect(arbiter(black)).toContainText('Illegal move: the knight cannot move in this way.');
  await expect(arbiter(black)).toContainText('This is your 1st illegal move.');
  await clickRestore(black);
  await expect(arbiter(black)).toContainText('Clock restarted. Game continues.');
  for (const page of [white, black]) await expectPiece(page, 'g8', 'BLACK_KNIGHT');
}

test('after the Revert of an illegal move, an illegal move of another piece is a touch-move violation, not a second illegal move', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser); // default limit: 2 illegal moves
  const { white, black } = game;
  await playE4ThenTheIllegalKnightMoveAndRevert();

  await dragPiece(black, 'b8', 'b6');
  await pressClock(black);

  await expect(arbiter(black)).toContainText('Touch-move violation: You first touched the knight on g8');
  await expect(arbiter(black)).toContainText('move the knight from g8');
  await expect(white.locator('#opponentInfoPanel')).toContainText('touch-move violation');
  for (const page of [white, black]) {
    await expect(page.locator('#gameResultPanel')).toBeHidden();
    // The knight that is owed is pointed at, in green; no illegal move is marked in red.
    await expect(squareLocator(page, 'g8')).toHaveClass(/mark-green/);
    await expect(page.locator('#board .square.mark-red')).toHaveCount(0);
  }

  await clickRestore(black);
  await expect(arbiter(black)).toContainText('Clock restarted. Game continues.');
  await dragPiece(black, 'g8', 'f6');
  await pressClock(black);
  await expect(arbiter(black)).toContainText('Move accepted');
  await expect(arbiter(white)).toContainText('Your turn');
  await expectPiece(white, 'f6', 'BLACK_KNIGHT');
});

test('after the Revert of an illegal move, a second illegal move of the touched piece itself loses the game', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await playE4ThenTheIllegalKnightMoveAndRevert();

  await dragPiece(black, 'g8', 'g5');
  await pressClock(black);

  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
  await expect(black.locator('#gameResultReason')).toContainText('2nd illegal move by Black');
});
