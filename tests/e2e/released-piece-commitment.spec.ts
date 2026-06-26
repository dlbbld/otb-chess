import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, clickRestore } from './helpers/app';
import { dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

/**
 * Regression for the released-piece (FIDE 4.7) bug: once the a-pawn is released on a4 the move is
 * final for the WHOLE turn. Previously the commitment was lost after the first revert, so the
 * second revert only undid b3 (not a4) and a different move (a2-a3) was wrongly accepted.
 */

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

// Waits until the clock has restarted after a revert (auto-resume), i.e. the board is live again.
async function expectRestoredAndResumed(white: import('@playwright/test').Page): Promise<void> {
  await expect(white.locator('#arbiterMessage')).toContainText('Clock restarted', { timeout: 15_000 });
}

test('released-piece commitment survives repeated reverts; only the committed move is accepted', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // --- Cycle 1: release a2-a4, fiddle back, play b2-b3, press -> released-piece violation ---
  await dragPiece(white, 'a2', 'a4'); // release the pawn on a4 -> commits a2-a4
  await dragPiece(white, 'a4', 'a2'); // put it back
  await dragPiece(white, 'b2', 'b3'); // play a different pawn
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);

  await clickRestore(white);
  // Revert restores the committed move (a4) and undoes b3.
  await expectPiece(white, 'a4', 'WHITE_PAWN');
  await expectEmpty(white, 'b3');
  await expectPiece(white, 'b2', 'WHITE_PAWN');
  // Requirement: the message tells the player the move is final and to press the clock.
  await expect(white.locator('#arbiterMessage')).toContainText(/your move is final/i);
  await expect(white.locator('#arbiterMessage')).toContainText(/press the clock/i);
  await expectRestoredAndResumed(white);

  // --- Cycle 2: undo a4-a2 again, play b2-b3 again, press -> STILL a released-piece violation ---
  await dragPiece(white, 'a4', 'a2');
  await dragPiece(white, 'b2', 'b3');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);

  await clickRestore(white);
  // The fix: the revert restores BOTH the committed a4 move AND b3->b2.
  await expectPiece(white, 'a4', 'WHITE_PAWN');
  await expectEmpty(white, 'b3');
  await expectPiece(white, 'b2', 'WHITE_PAWN');
  await expectEmpty(white, 'a2');
  await expectRestoredAndResumed(white);

  // --- A different move with the committed pawn (a2-a3) must be REJECTED ---
  await dragPiece(white, 'a4', 'a2');
  await dragPiece(white, 'a2', 'a3');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);

  await clickRestore(white);
  await expectPiece(white, 'a4', 'WHITE_PAWN'); // restored to the committed move again
  await expectRestoredAndResumed(white);

  // --- Finally playing the committed move a2-a4 is accepted (no deadlock) ---
  await pressClock(white); // a4 is already on the board (the committed move)
  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
});
