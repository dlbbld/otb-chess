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

for (const attemptReplacementCastle of [false, true]) {
  test(`Black Kf8 then Rh8-e8 and Revert keeps Kf8 final${attemptReplacementCastle ? ' against replacement castling' : ''}`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { fen: '4k2r/8/8/8/8/8/8/4K3 b k - 0 1' });
    const { white, black } = game;

    // Replay the reported journey literally: release Kf8, release Rh8-e8, clock, Revert, clock.
    await dragPiece(black, 'e8', 'f8');
    await dragPiece(black, 'h8', 'e8');
    await pressClock(black);
    await expect(black.locator('#arbiterMessage')).toContainText('king release on f8');
    await expect(black.getByRole('button', { name: 'Revert' })).toBeVisible();
    await expect(white.locator('#opponentInfoPanel')).toContainText('king release on f8');
    await expect(white.getByRole('button', { name: 'Revert' })).toBeHidden();
    await clickRestore(black);
    await expect(black.locator('#arbiterMessage')).toContainText('your move is final');
    await expect(black.locator('#arbiterMessage')).toContainText(/press the clock/i);
    await expectRestoredAndResumed(black);
    await expect(white.locator('#arbiterMessage')).not.toContainText('Your move is final');

    if (attemptReplacementCastle) {
      // After recovery try replacing the final move with legal-from-turn-start short castling.
      // Repeating the recovery must neither erase the lock nor restore the king to e8.
      for (let cycle = 0; cycle < 2; cycle++) {
        await dragPiece(black, 'f8', 'e8');
        await dragPiece(black, 'e8', 'g8');
        await dragPiece(black, 'h8', 'f8');
        await pressClock(black);
        await expect(black.locator('#arbiterMessage')).toContainText('put the king back on f8');
        await expect(black.locator('#arbiterMessage')).not.toContainText('please perform the castling move');
        await expect(white.locator('#opponentInfoPanel')).toContainText('f8');
        await expect(white.locator('#arbiterMessage')).not.toContainText('Your turn');
        await clickRestore(black);
        await expectRestoredAndResumed(black);
      }
    }

    for (const page of [white, black]) {
      await expectPiece(page, 'f8', 'BLACK_KING');
      await expectPiece(page, 'h8', 'BLACK_ROOK');
      await expectEmpty(page, 'e8');
      await expectEmpty(page, 'g8');
    }
    await pressClock(black);
    await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
    await expect(white.locator('#arbiterMessage')).toContainText('Your turn');
    await expect(black.getByRole('button', { name: 'Revert' })).toBeHidden();
    await expect(white.locator('#gameResultPanel')).toBeHidden();
    await expect(black.locator('#gameResultPanel')).toBeHidden();

    // The next turn is playable, while the king stays on its final square on both boards.
    await dragPiece(white, 'e1', 'f2');
    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
    await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
    for (const page of [white, black]) {
      await expectPiece(page, 'f8', 'BLACK_KING');
      await expectPiece(page, 'f2', 'WHITE_KING');
    }
  });
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
  // Regression: the "your move is final" guidance is for the committer only — the OPPONENT (Black)
  // must NOT see it; Black just sees the game continue.
  await expect(black.locator('#arbiterMessage')).toContainText(/continues/i);
  await expect(black.locator('#arbiterMessage')).not.toContainText(/your move is final/i);

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

test('released-piece message distinguishes later position change from moving the released pawn', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'a2', 'a4');
  await dragPiece(white, 'h2', 'h3');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText(
    'Please revert the position change after pawn release on a4.');
  await expect(white.locator('#arbiterMessage')).not.toContainText('put the pawn back on a4');
  await expect(black.locator('#opponentInfoPanel')).toContainText(
    'revert the position change after pawn release on a4');
  await expect(black.locator('#arbiterMessage')).not.toContainText('revert the position change');

  await clickRestore(white);
  await expectPiece(white, 'a4', 'WHITE_PAWN');
  await expectPiece(white, 'h2', 'WHITE_PAWN');
  await expectEmpty(white, 'h3');
  await expectRestoredAndResumed(white);

  await dragPiece(white, 'a4', 'a5');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Please put the pawn back on a4 and press the clock.');
  await expect(white.locator('#arbiterMessage')).not.toContainText('revert the position change after pawn release');
});

test('released-piece final move can be clock-pressed immediately after Revert', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'a2', 'a4');
  await dragPiece(white, 'h2', 'h3');
  await pressClock(white);

  await clickRestore(white);
  await expect(white.locator('#arbiterMessage')).toContainText('Position restored');

  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(white, 'a4', 'WHITE_PAWN');
  await expectEmpty(white, 'a2');
  await expectPiece(white, 'h2', 'WHITE_PAWN');
  await expectEmpty(white, 'h3');
});
