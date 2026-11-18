import { test, expect, Browser, Page } from '@playwright/test';
import {
  createGame, joinGame, expectGameStarted, expectGameResult, startTwoPlayerGame, claimThreefoldOnBoard,
  claimThreefoldWithMove, CreateOptions, SCORE_DRAW, TwoPlayerGame,
} from './helpers/app';
import { bothPlayersMove, clickSquare, dragPiece, expectPiece, pressClock } from './helpers/board';
import { startIsolatedServer } from './helpers/isolated-server';

// Regressions for the findings of the cloud review of the review branch. Each test replays the journey of
// its finding on both boards. Two findings are races inside the server (a socket that closes between the
// open check and the send; two overlapping expiry sweeps): a browser cannot force those, so their tests
// replay the nearest journey a player can take, and the race itself is pinned by the Java tests.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const offerPanel = (page: Page) => page.locator('#drawOfferPanel');
const revertButton = (page: Page) => page.getByRole('button', { name: 'Revert', exact: true });

interface Frame { type: string; running?: string; whiteTimeMs?: number }
interface FrameLog {
  /** When this page received gameStarted - the server starts the clock and its 1 s ticker right after. */
  startedAt: number;
  frames: Frame[];
}
const count = (log: FrameLog, type: string) => log.frames.filter(frame => frame.type === type).length;

/** Like startTwoPlayerGame (creator is White), but records the server frames each page receives from the start. */
async function startLoggedGame(browser: Browser, opts: CreateOptions = {}): Promise<{ white: FrameLog; black: FrameLog }> {
  const contexts = [await browser.newContext(), await browser.newContext()];
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();
  game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
  const logs = { white: { startedAt: 0, frames: [] as Frame[] }, black: { startedAt: 0, frames: [] as Frame[] } };
  for (const side of ['white', 'black'] as const) {
    game[side].on('websocket', socket => socket.on('framereceived', frame => {
      const data = JSON.parse(frame.payload.toString());
      if (data.type === 'gameStarted') logs[side].startedAt = Date.now();
      logs[side].frames.push({ type: data.type, running: data.running, whiteTimeMs: data.whiteTimeMs });
    }));
  }
  game.gameId = await createGame(white, opts);
  await joinGame(black, game.gameId);
  await expectGameStarted(white);
  await expectGameStarted(black);
  return logs;
}

// White has 3.2 s; the server's ticker looks at the clocks 3 s and 4 s after the start. Acting at 3.45 s
// therefore meets a clock that has run out but has not been adjudicated yet.
const EXPIRED_BUT_UNNOTICED = { timeMs: 3200, actAtMs: 3450 };

async function waitUntilExpiredButUnnoticed(page: Page, log: FrameLog) {
  await page.waitForTimeout(Math.max(0, log.startedAt + EXPIRED_BUT_UNNOTICED.actAtMs - Date.now()));
  expect(count(log, 'gameEnded'), 'the action must arrive before the periodic flag-fall check').toBe(0);
}

test('a clock press after expiry with a move owed from a rejected claim shows the loss on time and no Revert', async ({ browser }) => {
  const logs = await startLoggedGame(browser, { timeMs: EXPIRED_BUT_UNNOTICED.timeMs });
  const { white, black } = game!;
  // The claim is rejected and binds White to e4; White lets the clock run out without playing it.
  await claimThreefoldWithMove(white, 'e4');
  await expect(arbiter(white)).toContainText('was rejected');
  await waitUntilExpiredButUnnoticed(white, logs.white);

  await pressClock(white);

  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toHaveText('White loses on time. Black wins the game.');
    await expect(revertButton(page)).toHaveCount(0);
    await expect(page.locator('#resignBtn')).toBeDisabled();
  }
  // The press used to be answered with a restore request on the finished game, ahead of the result.
  expect(count(logs.white, 'restoreRequired')).toBe(0);
  expect(count(logs.black, 'restoreRequired')).toBe(0);
  expect(count(logs.white, 'gameEnded')).toBe(1);
  expect(count(logs.black, 'gameEnded')).toBe(1);
});

test('a flag fall sends one final clock update at zero, right before the result, to both players', async ({ browser }) => {
  const logs = await startLoggedGame(browser, { timeMs: 2200 });
  const { white, black } = game!;
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expect(page.locator('#gameResultReason')).toHaveText('White loses on time. Black wins the game.');
  }
  // White's clock is the top one on Black's board and the bottom one on White's own.
  await expect(white.locator('#bottomClockTime')).toHaveText(/^0?0:00/);
  await expect(black.locator('#topClockTime')).toHaveText(/^0?0:00/);
  for (const log of [logs.white, logs.black]) {
    const ended = log.frames.findIndex(frame => frame.type === 'gameEnded');
    expect(log.frames[ended - 1]).toEqual({ type: 'clockUpdate', running: 'none', whiteTimeMs: 0 });
    const stoppedClockUpdates = log.frames.filter(frame => frame.type === 'clockUpdate' && frame.running === 'none');
    expect(stoppedClockUpdates, 'the final clock update must not be sent twice').toHaveLength(1);
  }
});

test('claiming victory after the own clock ran out loses on time', async ({ browser }) => {
  const logs = await startLoggedGame(browser, { timeMs: EXPIRED_BUT_UNNOTICED.timeMs });
  const { white } = game!;
  // Black leaves at once; after the grace (1.5 s on the test server) White is offered Claim victory.
  await game!.contexts[1].close();
  const claimVictory = white.locator('#arbiterButtons').getByText('Claim victory');
  await expect(claimVictory).toBeVisible({ timeout: 3000 });
  // White is to move and lets the own clock run out, then claims before the server has noticed.
  await waitUntilExpiredButUnnoticed(white, logs.white);

  await claimVictory.click();

  await expectGameResult(white, '0-1');
  await expect(white.locator('#gameResultReason')).toHaveText('White loses on time. Black wins the game.');
  await expect(arbiter(white)).not.toContainText('You win');
  expect(count(logs.white, 'gameEnded')).toBe(1);
  expect(count(logs.white, 'error')).toBe(0);
});

test('an offer answering an offer made with a move that is not yet completed agrees the draw', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await bothPlayersMove(white, black);
  // White makes the move, offers a draw with it and does not press the clock.
  await dragPiece(white, 'e2', 'e4');
  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toHaveText('Draw offer sent.');
  await expect(black.locator('#acceptDrawBtn')).toBeVisible();

  // Black answers with the own Offer-draw button instead of Accept.
  await black.locator('#offerDrawBtn').click();

  for (const page of [white, black]) {
    await expectGameResult(page, SCORE_DRAW);
    await expect(page.locator('#gameResultReason')).toHaveText('The game is drawn by agreement.');
    await expect(offerPanel(page)).toBeHidden();
  }
  await expect(arbiter(black)).toHaveText('You accepted the draw offer.');
  await expect(arbiter(white)).toHaveText('Your opponent accepted the draw offer.');
});

test('an offer after the opponent\'s released move is still invalid when no offer is pending', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');

  await black.locator('#offerDrawBtn').click();

  await expect(arbiter(black)).toHaveText(
    'The draw offer is not valid because your opponent has already made a move and is about to press the clock.');
  await expect(offerPanel(white)).toBeHidden();
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
});

test('a very long turn of moving a pawn back and forth still gets a prompt ruling', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  // Touching the knight binds White to a knight move, so no pawn release completes the move and the
  // arbiter keeps weighing every release of the turn. 800 of them used to keep the server busy for
  // more than a minute, with the clocks of this game standing still on both boards. A turn now records
  // at most 500 events (see final-pass.spec.ts); this one stays below that.
  await clickSquare(white, 'g1');
  await white.evaluate(() => {
    const game = (window as any).game;
    const start = game.board.getBoardState();
    const pawnOnE4 = { ...start, e2: start.e4, e4: start.e2 };
    for (let i = 0; i < 400; i++) {
      const out = i % 2 === 0;
      game.ws.sendBoardEvent({
        eventType: 'DRAG_MOVE', square: out ? 'e2' : 'e4', targetSquare: out ? 'e4' : 'e2',
        piece: 'WHITE_PAWN', displacedPiece: 'NONE',
      }, out ? pawnOnE4 : start);
    }
  });

  // The pawn is back on e2. White now moves the touched knight and presses the clock.
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('Move accepted', { timeout: 10_000 });
  await expect(arbiter(black)).toContainText('Your turn');
  for (const page of [white, black]) {
    await expectPiece(page, 'f3', 'WHITE_KNIGHT');
    await expectPiece(page, 'e2', 'WHITE_PAWN');
    expect(await page.evaluate(() => (window as any).game.clockRunning)).toBe('black');
  }
});

test('a player whose opponent has just closed the tab still gets the result of the own resignation', async ({ browser }) => {
  const logs = await startLoggedGame(browser);
  const { white } = game!;
  // Black's socket is closing while White ends the game: the result must reach White all the same.
  await game!.contexts[1].close();
  await white.locator('#resignBtn').click();

  await expectGameResult(white, '0-1');
  await expect(white.locator('#gameResultReason')).toHaveText('White resigns. Black wins the game.');
  expect(count(logs.white, 'gameEnded')).toBe(1);
  expect(count(logs.white, 'error')).toBe(0);
});

test.describe('on a server of its own', () => {
  let isolated: Awaited<ReturnType<typeof startIsolatedServer>> | undefined;
  test.afterEach(async () => {
    for (const context of game?.contexts ?? []) await context.close();
    game = undefined;
    await isolated?.stop();
    isolated = undefined;
  });

  async function startIsolatedGame(browser: Browser) {
    const contexts = [await isolated!.context(browser), await isolated!.context(browser)];
    const white = await contexts[0].newPage();
    const black = await contexts[1].newPage();
    game = { contexts, white, black, creator: white, joiner: black, gameId: '' };
    game.gameId = await createGame(white);
    await joinGame(black, game.gameId);
    await expectGameStarted(white);
    await expectGameStarted(black);
    return { white, black };
  }

  test('a draw claim and draw offers during the pause after a wrong clock press are not considered', async ({ browser, request }) => {
    isolated = await startIsolatedServer(request, { OTB_WRONG_CLOCK_PAUSE_MS: '5000' });
    const { white, black } = await startIsolatedGame(browser);
    const paused = 'The game is paused. Please wait until the clock restarts.';

    // White is to move; Black presses White's clock. The arbiter stops the clocks for five seconds.
    const pressedAt = Date.now();
    await black.locator('#topClockBtn').click();
    await expect(white.locator('#opponentInfoPanel')).toContainText('paused');

    await claimThreefoldOnBoard(white);
    await expect(arbiter(white)).toHaveText(paused);
    await white.locator('#offerDrawBtn').click();
    await expect(arbiter(white)).toHaveText(paused);
    await black.locator('#offerDrawBtn').click();
    await expect(arbiter(black)).toHaveText(paused);
    expect(Date.now() - pressedAt, 'all three must happen inside the five-second pause').toBeLessThan(4500);
    for (const page of [white, black]) {
      await expect(offerPanel(page)).toBeHidden();
      expect(await page.evaluate(() => (window as any).game.clockRunning)).toBe('none');
    }

    // The clock restarts by itself. The incorrect claim cost nothing: no two minutes for Black.
    await expect(white.locator('#chessClock')).not.toHaveClass(/paused/, { timeout: 10_000 });
    for (const page of [white, black]) {
      await expect.poll(() => page.evaluate(() => (window as any).game.clockRunning)).toBe('white');
      expect(await page.evaluate(() => (window as any).game._lastClockData.blackTimeMs)).toBeLessThanOrEqual(180_000);
      await expect(offerPanel(page)).toBeHidden();
    }

    // The claim of this move is still available, and now it is examined: rejected, and an offer to Black.
    await claimThreefoldOnBoard(white);
    await expect(arbiter(white)).toContainText('claim rejected');
    await expect(offerPanel(black)).toBeVisible();
    await expect.poll(() => black.evaluate(() => (window as any).game._lastClockData.blackTimeMs))
      .toBeGreaterThan(180_000);
  });

  test('a waiting game that expires sends its creator to the lobby once, and a new game then works', async ({ browser, request }) => {
    // The waiting-room lifetime is one second; rooms are swept whenever a game is created.
    isolated = await startIsolatedServer(request, { OTB_ROOM_TTL_MS: '1000', OTB_MAX_GAMES: '1' });
    const contexts = [await isolated.context(browser), await isolated.context(browser)];
    const creator = await contexts[0].newPage();
    const other = await contexts[1].newPage();
    game = { contexts, white: creator, black: other, creator, joiner: other, gameId: '' };
    let aborted = 0;
    creator.on('websocket', socket => socket.on('framereceived', frame => {
      if (JSON.parse(frame.payload.toString()).type === 'gameAborted') aborted++;
    }));
    const expiredId = await createGame(creator);
    await creator.waitForTimeout(1300);

    // The server is full (one game) - until the other player's createGame sweeps the expired room.
    const otherId = await createGame(other);
    expect(otherId).not.toBe(expiredId);
    await expect(creator).toHaveURL(/\/(index\.html)?$/, { timeout: 10_000 });
    expect(aborted, 'the expiry must be announced once').toBe(1);

    // Back in the lobby the creator joins the other player's game: the seat works.
    await joinGame(creator, otherId);
    await expectGameStarted(creator);
    await expectGameStarted(other);
    await other.locator('#resignBtn').click();
    for (const page of [creator, other]) await expectGameResult(page, '0-1');
  });
});
