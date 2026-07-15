import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  SCORE_DRAW,
  acceptDraw,
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

// ---- Claims while not having the move ----------------------------------------------------------

test('wrong-time claims escalate: rejection, warning, then loss of the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White has the move. Black presses a claim button anyway — rejected, but per the teaching
  // philosophy the buttons stay ENABLED so the fault can be repeated. White sees what happened
  // in the PASSIVE info window below the clock (face-to-face principle) — the arbiter message
  // window stays untouched since no action is required.
  await claimThreefoldOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText('cannot claim a draw when not having the move');
  await expect(black.locator('#claimThreefoldOnBoardBtn')).toBeEnabled();
  await expect(black.locator('#claimFiftyMoveOnBoardBtn')).toBeEnabled();
  await expect(white.locator('#opponentInfoPanel')).toContainText('claimed a draw while not having the move');
  // "Not considered", not "rejected" — the claim never reached the rule machinery.
  await expect(white.locator('#opponentInfoPanel')).toContainText('The claim was not considered');
  await expect(white.locator('#opponentInfoPanel')).not.toContainText('rejected');
  await expect(white.locator('#arbiterMessage')).not.toContainText('claimed');

  // Second press: same rejection plus the warning; White's passive info mentions the warning.
  await claimFiftyMoveOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText(
    'Warning: your next draw claim when not having the move loses the game');
  await expect(black.locator('#claimThreefoldOnBoardBtn')).toBeEnabled();
  await expect(white.locator('#opponentInfoPanel')).toContainText('been warned');
  await expect(white.locator('#arbiterMessage')).not.toContainText('claimed');

  // Third press: Black loses the game; White is told why — now action-relevant, so it arrives
  // in the standard arbiter window, and the stale passive info disappears with the game.
  await claimThreefoldOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(white.locator('#arbiterMessage')).toContainText('repeatedly requested to claim a draw');
  await expectGameResult(black, '1-0');
  await expectGameResult(white, '1-0');
  await expect(black.locator('#gameResultReason')).toContainText('repeatedly claiming a draw');
  await expect(white.locator('#opponentInfoPanel')).toBeHidden();
});

test('wrong-time with-move claim skips the SAN prompt; counts are per player; claim right survives', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White has the move. Black presses a WITH-MOVE claim button: no SAN prompt appears (the claim
  // is rejected regardless of any move) — the rejection comes straight at the button press.
  await black.locator('#claimThreefoldWithMoveBtn').click();
  await expect(black.locator('#arbiterMessage')).toContainText('cannot claim a draw when not having the move');
  await expect(black.locator('#sanInputPanel')).toBeHidden();
  await expect(white.locator('#opponentInfoPanel')).toContainText('claimed a draw while not having the move');

  // White plays 1. e4 — now Black has the move, and White's move closes the episode the passive
  // info reported on: the info window empties.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(white.locator('#opponentInfoPanel')).toBeHidden();

  // White (now off move) presses a claim button: PLAIN first rejection — Black's earlier
  // wrong-time press did not count against White.
  await claimFiftyMoveOnBoard(white);
  await expect(white.locator('#arbiterMessage')).toContainText('cannot claim a draw when not having the move');
  await expect(white.locator('#arbiterMessage')).not.toContainText('Warning');

  // Black IS on move and claims: processed on the merits (rejected, forwarded as a draw offer) —
  // the earlier wrong-time press did not burn Black's once-per-move claim right.
  await claimThreefoldOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText('rejected');
  await expect(white.locator('#drawOfferPanel')).toBeVisible();
});

test('wrong-time claim escalation accumulates across different moves', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // While White is on move 1: Black's first wrong-time claim — plain rejection, no warning yet.
  await claimThreefoldOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText('cannot claim a draw when not having the move');
  await expect(black.locator('#arbiterMessage')).not.toContainText('Warning');

  // 1. e4 e5 — the game goes on.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');

  // While White is on move 2: the second wrong-time claim — now the warning (count persisted).
  await claimFiftyMoveOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText(
    'Warning: your next draw claim when not having the move loses the game');

  // 2. Nf3 Nf6.
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(black, 'g8', 'f6');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');

  // While White is on move 3: the third wrong-time claim — Black loses.
  await claimThreefoldOnBoard(black);
  await expect(black.locator('#arbiterMessage')).toContainText('you lose the game');
  await expectGameResult(black, '1-0');
  await expectGameResult(white, '1-0');
});

test('claims after touching a piece (move made, clock not pressed) escalate across moves', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White makes the move ON THE BOARD but does not press the clock — the touch forfeits the
  // claim right (FIDE 9.4). The claim is rejected straight at the button; Black sees it
  // passively; the buttons stay enabled.
  await dragPiece(white, 'e2', 'e4');
  await claimThreefoldOnBoard(white);
  await expect(white.locator('#arbiterMessage')).toContainText('after touching or moving a piece');
  await expect(white.locator('#claimThreefoldOnBoardBtn')).toBeEnabled();
  await expect(black.locator('#opponentInfoPanel')).toContainText('claimed a draw after touching a piece');
  await expect(black.locator('#opponentInfoPanel')).toContainText('The claim was not considered');

  // Second press in the same situation — via a WITH-MOVE button: no SAN prompt, the warning.
  await white.locator('#claimFiftyMoveWithMoveBtn').click();
  await expect(white.locator('#arbiterMessage')).toContainText(
    'Warning: your next draw claim after touching a piece loses the game');
  await expect(white.locator('#sanInputPanel')).toBeHidden();
  await expect(black.locator('#opponentInfoPanel')).toContainText('been warned');

  // White completes the move; Black replies — the game goes on, the count persists.
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');

  // A NEW move, the same fault: White touches the knight and claims — third time, White loses.
  await dragPiece(white, 'g1', 'f3');
  await claimThreefoldOnBoard(white);
  await expect(white.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(black.locator('#arbiterMessage')).toContainText(
    'repeatedly claimed a draw after touching a piece');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
});

test('repeat claims on the same move escalate: warning, then loss of the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White (on move) uses their one legitimate claim — rejected on the merits, forwarded to
  // Black as a draw offer. The claim buttons stay ENABLED.
  await claimThreefoldOnBoard(white);
  await expect(white.locator('#arbiterMessage')).toContainText('rejected');
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#claimThreefoldOnBoardBtn')).toBeEnabled();
  await expect(white.locator('#claimFiftyMoveWithMoveBtn')).toBeEnabled();

  // Second claim on the same move: the warning — straight at the button, even for a with-move
  // claim (no SAN prompt; the claim is refused regardless of any move). Black sees the repeat
  // passively below the clock while the accept/reject question stays in the arbiter window.
  await white.locator('#claimFiftyMoveWithMoveBtn').click();
  await expect(white.locator('#arbiterMessage')).toContainText('cannot make more than one draw claim');
  await expect(white.locator('#arbiterMessage')).toContainText('You are warned');
  await expect(white.locator('#sanInputPanel')).toBeHidden();
  await expect(white.locator('#claimThreefoldOnBoardBtn')).toBeEnabled();
  await expect(black.locator('#opponentInfoPanel')).toContainText('second draw claim on the same move');
  await expect(black.locator('#arbiterMessage')).toContainText('still counts as a draw offer');

  // Third claim: White loses the game; Black is told why.
  await claimFiftyMoveOnBoard(white);
  await expect(white.locator('#arbiterMessage')).toContainText('you lose the game');
  await expect(black.locator('#arbiterMessage')).toContainText('repeatedly claimed a draw on the same move');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expect(black.locator('#gameResultReason')).toContainText('repeatedly claiming a draw on the same move');
  // The pending draw offer died with the game — no Accept/Reject on a finished game — and the
  // stale passive info disappeared with it.
  await expect(black.locator('#drawOfferPanel')).toBeHidden();
  await expect(black.locator('#opponentInfoPanel')).toBeHidden();
});

test('canceling a with-move claim retracts it but consumes the claim for this move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await white.locator('#claimThreefoldWithMoveBtn').click();
  await expect(white.locator('#sanInputPanel')).toBeVisible();
  await expect(white.locator('#submitClaimMoveBtn')).toHaveText('Submit');
  await expect(white.locator('#cancelClaimMoveBtn')).toHaveText('Cancel');

  await white.locator('#cancelClaimMoveBtn').click();

  await expect(white.locator('#arbiterMessage')).toHaveText(
    'You retracted your draw claim. The claim was not considered, but it counts as your claim on this move.');
  await expect(white.locator('#sanInputPanel')).toBeHidden();
  await expect(black.locator('#opponentInfoPanel')).toContainText('retracted a draw claim');
  await expect(black.locator('#arbiterMessage')).not.toContainText('retracted');
  await expect(black.locator('#drawOfferPanel')).toBeHidden();

  await white.locator('#claimFiftyMoveWithMoveBtn').click();

  await expect(white.locator('#sanInputPanel')).toBeHidden();
  await expect(white.locator('#arbiterMessage')).toContainText(
    'You cannot make more than one draw claim on your move');
  await expect(black.locator('#opponentInfoPanel')).toContainText('second draw claim on the same move');
});

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
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Your claim under the 50-move rule for move Rd1 was accepted.');
});

test('a with-move claim accepts a lenient SAN (spurious check mark)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_ONE_AWAY });
  const { white, black } = game;

  // "Rd1+" is strict-invalid (Rd1 is not check) but lenient forgives the spurious marker.
  await claimFiftyMoveWithMove(white, 'Rd1+');

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Your claim under the 50-move rule for move Rd1 was accepted.');
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
  const { white, black } = game;

  await claimFiftyMoveWithMove(white, 'c2');

  await expect(white.locator('#arbiterMessage')).toHaveText(
    "The claim was not considered because the presented move 'c2' is not legal: A pawn cannot move backwards."
      + " You may make any legal move.");
  await expect(white.locator('#arbiterMessage')).not.toContainText('lenient SAN parser');
  await expect(white.locator('#sanInputPanel')).toBeHidden();
  await expect(white.locator('#gameResultPanel')).toBeHidden();

  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expectPiece(black, 'e4', 'WHITE_PAWN');
});

test('rejected on-board 50-move claim announces the claim and the offer to the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_FAR });
  const { white, black } = game;

  await claimFiftyMoveOnBoard(white); // nowhere near the rule -> rejected

  await expect(white.locator('#arbiterMessage')).toContainText('rejected');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'The claim also counts as a draw offer for your opponent, which he can accept or reject.');
  await expect(black.locator('#arbiterMessage')).toContainText(
    'claimed a draw by the 50-move rule on the current position');
  await expect(black.locator('#arbiterMessage')).toContainText('still counts as a draw offer');
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
});

test('the draw offer converted from a rejected claim can be accepted and draws the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White's claim is rejected on the merits and forwarded to Black as a draw offer (FIDE 9.5).
  await claimThreefoldOnBoard(white);
  await expect(black.locator('#drawOfferPanel')).toBeVisible();

  // Black accepts: the game is drawn like any agreed draw.
  await acceptDraw(black);
  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
});

test('50-move claim with a legal move that does not satisfy the rule is rejected and informs the opponent', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser, { fen: FIFTY_FAR });
  const { white, black } = game;

  await claimFiftyMoveWithMove(white, 'Rd1'); // legal, but the rule is nowhere near satisfied

  await expect(white.locator('#arbiterMessage')).toContainText('Claim rejected');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'The claim also counts as a draw offer for your opponent, which he can accept or reject.');
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer — announced with
  // what actually happened, not a bare "offers a draw".
  await expect(black.locator('#arbiterMessage')).toContainText(
    'claimed a draw by the 50-move rule with the move Rd1');
  await expect(black.locator('#arbiterMessage')).toContainText('still counts as a draw offer');
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#gameResultPanel')).toBeHidden();

  await black.locator('#rejectDrawBtn').click();
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Your opponent rejected the draw offer, which was automatically part of your claim under the 50-move rule.');
});

// ---- threefold repetition ----------------------------------------------------------------------

test('threefold claim with the move that reaches it draws and normalizes the SAN in the message', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await dragPiece(black, 'g8', 'f6');
  await pressClock(black);
  await dragPiece(white, 'f3', 'g1');
  await pressClock(white);
  await dragPiece(black, 'f6', 'g8');
  await pressClock(black);
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await dragPiece(black, 'g8', 'f6');
  await pressClock(black);
  await dragPiece(white, 'f3', 'g1');
  await pressClock(white);
  await dragPiece(black, 'f6', 'g8');
  await pressClock(black);

  await claimThreefoldWithMove(white, 'nf3');

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Your claim for threefold repetition for move Nf3 was accepted.');
});

test('threefold claim without a repetition is rejected and informs the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await claimThreefoldOnBoard(white);

  await expect(white.locator('#arbiterMessage')).toContainText('rejected');
  await expect(white.locator('#arbiterMessage')).toContainText(
    'The claim also counts as a draw offer for your opponent, which he can accept or reject.');
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer — announced with
  // what actually happened, not a bare "offers a draw".
  await expect(black.locator('#arbiterMessage')).toContainText(
    'claimed a draw by threefold repetition of the current position');
  await expect(black.locator('#arbiterMessage')).toContainText('still counts as a draw offer');
  await expect(black.locator('#drawOfferPanel')).toBeVisible();
  await expect(white.locator('#gameResultPanel')).toBeHidden();

  await black.locator('#rejectDrawBtn').click();
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'Your opponent rejected the draw offer, which was automatically part of your claim for threefold repetition.');
});

test('threefold claim with an invalid SAN reports a validation message', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  await claimThreefoldWithMove(white, 'Zz9');

  await expect(white.locator('#arbiterMessage')).toContainText(
    "The claim was not considered because the presented move 'Zz9' is not legal:");
  await expect(white.locator('#arbiterMessage')).not.toContainText('lenient SAN parser');
  await expect(white.locator('#sanInputPanel')).toBeHidden();
});

test('threefold claim with a legal move that creates no repetition is rejected and informs the opponent', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await claimThreefoldWithMove(white, 'Nf3'); // legal move, but no repetition results

  await expect(white.locator('#arbiterMessage')).toContainText(/reject/i);
  await expect(white.locator('#arbiterMessage')).toContainText(
    'The claim also counts as a draw offer for your opponent, which he can accept or reject.');
  // FIDE: an unsuccessful claim is forwarded to the opponent as a draw offer — announced with
  // what actually happened, not a bare "offers a draw".
  await expect(black.locator('#arbiterMessage')).toContainText(
    'claimed a draw by threefold repetition with the move Nf3');
  await expect(black.locator('#arbiterMessage')).toContainText('still counts as a draw offer');
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
  const plies = [];
  for (let c = 0; c < 4; c++) plies.push(...cycle);
  for (let i = 0; i < plies.length; i++) {
    const m = plies[i];
    await dragPiece(m.p, m.from, m.to);
    await pressClock(m.p);
    // Wait for each move to be confirmed (turn switches) before the next ply, so the rapid sequence
    // doesn't race ahead of the server round-trip over a high-latency link. The final ply triggers
    // the fivefold auto-draw (no "Move accepted"), so skip the wait there.
    if (i < plies.length - 1) {
      await expect(m.p.locator('#arbiterMessage')).toContainText('Move accepted', { timeout: 15_000 });
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
