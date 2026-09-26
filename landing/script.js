// FuncHole landing page behaviour.
//
// Everything that matters works without the CDN modules: `three` (hero dot
// field) and `motion` (animation) are loaded dynamically, so a blocked CDN
// just means a static page, never a broken one. Every animated mock's markup
// holds its *final* state; the scripts below replay it.

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
const EASE_OUT = [0.16, 1, 0.3, 1];
const SPRING = { type: 'spring', stiffness: 420, damping: 32 };
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const escapeHtml = (text) => text.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);

const CHECK_SVG =
  '<svg class="text-ok" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" aria-hidden="true"><path d="m5 12 5 5L20 7"/></svg>';

let motion = null; // filled in at boot once the module loads

/* ---------------------------------------------------------------------------
 * Small shared pieces
 * ------------------------------------------------------------------------ */

// Roving-tabindex tablist: arrow keys move between tabs, Home/End jump.
function setupTablist(tablist, onSelect) {
  const tabs = $$('[role="tab"]', tablist);
  const select = (tab, { focus = false, user = true } = {}) => {
    tabs.forEach((t) => {
      const active = t === tab;
      t.setAttribute('aria-selected', String(active));
      t.tabIndex = active ? 0 : -1;
    });
    if (focus) tab.focus();
    onSelect(tab, user);
  };
  tablist.addEventListener('click', (event) => {
    const tab = event.target.closest('[role="tab"]');
    if (tab) select(tab);
  });
  tablist.addEventListener('keydown', (event) => {
    const index = tabs.indexOf(document.activeElement);
    if (index < 0) return;
    const moves = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    let next = null;
    if (event.key in moves) next = tabs[(index + moves[event.key] + tabs.length) % tabs.length];
    if (event.key === 'Home') next = tabs[0];
    if (event.key === 'End') next = tabs[tabs.length - 1];
    if (next) {
      event.preventDefault();
      select(next, { focus: true });
    }
  });
  return { tabs, select };
}

// Runs `tick` on an interval only while `el` is on screen, until `stop()`.
function whileVisible(el, intervalMs, tick) {
  let timer = null;
  let stopped = false;
  const start = () => {
    if (!stopped && !timer) timer = setInterval(tick, intervalMs);
  };
  const pause = () => {
    clearInterval(timer);
    timer = null;
  };
  new IntersectionObserver(([entry]) => (entry.isIntersecting ? start() : pause())).observe(el);
  return {
    stop() {
      stopped = true;
      pause();
    },
    restart() {
      pause();
      start();
    },
  };
}

/* ---------------------------------------------------------------------------
 * Toast + copy
 * ------------------------------------------------------------------------ */

let toastTimer = null;
function toast(message) {
  const el = $('#toast');
  el.innerHTML = `${CHECK_SVG}<span>${escapeHtml(message)}</span>`;
  clearTimeout(toastTimer);
  if (motion && !reduceMotion) {
    motion.animate(el, { opacity: [0, 1], y: [16, 0], scale: [0.94, 1] }, { type: 'spring', stiffness: 500, damping: 30 });
  } else {
    el.style.opacity = '1';
  }
  toastTimer = setTimeout(() => {
    if (motion && !reduceMotion) motion.animate(el, { opacity: 0, y: 8 }, { duration: 0.2 });
    else el.style.opacity = '0';
  }, 1800);
}

function setupCopy() {
  document.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-copy], [data-copy-text]');
    if (!button) return;
    // Strip "$ " prompts so the copied text pastes straight into a shell.
    const text = button.dataset.copyText ?? $(button.dataset.copy).textContent.replace(/^\$ /gm, '').trim();
    try {
      await navigator.clipboard.writeText(text);
      toast('Copied to clipboard');
    } catch {
      toast('Copy failed — select the text instead');
    }
  });
}

async function loadStars() {
  const format = (n) => (n >= 1000 ? `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k` : String(n));
  let count = null;
  try {
    count = Number(sessionStorage.getItem('fh-stars')) || null;
  } catch {}
  if (count === null) {
    try {
      const response = await fetch('https://api.github.com/repos/stoopid-computers/funchole');
      if (!response.ok) return;
      count = (await response.json()).stargazers_count;
      try {
        sessionStorage.setItem('fh-stars', String(count));
      } catch {}
    } catch {
      return;
    }
  }
  if (typeof count === 'number') $$('[data-stars]').forEach((el) => (el.textContent = format(count)));
}

// Cursor-following highlight on panels and grid cells.
function setupSpotlights() {
  document.addEventListener(
    'pointermove',
    (event) => {
      const el = event.target.closest?.('.spotlight');
      if (!el) return;
      const rect = el.getBoundingClientRect();
      el.style.setProperty('--x', `${event.clientX - rect.left}px`);
      el.style.setProperty('--y', `${event.clientY - rect.top}px`);
    },
    { passive: true },
  );
}

/* ---------------------------------------------------------------------------
 * 01 · Agents — tabs with a sliding underline, live tool filter
 * ------------------------------------------------------------------------ */

const AGENT_COMMANDS = {
  claude:
    'claude mcp add --transport http funchole https://app.funchole.dev/mcp --header "Authorization: Bearer fh_mcp_..."',
  codex:
    'export FUNCHOLE_MCP_TOKEN=fh_mcp_...\ncodex mcp add funchole --url https://app.funchole.dev/mcp --bearer-token-env-var FUNCHOLE_MCP_TOKEN',
  opencode:
    'opencode mcp add funchole --url https://app.funchole.dev/mcp --header "Authorization=Bearer fh_mcp_..."',
};

function setupAgentTabs() {
  const tablist = $('#agent-tabs');
  const underline = $('#agent-underline');
  const code = $('#agent-cmd-text');

  const place = (tab, animate) => {
    const target = { left: `${tab.offsetLeft}px`, width: `${tab.offsetWidth}px` };
    if (animate && motion && !reduceMotion) motion.animate(underline, target, SPRING);
    else Object.assign(underline.style, target);
  };

  setupTablist(tablist, (tab) => {
    code.textContent = AGENT_COMMANDS[tab.dataset.agent];
    $('#agent-cmd').setAttribute('aria-labelledby', tab.id);
    place(tab, true);
    if (motion && !reduceMotion) motion.animate(code, { opacity: [0, 1] }, { duration: 0.25 });
  });
  underline.style.bottom = '-1px';
  place($('[aria-selected="true"]', tablist), false);
  window.addEventListener('resize', () => place($('[aria-selected="true"]', tablist), false));
}

// The MCP server's tool surface, as exposed by controlplane's *McpTools classes.
const TOOLS = `create_function get_function list_functions update_function delete_function
create_function_version get_function_version list_function_versions submit_function_version_source
get_function_version_source get_function_version_config get_function_version_build_logs deploy_function_version
invoke_function_version set_function_version_env_var set_function_version_secret attach_function_version_database
detach_function_version_database list_function_version_databases get_function_example
create_flow get_flow list_flows update_flow delete_flow create_flow_version get_flow_version list_flow_versions
adopt_flow_version archive_flow_version delete_flow_version create_flow_step list_flow_steps update_flow_step
delete_flow_step get_flow_full_source invoke_flow_version attach_flow_database detach_flow_database
list_flow_databases attach_flow_environment detach_flow_environment list_flow_environments
create_gateway get_gateway list_gateways update_gateway delete_gateway
create_domain get_domain list_domains initiate_domain_verification
create_database get_database list_databases update_database delete_database
create_environment get_environment list_environments update_environment delete_environment
get_environment_config set_environment_env_var set_environment_secret get_invocation`
  .split(/\s+/)
  .filter(Boolean);

const toolGroup = (name) => {
  if (/invoke|invocation/.test(name)) return 'invoke';
  if (/function/.test(name)) return 'function';
  if (/flow/.test(name)) return 'flow';
  if (/gateway|domain/.test(name)) return 'gateway';
  if (/database/.test(name)) return 'database';
  return 'config';
};

function setupToolFilter() {
  const list = $('#tool-list');
  const input = $('#tool-filter');
  const shown = $('#tool-shown');
  $$('[data-tool-count]').forEach((el) => (el.textContent = String(TOOLS.length)));

  const render = () => {
    const query = input.value.trim().toLowerCase().replace(/\s+/g, '_');
    const matches = TOOLS.filter((name) => name.includes(query));
    shown.textContent = String(matches.length);
    list.innerHTML = matches.length
      ? matches
          .map((name) => {
            const label = query
              ? escapeHtml(name).replace(query, `<mark class="rounded-sm bg-accent-soft px-px text-accent">${query}</mark>`)
              : escapeHtml(name);
            return `<li class="flex items-center gap-2 rounded-md px-2.5 py-1.5 text-soft hover:bg-white/[0.04]"><span class="text-faint">ƒ</span><span class="min-w-0 truncate">${label}</span><span class="ml-auto text-[10px] text-faint">${toolGroup(name)}</span></li>`;
          })
          .join('')
      : `<li class="px-2.5 py-6 text-center text-subtle">No tool matches “${escapeHtml(input.value)}”</li>`;
  };
  input.addEventListener('input', render);
  render();
}

/* ---------------------------------------------------------------------------
 * 02 · Flows — clickable steps with source
 * ------------------------------------------------------------------------ */

// A tiny single-pass highlighter: one regex walks the source left to right, so
// a token inside a comment or string is never re-coloured. Good enough for the
// handful of snippets below.
const TOKENS = /(\/\/.*$)|('[^'\n]*'|`[^`\n]*`)|\b(export|async|function|const|return|await|if|new)\b|\b(\d+)\b|\b([a-zA-Z_]\w*)(?=\()/gm;
const TOKEN_CLASSES = ['text-faint italic', 'text-code-str', 'text-code-kw', 'text-code-num', 'text-code-fn'];

function highlight(code) {
  return escapeHtml(code).replace(TOKENS, (match, ...groups) => {
    const kind = groups.slice(0, 5).findIndex((g) => g !== undefined);
    return `<span class="${TOKEN_CLASSES[kind]}">${match}</span>`;
  });
}

const FLOW_STEPS = [
  {
    type: 'MIDDLEWARE',
    name: 'log-request',
    color: 'text-code-kw',
    meta: 'index.mjs · runs first',
    code: `// Each step's return value becomes the next step's input.
export async function handler(input) {
  console.log(input.method, input.path);
  return input;
}`,
  },
  {
    type: 'FUNCTION',
    name: 'parse-todo',
    color: 'text-accent',
    meta: 'index.mjs',
    code: `export async function handler(input) {
  const { title } = JSON.parse(input.body);
  return { title: String(title ?? '').trim() };
}`,
  },
  {
    type: 'FUNCTION',
    name: 'save-todo',
    color: 'text-accent',
    meta: 'index.mjs · db: primary',
    code: `export async function handler(input, context) {
  const pool = context.db('primary');
  const { rows } = await pool.query(
    'INSERT INTO todos(title) VALUES($1) RETURNING *',
    [input.title]);
  return rows[0];
}`,
  },
  {
    type: 'SUB_FLOW',
    name: 'notify-team',
    color: 'text-code-num',
    meta: 'flow · notify-team · v3',
    code: `// SUB_FLOW: another Flow's steps, inlined into this
// invocation's frozen snapshot at request time.

notify-team v3
  1  FUNCTION  format-message
  2  FUNCTION  post-to-webhook`,
  },
  {
    type: 'RESPONSE',
    name: 'created',
    color: 'text-ok',
    meta: 'index.mjs · ends the invocation',
    code: `export async function handler(todo) {
  return { status: 201, body: todo };
}`,
  },
];

function setupFlowSteps() {
  const tablist = $('#step-tabs');
  const code = $('#step-code');
  const meta = $('#step-meta');
  const pulse = $('#step-pulse');

  tablist.insertAdjacentHTML(
    'beforeend',
    FLOW_STEPS.map(
      (step, i) => `
      <button type="button" role="tab" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}" aria-controls="step-code" data-index="${i}"
        class="group relative flex w-full cursor-pointer items-center gap-3 rounded-lg py-2 pr-2 pl-9 text-left transition-colors hover:bg-white/[0.03] aria-selected:bg-white/[0.05]">
        <span class="absolute left-[0.85rem] size-2 -translate-x-1/2 rounded-full border border-line-strong bg-panel transition-colors group-aria-selected:border-fg group-aria-selected:bg-fg"></span>
        <span class="min-w-0">
          <span class="block font-mono text-[10px] tracking-wider ${step.color}">${step.type}</span>
          <span class="block truncate font-mono text-[12px] text-soft group-aria-selected:text-fg">${step.name}</span>
        </span>
      </button>`,
    ).join(''),
  );

  const show = (index, animate) => {
    const step = FLOW_STEPS[index];
    meta.textContent = step.meta;
    code.innerHTML = highlight(step.code);
    const tab = $(`[data-index="${index}"]`, tablist);
    const top = tab.offsetTop + tab.offsetHeight / 2;
    if (animate && motion && !reduceMotion) {
      motion.animate(code, { opacity: [0, 1], x: [6, 0] }, { duration: 0.3, ease: EASE_OUT });
      motion.animate(pulse, { top: `${top}px`, opacity: 1 }, SPRING);
    } else {
      Object.assign(pulse.style, { top: `${top}px`, opacity: '1', transform: 'translate(-50%, -50%)' });
    }
  };

  let auto = null;
  const { tabs, select } = setupTablist(tablist, (tab, user) => {
    if (user) auto?.stop();
    show(Number(tab.dataset.index), true);
  });
  pulse.style.transform = 'translate(-50%, -50%)';
  show(0, false);

  // Walk the pipeline on its own until the visitor takes over.
  if (!reduceMotion) {
    auto = whileVisible(tablist, 2600, () => {
      const current = tabs.findIndex((t) => t.getAttribute('aria-selected') === 'true');
      select(tabs[(current + 1) % tabs.length], { user: false });
    });
  }
}

/* ---------------------------------------------------------------------------
 * 03 · Routing — shapes with an auto-advance progress bar
 * ------------------------------------------------------------------------ */

const ROUTES = [
  {
    url: '/orders',
    pattern: '/orders',
    runtime: 'NODE',
    caption: 'handler(input)',
    event: `{
  <span class="text-code-fn">"method"</span>: <span class="text-code-str">"GET"</span>,
  <span class="text-code-fn">"path"</span>: <span class="text-code-str">"/orders"</span>,
  <span class="text-code-fn">"pathParameters"</span>: {}
}`,
  },
  {
    url: '/api/todos/42',
    pattern: '/api/todos/:id',
    runtime: 'NODE',
    caption: 'handler(input)',
    event: `{
  <span class="text-code-fn">"method"</span>: <span class="text-code-str">"GET"</span>,
  <span class="text-code-fn">"path"</span>: <span class="text-code-str">"/api/todos/42"</span>,
  <span class="text-code-fn">"pathParameters"</span>: { <span class="rounded bg-accent-soft px-1 text-accent">"id": "42"</span> }
}`,
  },
  {
    url: '/app/assets/main.js',
    pattern: '/app/*',
    runtime: 'STATIC',
    caption: 'no handler runs',
    event: `<span class="text-faint">// The gateway serves the file straight from the
// STATIC artifact's cache - no invocation, no
// dispatcher on the request path.</span>

GET /app/assets/main.js  <span class="text-ok">200</span>`,
  },
];

function setupRoutes() {
  const tablist = $('#route-tabs');
  const els = {
    url: $('#route-url'),
    pattern: $('#route-pattern'),
    runtime: $('#route-runtime'),
    caption: $('#route-caption'),
    event: $('#route-event'),
  };
  const CYCLE = 4200;
  let current = -1;
  let progress = null;
  let manual = false;

  const render = (index) => {
    current = index;
    const route = ROUTES[index];
    els.url.textContent = route.url;
    els.pattern.textContent = route.pattern;
    els.runtime.textContent = route.runtime;
    els.caption.textContent = route.caption;
    els.event.innerHTML = route.event;
    if (motion && !reduceMotion) {
      motion.animate(els.url, { opacity: [0, 1], x: [-4, 0] }, { duration: 0.3 });
      motion.animate(els.event, { opacity: [0, 1], y: [6, 0] }, { duration: 0.4, ease: EASE_OUT });
    }
    // Progress bar under the active tab shows when it will advance.
    progress?.stop();
    $$('[data-route-progress]', tablist).forEach((bar) => (bar.style.width = '0'));
    if (!manual && motion && !reduceMotion) {
      const bar = $$('[data-route-progress]', tablist)[index];
      progress = motion.animate(bar, { width: ['0%', '100%'] }, { duration: CYCLE / 1000, ease: 'linear' });
    }
  };

  let auto = null;
  const { tabs, select } = setupTablist(tablist, (tab, user) => {
    if (user) {
      manual = true;
      auto?.stop();
    }
    render(Number(tab.dataset.route));
  });
  render(0);
  if (!reduceMotion && motion) {
    auto = whileVisible(tablist, CYCLE, () => select(tabs[(current + 1) % tabs.length], { user: false }));
  }
}

/* ---------------------------------------------------------------------------
 * 04 · Versions — v4 builds when scrolled into view
 * ------------------------------------------------------------------------ */

function setupVersions() {
  if (!motion || reduceMotion) return;
  const status = $('#v4-status');
  const lines = $$('#v4-log p');
  const chips = $$('#v4-carried .chip');
  const finalStatus = status.outerHTML;

  const play = async () => {
    status.className = 'ml-auto rounded bg-white/[0.06] px-1.5 py-0.5 text-[11px]';
    status.innerHTML = '<span class="shimmer">BUILDING</span>';
    lines.forEach((l) => (l.style.opacity = '0'));
    chips.forEach((c) => (c.style.opacity = '0'));
    for (const chip of chips) {
      await wait(160);
      motion.animate(chip, { opacity: [0, 1], scale: [0.9, 1] }, SPRING);
    }
    for (const line of lines.slice(0, -1)) {
      await wait(650);
      motion.animate(line, { opacity: [0, 1], x: [-4, 0] }, { duration: 0.25 });
    }
    await wait(800);
    motion.animate(lines.at(-1), { opacity: [0, 1] }, { duration: 0.25 });
    status.outerHTML = finalStatus;
    motion.animate($('#v4-status'), { scale: [0.8, 1] }, { type: 'spring', bounce: 0.5, duration: 0.45 });
  };
  motion.inView($('#versions-panel'), () => void play(), { amount: 0.5 });
}

/* ---------------------------------------------------------------------------
 * Header — solid once the page scrolls
 * ------------------------------------------------------------------------ */

function setupHeader() {
  const header = $('#site-header');
  const update = () => header.toggleAttribute('data-scrolled', window.scrollY > 8);
  update();
  window.addEventListener('scroll', update, { passive: true });
}

/* ---------------------------------------------------------------------------
 * Hero — a looping "film": prompt → tool calls → live URL
 * ------------------------------------------------------------------------ */

const SCENES = [
  {
    prompt: 'Build a todo API backed by Postgres and put it at /todos',
    tools: ['create_function', 'submit_function_version_source', 'attach_function_version_database', 'deploy_function_version', 'adopt_flow_version'],
    url: 'gw3f81.funchole.dev/todos',
  },
  {
    prompt: 'Ship my Vite app as the frontend at /app/*',
    tools: ['create_function', 'submit_function_version_source', 'deploy_function_version', 'adopt_flow_version'],
    url: 'gw3f81.funchole.dev/app',
  },
  {
    prompt: 'The deploy failed. Read the build logs and fix it',
    tools: ['get_function_version_build_logs', 'create_function_version', 'submit_function_version_source', 'deploy_function_version'],
    url: 'todos-handler v4 · READY',
  },
];
const SCENE_MS = 9500;

const toolChip = (name, done) =>
  `<li class="inline-flex items-center gap-1.5 rounded-md bg-white/10 px-2 py-1 font-mono text-[11px] text-white/85">${
    done ? '<span class="text-ok">✓</span>' : '<span class="spinner"></span>'
  }${name}</li>`;

function setupHeroScene() {
  const frame = $('#hero-frame');
  const card = $('#scene-card');
  const prompt = $('#scene-prompt');
  const tools = $('#scene-tools');
  const live = $('#scene-live');
  const url = $('#scene-url');
  const index = $('#scene-index');
  const toggle = $('#scene-toggle');
  const progress = $('#scene-progress');

  if (!motion || reduceMotion) {
    toggle.hidden = true;
    return;
  }

  let paused = false;
  let visible = true;
  let bar = null;
  const running = () => !paused && visible && !document.hidden;
  // Waits `ms` of *playing* time: pausing (button, offscreen, hidden tab) freezes the film.
  const hold = async (ms) => {
    let elapsed = 0;
    while (elapsed < ms) {
      await wait(40);
      if (running()) elapsed += 40;
    }
  };

  const syncBar = () => bar && (running() ? bar.play() : bar.pause());
  new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting;
    syncBar();
  }).observe(frame);
  document.addEventListener('visibilitychange', syncBar);
  toggle.addEventListener('click', () => {
    paused = !paused;
    toggle.setAttribute('aria-label', paused ? 'Play demo' : 'Pause demo');
    $('[data-icon-pause]', toggle).classList.toggle('hidden', paused);
    $('[data-icon-play]', toggle).classList.toggle('hidden', !paused);
    syncBar();
  });

  async function play(scene, i) {
    index.textContent = `${String(i + 1).padStart(2, '0')} / ${String(SCENES.length).padStart(2, '0')}`;
    bar = motion.animate(progress, { width: ['0%', '100%'] }, { duration: SCENE_MS / 1000, ease: 'linear' });
    syncBar();

    prompt.textContent = '';
    tools.innerHTML = '';
    live.style.opacity = '0';
    motion.animate([prompt, tools], { opacity: 1 }, { duration: 0.01 });
    const caret = document.createElement('span');
    caret.className = 'caret';
    prompt.append(caret);

    await hold(500);
    for (const char of scene.prompt) {
      caret.before(char);
      await hold(22 + Math.random() * 30);
    }
    await hold(350);
    caret.remove();
    // "Send": the card dips and the glow flares.
    motion.animate(card, { scale: [1, 0.975, 1] }, { duration: 0.45, ease: EASE_OUT });

    for (const name of scene.tools) {
      await hold(260);
      tools.insertAdjacentHTML('beforeend', toolChip(name, false));
      const chip = tools.lastElementChild;
      motion.animate(chip, { opacity: [0, 1], y: [6, 0], scale: [0.9, 1] }, SPRING);
      await hold(380 + Math.random() * 260);
      chip.outerHTML = toolChip(name, true);
    }

    await hold(300);
    url.textContent = scene.url;
    motion.animate(live, { opacity: [0, 1], y: [10, 0], scale: [0.92, 1] }, { type: 'spring', stiffness: 380, damping: 22 });

    await hold(Math.max(1200, SCENE_MS - 5200 - scene.prompt.length * 47));
    await motion.animate([prompt, tools, live], { opacity: 0 }, { duration: 0.35 });
  }

  (async () => {
    await wait(900);
    for (let i = 0; ; i = (i + 1) % SCENES.length) await play(SCENES[i], i);
  })();
}

/* ---------------------------------------------------------------------------
 * Sticky agent panel — the conversation behind whichever card is in view
 * ------------------------------------------------------------------------ */

const CHATS = [
  {
    prompt: 'Add a notify step to todos-api and return 201 when a todo is created',
    reply: "I'll insert a SUB_FLOW step that runs notify-team, end the flow with a RESPONSE step, and publish it as a new version.",
    changes: [
      ['create_flow_version', 'todos-api v2'],
      ['create_flow_step', 'SUB_FLOW notify-team'],
      ['create_flow_step', 'RESPONSE created'],
      ['adopt_flow_version', 'v2 live'],
    ],
  },
  {
    prompt: 'Serve the dashboard at /app/* and the API at /api/todos/:id',
    reply: 'Two flows on the same gateway: a STATIC one that owns /app/*, and the API with an id parameter captured into its input.',
    changes: [
      ['create_flow', 'web-app → /app/*'],
      ['create_flow', 'todos-api → /api/todos/:id'],
      ['get_gateway', 'gw3f81.funchole.dev'],
    ],
  },
  {
    prompt: 'The last deploy failed — can you fix it?',
    reply: "The build log points at a stray brace in index.mjs:18. I'll patch it in a new version — source, secrets, and database carry over.",
    changes: [
      ['get_function_version_build_logs', 'v3'],
      ['create_function_version', 'v4 from v3'],
      ['deploy_function_version', 'READY'],
    ],
  },
  {
    prompt: 'What can you do on FuncHole?',
    reply: 'I can create and deploy functions, compose flows, route them through gateways, attach databases and secrets, and invoke anything to check it works.',
    changes: [
      ['funchole', 'connected'],
      ['tools', `${TOOLS.length} available`],
      ['transport', 'streamable http'],
    ],
  },
];

function chatHtml(chat, { pending = false } = {}) {
  const rows = chat.changes
    .map(
      ([tool, meta]) => `
      <li class="flex items-center gap-2 px-3 py-2" data-change>
        <span class="flex w-3 justify-center" data-change-icon>${pending ? '<span class="spinner"></span>' : '<span class="text-ok">✓</span>'}</span>
        <span class="min-w-0 truncate text-soft">${tool}</span>
        <span class="ml-auto shrink-0 text-subtle">${meta}</span>
      </li>`,
    )
    .join('');
  return `
    <div class="rounded-2xl border border-line bg-panel-2 p-3 text-[13px] leading-snug text-fg" data-chat-part>${escapeHtml(chat.prompt)}</div>
    <p class="px-1.5 text-[12px] text-subtle ${pending ? '' : 'hidden'}" data-chat-part data-thinking><span class="shimmer">Thinking…</span></p>
    <p class="px-1.5 text-[13px] leading-relaxed text-soft" data-chat-part data-reply>${escapeHtml(chat.reply)}</p>
    <div class="overflow-hidden rounded-2xl border border-line bg-panel-2" data-chat-part data-changes>
      <div class="flex items-center justify-between border-b border-line px-3 py-2 text-[12px]"><span class="text-fg">Changes</span><span class="rounded bg-raise px-1.5 py-0.5 text-[11px] text-subtle">Undo</span></div>
      <ul class="font-mono text-[11px]">${rows}</ul>
    </div>`;
}

function setupStickyChat() {
  const chat = $('#chat');
  const cards = $$('[data-card]');
  let active = -1;
  let run = 0;

  const show = async (index) => {
    if (index === active) return;
    active = index;
    const id = ++run;
    const alive = () => id === run;
    const data = CHATS[index];

    if (!motion || reduceMotion) {
      chat.innerHTML = chatHtml(data);
      return;
    }
    chat.innerHTML = chatHtml(data, { pending: true });
    const [promptEl, thinking, reply, changes] = $$('[data-chat-part]', chat);
    [thinking, reply, changes].forEach((el) => (el.style.opacity = '0'));
    $$('[data-change]', chat).forEach((el) => (el.style.opacity = '0'));

    motion.animate(promptEl, { opacity: [0, 1], y: [10, 0] }, { duration: 0.4, ease: EASE_OUT });
    await wait(300);
    if (!alive()) return;
    motion.animate(thinking, { opacity: [0, 1] }, { duration: 0.25 });
    await wait(750);
    if (!alive()) return;
    thinking.style.display = 'none';
    motion.animate(reply, { opacity: [0, 1], y: [6, 0] }, { duration: 0.4, ease: EASE_OUT });
    await wait(350);
    if (!alive()) return;
    motion.animate(changes, { opacity: [0, 1], y: [6, 0] }, { duration: 0.35, ease: EASE_OUT });
    for (const row of $$('[data-change]', chat)) {
      await wait(180);
      if (!alive()) return;
      motion.animate(row, { opacity: [0, 1], x: [-4, 0] }, { duration: 0.25 });
      await wait(320);
      if (!alive()) return;
      $('[data-change-icon]', row).innerHTML = '<span class="text-ok">✓</span>';
    }
  };

  // The card crossing the middle of the viewport owns the panel.
  const observer = new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && show(Number(e.target.dataset.card))),
    { rootMargin: '-45% 0px -45% 0px' },
  );
  cards.forEach((card) => observer.observe(card));
  chat.innerHTML = chatHtml(CHATS[0]);
  active = 0;
}

/* ---------------------------------------------------------------------------
 * Bento bits: live sparkline
 * ------------------------------------------------------------------------ */

function setupSparkline() {
  const spark = $('#spark');
  const p50 = $('#stat-p50');
  const BARS = 32;
  const bar = (h) =>
    `<span class="flex-1 rounded-sm bg-accent/70" style="height:${h}%"></span>`;
  const next = (prev) => Math.max(12, Math.min(100, prev + (Math.random() - 0.45) * 30));
  let h = 45;
  spark.innerHTML = Array.from({ length: BARS }, () => bar((h = next(h)))).join('');
  if (!motion || reduceMotion) return;

  whileVisible(spark, 900, () => {
    spark.firstElementChild.remove();
    spark.insertAdjacentHTML('beforeend', bar((h = next(h))));
    motion.animate(spark.lastElementChild, { scaleY: [0, 1], opacity: [0.3, 1] }, { duration: 0.4, ease: EASE_OUT });
    spark.lastElementChild.style.transformOrigin = 'bottom';
    p50.textContent = `${Math.round(10 + h / 12)}ms`;
  });
}

/* ---------------------------------------------------------------------------
 * "Built in the open" rail
 * ------------------------------------------------------------------------ */

function setupRail() {
  const rail = $('#rail');
  const buttons = $$('[data-scroll]');
  const update = () => {
    const max = rail.scrollWidth - rail.clientWidth - 2;
    buttons.forEach((b) => (b.disabled = b.dataset.scroll === '-1' ? rail.scrollLeft <= 2 : rail.scrollLeft >= max));
  };
  buttons.forEach((b) =>
    b.addEventListener('click', () => rail.scrollBy({ left: Number(b.dataset.scroll) * 316 * 2, behavior: 'smooth' })),
  );
  rail.addEventListener('scroll', update, { passive: true });
  window.addEventListener('resize', update);
  update();
}

/* ---------------------------------------------------------------------------
 * Final CTA — compose a prompt to paste into your agent
 * ------------------------------------------------------------------------ */

const PROMPT_PRESETS = {
  'Todo API with Postgres': 'Build a todo API backed by a Postgres database and put it at /todos',
  'Webhook receiver': 'Build a webhook receiver at /hooks/:source that stores every payload in Postgres',
  'Static portfolio site': 'Build a static portfolio site and serve it at /*',
  'URL shortener': 'Build a URL shortener with a Postgres table and put it at /s/:code',
};

function setupPromptBox() {
  const form = $('#prompt-form');
  const input = $('#prompt-input');
  const agent = $('#prompt-agent');
  const presets = Object.values(PROMPT_PRESETS);

  $$('#prompt-chips button').forEach((chip) =>
    chip.addEventListener('click', () => {
      input.value = PROMPT_PRESETS[chip.textContent.trim()];
      input.focus();
      if (motion && !reduceMotion) motion.animate(input, { opacity: [0.3, 1] }, { duration: 0.35 });
    }),
  );

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const ask = (input.value.trim() || input.placeholder.replace(/…$/, '')).replace(/[.\s]+$/, '');
    const text = `Using the funchole MCP server: ${ask}. Deploy it, wire it into a Flow on my gateway, invoke it once to check it works, and give me the live URL.`;
    try {
      await navigator.clipboard.writeText(text);
      toast(`Prompt copied — paste it into ${agent.value}`);
    } catch {
      toast('Copy failed — select the text instead');
    }
  });

  // Rotate the placeholder through ideas while the box is empty and idle.
  if (reduceMotion) return;
  let i = presets.length - 1;
  whileVisible(form, 3500, () => {
    if (input.value || document.activeElement === input) return;
    i = (i + 1) % presets.length;
    input.placeholder = `${presets[i]}…`;
  });
}

/* ---------------------------------------------------------------------------
 * Architecture pulse
 * ------------------------------------------------------------------------ */

function setupPulse() {
  if (!motion || reduceMotion) return;
  const track = $('[data-pulse]');
  const container = $('#arch');
  const nodes = $$('[data-node]', container);
  const dot = track.firstElementChild;
  const DURATION = 3.2;
  const options = { duration: DURATION, repeat: Infinity, repeatDelay: 0.8 };
  const base = 'rgba(42, 42, 42, 1)';
  const hot = 'rgba(123, 147, 255, 1)';
  let animations = null;

  // Strictly-increasing keyframe offsets around each node's position on the track.
  const glow = (f) => {
    const frames = [[0, f === 0 ? hot : base]];
    if (f - 0.05 > 0) frames.push([f - 0.05, base]);
    if (f > 0) frames.push([f, hot]);
    if (f + 0.16 < 1) frames.push([f + 0.16, base]);
    frames.push([1, f + 0.16 < 1 ? base : hot]);
    return { times: frames.map(([t]) => t), values: frames.map(([, v]) => v) };
  };

  motion.inView(container, () => {
    if (getComputedStyle(track).display === 'none') return;
    if (!animations) {
      animations = [
        motion.animate(track, { x: ['0%', '100%'] }, { ...options, ease: [0.45, 0, 0.55, 1] }),
        motion.animate(dot, { opacity: [0, 1, 1, 0] }, { ...options, ease: 'linear', times: [0, 0.06, 0.94, 1] }),
        ...nodes.map((node, i) => {
          const { times, values } = glow(i / (nodes.length - 1));
          return motion.animate(node, { borderColor: values }, { ...options, ease: 'linear', times });
        }),
      ];
    } else animations.forEach((a) => a.play());
    return () => animations?.forEach((a) => a.pause());
  });
}

/* ---------------------------------------------------------------------------
 * Headline split + reveals
 * ------------------------------------------------------------------------ */

function splitWords() {
  $$('[data-split]').forEach((el) => {
    const words = el.textContent.trim().split(/\s+/);
    el.setAttribute('aria-label', words.join(' '));
    el.innerHTML = words
      .map((w) => `<span class="inline-block" data-word aria-hidden="true">${escapeHtml(w)}</span>`)
      .join(' ');
  });
}

function setupReveals() {
  const { animate, inView, stagger } = motion;

  animate(
    '[data-word]',
    { opacity: [0, 1], y: ['0.35em', '0em'], filter: ['blur(12px)', 'blur(0px)'] },
    { duration: 0.9, delay: stagger(0.06, { startDelay: 0.05 }), ease: EASE_OUT },
  ).then(() => $$('[data-word]').forEach((w) => (w.style.filter = '')));

  animate(
    '[data-hero]',
    { opacity: [0, 1], y: [18, 0] },
    { duration: 0.9, delay: stagger(0.08, { startDelay: 0.35 }), ease: EASE_OUT },
  );

  inView(
    '[data-reveal]',
    (el) => {
      const siblings = $$(':scope > [data-reveal]', el.parentElement);
      const index = Math.max(0, siblings.indexOf(el));
      animate(
        el,
        { opacity: [0, 1], y: [24, 0], filter: ['blur(6px)', 'blur(0px)'] },
        { duration: 0.8, delay: index * 0.1, ease: EASE_OUT },
      ).then(() => (el.style.filter = ''));
    },
    { margin: '0px 0px -12% 0px' },
  );
}

/* ---------------------------------------------------------------------------
 * Hero dot field — a halftone grid; your cursor is the hole.
 * ------------------------------------------------------------------------ */

const FIELD_VERTEX = /* glsl */ `
  uniform float uTime;
  uniform vec2 uRes;
  uniform vec2 uMouse;
  uniform float uPull;
  uniform vec3 uClick;
  uniform float uPR;
  varying float vLight;
  varying float vAlpha;

  // 2D simplex noise (Ashima Arts, MIT).
  vec3 permute(vec3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
  float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    vec2 i = floor(v + dot(v, C.yy));
    vec2 x0 = v - i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = mod(i, 289.0);
    vec3 p = permute(permute(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 a0 = x - floor(x + 0.5);
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
  }

  void main() {
    vec2 p = position.xy;

    // Halftone mask: dense at the side edges, clear behind the headline.
    float nx = abs(p.x / uRes.x - 0.5);
    float edge = smoothstep(0.1, 0.48, nx);
    float n = snoise(p * 0.0045 + vec2(uTime * 0.04, -uTime * 0.03)) * 0.5 + 0.5;
    float vfade = 1.0 - smoothstep(0.5, 1.0, p.y / uRes.y);
    float base = edge * smoothstep(0.25, 0.85, n) * vfade;

    // Gravity well around the cursor: dots are pulled in and lit up...
    vec2 d = uMouse - p;
    float dist = length(d);
    float well = exp(-(dist * dist) / (180.0 * 180.0)) * uPull;
    p += d * 0.32 * well;
    // ...and swallowed at the centre.
    float horizon = mix(1.0, smoothstep(6.0, 34.0, dist), uPull);

    // Click shockwave.
    float age = uTime - uClick.z;
    vec2 dc = p - uClick.xy;
    float dcl = length(dc);
    float wave = exp(-pow((dcl - age * 520.0) / 36.0, 2.0)) * exp(-age * 1.6) * step(0.0, age);
    p += (dcl > 0.0 ? dc / dcl : vec2(0.0)) * wave * 16.0;

    vLight = clamp(well * 1.2 + wave, 0.0, 1.0);
    vAlpha = clamp(base * 0.55 + vLight * 0.85, 0.0, 1.0) * horizon;
    gl_PointSize = (1.3 + 1.7 * max(base, vLight)) * uPR;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 0.0, 1.0);
  }
`;

const FIELD_FRAGMENT = /* glsl */ `
  varying float vLight;
  varying float vAlpha;
  void main() {
    float d = length(gl_PointCoord - 0.5);
    float a = smoothstep(0.5, 0.2, d) * vAlpha;
    vec3 grey = vec3(0.55, 0.64, 1.0);
    vec3 lit = vec3(0.92, 0.95, 1.0);
    gl_FragColor = vec4(mix(grey, lit, vLight), a);
  }
`;

function initField(THREE) {
  const canvas = $('#field');
  const wrap = canvas.parentElement;
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: false });
  } catch {
    return; // No WebGL: the hero is simply plain black.
  }
  const pr = Math.min(window.devicePixelRatio || 1, 2);
  renderer.setPixelRatio(pr);
  renderer.setClearColor(0x000000, 0);

  const scene = new THREE.Scene();
  const camera = new THREE.OrthographicCamera(0, 1, 0, 1, -1, 1);
  const uniforms = {
    uTime: { value: 0 },
    uRes: { value: new THREE.Vector2(1, 1) },
    uMouse: { value: new THREE.Vector2(-9999, -9999) },
    uPull: { value: 0 },
    uClick: { value: new THREE.Vector3(-9999, -9999, -99) },
    uPR: { value: pr },
  };
  const material = new THREE.ShaderMaterial({
    uniforms,
    vertexShader: FIELD_VERTEX,
    fragmentShader: FIELD_FRAGMENT,
    transparent: true,
    depthTest: false,
  });
  let points = null;

  const build = () => {
    const width = wrap.clientWidth;
    const height = wrap.clientHeight;
    if (!width || !height) return;
    renderer.setSize(width, height, false);
    Object.assign(camera, { left: 0, right: width, top: 0, bottom: height });
    camera.updateProjectionMatrix();
    uniforms.uRes.value.set(width, height);

    const gap = width < 640 ? 15 : 17;
    const cols = Math.ceil(width / gap) + 1;
    const rows = Math.ceil(height / gap) + 1;
    const positions = new Float32Array(cols * rows * 3);
    let i = 0;
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < cols; x++) {
        positions[i++] = x * gap + (width % gap) / 2;
        positions[i++] = y * gap;
        positions[i++] = 0;
      }
    }
    if (points) {
      points.geometry.dispose();
      scene.remove(points);
    }
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3));
    points = new THREE.Points(geometry, material);
    points.frustumCulled = false;
    scene.add(points);
    renderer.render(scene, camera);
  };
  new ResizeObserver(build).observe(wrap);
  build();

  if (reduceMotion) {
    uniforms.uTime.value = 12;
    renderer.render(scene, camera);
    canvas.classList.add('is-ready');
    return;
  }

  const target = { x: -9999, y: -9999, pull: 0 };
  let elapsed = 0;

  const local = (event) => {
    const rect = wrap.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top, inside: event.clientY >= rect.top && event.clientY <= rect.bottom };
  };
  window.addEventListener(
    'pointermove',
    (event) => {
      const { x, y, inside } = local(event);
      if (target.pull === 0 && inside) uniforms.uMouse.value.set(x, y); // no swoop-in from far away
      Object.assign(target, { x, y, pull: inside ? 1 : 0 });
    },
    { passive: true },
  );
  document.documentElement.addEventListener('pointerleave', () => (target.pull = 0));
  window.addEventListener(
    'pointerdown',
    (event) => {
      if (event.target.closest('a, button, input, #scene-card')) return;
      const { x, y, inside } = local(event);
      if (inside) uniforms.uClick.value.set(x, y, elapsed);
    },
    { passive: true },
  );

  let running = false;
  let onScreen = true;
  let last = 0;
  const frame = (now) => {
    if (!running) return;
    const dt = Math.min((now - last) / 1000, 0.05);
    last = now;
    elapsed += dt;
    uniforms.uTime.value = elapsed;
    const mouse = uniforms.uMouse.value;
    mouse.x += (target.x - mouse.x) * 0.14;
    mouse.y += (target.y - mouse.y) * 0.14;
    uniforms.uPull.value += (target.pull - uniforms.uPull.value) * 0.06;
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  };
  const sync = () => {
    const should = onScreen && !document.hidden;
    if (should && !running) {
      running = true;
      last = performance.now();
      requestAnimationFrame(frame);
    } else if (!should) running = false;
  };
  new IntersectionObserver(([entry]) => {
    onScreen = entry.isIntersecting;
    sync();
  }).observe(wrap);
  document.addEventListener('visibilitychange', sync);
  sync();
  requestAnimationFrame(() => canvas.classList.add('is-ready'));
}

/* ---------------------------------------------------------------------------
 * Boot
 * ------------------------------------------------------------------------ */

setupCopy();
setupSpotlights();
setupToolFilter();
loadStars();

import('three')
  .then(initField)
  .catch(() => {});

try {
  motion = await import('motion');
} catch {
  motion = null;
}

const root = document.documentElement;
// If the fallback timer already revealed everything, skip entrance motion so
// content doesn't flash back to invisible.
const canAnimate = Boolean(motion) && root.classList.contains('js') && !reduceMotion;
window.__landingReady = true;
if (!canAnimate) {
  root.classList.remove('js');
  motion = reduceMotion ? motion : null;
}

$('#year').textContent = String(new Date().getFullYear());
if (canAnimate) splitWords();
setupHeader();
setupAgentTabs();
setupFlowSteps();
setupRoutes();
setupVersions();
setupHeroScene();
setupStickyChat();
setupSparkline();
setupRail();
setupPromptBox();
setupPulse();
if (canAnimate) setupReveals();
