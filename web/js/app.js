import { TOOLS, CATEGORIES, HINTS, OCR_LANGS, byId } from './tools.js';
import { h, icon, iconBtn, toolIcon, toast, humanSize, pickFiles, download, share, openFile, dialog } from './ui.js';
import { run } from './runner.js';
import * as P from './pdfops.js';

const app = document.getElementById('app');
const store = { get: (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } }, set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } } };
const S = { screen: 'home', tool: null, files: [], opts: {}, running: false, progress: -1, ptext: '', error: null, result: null, carried: [], query: '', cat: null };

// ---------- theme ----------
export function applyTheme(mode) {
  store.set('theme', mode);
  if (mode === 'system') delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = mode;
  document.querySelector('meta[name=theme-color]').content = isDark() ? '#101217' : '#F6F7FA';
}
const isDark = () => { const t = document.documentElement.dataset.theme; return t ? t === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches; };
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (store.get('theme', 'system') === 'system') render(); });

// ---------- navigation ----------
function go(screen, extra = {}, push = true) {
  Object.assign(S, { screen, ...extra });
  if (push) history.pushState({ screen }, '');
  render(); window.scrollTo(0, 0);
}
window.addEventListener('popstate', () => {
  if (S.running) { history.pushState({ screen: S.screen }, ''); return; }
  if (S.screen === 'done') { S.screen = 'form'; } else if (S.screen === 'editor' || S.screen === 'organize') S.screen = 'form'; else S.screen = 'home';
  render();
});

function openTool(t) {
  S.tool = t; S.files = []; S.error = null; S.result = null; S.opts = {};
  for (const o of t.options ?? []) S.opts[o.key] = o.pref ? store.get(o.pref, o.def) : (o.def ?? (o.type === 'toggle' ? false : ''));
  const usable = S.carried.filter((f) => accepts(t, f));
  if (usable.length) { addFiles(t.input.multiple ? usable : usable.slice(0, 1)); S.carried = []; }
  closeDrawer(); go('form');
}
function accepts(t, f) {
  const ext = '.' + f.name.split('.').pop().toLowerCase();
  return t.input.accept.split(',').some((a) => a === ext || (a.endsWith('/*') && f.type.startsWith(a.slice(0, -1))) || a === f.type);
}

async function addFiles(list) {
  const t = S.tool; if (!list.length) return;
  if (!t.input.multiple) S.files = [];
  for (const file of list) {
    const entry = { file, pages: null, locked: false };
    S.files.push(entry);
    if (/\.pdf$/i.test(file.name)) {
      (async () => {
        try { const b = new Uint8Array(await file.arrayBuffer()); entry.pages = await P.pageCount(b); } catch (e) { entry.locked = !!e.locked; entry.err = e.locked ? null : e.message; }
        if (S.screen === 'form') render();
      })();
    }
  }
  if (t.input.max) S.files = S.files.slice(-t.input.max);
  render();
}

// ---------- drawer ----------
const drawer = document.getElementById('drawer'), scrim = document.getElementById('drawer-scrim');
const openCats = new Set();
function openDrawer() { renderDrawer(); drawer.hidden = false; scrim.hidden = false; requestAnimationFrame(() => drawer.classList.add('open')); }
function closeDrawer() { drawer.classList.remove('open'); scrim.hidden = true; setTimeout(() => { if (!drawer.classList.contains('open')) drawer.hidden = true; }, 250); }
scrim.addEventListener('click', closeDrawer);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && drawer.classList.contains('open')) closeDrawer(); });
function renderDrawer() {
  const mode = store.get('theme', 'system');
  drawer.replaceChildren(
    h('div', { style: { padding: '0 12px 12px' } }, h('h2', {}, 'PDF Master'), h('div', { class: 'small muted' }, `${TOOLS.length} tools in ${CATEGORIES.length} groups`)),
    h('div', { class: 'label', style: { margin: '6px 12px 8px' } }, 'Tools'),
    ...CATEGORIES.map((c) => {
      const tools = TOOLS.filter((t) => t.cat === c.id); const open = openCats.has(c.id);
      return h('div', {},
        h('button', { class: 'dcat', 'data-cat': c.id, 'aria-expanded': open, onclick: () => { open ? openCats.delete(c.id) : openCats.add(c.id); renderDrawer(); } },
          toolIcon(c.icon, c.tint, 32), h('b', { class: 'grow', style: { fontWeight: 600 } }, c.title), h('span', { class: 'count' }, tools.length), icon(open ? 'up' : 'down')),
        open && h('div', {}, ...tools.map((t) => h('button', { class: 'ditem', onclick: () => openTool(t) }, h('span', { style: { color: c.tint } }, icon(t.id, 20)),
          h('div', { class: 'grow' }, h('div', {}, t.title), h('div', { class: 'small muted name' }, t.sub))))));
    }),
    h('hr', { style: { border: 0, borderTop: '1px solid var(--line)', margin: '14px 0' } }),
    h('div', { class: 'label', style: { margin: '0 12px 8px' } }, 'Appearance'),
    h('div', { class: 'segbtns' }, ...[['light', 'Light', 'sun'], ['dark', 'Dark', 'moon'], ['system', 'System', 'phone']].map(([k, label, ic]) =>
      h('button', { class: mode === k ? 'on' : '', onclick: () => { applyTheme(k); renderDrawer(); render(); } }, icon(ic, 16), label))),
    h('div', { class: 'label', style: { margin: '20px 12px 8px' } }, 'OCR language'),
    h('div', { style: { padding: '0 12px' } }, h('select', { class: 'field', onchange: (e) => store.set('ocrLang', e.target.value) }, ...OCR_LANGS.map(([c, n]) => h('option', { value: c, selected: store.get('ocrLang', 'eng') === c }, n)))),
    h('div', { class: 'small muted', style: { padding: '6px 12px 0' } }, 'Used by OCR, Scan and Edit text.'),
    h('hr', { style: { border: 0, borderTop: '1px solid var(--line)', margin: '14px 0' } }),
    h('div', { class: 'row small muted', style: { padding: '0 12px' } }, icon('shield', 18), 'Files are processed on your device and never uploaded (except text you choose to translate).'),
  );
}

// ---------- screens ----------
function render() {
  if (S.screen === 'home') return renderHome();
  if (S.screen === 'form') return renderForm();
  if (S.screen === 'done') return renderDone();
  if (S.screen === 'editor') return import('./editor.js').then((m) => m.mountEditor(app, ctx()));
  if (S.screen === 'organize') return import('./organize.js').then((m) => m.mountOrganizer(app, ctx()));
}
const ctx = () => ({ S, tool: S.tool, file: S.files[0]?.file, back: () => history.back(), runJob, h, progress: (p, t) => setProgress(p, t), ocrLang: () => store.get('ocrLang', 'eng'), setOcrLang: (c) => store.set('ocrLang', c), renderBusy: busyPanel });

let deferredInstall = null;
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferredInstall = e; if (S.screen === 'home') render(); });
const standalone = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone;
const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

function renderHome() {
  const q = S.query.trim().toLowerCase();
  const visible = TOOLS.filter((t) => (!S.cat || t.cat === S.cat) && (!q || (t.title + ' ' + t.sub + ' ' + CATEGORIES.find((c) => c.id === t.cat).title).toLowerCase().includes(q)));
  const card = (t) => { const c = CATEGORIES.find((x) => x.id === t.cat); return h('button', { class: 'tool', onclick: () => openTool(t) }, toolIcon(t.id, c.tint), h('b', {}, t.title), h('span', {}, t.sub)); };
  const grouped = !S.cat && !q;
  const search = h('input', { placeholder: 'Search tools', value: S.query, 'aria-label': 'Search tools', oninput: (e) => { S.query = e.target.value; renderHome(); const i = app.querySelector('.search input'); i.focus(); i.setSelectionRange(i.value.length, i.value.length); } });
  app.replaceChildren(h('div', { class: 'wrap' },
    h('div', { class: 'row', style: { marginTop: '4px' } },
      iconBtn('menu', 'Menu', openDrawer), h('h1', { class: 'grow' }, 'PDF Master'),
      iconBtn(isDark() ? 'sun' : 'moon', isDark() ? 'Switch to light theme' : 'Switch to dark theme', () => { applyTheme(isDark() ? 'light' : 'dark'); render(); })),
    h('div', { class: 'row small muted', style: { margin: '2px 0 0 54px' } }, icon('shield', 14), 'Every PDF tool. Files stay on your device.'),
    !standalone() && isIOS && h('div', { class: 'install' }, icon('share'), h('div', {}, h('b', {}, 'Install on iPhone: '), 'tap the Share button, then “Add to Home Screen”.')),
    !standalone() && deferredInstall && h('div', { class: 'install' }, icon('download'), h('div', { class: 'grow' }, 'Install PDF Master as an app'), h('button', { class: 'btn primary', style: { padding: '8px 14px' }, onclick: async () => { deferredInstall.prompt(); deferredInstall = null; render(); } }, 'Install')),
    S.carried.length > 0 && h('div', { class: 'install' }, icon('pdf'), h('div', { class: 'grow' }, h('b', {}, S.carried.length === 1 ? S.carried[0].name : `${S.carried.length} files ready`), h('div', { class: 'small' }, 'Pick a tool to use it')), iconBtn('close', 'Dismiss', () => { S.carried = []; render(); })),
    h('label', { class: 'search' }, icon('search'), search, S.query && iconBtn('close', 'Clear', () => { S.query = ''; render(); })),
    h('div', { class: 'chips' }, h('button', { class: 'chip' + (!S.cat ? ' on' : ''), onclick: () => { S.cat = null; render(); } }, 'All'),
      ...CATEGORIES.map((c) => h('button', { class: 'chip' + (S.cat === c.id ? ' on' : ''), onclick: () => { S.cat = S.cat === c.id ? null : c.id; render(); } }, c.title))),
    !visible.length && h('p', { class: 'muted', style: { padding: '30px 4px' } }, `No tools match “${S.query}”`),
    ...(grouped ? CATEGORIES.map((c) => [h('div', { class: 'label' }, c.title), h('div', { class: 'grid' }, ...visible.filter((t) => t.cat === c.id).map(card))]).flat()
      : [h('div', { class: 'grid', style: { marginTop: '14px' } }, ...visible.map(card))]),
  ));
}

function topbar(title, actions = []) { return h('div', { class: 'topbar' }, iconBtn('back', 'Back', () => history.back()), h('h2', {}, title), ...actions); }

function busyPanel() {
  return h('div', { class: 'card', style: { padding: '16px' } },
    h('div', { class: 'row', style: { marginBottom: '10px' } }, h('b', { class: 'grow', style: { fontWeight: 600 } }, S.ptext || 'Working…'), S.progress >= 0 && h('b', { style: { color: 'var(--primary)' } }, Math.round(S.progress * 100) + '%')),
    h('div', { class: 'progress' + (S.progress < 0 ? ' indet' : '') }, h('i', { style: { width: S.progress >= 0 ? (S.progress * 100) + '%' : null } })));
}

function renderForm() {
  const t = S.tool; const c = CATEGORIES.find((x) => x.id === t.cat); const input = t.input;
  const ready = input.min === 0 ? (S.files.length > 0 || (S.opts.url ?? '').trim()) : S.files.length >= (input.min ?? 1);
  const pick = async () => addFiles(await pickFiles(input.accept, input.multiple, false));
  const camera = async () => addFiles(await pickFiles('image/*', false, true));
  const drop = h('button', { class: 'drop', onclick: pick }, h('div', {}, icon('upload', 34), h('div', { style: { marginTop: '8px' } }, `Select ${input.noun}`),
    input.min > 1 && h('div', { class: 'small muted', style: { fontWeight: 400 } }, t.id === 'compare' ? 'Original first, then the changed version' : `Choose ${input.min} or more`)));
  drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
  drop.addEventListener('dragleave', () => drop.classList.remove('over'));
  drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); addFiles([...e.dataTransfer.files]); });
  const action = async () => {
    if (t.mode === 'editor' || t.mode === 'organize') {
      const f = S.files[0]; if (!f) return; if (f.locked) { S.error = 'This PDF is password protected. Unlock it first.'; return render(); }
      go(t.mode === 'editor' ? 'editor' : 'organize'); return;
    }
    if (!t.allowLocked && S.files.some((f) => f.locked)) { S.error = `“${S.files.find((f) => f.locked).file.name}” is password protected. Unlock it first.`; return render(); }
    if (t.id === 'ocr' || (t.id === 'scan' && S.opts.ocr)) store.set('ocrLang', S.opts.lang);
    runJob(() => run(t, S.files.map((f) => f.file), S.opts, setProgress));
  };
  app.replaceChildren(topbar(t.title), h('div', { class: 'page' },
    h('div', { class: 'row' }, toolIcon(t.id, c.tint, 48), h('div', { class: 'muted' }, t.sub)),
    S.error && h('div', { class: 'error' }, S.error),
    t.id === 'html2pdf' && optionsCard(t.options.filter((o) => o.key === 'url')),
    !S.files.length && (t.id === 'html2pdf' ? h('button', { class: 'btn outline', onclick: pick }, icon('upload'), 'Or choose an HTML file') : drop),
    t.id === 'scan' && h('button', { class: 'btn tonal', onclick: camera }, icon('scan'), S.files.length ? 'Take another photo' : 'Take a photo'),
    ...S.files.map((f, i) => fileRow(f, i)),
    S.files.length > 0 && input.multiple && !(input.max && S.files.length >= input.max) && h('button', { class: 'btn outline', onclick: pick }, icon('plus'), t.id === 'compare' ? 'Add the second PDF' : 'Add more files'),
    t.options && optionsCard(t.options.filter((o) => !(t.id === 'html2pdf' && o.key === 'url'))),
    HINTS[t.id] && h('div', { class: 'small muted', style: { padding: '0 4px' } }, HINTS[t.id]),
  ), h('div', { class: 'bottom' }, S.running ? busyPanel() : h('button', { class: 'btn primary block', disabled: !ready, onclick: action }, t.action)));
}

function fileRow(f, i) {
  const t = S.tool; const meta = [humanSize(f.file.size), f.pages != null && `${f.pages} page${f.pages === 1 ? '' : 's'}`, f.locked && 'Password protected', f.err].filter(Boolean).join(' · ');
  return h('div', { class: 'card file' },
    h('div', { class: 'badge' }, f.locked ? icon('lock', 18) : f.file.name.split('.').pop().toUpperCase().slice(0, 4)),
    h('div', { class: 'grow' }, h('div', { class: 'name' }, f.file.name), h('div', { class: 'small muted' }, meta)),
    t.reorder && S.files.length > 1 && iconBtn('up', 'Move up', () => { if (i > 0) { [S.files[i - 1], S.files[i]] = [S.files[i], S.files[i - 1]]; render(); } }, { disabled: i === 0 }),
    t.reorder && S.files.length > 1 && iconBtn('down', 'Move down', () => { if (i < S.files.length - 1) { [S.files[i + 1], S.files[i]] = [S.files[i], S.files[i + 1]]; render(); } }, { disabled: i === S.files.length - 1 }),
    iconBtn('close', 'Remove', () => { S.files.splice(i, 1); render(); }));
}

function optionsCard(opts) {
  const o = S.opts; const vis = opts.filter((x) => !x.when || x.when(o)); if (!vis.length) return null;
  const rerender = () => { const y = window.scrollY; render(); window.scrollTo(0, y); };
  return h('div', { class: 'card opts' }, ...vis.map((x) => {
    if (x.type === 'choice') return h('div', { class: 'opt' }, h('div', { class: 't' }, x.label), h('div', { class: 'seg' }, ...x.choices.map(([k, label]) => h('button', { class: 'chip' + (o[x.key] === k ? ' on' : ''), onclick: () => { o[x.key] = k; rerender(); } }, label))));
    if (x.type === 'select') return h('div', { class: 'opt' }, h('div', { class: 't' }, x.label), h('select', { class: 'field', onchange: (e) => { o[x.key] = e.target.value; } }, ...x.choices().map(([k, label]) => h('option', { value: k, selected: o[x.key] === k }, label))));
    if (x.type === 'toggle') return h('label', { class: 'switch' }, h('span', { class: 'grow' }, x.label), h('input', { type: 'checkbox', checked: !!o[x.key], onchange: (e) => { o[x.key] = e.target.checked; rerender(); } }));
    if (x.type === 'slider') return h('div', { class: 'opt' }, h('div', { class: 'row t' }, h('span', { class: 'grow' }, x.label), h('span', { style: { color: 'var(--primary)' }, id: 'v_' + x.key }, `${o[x.key]}${x.suffix ?? ''}`)),
      h('input', { type: 'range', min: x.min, max: x.max, value: o[x.key], oninput: (e) => { o[x.key] = +e.target.value; document.getElementById('v_' + x.key).textContent = `${o[x.key]}${x.suffix ?? ''}`; } }));
    if (x.type === 'image') return h('button', { class: 'btn outline', onclick: async () => { const [f] = await pickFiles('image/png,image/jpeg', false); if (f) { o[x.key] = f; rerender(); } } }, icon('image'), o[x.key]?.name ?? 'Choose image');
    return h('div', { class: 'opt' }, h('div', { class: 't' }, x.label), h('input', { class: 'field', type: x.type === 'password' ? 'password' : x.type === 'number' ? 'number' : x.key === 'url' ? 'url' : 'text', inputmode: x.type === 'number' ? 'numeric' : null, placeholder: x.hint ?? '', value: o[x.key] ?? '', autocomplete: x.type === 'password' ? 'new-password' : 'off', oninput: (e) => { o[x.key] = e.target.value; if (x.key === 'url') { const b = app.querySelector('.bottom .btn'); if (b) b.disabled = !(S.files.length || e.target.value.trim()); } } }));
  }));
}

let busyOverlay = null;
function setProgress(p, text) {
  S.progress = p; if (text) S.ptext = text;
  if (busyOverlay) busyOverlay.replaceChildren(busyPanel());
  else { const bar = app.querySelector('.bottom'); if (bar && S.running && S.screen === 'form') bar.replaceChildren(busyPanel()); }
}

/** Runs a job, then shows the result screen. Editor/organizer keep their state while it runs. */
async function runJob(fn) {
  if (S.running) return;
  S.running = true; S.error = null; S.progress = -1; S.ptext = 'Working…';
  if (S.screen === 'form') render();
  else { busyOverlay = h('div', { class: 'bottom', style: { zIndex: 15 } }, busyPanel()); document.body.append(busyOverlay); }
  try {
    const result = await fn();
    S.running = false; busyOverlay?.remove(); busyOverlay = null;
    go('done', { result });
  } catch (e) {
    console.error(e); S.running = false; busyOverlay?.remove(); busyOverlay = null;
    const msg = e?.message || String(e);
    if (S.screen === 'form') { S.error = msg; render(); } else toast(msg, 6000);
  }
}

function renderDone() {
  const r = S.result; const t = S.tool;
  app.replaceChildren(topbar(t.title, [iconBtn('home', 'All tools', () => go('home'))]),
    h('div', { class: 'page' },
      h('div', { style: { textAlign: 'center', padding: '6px 0 10px' } }, h('div', { class: 'done-mark' }, icon('check', 34)), h('h1', {}, 'Done'), h('p', { class: 'muted', style: { margin: '6px 0 0' } }, r.message)),
      ...r.outputs.map((o) => h('div', { class: 'card', style: { padding: '16px', display: 'flex', flexDirection: 'column', gap: '12px' } },
        h('div', { class: 'row' }, h('div', { class: 'badge', style: { background: 'var(--primary-soft)', color: 'var(--on-primary-soft)' } }, o.name.split('.').pop().toUpperCase()), h('div', { class: 'grow' }, h('div', { class: 'name', style: { fontWeight: 600 } }, o.name), h('div', { class: 'small muted' }, humanSize(o.blob.size)))),
        h('button', { class: 'btn primary block', onclick: () => download(o.name, o.blob) }, icon('download'), 'Download'),
        h('div', { class: 'row' }, h('button', { class: 'btn tonal grow', onclick: () => share(o.name, o.blob) }, icon('share'), 'Share / Save to Files'), h('button', { class: 'btn tonal', onclick: () => openFile(o.blob) }, icon('open'), 'Open')),
        /\.pdf$/.test(o.name) && h('button', { class: 'btn outline', onclick: () => { S.carried = [new File([o.blob], o.name, { type: 'application/pdf' })]; S.screen = 'home'; history.pushState({ screen: 'home' }, ''); render(); } }, icon('spark'), 'Continue with another tool'))),
      r.text && h('div', { class: 'card', style: { padding: '16px' } }, h('div', { class: 'row' }, h('div', { class: 'label grow', style: { margin: 0 } }, 'Summary'), iconBtn('copy', 'Copy', () => navigator.clipboard?.writeText(r.text).then(() => toast('Copied')))), h('div', { style: { whiteSpace: 'pre-wrap', userSelect: 'text' } }, r.text)),
      r.diff && r.diff.lines.some((l) => l.kind !== 'same') && h('div', { class: 'label' }, `Changes (${r.diff.added + r.diff.removed})`),
      ...(r.diff ? r.diff.lines.filter((l) => l.kind !== 'same').slice(0, 500).map((l) => h('div', { class: 'diff ' + l.kind }, h('b', {}, l.kind === 'add' ? '+' : '−'), h('span', { class: 'grow' }, l.text), h('span', { class: 'small muted' }, 'p.' + l.page))) : []),
    ));
}

// share target / file handling from launch queue (installed desktop/Android)
if ('launchQueue' in window) window.launchQueue.setConsumer(async (p) => { if (p.files?.length) { S.carried = await Promise.all(p.files.map((fh) => fh.getFile())); render(); } });

applyTheme(store.get('theme', 'system'));
history.replaceState({ screen: 'home' }, '');
render();
if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
export { S, render, dialog };
