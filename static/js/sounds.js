// Sounds of the board: a piece set down on a square, and a clock lever pressed down.
//
// OtbSoundSettings holds the player's two options. They belong to this browser, not to the game:
// the entry screen writes them, the game page reads them, and the server never sees them.
// GameSounds plays the two samples on the game page.

const OtbSoundSettings = {
  KEYS: { piece: 'otbSoundPiece', clock: 'otbSoundClock' },

  /** On unless the player switched it off. Storage that cannot be read means on. */
  isOn(kind) {
    try {
      return localStorage.getItem(this.KEYS[kind]) !== 'off';
    } catch (e) {
      return true;
    }
  },

  set(kind, on) {
    try {
      localStorage.setItem(this.KEYS[kind], on ? 'on' : 'off');
    } catch (e) {
      /* storage unavailable: the option holds for this page only */
    }
  }
};

class GameSounds {
  static FILES = { piece: 'sounds/piece.wav', clock: 'sounds/clock.wav' };

  // A sound asked for while the audio output is still waking up is played if the output is there
  // within this time, and dropped otherwise. Nothing is queued: a sound that comes late is worse
  // than none.
  static LATE_MS = 250;

  constructor() {
    // Read once: the options are set on the entry screen, before the game page is opened.
    this.enabled = { piece: OtbSoundSettings.isOn('piece'), clock: OtbSoundSettings.isOn('clock') };
    this.buffers = {};
    this.context = null;
    this.gestureSeen = false;

    // Nothing here may disturb the game: without audio the game goes on silently.
    if (!this.enabled.piece && !this.enabled.clock) return;
    try {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) return;
      this.context = new AudioContextClass();
    } catch (e) {
      this.context = null;
      return;
    }
    this.listenForGestures();
    for (const kind of Object.keys(GameSounds.FILES)) {
      if (this.enabled[kind]) this.load(kind);
    }
  }

  /** Fetches and decodes one sample. Until it is there, and if it never arrives, that sound is silent. */
  async load(kind) {
    try {
      const response = await fetch(GameSounds.FILES[kind]);
      if (!response.ok) return;
      const data = await response.arrayBuffer();
      this.buffers[kind] = await new Promise((resolve, reject) => {
        // Callback form for older Safari; where a promise is returned as well, the callbacks have
        // already reported its outcome.
        const decoding = this.context.decodeAudioData(data, resolve, reject);
        if (decoding && decoding.catch) decoding.catch(() => {});
      });
    } catch (e) {
      /* silent */
    }
  }

  // A page may not start audio before the player has acted on it, and a phone takes the audio
  // output away again when the page goes to the background. So the output is woken on every
  // gesture that browsers accept for it, for as long as the page lives: a mouse press, the end of
  // a touch or pen contact, a key. The call must be made inside the gesture's own handler.
  listenForGestures() {
    const onGesture = () => {
      this.gestureSeen = true;
      this.wake();
    };
    for (const type of ['pointerdown', 'pointerup', 'mousedown', 'touchend', 'keydown', 'click']) {
      document.addEventListener(type, onGesture, { capture: true, passive: true });
    }
    // Back in the foreground: try at once, so that the opponent's next sound is heard without a
    // touch. Where the browser insists on a gesture, the next one does it.
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && this.gestureSeen) this.wake();
    });
  }

  /** Asks the browser for the audio output if it is not running. Resolves to whether it runs. */
  wake() {
    if (!this.context || this.context.state === 'closed') return Promise.resolve(false);
    if (this.context.state === 'running') return Promise.resolve(true);
    try {
      return this.context.resume().then(() => true, () => false);
    } catch (e) {
      return Promise.resolve(false);
    }
  }

  /** Plays 'piece' or 'clock' now, or not at all. Never throws. */
  play(kind) {
    if (!this.enabled[kind] || !this.context) return;
    const buffer = this.buffers[kind];
    if (!buffer) return;
    if (this.context.state === 'running') {
      this.start(buffer);
      return;
    }
    // Before the player's first gesture the browser gives no audio, and asking only earns a
    // warning: the sound is dropped.
    if (!this.gestureSeen) return;
    const asked = performance.now();
    this.wake().then((running) => {
      if (running && performance.now() - asked <= GameSounds.LATE_MS) this.start(buffer);
    });
  }

  start(buffer) {
    try {
      // A new source for every sound: it starts within milliseconds and two sounds can overlap.
      const source = this.context.createBufferSource();
      source.buffer = buffer;
      source.connect(this.context.destination);
      source.start();
    } catch (e) {
      /* silent */
    }
  }
}
