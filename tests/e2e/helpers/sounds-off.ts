/**
 * Browser storage of a player who switched both sounds off on the entry screen of the server at
 * `origin`. Every test except sounds.spec.ts starts like this: the suite is not about sounds, and a
 * game page with sounds opens the audio output - on a machine where that is slow (a virtual output
 * device can take 20 s), every page of every test would wait for it.
 *
 * playwright.config.ts applies it for the suite's own server. A test that starts a server of its
 * own has another origin and must pass it to browser.newContext({ storageState: soundsOff(origin) }).
 */
export function soundsOff(origin: string) {
  return {
    cookies: [],
    origins: [{
      origin,
      localStorage: [
        { name: 'otbSoundPiece', value: 'off' },
        { name: 'otbSoundClock', value: 'off' },
      ],
    }],
  };
}
