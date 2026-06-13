import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  SCORE_DRAW,
  claimFiftyMoveOnBoard,
  claimThreefoldOnBoard,
  claimFiftyMoveWithMove,
} from './helpers/app';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('50-move rule claim on a qualifying position draws the game', async ({ browser }) => {
  // Half-move clock 100 = the 50-move rule is satisfied.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/3R4/8/8/4K3 w - - 100 80' });
  const { white, black } = game;

  await claimFiftyMoveOnBoard(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
});

test('50-move rule claim with the move that reaches it draws the game', async ({ browser }) => {
  // Half-move clock 99; a non-pawn, non-capture move (Rd4-d1) brings it to 100.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/3R4/8/8/4K3 w - - 99 51' });
  const { white } = game;

  await claimFiftyMoveWithMove(white, 'Rd1');

  await expectGameResult(white, SCORE_DRAW);
});

test('an invalid claim move is reported as invalid and the game continues', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await claimFiftyMoveWithMove(white, 'Zz9'); // not a legal SAN

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});

test('a threefold claim without a repetition is rejected and the game continues', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await claimThreefoldOnBoard(white);

  await expect(white.locator('#arbiterMessage')).toContainText(/reject/i);
  await expect(white.locator('#gameResultPanel')).toBeHidden();
});
