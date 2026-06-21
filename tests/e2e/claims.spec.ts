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
import { dragPiece, pressClock, expectPiece } from './helpers/board';

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
  // The claimant's own message now names the move that was claimed.
  await expect(white.locator('#arbiterMessage')).toContainText('Rd1');
});

test('a with-move claim accepts a lenient SAN (spurious check mark)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_ONE_AWAY });
  const { white, black } = game;

  // "Rd1+" is strict-invalid (Rd1 is not check) but lenient forgives the spurious marker.
  await claimFiftyMoveWithMove(white, 'Rd1+');

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#arbiterMessage')).toContainText('Rd1+');
});

test('an unsuccessful with-move claim names the owed move and offers a Revert', async ({ browser }) => {
  // Clock at 99 with a pawn available: a quiet rook move would reach 50 moves, so the claim is
  // feasible. White claims with the pawn move e4 (which resets the counter) -> rejected, and White
  // must still play e4. White instead plays a rook move, so the arbiter asks to revert.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/3R4/4P3/8/4K3 w - - 99 51' });
  const { white } = game;

  await claimFiftyMoveWithMove(white, 'e4'); // pawn move resets the 50-move counter -> rejected
  await expect(white.locator('#arbiterMessage')).toContainText('rejected');

  // White plays the wrong move (a rook move instead of e4) and presses the clock.
  await dragPiece(white, 'd4', 'd1');
  await pressClock(white);

  // The arbiter names the owed move and offers a Revert button.
  await expect(white.locator('#arbiterMessage')).toContainText('e4');
  await expect(white.locator('#arbiterMessage')).toContainText('was not executed');
  await expect(white.getByRole('button', { name: 'Revert' })).toBeVisible();

  // Reverting restores the board to the start of the turn.
  await white.getByRole('button', { name: 'Revert' }).click();
  await expectPiece(white, 'd4', 'WHITE_ROOK');
  await expectPiece(white, 'e3', 'WHITE_PAWN');
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
  // Personalised arbiter message, like checkmate/stalemate.
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Your last move led to 75 moves each without a capture or pawn move',
  );
  await expect(black.locator('#arbiterMessage')).toContainText(
    "Your opponent's last move led to 75 moves each without a capture or pawn move",
  );
});

test('fivefold repetition auto-draws and is announced like checkmate/stalemate', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Shuffle the knights back to the start position until it has occurred five times. One cycle
  // (Nf3 Nf6 Ng1 Ng8) returns to the start; the start counts as occurrence 1, so four cycles
  // (16 plies) reach the fifth, which auto-ends the game on the final drag. The clock press on
  // that final ply is a no-op (the game is already over).
  const cycle = [
    { p: white, from: 'g1', to: 'f3' },
    { p: black, from: 'g8', to: 'f6' },
    { p: white, from: 'f3', to: 'g1' },
    { p: black, from: 'f6', to: 'g8' },
  ];
  for (let c = 0; c < 4; c++) {
    for (const m of cycle) {
      await dragPiece(m.p, m.from, m.to);
      await pressClock(m.p);
    }
  }

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  // Black played the final ply, so the messages are personalised accordingly.
  await expect(black.locator('#arbiterMessage')).toContainText('Your last move led to a fivefold repetition');
  await expect(white.locator('#arbiterMessage')).toContainText(
    "Your opponent's last move led to a fivefold repetition",
  );
});
