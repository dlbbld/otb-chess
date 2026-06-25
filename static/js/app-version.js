(function () {
  const versionEls = document.querySelectorAll('[data-app-version]');
  if (!versionEls.length) return;

  fetch('/api/version', { cache: 'no-store' })
    .then(r => r.ok ? r.json() : null)
    .then(data => {
      if (!data || !data.version) return;
      versionEls.forEach(el => {
        el.textContent = 'v' + data.version;
      });
    })
    .catch(() => { /* Version display is optional chrome. */ });
})();
