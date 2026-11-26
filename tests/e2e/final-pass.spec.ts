import { test, expect, Browser, Page } from '@playwright/test';
import {
  createGame, joinGame, expectGameStarted, startTwoPlayerGame, expectGameResult, SCORE_DRAW, TwoPlayerGame,
} from './helpers/app';
import { clickSquare, dragPiece, expectPiece, flipBoard, pressClock, pressOwnClock, squareLocator } from './helpers/board';
import { startIsolatedServer } from './helpers/isolated-server';

// Regressions for the findings of the final review pass before the release. Each test replays the journey
// its finding was reproduced with, on both boards.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const offerPanel = (page: Page) => page.locator('#drawOfferPanel');
const resultPanel = (page: Page) => page.locator('#gameResultPanel');
const REPLACED = 'This game is now open in another window. This window no longer takes part.';

/** One move by each player, so that both have made a move (a draw can be agreed from then on). */
async function playOneMoveEach(white: Page, black: Page) {
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
}

test('the game opened in a second window: the old window is out of the game and says so', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black: blackOld } = game;
  await playOneMoveEach(white, blackOld);

  // Black opens the running game again in a second window of the same browser.
  const blackNew = await game.contexts[1].newPage();
  await blackNew.goto('/game.html');
  await expect(arbiter(blackNew)).toHaveText('Reconnected.', { timeout: 15_000 });

  // The old window used to look alive: enabled buttons, a standing clock, and a server that still
  // acted on its messages.
  await expect(arbiter(blackOld)).toHaveText(REPLACED);
  for (const id of ['offerDrawBtn', 'resignBtn', 'claimThreefoldOnBoardBtn', 'flipBoardBtn']) {
    await expect(blackOld.locator(`#${id}`)).toBeDisabled();
  }
  expect(await blackOld.evaluate(() => (window as any).game.board.enabled)).toBe(false);

  await blackNew.locator('#offerDrawBtn').click();
  await expect(offerPanel(white)).toBeVisible();

  // The journey of the finding: Offer draw (and Accept) from the old window agreed the draw alone.
  await blackOld.evaluate(() => {
    const ws = (window as any).game.ws;
    ws.send({ type: 'offerDraw' });
    ws.send({ type: 'acceptDraw' });
    ws.send({ type: 'rejectDraw' });
    ws.send({ type: 'resign' });
  });
  await expect(arbiter(blackOld)).toHaveText('You are not in a game.');
  await expect(blackOld.locator('#devConsole')).toBeHidden();
  for (const page of [white, blackNew]) {
    await expect(resultPanel(page)).toBeHidden();
  }
  // White still has Black's offer, and answers it.
  await expect(offerPanel(white)).toBeVisible();
  await white.locator('#rejectDrawBtn').click();
  await expect(arbiter(white)).toHaveText('You rejected the draw offer.');
  await expect(arbiter(blackNew)).toHaveText('Your opponent rejected the draw offer.');

  // The game goes on between White and Black's new window.
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(arbiter(blackNew)).toContainText('Your turn');
  await expectPiece(blackNew, 'f3', 'WHITE_KNIGHT');
  await blackNew.locator('#offerDrawBtn').click();
  await white.locator('#acceptDrawBtn').click();
  for (const page of [white, blackNew]) {
    await expectGameResult(page, SCORE_DRAW);
  }
  await expect(arbiter(white)).toHaveText('You accepted the draw offer.');
  await expect(resultPanel(blackOld)).toBeHidden();
});

test('the window that is opened last holds the seat', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black: first } = game;
  const second = await game.contexts[1].newPage();
  await second.goto('/game.html');
  await expect(arbiter(second)).toHaveText('Reconnected.', { timeout: 15_000 });
  await expect(arbiter(first)).toHaveText(REPLACED);

  // A refresh of the first window takes the seat back; now the second window is out.
  await first.reload();
  await expect(arbiter(first)).toHaveText('Reconnected.', { timeout: 15_000 });
  await expect(arbiter(second)).toHaveText(REPLACED);
  await expect(second.locator('#resignBtn')).toBeDisabled();

  // The frozen window does not reconnect behind the player's back, and the opponent saw no disconnect.
  await second.waitForTimeout(2500);
  await expect(arbiter(first)).toHaveText('Reconnected.');
  await expect(arbiter(white)).not.toContainText('disconnected');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(first)).toContainText('Your turn');
  await expectPiece(first, 'e4', 'WHITE_PAWN');
});

// ----- a player who is away when the game starts, or while the opponent reconnects -----
// The test server tells the opponent 1.5 s after a drop and adjudicates the abandonment after 6 s.

const countdown = (page: Page) => page.locator('#arbiterMessage');
const claimVictory = (page: Page) => page.locator('#arbiterButtons').getByText('Claim victory');
/** The socket is lost and the page does not reconnect - a phone browser in the background. */
const dropSocket = (page: Page) => page.evaluate(() => {
  const ws = (window as any).game.ws; ws.shouldReconnect = false; ws.ws.close();
});
/** The page is in front again and reconnects. */
const comeBack = (page: Page) => page.evaluate(() => {
  const ws = (window as any).game.ws; ws.shouldReconnect = true; ws._open(null, null, true);
});

async function waitingCreatorAndJoiner(browser: Browser) {
  const contexts = [await browser.newContext(), await browser.newContext()];
  const creator = await contexts[0].newPage();
  const joiner = await contexts[1].newPage();
  game = { contexts, white: creator, black: joiner, creator, joiner, gameId: '' };
  game.gameId = await createGame(creator);
  return { creator, joiner, gameId: game.gameId };
}

test('an opponent who joins shortly after the creator dropped does not win a game of a few seconds', async ({ browser }) => {
  const { creator, joiner, gameId } = await waitingCreatorAndJoiner(browser);
  await dropSocket(creator);
  const droppedAt = Date.now();
  await creator.waitForTimeout(2500);

  await joinGame(joiner, gameId);
  await expectGameStarted(joiner);
  const startedAt = Date.now();
  // The notice of the drop went to nobody. The joiner is told now, counted from the start of the game.
  await expect(countdown(joiner)).toContainText('Your opponent has disconnected. The game will be ended in', { timeout: 5000 });
  await expect(claimVictory(joiner)).toBeVisible();

  // 6 s after the drop the creator used to lose - less than 4 s into the game.
  await joiner.waitForTimeout(Math.max(0, droppedAt + 6600 - Date.now()));
  expect(Date.now() - startedAt, 'still inside the deadline of this game').toBeLessThan(5500);
  await expect(resultPanel(joiner)).toBeHidden();

  // The creator is back in time and finds the game running.
  await comeBack(creator);
  await expect(arbiter(creator)).toHaveText('Reconnected.', { timeout: 10_000 });
  await expect(arbiter(joiner)).toHaveText('Your opponent has reconnected.');
  await expect(claimVictory(joiner)).toHaveCount(0);
  await dragPiece(creator, 'e2', 'e4');
  await pressClock(creator);
  await expect(arbiter(joiner)).toContainText('Your turn');
  await expectPiece(joiner, 'e4', 'WHITE_PAWN');
  await joiner.waitForTimeout(Math.max(0, startedAt + 6800 - Date.now()));
  await expect(resultPanel(joiner)).toBeHidden();
});

test('an opponent who joins long after the creator dropped is told and can claim the win', async ({ browser }) => {
  const { creator, joiner, gameId } = await waitingCreatorAndJoiner(browser);
  await dropSocket(creator);
  await creator.waitForTimeout(7500); // both timers of the drop are spent

  await joinGame(joiner, gameId);
  await expectGameStarted(joiner);
  // This game used to run against nobody: no notice, no countdown, no Claim victory.
  await expect(countdown(joiner)).toContainText('Your opponent has disconnected. The game will be ended in', { timeout: 5000 });
  await claimVictory(joiner).click();

  await expectGameResult(joiner, '0-1');
  await expect(joiner.locator('#gameResultReason')).toHaveText('White left the game. Black wins the game.');
  await expect(arbiter(joiner)).toHaveText('Your opponent left the game. You win.');
});

test('a creator who never returns loses by abandonment, counted from the start of the game', async ({ browser }) => {
  const { creator, joiner, gameId } = await waitingCreatorAndJoiner(browser);
  await dropSocket(creator);
  await creator.waitForTimeout(7500);
  await joinGame(joiner, gameId);
  await expectGameStarted(joiner);
  const startedAt = Date.now();

  await expectGameResult(joiner, '0-1');
  const lasted = Date.now() - startedAt;
  expect(lasted, 'the abandonment window of the test server is 6 s').toBeGreaterThan(5000);
  expect(lasted).toBeLessThan(9000);
  await expect(arbiter(joiner)).toHaveText('Your opponent left the game. You win.');
});

test('a player who reconnects while the opponent is away sees the countdown and Claim victory again', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dropSocket(black);
  await expect(countdown(white)).toContainText('The game will be ended in', { timeout: 5000 });

  // White's page is refreshed. The notice had gone to the old socket; nothing said the opponent was gone.
  await white.reload();
  await expect(countdown(white)).toContainText('Your opponent has disconnected. The game will be ended in', { timeout: 10_000 });
  await claimVictory(white).click();

  await expectGameResult(white, '1-0');
  await expect(arbiter(white)).toHaveText('Your opponent left the game. You win.');
});

// ----- the board events of one turn are limited -----

test('a turn records no more than 500 piece movements; the move is still ruled on', async ({ browser }) => {
  // The script below sends the frames of a game with centred pieces.
  game = await startTwoPlayerGame(browser, { free: false });
  const { white, black } = game;
  // Touching the knight binds White to a knight move; then the pawn goes back and forth 509 times.
  await clickSquare(white, 'g1');
  await white.evaluate(() => {
    const game = (window as any).game;
    const start = game.board.getBoardState();
    const pawnOnE4 = { ...start, e2: start.e4, e4: start.e2 };
    for (let i = 0; i < 510; i++) {
      const out = i % 2 === 0;
      game.ws.sendBoardEvent({
        eventType: 'DRAG_MOVE', square: out ? 'e2' : 'e4', targetSquare: out ? 'e4' : 'e2',
        piece: 'WHITE_PAWN', displacedPiece: 'NONE',
      }, out ? pawnOnE4 : start);
    }
  });
  await expect(arbiter(white)).toHaveText('Too many piece movements in one move. Further movements are not recorded.');

  // The pawn is back on e2. The touched knight is moved and the clock pressed.
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted', { timeout: 10_000 });
  await expect(arbiter(black)).toContainText('Your turn');
  for (const page of [white, black]) {
    await expectPiece(page, 'f3', 'WHITE_KNIGHT');
    await expectPiece(page, 'e2', 'WHITE_PAWN');
  }
  // The next turn starts at zero.
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
});

// ----- a resignation needs a running game -----

test('a resignation sent before an opponent joined is refused, and the game then starts normally', async ({ browser }) => {
  const { creator, joiner, gameId } = await waitingCreatorAndJoiner(browser);
  // The button is hidden while waiting; the message is sent by hand.
  await creator.evaluate(() => (window as any).game.ws.send({ type: 'resign' }));
  await expect(arbiter(creator)).toHaveText('The game has not started.');
  await expect(resultPanel(creator)).toBeHidden();

  // The joiner used to get a game whose clock ran while every move was dropped.
  await joinGame(joiner, gameId);
  await expectGameStarted(creator);
  await expectGameStarted(joiner);
  await dragPiece(creator, 'e2', 'e4');
  await pressClock(creator);
  await expect(arbiter(joiner)).toContainText('Your turn');
  await expectPiece(joiner, 'e4', 'WHITE_PAWN');
  await joiner.locator('#resignBtn').click();
  for (const page of [creator, joiner]) {
    await expectGameResult(page, '1-0');
    await expect(page.locator('#gameResultReason')).toHaveText('Black resigns. White wins the game.');
  }
});

// ----- a connection has one game -----

test('a creator who creates another game gives up the first one', async ({ browser }) => {
  const { creator, joiner, gameId } = await waitingCreatorAndJoiner(browser);
  // A second createGame on the same connection (the page itself never sends one).
  await creator.evaluate(() => (window as any).game.ws.createGame('white', 180000, 0, 2, true, ''));
  await expect.poll(() => creator.evaluate(() => (window as any).game.gameId)).not.toBe(gameId);
  const secondId = await creator.evaluate(() => (window as any).game.gameId);

  // The first room used to stay on the server until it expired, taking one of its rooms.
  await joinGame(joiner, gameId);
  await expect(arbiter(joiner)).toContainText("This game code wasn't found");
  await joinGame(joiner, secondId);
  await expectGameStarted(creator);
  await expectGameStarted(joiner);

  // In a running game a further createGame is refused and the game goes on.
  await creator.evaluate(() => (window as any).game.ws.createGame('white', 180000, 0, 2, true, ''));
  await expect(arbiter(creator)).toHaveText('You are already playing a game.');
  await dragPiece(creator, 'e2', 'e4');
  await pressClock(creator);
  await expect(arbiter(joiner)).toContainText('Your turn');
});

// ----- no draw before both players have moved; the offered move and the result in the record -----

const TOO_EARLY = 'a draw can be agreed only after both players have made at least one move (FIDE 5.2.3).';

async function shownPgn(page: Page): Promise<string> {
  await page.locator('#exportPgnBtn').click();
  await expect(page.locator('#pgnDialog')).toBeVisible();
  const pgn = (await page.locator('#pgnText').inputValue()).trim();
  await page.locator('#exportPgnBtn').click();
  return pgn;
}

test('a draw offered on the first move is declared not valid; Black\'s first move with an offer can be agreed', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  // White makes the first move and offers a draw with it: only White has made a move. The arbiter
  // declares the offer not valid to both players, and Black is not asked to accept anything.
  await dragPiece(white, 'e2', 'e4');
  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toHaveText(`The draw offer is not valid: ${TOO_EARLY}`);
  await expect(black.locator('#opponentInfoPanel')).toHaveText(
    `Your opponent offered a draw. The offer is not valid: ${TOO_EARLY}`);
  for (const page of [white, black]) {
    await expect(offerPanel(page)).toBeHidden();
    await expect(resultPanel(page)).toBeHidden();
  }

  // White completes the move. Black makes the first move and offers with it: now both have moved.
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await black.locator('#offerDrawBtn').click();
  await expect(arbiter(black)).toHaveText('Draw offer sent.');
  await expect(offerPanel(white)).toBeVisible();
  await white.locator('#acceptDrawBtn').click();

  for (const page of [white, black]) {
    await expectGameResult(page, SCORE_DRAW);
    await expect(page.locator('#gameResultReason')).toHaveText('The game is drawn by agreement.');
    // Black's move was made on the board and belongs to the game, although the clock was not pressed.
    expect(await shownPgn(page)).toBe('1. e4 e5 1/2-1/2');
    await expectPiece(page, 'e5', 'BLACK_PAWN');
  }
});

test('the PGN ends with the result of the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await playOneMoveEach(white, black);
  expect(await shownPgn(white)).toBe('1. e4 e5 *');

  await white.locator('#resignBtn').click();
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    expect(await shownPgn(page)).toBe('1. e4 e5 0-1');
  }
});

// ----- an offer that answers an offer, during a pause -----

test.describe('on a server of its own', () => {
  let isolated: Awaited<ReturnType<typeof startIsolatedServer>> | undefined;
  test.afterEach(async () => {
    for (const context of game?.contexts ?? []) await context.close();
    game = undefined;
    await isolated?.stop();
    isolated = undefined;
  });

  test('Offer draw answering an offer during the pause after a wrong clock press agrees the draw', async ({ browser, request }) => {
    isolated = await startIsolatedServer(request, { OTB_WRONG_CLOCK_PAUSE_MS: '8000' });
    const contexts = [await isolated.context(browser), await isolated.context(browser)];
    const white = await contexts[0].newPage();
    const black = await contexts[1].newPage();
    game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
    game.gameId = await createGame(white);
    await joinGame(black, game.gameId);
    await expectGameStarted(white);
    await expectGameStarted(black);
    await playOneMoveEach(white, black);

    // Black offers a draw and then presses White's clock: the arbiter stops the clocks.
    await black.locator('#offerDrawBtn').click();
    await expect(offerPanel(white)).toBeVisible();
    await black.locator('#topClockBtn').click();
    await expect(white.locator('#opponentInfoPanel')).toContainText('paused');
    expect(await white.evaluate(() => (window as any).game.clockRunning)).toBe('none');

    // White answers with the own Offer-draw button. It used to be "The game is paused...", while
    // Accept on the same panel agreed the draw.
    await white.locator('#offerDrawBtn').click();

    for (const page of [white, black]) {
      await expectGameResult(page, SCORE_DRAW);
      await expect(page.locator('#gameResultReason')).toHaveText('The game is drawn by agreement.');
      await expect(offerPanel(page)).toBeHidden();
    }
    await expect(arbiter(white)).toHaveText('You accepted the draw offer.');
    await expect(arbiter(black)).toHaveText('Your opponent accepted the draw offer.');
  });

  test('a player told to wait during the pause after a wrong clock press is told when the clock restarts', async ({ browser, request }) => {
    isolated = await startIsolatedServer(request, { OTB_WRONG_CLOCK_PAUSE_MS: '4000' });
    const contexts = [await isolated.context(browser), await isolated.context(browser)];
    const white = await contexts[0].newPage();
    const black = await contexts[1].newPage();
    game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
    game.gameId = await createGame(white);
    await joinGame(black, game.gameId);
    await expectGameStarted(white);
    await expectGameStarted(black);

    await black.locator('#topClockBtn').click();
    await expect(white.locator('#opponentInfoPanel')).toContainText('The game is paused');
    // White moves and presses the clock inside the pause: the arbiter asks White to wait.
    await dragPiece(white, 'e2', 'e4');
    await pressClock(white);
    await expect(arbiter(white)).toHaveText('The game is paused. Please wait until the clock restarts.');

    // That request used to stay in the window after the clock had restarted.
    await expect(arbiter(white)).toHaveText(RESTARTED, { timeout: 10_000 });
    await expect(arbiter(black)).toHaveText(RESTARTED);
    await expect(white.locator('#chessClock')).not.toHaveClass(/paused/);
    // White presses again, now that the clock runs: the move stands.
    await pressClock(white);
    await expect(arbiter(black)).toContainText('Your turn');
    await expectPiece(black, 'e4', 'WHITE_PAWN');
  });
});

// ----- the three notes of the final pass -----

const RESTARTED = 'Clock restarted. Game continues.';

test('the restart of the clock after a wrong clock press is announced on both boards', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // White is on move; Black presses White's clock. The test server pauses for one second.
  await black.locator('#topClockBtn').click();
  await expect(arbiter(black)).toContainText('The game is paused and will continue shortly.');
  await expect(white.locator('#opponentInfoPanel')).toContainText('your clock will restart shortly');

  // Both messages used to stand unchanged after the clock had restarted.
  await expect(arbiter(black)).toHaveText(RESTARTED, { timeout: 10_000 });
  await expect(arbiter(black)).not.toHaveClass(/error/);
  await expect(white.locator('#opponentInfoPanel')).toHaveText('Your opponent pressed your clock. Your clock has restarted.');
  await expect(black.locator('#chessClock')).not.toHaveClass(/paused/);
  // White did nothing during the pause: what the arbiter had told White stays.
  await expect(arbiter(white)).toContainText('clock has been started');

  // The second press: the warning is still there after the restart.
  await black.locator('#topClockBtn').click();
  await expect(arbiter(black)).toContainText('The game is paused and will continue shortly. Warning');
  await expect(arbiter(black)).toHaveText(
    `${RESTARTED} Warning: the next press of your opponent's clock loses the game.`, { timeout: 10_000 });
  await expect(arbiter(black)).toHaveClass(/error/);
  await expect(white.locator('#opponentInfoPanel')).toHaveText(
    'Your opponent pressed your clock and has been warned: the next press loses them the game. Your clock has restarted.');

  // The game goes on, and the move closes the episode.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expect(white.locator('#opponentInfoPanel')).toBeHidden();
});

/** Records the types of the messages this page receives from now on. */
const recordMessages = (page: Page) => page.evaluate(() => {
  (window as any).received = [];
  (window as any).game.ws.ws.addEventListener('message', (e: MessageEvent) => {
    (window as any).received.push(JSON.parse(e.data).type);
  });
});
const received = (page: Page) => page.evaluate(() => (window as any).received as string[]);

test('a move that ends the game is followed by one final clock update, not two', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'f2', 'f3');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await dragPiece(white, 'g2', 'g4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await recordMessages(white);
  await recordMessages(black);

  await dragPiece(black, 'd8', 'h4'); // mate: the game ends without a clock press

  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toContainText('checkmate');
  }
  // From the arbiter's answer to the move on: the clock values once, then the result.
  const whiteTypes = await received(white);
  const blackTypes = await received(black);
  expect(whiteTypes.slice(whiteTypes.lastIndexOf('opponentMoved'))).toEqual(['opponentMoved', 'clockUpdate', 'gameEnded']);
  expect(blackTypes.slice(blackTypes.lastIndexOf('move_accepted'))).toEqual(['move_accepted', 'clockUpdate', 'gameEnded']);
});

/** Whose side the player has at the bottom of the board. */
async function colourAtTheBottom(page: Page) {
  const a1 = await squareLocator(page, 'a1').boundingBox();
  const a8 = await squareLocator(page, 'a8').boundingBox();
  return a1!.y > a8!.y ? 'white' : 'black';
}

test('a board turned with Flip board stays turned when the connection is lost and comes back', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await flipBoard(white);
  await flipBoard(black);
  expect(await colourAtTheBottom(white)).toBe('black');
  expect(await colourAtTheBottom(black)).toBe('white');

  // Both lose the connection one after the other and reconnect on the same page.
  for (const [page, other] of [[white, black], [black, white]] as const) {
    await dropSocket(page);
    await comeBack(page);
    await expect(arbiter(page)).toHaveText('Reconnected.', { timeout: 10_000 });
    await expect(arbiter(other)).not.toContainText('disconnected');
  }

  // The reconnect used to turn each board back to the player's own colour.
  expect(await colourAtTheBottom(white)).toBe('black');
  expect(await colourAtTheBottom(black)).toBe('white');
  // The clocks follow the board: the own clock is at the top, and the game is played from there.
  await expect(white.locator('#topClockDisplay')).toHaveClass(/own-clock/);
  await dragPiece(white, 'e2', 'e4');
  await pressOwnClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectPiece(black, 'e4', 'WHITE_PAWN');

  // Flip board again gives the own colour back, and that stays across a reconnect as well.
  await flipBoard(black);
  await dropSocket(black);
  await comeBack(black);
  await expect(arbiter(black)).toHaveText('Reconnected.', { timeout: 10_000 });
  expect(await colourAtTheBottom(black)).toBe('black');

  // A page that is loaded anew starts from the player's own colour.
  await white.reload();
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colourAtTheBottom(white)).toBe('white');
});

test('a rematch shows the new colour at the bottom although the board was turned', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await flipBoard(white);
  expect(await colourAtTheBottom(white)).toBe('black');
  await white.locator('#resignBtn').click();
  await expectGameResult(white, '0-1');

  await white.locator('#rematchBtn').click();
  await black.locator('#rematchBtn').click();
  await expect(resultPanel(white)).toBeHidden();

  // White of the first game plays Black now, and Black is at the bottom because it is the own colour.
  expect(await white.evaluate(() => (window as any).game.side)).toBe('black');
  expect(await colourAtTheBottom(white)).toBe('black');
  expect(await colourAtTheBottom(black)).toBe('white');
  // The turn of the board from the first game is forgotten: a reconnect keeps the own colour.
  await dropSocket(white);
  await comeBack(white);
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 10_000 });
  expect(await colourAtTheBottom(white)).toBe('black');
});
