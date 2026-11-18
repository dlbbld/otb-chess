import { test, expect, Browser, Page } from '@playwright/test';
import {
  createGame, joinGame, expectGameStarted, startTwoPlayerGame, expectGameResult, SCORE_DRAW, TwoPlayerGame,
} from './helpers/app';
import { clickSquare, dragPiece, expectPiece, pressClock } from './helpers/board';
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
  game = await startTwoPlayerGame(browser);
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
});
