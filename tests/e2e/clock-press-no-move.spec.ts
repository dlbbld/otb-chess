import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';
import { dragPiece, pressClock, expectPiece } from './helpers/board';

// FIDE 7.5.3: pressing the clock without making a move is considered and penalised as an
// illegal move. With the default limit (two illegal moves lose), the second press in a row
// loses the game.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('a clock press without a move is an illegal move; the game continues with a real move', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser); // 3+0, default limit: 2 illegal moves
  const { white, black } = game;

  // White presses the clock without having touched a piece.
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText(
    'the clock was pressed without a move being made (FIDE 7.5.3)');
  await expect(white.locator('#arbiterMessage')).toContainText('Please make a move.');
  await expect(white.locator('#arbiterMessage')).not.toContainText('restore');
  await expect(black.locator('#arbiterMessage')).not.toContainText('Your opponent made an illegal move');
  await expect(black.locator('#opponentInfoPanel')).toContainText('Your opponent made an illegal move');

  // The standard illegal-move penalty: Black gains 2 minutes (3:00 -> 5:00, clock not running).
  await expect(white.locator('#topClockTime')).toHaveText('5:00', { timeout: 10_000 });

  // Nothing to restore — White simply plays a real move and the game continues normally.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
});

test('two clock presses in a row without a move lose the game (default limit)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Your next illegal move will lose the game');

  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('White loses the game');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expect(black.locator('#gameResultReason')).toContainText('2nd illegal move by White');
});
