import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { Browser, BrowserContext, Page, chromium, expect } from '@playwright/test';
import { colorOf } from './app';

/**
 * What a page really started on its audio output, read by an observer the test puts between the
 * page and the browser - not by anything the sounds module says about itself.
 *
 * A start counts as heard only when the source holds a decoded sample, is connected to the output
 * of its own audio context, and that context is running at that moment. Anything else that was
 * started is counted as `unheard` and fails the comparison.
 *
 * Two modes, chosen once per run (see audioMode):
 *  - 'real': the browser's own Web Audio. The observer wraps the two calls every sound goes
 *    through, AudioNode.connect and AudioBufferSourceNode.start.
 *  - 'stand-in': where the browser needs seconds to open the audio output (a virtual output device
 *    can block `new AudioContext()` for 20 s), the page gets a stand-in audio context with the same
 *    rules: suspended until the page has had a real user gesture, samples decoded from the WAV
 *    bytes the page fetched, a source that is connected and started. Gestures, fetches, the module
 *    and the game are real in both modes.
 */
export interface Heard {
  piece: number;
  clock: number;
  unheard: number;
}

interface Start {
  ms: number;
  running: boolean;
  toOutput: boolean;
}

export type AudioMode = 'real' | 'stand-in';

/** Generous: with real audio on a slow output device a game page takes 20 s to open. */
const PAGE_TIMEOUT = 60_000;

/** Storage as a first-time visitor has it: the suite's default switches both sounds off. */
const EMPTY_STORAGE = { cookies: [], origins: [] };

/** Length of a sample file in milliseconds, from its WAV header: the two sounds are told apart by it. */
function sampleMs(name: string): number {
  const bytes = readFileSync(join(process.cwd(), 'static', 'sounds', name));
  return (1000 * bytes.readUInt32LE(40)) / bytes.readUInt32LE(28);
}

const PIECE_MS = sampleMs('piece.wav');
const CLOCK_MS = sampleMs('clock.wav');

let mode: Promise<AudioMode> | undefined;

/**
 * 'real' when the browser opens its audio output at once, 'stand-in' when that takes seconds.
 * OTB_E2E_AUDIO=real or =stand-in decides it by hand.
 */
export function audioMode(): Promise<AudioMode> {
  mode ??= (async () => {
    const forced = process.env.OTB_E2E_AUDIO;
    if (forced === 'real' || forced === 'stand-in') return forced;
    // A browser of its own: if its page is stuck opening the audio output, closing it ends that.
    const probe = await chromium.launch();
    try {
      const page = await probe.newPage();
      const quick = await Promise.race([
        page.evaluate(() => { new AudioContext(); return true; }).catch(() => false),
        new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 3_000)),
      ]);
      return quick ? 'real' : 'stand-in';
    } finally {
      await probe.close();
    }
  })();
  return mode;
}

function observeRealAudio(): void {
  const starts: { ms: number; running: boolean; toOutput: boolean }[] = [];
  (window as any).__audioStarts = starts;
  const outputs = new WeakMap<object, Set<unknown>>();
  const connect = AudioNode.prototype.connect as any;
  (AudioNode.prototype as any).connect = function (target: unknown, ...rest: unknown[]) {
    if (!outputs.has(this)) outputs.set(this, new Set());
    outputs.get(this)!.add(target);
    return connect.call(this, target, ...rest);
  };
  const start = AudioBufferSourceNode.prototype.start;
  AudioBufferSourceNode.prototype.start = function (...args: any[]) {
    const targets = outputs.get(this);
    starts.push({
      ms: this.buffer ? this.buffer.duration * 1000 : 0,
      running: this.context.state === 'running',
      toOutput: !!targets && targets.has(this.context.destination),
    });
    return start.apply(this, args as any);
  };
}

function installStandInAudio(): void {
  const starts: { ms: number; running: boolean; toOutput: boolean }[] = [];
  (window as any).__audioStarts = starts;
  // The browser's own record of whether this page has had a user gesture.
  const gestureSeen = () => (navigator as any).userActivation.hasBeenActive;

  class StandInSource {
    buffer: { duration: number } | null = null;
    targets = new Set<unknown>();
    constructor(public context: StandInAudioContext) {}
    connect(target: unknown) {
      this.targets.add(target);
      return target;
    }
    start() {
      starts.push({
        ms: this.buffer ? this.buffer.duration * 1000 : 0,
        running: this.context.state === 'running',
        toOutput: this.targets.has(this.context.destination),
      });
    }
  }

  class StandInAudioContext {
    destination = {};
    state = gestureSeen() ? 'running' : 'suspended';
    resume() {
      if (this.state === 'closed') return Promise.reject(new Error('closed'));
      if (gestureSeen()) {
        this.state = 'running';
        return Promise.resolve();
      }
      // As in the browser: the request stays open until the page is allowed to play.
      return new Promise<void>(() => {});
    }
    decodeAudioData(data: ArrayBuffer, done?: (buffer: unknown) => void, failed?: (error: unknown) => void) {
      const decoding = new Promise<{ duration: number }>((resolve, reject) => {
        const view = new DataView(data);
        const tag = (at: number) => String.fromCharCode(...new Uint8Array(data, at, 4));
        if (data.byteLength < 44 || tag(0) !== 'RIFF' || tag(8) !== 'WAVE') {
          reject(new Error('not a WAV file'));
          return;
        }
        resolve({ duration: view.getUint32(40, true) / view.getUint32(28, true) });
      });
      decoding.then(done, failed);
      return decoding;
    }
    createBufferSource() {
      return new StandInSource(this);
    }
  }

  (window as any).AudioContext = StandInAudioContext;
  (window as any).webkitAudioContext = undefined;
}

/** Installs the observer on every page of the context, before any script of the page runs. */
export async function observeAudio(context: BrowserContext): Promise<void> {
  await context.addInitScript((await audioMode()) === 'real' ? observeRealAudio : installStandInAudio);
}

/** A browser context of a first-time visitor (no stored options), with the audio observer. */
export async function newListeningContext(browser: Browser): Promise<BrowserContext> {
  const context = await browser.newContext({ storageState: EMPTY_STORAGE });
  await observeAudio(context);
  return context;
}

export async function heard(page: Page): Promise<Heard> {
  const starts: Start[] = await page.evaluate(() => (window as any).__audioStarts ?? []);
  const result: Heard = { piece: 0, clock: 0, unheard: 0 };
  for (const start of starts) {
    if (!start.running || !start.toOutput || start.ms === 0) {
      result.unheard++;
    } else if (Math.abs(start.ms - PIECE_MS) < Math.abs(start.ms - CLOCK_MS)) {
      result.piece++;
    } else {
      result.clock++;
    }
  }
  return result;
}

/**
 * Asserts (with auto-retry) the exact number of piece and clock sounds the page has started since
 * it was loaded. For a sound that must NOT come, first wait for something the same message or event
 * shows on the page - the sound is started in the same step - and then compare.
 */
export async function expectHeard(page: Page, piece: number, clock: number): Promise<void> {
  await expect.poll(() => heard(page), { timeout: 10_000 }).toEqual({ piece, clock, unheard: 0 });
}

/** Both boards at once: every sound is for both players. */
export async function expectBothHeard(a: Page, b: Page, piece: number, clock: number): Promise<void> {
  await expectHeard(a, piece, clock);
  await expectHeard(b, piece, clock);
}

/** The state of the page's audio output: 'running', 'suspended', or 'none' when the page made none. */
export async function audioState(page: Page): Promise<string> {
  return page.evaluate(() => (window as any).game?.sounds?.context?.state ?? 'none');
}

/** Waits until the samples of the given kinds are decoded (default: those the player has switched on). */
export async function samplesDecoded(page: Page, kinds?: string[]): Promise<void> {
  await expect
    .poll(() => page.evaluate((wanted) => {
      const sounds = (window as any).game.sounds;
      return (wanted ?? Object.keys(sounds.enabled).filter((kind) => sounds.enabled[kind]))
        .every((kind: string) => !!sounds.buffers[kind]);
    }, kinds ?? null), { timeout: PAGE_TIMEOUT })
    .toBe(true);
}

/**
 * Makes the page able to play: one real gesture on the game page - a mouse click on an empty corner,
 * which is what a browser asks for before it gives a page its audio output - and then waits until
 * the output runs and the samples are decoded.
 */
export async function unlockAudio(page: Page, kinds?: string[]): Promise<void> {
  await page.mouse.click(4, 4);
  await expect.poll(() => audioState(page), { timeout: PAGE_TIMEOUT }).toBe('running');
  await samplesDecoded(page, kinds);
}

export interface SoundOptions {
  piece?: boolean;
  clock?: boolean;
}

export const pieceOption = (page: Page) => page.getByLabel('Sound for a moved piece');
export const clockOption = (page: Page) => page.getByLabel('Sound for the clock press');

/** Sets the two options on the entry screen as a player does, by clicking the boxes. */
export async function setSoundOptions(page: Page, options: SoundOptions): Promise<void> {
  if (options.piece !== undefined) await pieceOption(page).setChecked(options.piece);
  if (options.clock !== undefined) await clockOption(page).setChecked(options.clock);
}

/** Creates a game through the entry screen (3+0) and returns its code. A FEN decides who plays White. */
export async function createFromEntryScreen(page: Page, options: SoundOptions = {}, fen?: string): Promise<string> {
  await page.goto('/');
  await setSoundOptions(page, options);
  await page.locator('.preset-btn', { hasText: '3+0' }).click();
  if (fen) await page.locator('#startingFen').fill(fen);
  await page.locator('#createGameBtn').click();
  const code = page.locator('.game-code-value');
  await expect(code).toBeVisible({ timeout: PAGE_TIMEOUT });
  return ((await code.textContent()) ?? '').trim();
}

/** Joins a game through the entry screen. */
export async function joinFromEntryScreen(page: Page, gameId: string, options: SoundOptions = {}): Promise<void> {
  await page.goto('/');
  await setSoundOptions(page, options);
  await page.locator('#gameCodeInput').fill(gameId);
  await page.locator('#joinGameBtn').click();
}

export async function expectStarted(page: Page): Promise<void> {
  await expect(page.locator('#arbiterMessage')).toContainText('clock has been started', { timeout: PAGE_TIMEOUT });
}

export interface SoundGame {
  contexts: BrowserContext[];
  creator: Page;
  joiner: Page;
  white: Page;
  black: Page;
  gameId: string;
}

export interface SoundGameSetup {
  creator?: SoundOptions;
  joiner?: SoundOptions;
  fen?: string;
}

/**
 * Two first-time visitors in two isolated browser contexts, each with the audio observer, who
 * create and join through the entry screen. Both game pages are unlocked and ready to play sounds.
 */
export async function startSoundGame(browser: Browser, setup: SoundGameSetup = {}): Promise<SoundGame> {
  const contexts = [await newListeningContext(browser), await newListeningContext(browser)];
  const creator = await contexts[0].newPage();
  const joiner = await contexts[1].newPage();
  const gameId = await createFromEntryScreen(creator, setup.creator, setup.fen);
  await joinFromEntryScreen(joiner, gameId, setup.joiner);
  await expectStarted(creator);
  await expectStarted(joiner);
  const [white, black] = (await colorOf(creator)) === 'white' ? [creator, joiner] : [joiner, creator];
  await unlockAudio(creator);
  await unlockAudio(joiner);
  return { contexts, creator, joiner, white, black, gameId };
}
