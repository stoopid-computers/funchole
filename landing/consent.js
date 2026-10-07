// Cookie consent for funchole.dev. Google Analytics is only loaded after the
// visitor accepts; the choice is one first-party cookie on the parent domain,
// shared with app.funchole.dev (see control-plane-web/lib/consent.ts).
(() => {
  const GA_ID = 'G-L9P40W5X66';
  const COOKIE = 'fh_consent';
  const shared = /(^|\.)funchole\.dev$/.test(location.hostname);

  const read = () => (document.cookie.match(new RegExp(`(?:^|; )${COOKIE}=(granted|denied)`)) || [])[1] || null;
  const write = (value) => {
    const secure = location.protocol === 'https:' ? '; Secure' : '';
    document.cookie = `${COOKIE}=${value}; max-age=31536000; path=/; SameSite=Lax${secure}${shared ? '; domain=.funchole.dev' : ''}`;
  };

  let loaded = false;
  function startAnalytics() {
    window[`ga-disable-${GA_ID}`] = false;
    if (loaded) return;
    loaded = true;
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', GA_ID);
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://www.googletagmanager.com/gtag/js?id=${GA_ID}`;
    document.head.appendChild(script);
  }

  // Withdrawing consent also removes the Google Analytics cookies already set.
  function stopAnalytics() {
    window[`ga-disable-${GA_ID}`] = true;
    const names = document.cookie.split('; ').map((c) => c.split('=')[0]).filter((n) => n === '_ga' || n.startsWith('_ga_'));
    for (const name of names) {
      for (const domain of ['', `; domain=${location.hostname}`, `; domain=.${location.hostname}`, '; domain=.funchole.dev']) {
        document.cookie = `${name}=; max-age=0; path=/${domain}`;
      }
    }
  }

  let banner = null;
  function closeBanner() {
    if (banner) banner.remove();
    banner = null;
  }

  function choose(value) {
    write(value);
    closeBanner();
    if (value === 'granted') startAnalytics();
    else stopAnalytics();
  }

  function openBanner() {
    if (banner) return;
    banner = document.createElement('div');
    banner.className = 'consent';
    banner.setAttribute('role', 'dialog');
    banner.setAttribute('aria-label', 'Cookie consent');
    banner.innerHTML = `
      <p class="consent-title">Help us improve FuncHole?</p>
      <p class="consent-text">We use Google Analytics cookies to see which pages are used. Nothing is loaded unless you accept. <a href="/cookies/">Cookie Policy</a></p>
      <div class="consent-actions">
        <button type="button" class="btn btn-ghost" data-choice="denied">Decline</button>
        <button type="button" class="btn btn-primary" data-choice="granted">Accept</button>
      </div>`;
    banner.addEventListener('click', (event) => {
      const button = event.target.closest('[data-choice]');
      if (button) choose(button.dataset.choice);
    });
    document.body.appendChild(banner);
  }

  document.addEventListener('click', (event) => {
    if (event.target.closest('[data-consent-open]')) openBanner();
  });

  const saved = read();
  if (saved === 'granted') startAnalytics();
  else if (saved === null) openBanner();
})();
