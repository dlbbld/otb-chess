(function () {
  const versionEls = document.querySelectorAll('[data-app-version]');
  if (!versionEls.length) return;

  fetch('/api/version', { cache: 'no-store' })
    .then(r => r.ok ? r.json() : null)
    .then(data => {
      if (!data || !data.version) return;
      versionEls.forEach(el => {
        // A version in development is "0.6.0-beta" and reads "v0.6.0 beta"; a release has no suffix.
        el.textContent = 'v' + data.version.replace('-', ' ');
      });
    })
    .catch(() => { /* Version display is optional chrome. */ });
})();
