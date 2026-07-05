import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

// Wrong-time draw offers (A-001): escalation is per player PER MOVE — first offer of the move
// is a real offer, the second is not considered (+ warning), the third loses the game. The count
// never carries over to the next move (an offer is only semi-illegal, unlike wrong-time claims).

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('wrong-time offers escalate within one move: real offer, not considered + warning, loss', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White is to move. Black's first wrong-time offer is a REAL offer: White gets Accept/Reject.
  await black.locator('#offerDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('should be made on your own turn');
  await expect(black.locator('#arbiterMessage')).toContainText('The offer still counts as a draw offer');
  await expect(white.locator('#drawOfferPanel')).toBeVisible();
  await white.locator('#rejectDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('Your opponent rejected the draw offer');

  // Second wrong-time offer on the same move: NOT considered — no panel for White, who instead
  // sees what happened passively; Black is warned.
  await black.locator('#offerDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('This offer was not considered');
  await expect(black.locator('#arbiterMessage')).toContainText(
    'Warning: your next draw offer on this move loses the game');
  await expect(white.locator('#drawOfferPanel')).toBeHidden();
  await expect(white.locator('#opponentInfoPanel')).toContainText('not considered');
  await expect(white.locator('#opponentInfoPanel')).toContainText('been warned');

  // Third on the same move: Black loses the game.
  await black.locator('#offerDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'repeatedly offered a draw at the wrong time');
  await expectGameResult(black, '1-0');
  await expectGameResult(white, '1-0');
  await expect(white.locator('#gameResultReason')).toContainText(
    'Black loses the game by repeatedly offering a draw at the wrong time.');
});

test('the wrong-time offer count resets every move — the first offer of a new move is real again', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Black reaches the warning on this move (offer, rejected, offer again).
  await black.locator('#offerDrawBtn').click();
  await expect(white.locator('#drawOfferPanel')).toBeVisible();
  await white.locator('#rejectDrawBtn').click();
  await black.locator('#offerDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('Warning');
  await expect(white.locator('#drawOfferPanel')).toBeHidden();

  // A move pair later, the slate is clean: Black's wrong-time offer is a REAL offer again.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');

  await black.locator('#offerDrawBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('The offer still counts as a draw offer');
  await expect(black.locator('#arbiterMessage')).not.toContainText('Warning');
  await expect(white.locator('#drawOfferPanel')).toBeVisible();
});
