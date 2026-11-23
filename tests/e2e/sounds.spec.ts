import { test, expect, BrowserContext } from '@playwright/test';
import { clickRestore, expectGameResult, expectGameResumed } from './helpers/app';
import {
  clickSquare,
  dragFromSideArea,
  dragPiece,
  expectEmpty,
  expectPiece,
  pressClock,
  pressOpponentClock,
  removePiece,
  squareLocator,
} from './helpers/board';
import {
  audioMode,
  audioState,
  clockOption,
  createFromEntryScreen,
  expectBothHeard,
  expectHeard,
  expectStarted,
  joinFromEntryScreen,
  newListeningContext,
  pieceOption,
  samplesDecoded,
  setSoundOptions,
  startSoundGame,
  unlockAudio,
} from './helpers/sounds';

// Sounds (E-004): a piece set down on the board and a clock lever pressed down are heard by both
// players, each on their own board, unless the player switched that sound off on the entry screen.
//
// The players arrive through the entry screen, as first-time visitors. What they hear is read by an
// observer on the audio output (helpers/sounds.ts), as exact counts per board. No test listens:
// how the samples sound is checked by ear before a release.

// A browser of the test runner plays without asking. A player's browser gives a page its audio
// output only after a gesture on it; this makes the test browser do the same.
test.use({ launchOptions: { args: ['--autoplay-policy=document-user-activation-required'] } });

// With real audio on a slow output device every game page takes 20 s to open.
test.setTimeout(240_000);

let contexts: BrowserContext[] = [];

test.beforeAll(async () => {
  console.log(`sounds.spec.ts: audio mode '${await audioMode()}'`);
});

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

const arbiter = (page: import('@playwright/test').Page) => page.locator('#arbiterMessage');

test('both sounds are on for a first-time visitor, and a move and a clock press are heard once on each board', async ({
  browser,
}) => {
  const visitor = await newListeningContext(browser);
  contexts.push(visitor);
  const entry = await visitor.newPage();
  await entry.goto('/');
  await expect(pieceOption(entry)).toBeChecked();
  await expect(clockOption(entry)).toBeChecked();
  await expect(entry.locator('.lobby-section', { hasText: 'Sounds' })).toContainText(
    'Saved in this browser, for every game you create, join or return to.');

  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;
  await expectBothHeard(white, black, 0, 0);

  // A piece touched and left where it stands is no piece set down.
  await clickSquare(white, 'g1');
  await expect(squareLocator(black, 'g1')).toHaveClass(/opponent-touch/);
  await expectBothHeard(white, black, 0, 0);

  // Set down on another square: heard at the release, before any clock press.
  await dragPiece(white, 'g1', 'f3');
  await expectPiece(black, 'f3', 'WHITE_KNIGHT');
  await expectBothHeard(white, black, 1, 0);

  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
  await expectBothHeard(white, black, 1, 1);

  // The same the other way round.
  await dragPiece(black, 'e7', 'e5');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
  await expectBothHeard(white, black, 2, 1);
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectBothHeard(white, black, 2, 2);
});

test('a capture and a piece brought back from the side sound, a piece taken off the board does not', async ({
  browser,
}) => {
  // Black to move, with a pawn that captures on a1 and promotes there.
  const game = await startSoundGame(browser, { fen: 'r3k3/8/8/8/8/8/1p6/R3K3 b - - 0 1' });
  contexts.push(...game.contexts);
  const { white, black } = game;

  // A piece set down on an occupied square.
  await dragPiece(black, 'b2', 'a1');
  await expectPiece(white, 'a1', 'BLACK_PAWN');
  await expectBothHeard(white, black, 1, 0);

  // A piece from the side set down on an occupied square (the queen onto the pawn).
  await dragFromSideArea(black, 'BLACK_QUEEN', 'a1');
  await expectPiece(white, 'a1', 'BLACK_QUEEN');
  await expectBothHeard(white, black, 2, 0);

  // A piece lifted off the board is put down beside it, not on it.
  await removePiece(black, 'a1');
  await expectEmpty(white, 'a1');
  await expectBothHeard(white, black, 2, 0);

  // A piece from the side set down on an empty square.
  await dragFromSideArea(black, 'BLACK_QUEEN', 'a1');
  await expectPiece(white, 'a1', 'BLACK_QUEEN');
  await expectBothHeard(white, black, 3, 0);

  await pressClock(black);
  await expect(arbiter(black)).toContainText('Move accepted');
  await expect(arbiter(white)).toContainText('Your turn');
  await expectBothHeard(white, black, 3, 1);
});

test('an illegal move is heard like any other; the arbiter, Revert and the clock that restarts are silent', async ({
  browser,
}) => {
  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await expectPiece(black, 'b3', 'WHITE_KNIGHT');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Illegal move');
  await expect(black.locator('#opponentInfoPanel')).toBeVisible();
  await expectBothHeard(white, black, 1, 1);

  // A lever is down and the game is paused: pressing again does nothing, on either lever.
  await pressClock(white);
  await pressOpponentClock(white);
  await pressOpponentClock(black);

  // Revert sets the position from the server: nobody set a piece down.
  await clickRestore(white);
  await expectPiece(black, 'b1', 'WHITE_KNIGHT');
  await expectEmpty(black, 'b3');
  await expectGameResumed(white);
  await expectGameResumed(black);
  await expectBothHeard(white, black, 1, 1);

  // The game goes on (the touched knight has to move), and each further sound is exactly one.
  await dragPiece(white, 'b1', 'c3');
  await expectPiece(black, 'c3', 'WHITE_KNIGHT');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
  await expectBothHeard(white, black, 2, 2);
});

test('a piece put back by hand during the restoration is heard on both boards', async ({ browser }) => {
  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Illegal move');
  await expectBothHeard(white, black, 1, 1);

  // White puts the knight back on b1 with their own hand instead of clicking Revert.
  await dragPiece(white, 'b3', 'b1');
  await expectPiece(black, 'b1', 'WHITE_KNIGHT');
  await expectEmpty(black, 'b3');
  await expectBothHeard(white, black, 2, 1);
});

test('a press of the opponent\'s lever is heard on both boards, the clock that restarts and a lever that is down are not', async ({
  browser,
}) => {
  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;

  // White is to move and White's clock runs: Black presses it.
  await pressOpponentClock(black);
  await expect(arbiter(black)).toContainText('Please do not press your opponent');
  await expect(white.locator('#opponentInfoPanel')).toContainText('pressed your clock');
  await expectBothHeard(white, black, 0, 1);

  // The arbiter restarts the clock after the pause: no lever was pressed.
  await expect(black.locator('#chessClock')).not.toHaveClass(/paused/, { timeout: 10_000 });
  await expect(white.locator('#opponentInfoPanel')).toContainText('Your clock has restarted');
  await expectBothHeard(white, black, 0, 1);

  // Levers that are down: Black's own (it is not Black's turn), and Black's pressed by White.
  await pressClock(black);
  await pressOpponentClock(white);

  // Had either of them sounded, the next press would not be the second.
  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectBothHeard(white, black, 1, 2);
});

test('each option silences its own sound for that player only, is kept in the browser, and holds on joining and returning', async ({
  browser,
}) => {
  contexts = [await newListeningContext(browser), await newListeningContext(browser)];
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();

  // White plays without the piece sound, Black without the clock sound. The choice is kept.
  await white.goto('/');
  await setSoundOptions(white, { piece: false });
  await white.reload();
  await expect(pieceOption(white)).not.toBeChecked();
  await expect(clockOption(white)).toBeChecked();
  await black.goto('/');
  await setSoundOptions(black, { clock: false });
  await black.reload();
  await expect(pieceOption(black)).toBeChecked();
  await expect(clockOption(black)).not.toBeChecked();

  const gameId = await createFromEntryScreen(white);
  await joinFromEntryScreen(black, gameId);
  await expectStarted(white);
  await expectStarted(black);
  await unlockAudio(white);
  await unlockAudio(black);

  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectHeard(white, 0, 1);
  await expectHeard(black, 1, 0);

  // White leaves for the entry screen and returns to the game: the options are as White left
  // them, and coming back makes no sound.
  await white.goto('/');
  await expect(pieceOption(white)).not.toBeChecked();
  await expect(clockOption(white)).toBeChecked();
  await white.locator('#returnToGameBtn').click();
  await expect(arbiter(white)).toContainText('Reconnected', { timeout: 60_000 });
  await unlockAudio(white);
  await expectPiece(white, 'e4', 'WHITE_PAWN');
  await expectHeard(white, 0, 0);

  await dragPiece(black, 'e7', 'e5');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectHeard(white, 0, 1);
  await expectHeard(black, 2, 0);
});

test('a player who has not yet touched the game page hears nothing, the game goes on, and after a touch they hear', async ({
  browser,
}) => {
  contexts = [await newListeningContext(browser), await newListeningContext(browser)];
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();
  const gameId = await createFromEntryScreen(white);
  // Black opens the game from a link: no click, no key on any page.
  await black.goto(`/game.html?gameId=${gameId}`);
  await expectStarted(white);
  await expectStarted(black);
  await unlockAudio(white);
  await samplesDecoded(black);
  expect(await audioState(black)).toBe('suspended');

  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectHeard(white, 1, 1);
  // Nothing was started on Black's page, and nothing is waiting to be played later.
  await expectHeard(black, 0, 0);
  expect(await audioState(black)).toBe('suspended');

  await unlockAudio(black);
  await expectHeard(black, 0, 0);
  await dragPiece(black, 'e7', 'e5');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectHeard(black, 1, 1);
  await expectHeard(white, 2, 2);
});

test('a sample that cannot be loaded costs that sound on that board and nothing else', async ({ browser }) => {
  contexts = [await newListeningContext(browser), await newListeningContext(browser)];
  await contexts[0].route('**/sounds/piece.wav', (route) => route.abort());
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();
  const gameId = await createFromEntryScreen(white);
  await joinFromEntryScreen(black, gameId);
  await expectStarted(white);
  await expectStarted(black);
  await unlockAudio(white, ['clock']);
  await unlockAudio(black);

  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
  await expectHeard(white, 0, 1);
  await expectHeard(black, 1, 1);
});

test('where the browser refuses storage, both sounds are on and the creator\'s board is set up', async ({
  browser,
}) => {
  contexts = [await newListeningContext(browser), await newListeningContext(browser)];
  // What a browser with site data blocked does: reading window.localStorage itself throws.
  await contexts[0].addInitScript(() => {
    Object.defineProperty(window, 'localStorage', {
      get() {
        throw new DOMException('The operation is insecure.', 'SecurityError');
      },
    });
  });
  const white = await contexts[0].newPage();
  const black = await contexts[1].newPage();

  await white.goto('/');
  await expect(pieceOption(white)).toBeChecked();
  await expect(clockOption(white)).toBeChecked();
  const gameId = await createFromEntryScreen(white);
  // The whole of the creator's setup ran: the board, the clock labels and the waiting actions.
  await expectPiece(white, 'e1', 'WHITE_KING');
  await expect(white.locator('#bottomClockLabel')).toHaveText('White');
  await expect(white.locator('#abortBtn')).toBeVisible();

  await joinFromEntryScreen(black, gameId);
  await expectStarted(white);
  await expectStarted(black);
  await unlockAudio(white);
  await unlockAudio(black);
  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectBothHeard(white, black, 1, 1);
});

test('a move that ends the game without a clock press, the rematch and its first move', async ({ browser }) => {
  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;

  const move = async (mover: typeof white, other: typeof white, from: string, to: string, piece: string) => {
    await dragPiece(mover, from, to);
    await expectPiece(other, to, piece);
    await pressClock(mover);
    await expect(arbiter(other)).toContainText('Your turn');
  };
  await move(white, black, 'f2', 'f3', 'WHITE_PAWN');
  await move(black, white, 'e7', 'e5', 'BLACK_PAWN');
  await move(white, black, 'g2', 'g4', 'WHITE_PAWN');
  await expectBothHeard(white, black, 3, 3);

  // Checkmate ends the game with the move: the queen is heard, and no clock.
  await dragPiece(black, 'd8', 'h4');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expectPiece(white, 'h4', 'BLACK_QUEEN');
  await expectBothHeard(white, black, 4, 3);

  // The rematch sets up a new board: silent.
  await white.locator('#rematchBtn').click();
  await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await black.locator('#rematchBtn').click();
  await expect(arbiter(white)).toContainText('Rematch started');
  await expect(arbiter(black)).toContainText('Rematch started');
  await expectPiece(white, 'd8', 'BLACK_QUEEN');
  await expectBothHeard(white, black, 4, 3);

  // The colours are swapped: the former Black plays White now.
  await dragPiece(black, 'e2', 'e4');
  await expectPiece(white, 'e4', 'WHITE_PAWN');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectBothHeard(white, black, 5, 4);
});

test('a page that is reloaded comes back silent and hears the next move', async ({ browser }) => {
  const game = await startSoundGame(browser);
  contexts.push(...game.contexts);
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectBothHeard(white, black, 1, 1);

  // White reloads: the server sends the position again, which is no piece set down.
  await white.reload();
  await expect(arbiter(white)).toContainText('Reconnected', { timeout: 60_000 });
  await expectPiece(white, 'e4', 'WHITE_PAWN');
  await unlockAudio(white);
  await expectHeard(white, 0, 0);
  await expectHeard(black, 1, 1);

  await dragPiece(black, 'e7', 'e5');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
  await expectHeard(white, 1, 1);
  await expectHeard(black, 2, 2);
});

test('the two samples are served as audio', async ({ request }) => {
  for (const name of ['piece.wav', 'clock.wav']) {
    const response = await request.get(`/sounds/${name}`);
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toBe('audio/wav');
    expect((await response.body()).subarray(0, 4).toString('latin1')).toBe('RIFF');
  }
});
