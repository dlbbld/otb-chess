import { test, expect } from '@playwright/test';
import { startTwoPlayerGame, expectGameResult, claimThreefoldOnBoard, TwoPlayerGame } from './helpers/app';
import { bothPlayersMove, dragPiece, pressClock, clickSquare } from './helpers/board';

let game: TwoPlayerGame;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
});

for (const recipient of ['white', 'black'] as const) {
  test(`${recipient} can accept a pending draw offer after refreshing`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser);
    await bothPlayersMove(game.white, game.black);
    const offerer = recipient === 'white' ? 'black' : 'white';
    await game[offerer].locator('#offerDrawBtn').click();
    await expect(game[recipient].locator('#drawOfferPanel')).toBeVisible();
    await game[recipient].reload();
    await expect(game[recipient].locator('#drawOfferMessage')).toHaveText('Your opponent offers a draw.');
    await expect(game[recipient].locator('#acceptDrawBtn')).toBeVisible();
    await expect(game[recipient].locator('#rejectDrawBtn')).toBeVisible();
    await expect(game[offerer].locator('#drawOfferPanel')).toBeHidden();
    await game[recipient].locator('#acceptDrawBtn').click();
    for (const page of [game.white, game.black]) {
      await expectGameResult(page, '½-½');
      await expect(page.locator('#gameResultReason')).toContainText('agreement');
      await expect(page.locator('#drawOfferPanel')).toBeHidden();
    }
  });
}

test('a correct-time offer can be rejected after refresh and stays rejected on another refresh', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  await dragPiece(game.white, 'e2', 'e4');
  await game.white.locator('#offerDrawBtn').click();
  await expect(game.black.locator('#drawOfferPanel')).toBeVisible();
  await pressClock(game.white);
  await game.black.reload();
  await game.black.locator('#rejectDrawBtn').click();
  await expect(game.white.locator('#arbiterMessage')).toContainText('rejected the draw offer');
  await expect(game.black.locator('#arbiterMessage')).toContainText('rejected');
  await game.black.reload();
  await expect(game.black.locator('#arbiterMessage')).toHaveText('Reconnected.');
  await expect(game.black.locator('#drawOfferPanel')).toBeHidden();
  await dragPiece(game.black, 'e7', 'e5');
  await pressClock(game.black);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Move accepted');
});

test('refresh preserves the explanation for an offer converted from a rejected draw claim', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  await bothPlayersMove(game.white, game.black);
  await claimThreefoldOnBoard(game.white);
  await expect(game.black.locator('#drawOfferMessage')).toContainText('still counts as a draw offer');
  const explanation = await game.black.locator('#drawOfferMessage').innerText();
  await game.black.reload();
  await expect(game.black.locator('#drawOfferMessage')).toHaveText(explanation);
  await game.black.locator('#acceptDrawBtn').click();
  for (const page of [game.white, game.black]) {
    await expectGameResult(page, '½-½');
    await expect(page.locator('#gameResultReason')).toContainText('agreement');
  }
});

test('refreshing a visible offer cannot restore the right to accept after touching a piece', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  await dragPiece(game.white, 'e2', 'e4');
  await game.white.locator('#offerDrawBtn').click();
  await expect(game.black.locator('#drawOfferPanel')).toBeVisible();
  await pressClock(game.white);
  await expect(game.black.locator('#arbiterMessage')).toContainText('Your turn');
  await clickSquare(game.black, 'e7');
  await game.black.reload();
  await game.black.locator('#acceptDrawBtn').click();
  await expect(game.black.locator('#arbiterMessage')).toContainText('no longer valid');
  for (const page of [game.white, game.black]) await expect(page.locator('#gameResultPanel')).toBeHidden();
  await dragPiece(game.black, 'e7', 'e5');
  await pressClock(game.black);
  await expect(game.white.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(game.black.locator('#arbiterMessage')).toContainText('Move accepted');
});
