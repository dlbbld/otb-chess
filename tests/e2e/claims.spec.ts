import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  SCORE_DRAW,
  claimFiftyMoveOnBoard,
  claimThreefoldOnBoard,
  claimFiftyMoveWithMove,
  claimThreefoldWithMove,
} from './helpers/app';
import { dragPiece, pressClock } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

const FIFTY_REACHED = '4k3/8/8/8/3R4/8/8/4K3 w - - 100 80'; // half-move clock 100
const FIFTY_ONE_AWAY = '4k3/8/8/8/3R4/8/8/4K3 w - - 99 51'; // 99 -> a quiet move makes 100
const FIFTY_FAR = '4k3/8/8/8/3R4/8/8/4K3 w - - 0 1'; // nowhere near the rule
const SEVENTYFIVE_ONE_AWAY = '4k3/8/8/8/3R4/8/8/4K3 w - - 149 100'; // 149 -> a quiet move makes 150

// ---- 50-move rule ------------------------------------------------------------------------------

test('50-move claim on a qualifying position draws the game and informs the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_REACHED });
  const { white, black } = game;

  await claimFiftyMoveOnBoard(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(black.locator('#gameResultReason')).toContainText('50-move');
});

test('50-move claim with the move that reaches it draws and informs the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_ONE_AWAY });
  const { white, black } = game;

  await claimFiftyMoveWithMove(white, 'Rd1'); // quiet rook move brings the clock to 100

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(black.locator('#gameResultReason')).toContainText('50-move');
});

test('50-move claim with an invalid SAN reports a validation message', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await claimFiftyMoveWithMove(white, 'Zz9');

  await expect(white.locator('#arbiterMessage')).toContainText('Invalid move');
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});

test('50-move claim with a legal move that does not satisfy the rule is rejected and informs the opponent', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_FAR });
  const { white, black } = game;

  await claimFiftyMoveWithMove(white, 'Rd1'); // legal, but the rule is nowhere near satisfied

  await expect(white.locator('#arbiterMessage')).toContainText('Claim rejected');
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer.
  await expect(black.locator('#arbiterMessage')).toContainText(/offers a draw/i);
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});

// ---- threefold repetition ----------------------------------------------------------------------

test('threefold claim without a repetition is rejected and informs the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await claimThreefoldOnBoard(white);

  await expect(white.locator('#arbiterMessage')).toContainText('rejected');
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer.
  await expect(black.locator('#arbiterMessage')).toContainText(/offers a draw/i);
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});

test('threefold claim with an invalid SAN reports a validation message', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await claimThreefoldWithMove(white, 'Zz9');

  await expect(white.locator('#arbiterMessage')).toContainText('Invalid move');
});

test('threefold claim with a legal move that creates no repetition is rejected and informs the opponent', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await claimThreefoldWithMove(white, 'Nf3'); // legal move, but no repetition results

  await expect(white.locator('#arbiterMessage')).toContainText(/reject/i);
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer.
  await expect(black.locator('#arbiterMessage')).toContainText(/offers a draw/i);
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});

// ---- automatic 75-move draw --------------------------------------------------------------------

test('the 75-move rule auto-draws the game and informs both players why', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: SEVENTYFIVE_ONE_AWAY });
  const { white, black } = game;

  await dragPiece(white, 'd4', 'd1'); // quiet move brings the half-move clock to 150
  await pressClock(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#gameResultReason')).toContainText('75-move');
  await expect(black.locator('#gameResultReason')).toContainText('75-move');
});
