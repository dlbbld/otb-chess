import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  clickRestore,
} from './helpers/app';
import { dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

// Moving an OPPONENT's piece — never legal, arbiter escalation per A-007:
// notice + restore, notice + warning + restore, loss of the game on the third time.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('moving an opponent\'s piece escalates: restore, restore + warning, then loss', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // First time: White (to move) drags BLACK's e7 pawn. The arbiter pauses the game and requires
  // a restore; Black sees what happened passively below the clock.
  await dragPiece(white, 'e7', 'e5');
  await expect(white.locator('#arbiterMessage')).toContainText('You moved an opponent');
  await expect(white.locator('#arbiterMessage')).not.toContainText('Warning');
  await expect(black.locator('#opponentInfoPanel')).toContainText('moved one of your pieces');
  await expect(white.locator('#chessClock')).toHaveClass(/paused/);

  // The player reverts; the clock restarts (auto-resume) and the pawn is back on e7.
  await clickRestore(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Game continues', { timeout: 15_000 });
  await expectPiece(white, 'e7', 'BLACK_PAWN');

  // Second time: same intervention plus the warning.
  await dragPiece(white, 'e7', 'e5');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Warning: the next time you move an opponent\'s piece, you lose the game');
  await expect(black.locator('#opponentInfoPanel')).toContainText('been warned');
  await clickRestore(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Game continues', { timeout: 15_000 });

  // Third time: White loses the game.
  await dragPiece(white, 'e7', 'e5');
  await expect(white.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(black.locator('#arbiterMessage')).toContainText('repeatedly moved your pieces');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expect(black.locator('#gameResultReason')).toContainText(
    'White loses the game by repeatedly moving the opponent\'s pieces.');
});

test('revert after a completed move preserves that move and only undoes later piece displacement', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'e2', 'e4');
  await dragPiece(white, 'e7', 'e5');

  await expect(white.locator('#arbiterMessage')).toContainText('You moved an opponent');
  await clickRestore(white);

  await expectPiece(white, 'e4', 'WHITE_PAWN');
  await expectEmpty(white, 'e2');
  await expectPiece(white, 'e7', 'BLACK_PAWN');
  await expectEmpty(white, 'e5');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await expectPiece(black, 'e7', 'BLACK_PAWN');

  await expect(white.locator('#arbiterMessage')).toContainText('Your move is final', { timeout: 15_000 });
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});
