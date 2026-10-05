// FuncHole landing. Vanilla JS, no dependencies. Everything here is
// enhancement: the page is complete and readable without it.
(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  /* ---------- Toast + copy ---------- */
  let toastTimer;
  function toast(message) {
    const el = $('#toast');
    el.textContent = message;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2200);
  }

  async function copy(text, message) {
    try {
      await navigator.clipboard.writeText(text);
      toast(message);
    } catch {
      toast('Copy failed. Select the text instead.');
    }
  }

  document.addEventListener('click', (event) => {
    const cmd = event.target.closest('[data-copy-text]');
    if (cmd) return copy(cmd.dataset.copyText, 'Template copied. Add your API key before running it.');

    const need = event.target.closest('.need');
    if (need) {
      const prompt = `Using the FuncHole MCP server: build ${need.dataset.ask} as a working web solution. Create the backend, publish it on my gateway, run a test, and give me the live URL.`;
      copy(prompt, 'Prompt copied. Paste it into your coding agent.');
    }
  });

  /* ---------- Header: scroll state + mobile menu ---------- */
  const header = $('#site-header');
  const menuBtn = $('#menu-btn');
  const nav = $('#primary-nav');
  const onScroll = () => header.toggleAttribute('data-scrolled', scrollY > 8);
  addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  const closeMenu = () => {
    nav.classList.remove('open');
    menuBtn.setAttribute('aria-expanded', 'false');
  };
  menuBtn.addEventListener('click', () => {
    const open = nav.classList.toggle('open');
    menuBtn.setAttribute('aria-expanded', String(open));
  });
  nav.addEventListener('click', (event) => event.target.closest('a') && closeMenu());
  addEventListener('keydown', (event) => event.key === 'Escape' && closeMenu());

  /* ---------- GitHub stars ---------- */
  (async () => {
    let count = null;
    try { count = Number(sessionStorage.getItem('fh-stars')) || null; } catch {}
    if (count === null) {
      try {
        const response = await fetch('https://api.github.com/repos/stoopid-computers/funchole');
        if (!response.ok) return;
        count = (await response.json()).stargazers_count;
        try { sessionStorage.setItem('fh-stars', String(count)); } catch {}
      } catch { return; }
    }
    if (typeof count !== 'number') return;
    const text = count >= 1000 ? `${(count / 1000).toFixed(1).replace(/\.0$/, '')}k` : String(count);
    $$('[data-stars]').forEach((el) => (el.textContent = text));
  })();

  $('#year').textContent = new Date().getFullYear();

  /* ---------- Status chips: "Starting…" -> "Running ✓" ---------- */
  async function runSteps(el, startDelay) {
    const steps = el.dataset.steps.split('|');
    el.classList.remove('is-ok');
    el.classList.add('is-wait');
    el.textContent = steps[0];
    await wait(startDelay);
    for (let i = 1; i < steps.length; i += 1) {
      el.textContent = steps[i];
      if (i < steps.length - 1) await wait(650);
    }
    el.classList.replace('is-wait', 'is-ok');
  }

  function startSteps(root) {
    // Show the first step before the stagger starts so nothing sits on its final state.
    $$('[data-steps]', root).forEach((el, i) => {
      const steps = el.dataset.steps.split('|');
      el.classList.remove('is-ok');
      el.classList.add('is-wait');
      el.textContent = steps[0];
      wait(500 + i * 260).then(() => runSteps(el, 650));
    });
  }

  /* ---------- Scroll-triggered sequences ---------- */
  $$('.chain li').forEach((li, i) => li.style.setProperty('--n', i % 10));

  const playbook = {
    handles: startSteps,
    problem(root) {
      const chips = $$('#pile li', root);
      chips.forEach((chip, i) => setTimeout(() => chip.classList.add('done'), reduceMotion ? 0 : 700 + i * 170));
    },
  };

  const io = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return;
      const section = entry.target;
      io.unobserve(section);
      section.classList.add('play');
      if (!reduceMotion) playbook[section.id]?.(section);
      else if (section.id === 'problem') playbook.problem(section);
    });
  }, { threshold: 0.3 });
  $$('[data-play]').forEach((section) => io.observe(section));

  $('#replay').addEventListener('click', () => {
    const section = $('#journey');
    section.classList.remove('play');
    void section.offsetWidth;
    section.classList.add('play');
  });

  /* ---------- Supported agents: tabs with a connect command each ---------- */
  const AGENTS = {
    claude: {
      name: 'Claude Code',
      hint: 'Run this in your terminal.',
      cmd: 'claude mcp add --transport http funchole https://app.funchole.dev/mcp --header "Authorization: Bearer fh_mcp_..."',
    },
    codex: {
      name: 'Codex',
      hint: 'Run these in your terminal.',
      cmd: 'export FUNCHOLE_MCP_TOKEN=fh_mcp_...\ncodex mcp add funchole --url https://app.funchole.dev/mcp --bearer-token-env-var FUNCHOLE_MCP_TOKEN',
    },
    opencode: {
      name: 'opencode',
      hint: 'Run this in your terminal.',
      cmd: 'opencode mcp add funchole --url https://app.funchole.dev/mcp --header "Authorization=Bearer fh_mcp_..."',
    },
    cursor: {
      name: 'Cursor',
      hint: 'Add a remote MCP server in Cursor’s settings with these details.',
      cmd: 'URL: https://app.funchole.dev/mcp\nHeader: Authorization: Bearer fh_mcp_...',
    },
    antigravity: {
      name: 'Antigravity',
      hint: 'Antigravity has no add command. Put this in its MCP config file, ~/.gemini/config/mcp_config.json.',
      cmd: '{\n  "mcpServers": {\n    "funchole": {\n      "serverUrl": "https://app.funchole.dev/mcp",\n      "headers": { "Authorization": "Bearer fh_mcp_..." }\n    }\n  }\n}',
    },
    puku: {
      name: 'Puku',
      hint: 'Run this in your terminal.',
      cmd: 'puku-cli mcp add funchole --transport http https://app.funchole.dev/mcp -H "Authorization: Bearer fh_mcp_..."',
    },
  };

  const tabs = $$('.agent-tabs [role="tab"]');
  const panel = $('#agent-panel');
  function selectAgent(tab) {
    const agent = AGENTS[tab.dataset.agent];
    tabs.forEach((t) => {
      t.setAttribute('aria-selected', String(t === tab));
      t.tabIndex = t === tab ? 0 : -1;
    });
    panel.setAttribute('aria-labelledby', tab.id);
    $('#agent-hint').textContent = agent.hint;
    const esc = (t) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    $('#agent-cmd').innerHTML = esc(agent.cmd).replace(/fh_mcp_\.\.\./g, '<mark class="ph">fh_mcp_...</mark>');
    $('#agent-copy').dataset.copyText = agent.cmd;
  }
  tabs.forEach((tab, i) => {
    tab.addEventListener('click', () => selectAgent(tab));
    tab.addEventListener('keydown', (event) => {
      const step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      const next = tabs[(i + step + tabs.length) % tabs.length];
      next.focus();
      selectAgent(next);
    });
  });

  /* ---------- Hero headline: the noun cycles through examples ---------- */
  const rot = $('#rot');
  if (!reduceMotion) {
    const nouns = ['booking system', 'customer portal', 'feedback board', 'team dashboard', 'lead form', 'business API', 'event page'];
    let n = 0;
    setInterval(() => {
      if (document.hidden) return;
      n = (n + 1) % nouns.length;
      rot.textContent = nouns[n];
      rot.classList.remove('swap');
      void rot.offsetWidth;
      rot.classList.add('swap');
    }, 2400);
  }

  /* ---------- Hero: five tiny live solutions ---------- */
  const stage = $('#stage');

  // Intro: every window goes "Deploying…" -> LIVE, one after another.
  if (!reduceMotion) {
    $$('.win [data-live]', stage).forEach(async (badge, i) => {
      badge.classList.add('is-wait');
      badge.lastChild.textContent = 'DEPLOYING…';
      await wait(500 + i * 380);
      badge.classList.remove('is-wait');
      badge.lastChild.textContent = 'LIVE';
    });
  }

  // Booking: pick a slot, then book it.
  const slots = $$('.slot:not(:disabled)', stage);
  const bookBtn = $('#book-btn');
  slots.forEach((slot) => {
    slot.setAttribute('aria-pressed', 'false');
    slot.addEventListener('click', () => {
      if (bookBtn.classList.contains('is-done')) return;
      slots.forEach((s) => s.setAttribute('aria-pressed', String(s === slot)));
      bookBtn.disabled = false;
      bookBtn.textContent = `Book ${slot.textContent}`;
    });
  });
  bookBtn.addEventListener('click', () => {
    bookBtn.classList.add('is-done');
    bookBtn.textContent = 'Booked ✓';
    bookBtn.disabled = true;
  });

  // Feedback board: one upvote per idea, click again to take it back.
  $$('.vote', stage).forEach((btn) => {
    const count = $('b', btn);
    const base = Number(count.textContent);
    btn.addEventListener('click', () => {
      const on = btn.getAttribute('aria-pressed') !== 'true';
      btn.setAttribute('aria-pressed', String(on));
      count.textContent = base + (on ? 1 : 0);
    });
  });

  // Customer portal: pay the open invoice.
  $('#pay-btn').addEventListener('click', (event) => {
    const btn = event.currentTarget;
    const tag = document.createElement('span');
    tag.className = 'tag tag-ok';
    tag.textContent = 'Paid ✓';
    btn.replaceWith(tag);
  });

  // Dashboard: a new order now and then.
  if (!reduceMotion) {
    const countEl = $('#orders-count');
    const bar = $('#bar-last');
    let orders = 128;
    let h = 92;
    setInterval(() => {
      if (document.hidden) return;
      orders += 1;
      countEl.textContent = orders;
      h = Math.min(100, h + 1);
      bar.style.setProperty('--h', `${h}%`);
    }, 2600);
  }

  // API: send the request, get a response.
  const sendBtn = $('#send-btn');
  const resp = $('#api-resp');
  const statuses = ['shipped', 'packed', 'delivered'];
  let sent = 0;
  sendBtn.addEventListener('click', async () => {
    sendBtn.disabled = true;
    resp.textContent = 'Sending…';
    await wait(reduceMotion ? 0 : 600);
    const ms = 28 + Math.floor(Math.random() * 30);
    sent += 1;
    resp.innerHTML = `<span class="ok">200 OK</span> · ${ms} ms\n{ "id": 1043, "status": "${statuses[sent % statuses.length]}" }`;
    sendBtn.disabled = false;
  });

  // Desktop: bring a window to front, and drag it by its title bar.
  const desktop = matchMedia('(min-width: 1100px)');
  let z = 10;
  stage.addEventListener('pointerdown', (event) => {
    const win = event.target.closest('.win');
    if (!win || !desktop.matches) return;
    win.style.zIndex = ++z;

    const bar = event.target.closest('.win-bar');
    if (!bar) return;
    const startX = event.clientX;
    const startY = event.clientY;
    const baseX = parseFloat(win.style.getPropertyValue('--dx')) || 0;
    const baseY = parseFloat(win.style.getPropertyValue('--dy')) || 0;
    bar.setPointerCapture(event.pointerId);
    win.classList.add('dragging');

    const move = (e) => {
      win.style.setProperty('--dx', `${baseX + e.clientX - startX}px`);
      win.style.setProperty('--dy', `${baseY + e.clientY - startY}px`);
    };
    const end = () => {
      win.classList.remove('dragging');
      bar.removeEventListener('pointermove', move);
      bar.removeEventListener('pointerup', end);
      bar.removeEventListener('pointercancel', end);
    };
    bar.addEventListener('pointermove', move);
    bar.addEventListener('pointerup', end);
    bar.addEventListener('pointercancel', end);
  });
})();
