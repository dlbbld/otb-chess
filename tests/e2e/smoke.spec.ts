import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame } from './helpers/app';
import { dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('two players: create, join, white plays e2-e4, black sees it', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black, gameId } = game;
  expect(gameId).toMatch(/\S/);

  // Both players see the time control with its FIDE discipline (3+0 = blitz).
  await expect(white.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');
  await expect(black.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');

  // White is to move. Play e2-e4 on the physical board, then commit with the clock.
  await dragPiece(white, 'e2', 'e4');
  await expectPiece(white, 'e4', 'WHITE_PAWN');
  await expectEmpty(white, 'e2');
  await pressClock(white);

  // White's move is accepted -> opponent's turn.
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');

  // Black receives the move on their (flipped) board and it becomes black's turn.
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await expectEmpty(black, 'e2');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});
