import { test, expect, Page } from '@playwright/test';
import {
  startTwoPlayerGame, expectGameResult, claimThreefoldOnBoard, SCORE_DRAW, TwoPlayerGame,
} from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

// FIDE 5.2.3: a draw can be agreed only after both players have made at least one move. An offer made before
// that is declared not valid by the arbiter at once, to both players - the opponent gets no Accept/Reject
// panel for an offer that could not be accepted. The offers of a move are counted like wrong-time offers.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const offerPanel = (page: Page) => page.locator('#drawOfferPanel');
const opponentInfo = (page: Page) => page.locator('#opponentInfoPanel');
const resultPanel = (page: Page) => page.locator('#gameResultPanel');

const RULE = 'a draw can be agreed only after both players have made at least one move (FIDE 5.2.3).';
const NOT_VALID = `The draw offer is not valid: ${RULE}`;
const NOT_VALID_FOR_OPPONENT = `Your opponent offered a draw. The offer is not valid: ${RULE}`;

/** The offer was declared not valid on both boards, and nobody was asked to accept anything. */
async function expectOfferDeclaredNotValid(offerer: Page, opponent: Page) {
  await expect(arbiter(offerer)).toHaveText(NOT_VALID);
  await expect(opponentInfo(opponent)).toHaveText(NOT_VALID_FOR_OPPONENT);
  for (const page of [offerer, opponent]) {
    await expect(offerPanel(page)).toBeHidden();
    await expect(resultPanel(page)).toBeHidden();
  }
}

test('the game starts and White offers a draw: the offer is declared not valid to both players', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await white.locator('#offerDrawBtn').click();

  await expectOfferDeclaredNotValid(white, black);
  // The game simply goes on.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
});

test('White moves and Black offers a draw before moving: the offer is declared not valid to both players', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');

  await black.locator('#offerDrawBtn').click();

  await expectOfferDeclaredNotValid(black, white);
  // Neither can White offer while Black has not made a move.
  await white.locator('#offerDrawBtn').click();
  await expectOfferDeclaredNotValid(white, black);
});

test('an early offer made with the first move is declared not valid, and the move is still played', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // The right way to offer - after the move, before the clock - but only White has made a move.
  await dragPiece(white, 'e2', 'e4');
  await white.locator('#offerDrawBtn').click();
  await expectOfferDeclaredNotValid(white, black);

  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');

  // Black makes the first move and offers with it: now both have moved, and the offer is a real one.
  await dragPiece(black, 'e7', 'e5');
  await black.locator('#offerDrawBtn').click();
  await expect(arbiter(black)).toHaveText('Draw offer sent.');
  await expect(offerPanel(white)).toBeVisible();
  await white.locator('#acceptDrawBtn').click();
  for (const page of [white, black]) await expectGameResult(page, SCORE_DRAW);
});

test('early offers on the same move are counted: not valid, then the warning, then the loss of the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await white.locator('#offerDrawBtn').click();
  await expectOfferDeclaredNotValid(white, black);

  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toHaveText('You are again offering a draw before both players have made a move.'
    + ' This offer was not considered. Warning: your next draw offer on this move loses the game.');
  await expect(opponentInfo(black)).toHaveText('Your opponent again offered a draw before both players have made'
    + ' a move. This offer was not considered, and they have been warned: their next draw offer on this move loses'
    + ' them the game.');
  await expect(offerPanel(black)).toBeHidden();

  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toContainText('As you have offered again, you lose the game.');
  await expect(arbiter(black)).toContainText('repeatedly offered a draw at the wrong time');
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toHaveText(
      'White loses the game by repeatedly offering a draw at the wrong time.');
  }
});

test('the count of early offers starts again on the next move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await white.locator('#offerDrawBtn').click();
  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toContainText('Warning: your next draw offer on this move loses the game.');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');

  // A new move: White's offer is again only declared not valid.
  await white.locator('#offerDrawBtn').click();
  await expectOfferDeclaredNotValid(white, black);
});

test('a draw claim on the first move is rejected and is not passed on as a draw offer', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await claimThreefoldOnBoard(white);

  await expect(arbiter(white)).toHaveText('Threefold repetition claim rejected. The position has not occurred'
    + ` three times. It does not count as a draw offer: ${RULE}`);
  await expect(opponentInfo(black)).toHaveText('Your opponent claimed a draw by threefold repetition of the'
    + ` current position, but the claim is not valid. It does not count as a draw offer: ${RULE}`);
  for (const page of [white, black]) {
    await expect(offerPanel(page)).toBeHidden();
    await expect(resultPanel(page)).toBeHidden();
  }
  // The incorrect claim still costs the two minutes, and the game goes on.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
});
