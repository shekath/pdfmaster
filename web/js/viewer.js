// PDF viewer: continuous pages with zoom (buttons, menu, Ctrl+wheel, trackpad and touch pinch, keyboard), panning
// (hand tool, Space/middle-button drag, scrollbars), page navigation, thumbnails, rotation, text selection and search.
import { h, icon, iconBtn, toast } from './ui.js';
import { openPdf } from './render.js';
import { pdfjs } from './lib.js';
import { byId, CATEGORIES } from './tools.js';

const QUICK = ['edit', 'sign', 'compress', 'organize', 'pdf2word', 'merge', 'protect', 'ocr'];
const PRESETS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4];
const MIN = 0.25, MAX = 6, GAP = 16, PAD = 24;
const PT = 96 / 72; // CSS px per PDF point at 100 %
const MAX_PX = 16_000_000;

export async function mountViewer(root, ctx) {
  const file = ctx.S.viewFile;
  const lib = await pdfjs();
  const st = { scale: 1, fit: 'width', rot: 0, hand: false, page: 0, thumbs: innerWidth >= 1000, search: { open: false, q: '', hits: [], i: -1 } };

  // ---------- DOM ----------
  const pageInput = h('input', { class: 'vw-pageinput', type: 'text', inputmode: 'numeric', 'aria-label': 'Page number', value: '1' });
  const pageTotal = h('span', { class: 'small muted' }, '/ –');
  const zoomSel = h('select', { class: 'vw-zoom', 'aria-label': 'Zoom' });
  const handBtn = iconBtn('hand', 'Hand tool (pan)', () => setHand(!st.hand));
  const thumbsBtn = iconBtn('organize', 'Page thumbnails', () => { st.thumbs = !st.thumbs; sideEl.hidden = !st.thumbs; thumbsBtn.classList.toggle('on', st.thumbs); if (st.thumbs) observeThumbs(); requestAnimationFrame(() => applyFit(true)); });
  const searchInput = h('input', { class: 'field vw-search', type: 'search', placeholder: 'Find in document', 'aria-label': 'Find in document' });
  const searchCount = h('span', { class: 'small muted vw-count' });
  const searchBar = h('div', { class: 'vw-searchbar', hidden: true }, searchInput, searchCount,
    iconBtn('up', 'Previous match', () => gotoHit(st.search.i - 1)), iconBtn('down', 'Next match', () => gotoHit(st.search.i + 1)),
    iconBtn('close', 'Close search', () => toggleSearch(false)));
  const pagesEl = h('div', { class: 'vw-pages' });
  const scroller = h('div', { class: 'vw-scroll', tabindex: '0' }, pagesEl);
  const sideEl = h('aside', { class: 'vw-side', hidden: !st.thumbs, 'aria-label': 'Pages' });
  const status = h('span', { class: 'small muted' }, 'Opening…');
  const quick = h('div', { class: 'vw-quick' }, ...QUICK.map((id) => {
    const t = byId(id); const c = CATEGORIES.find((x) => x.id === t.cat);
    return h('button', { class: 'chip row', style: { gap: '6px' }, onclick: () => ctx.useTool(t, file) }, h('span', { style: { color: c.tint, display: 'flex' } }, icon(t.id, 16)), t.title.replace(' PDF', '').replace('PDF to ', 'To '));
  }), h('button', { class: 'chip', onclick: () => ctx.useTool(null, file) }, 'All tools…'));

  root.replaceChildren(h('div', { class: 'vw-root' },
    h('div', { class: 'vw-top' },
      iconBtn('back', 'Back', ctx.back), h('h2', { class: 'vw-title', title: file.name }, file.name),
      h('div', { class: 'vw-group' }, iconBtn('left', 'Previous page', () => gotoPage(st.page - 1)), pageInput, pageTotal, iconBtn('right', 'Next page', () => gotoPage(st.page + 1))),
      h('div', { class: 'vw-group' }, iconBtn('minus', 'Zoom out (Ctrl −)', () => zoomBy(1 / 1.2)), zoomSel, iconBtn('plus', 'Zoom in (Ctrl +)', () => zoomBy(1.2))),
      h('div', { class: 'vw-group' }, handBtn, iconBtn('rotateR', 'Rotate view', () => rotate()), iconBtn('search', 'Find (Ctrl F)', () => toggleSearch(true)), thumbsBtn)),
    searchBar,
    h('div', { class: 'vw-body' }, sideEl, scroller),
    h('div', { class: 'vw-bar' }, h('div', { class: 'row', style: { marginBottom: '6px' } }, status), quick)));
  thumbsBtn.classList.toggle('on', st.thumbs);

  // ---------- open ----------
  let pdf;
  try { pdf = await openPdf(new Uint8Array(await file.arrayBuffer())); }
  catch (e) {
    pagesEl.replaceChildren(h('div', { class: 'error', style: { margin: '24px auto', maxWidth: '520px' } }, e.message,
      e.locked && h('div', { style: { marginTop: '10px' } }, h('button', { class: 'btn primary', onclick: () => ctx.useTool(byId('unlock'), file) }, 'Unlock this PDF'))));
    status.textContent = ''; return;
  }
  const N = pdf.numPages;
  pageTotal.textContent = `/ ${N}`;
  status.textContent = `${N} page${N === 1 ? '' : 's'} · Ctrl + scroll or pinch to zoom · drag with the hand tool to pan`;
  const pages = []; // { page, base:{w,h}, slot, canvas, textDiv, rendered, task, textBuilt }
  for (let i = 0; i < N; i++) pages.push({ page: await pdf.getPage(i + 1) });

  function viewportFor(p, scale) { return p.page.getViewport({ scale: scale * PT, rotation: (p.page.rotate + st.rot) % 360 }); }
  function buildSlots() {
    pagesEl.replaceChildren(...pages.map((p, i) => {
      const v = viewportFor(p, 1); p.base = { w: v.width, h: v.height };
      p.slot = h('div', { class: 'vw-page', 'data-i': i }, h('span', { class: 'vw-num' }, i + 1));
      p.canvas = null; p.rendered = 0; p.textDiv = null; p.textBuilt = false; p.task?.cancel(); p.task = null;
      return p.slot;
    }));
  }
  buildSlots();

  // ---------- layout & zoom ----------
  const maxBase = () => ({ w: Math.max(...pages.map((p) => p.base.w)), h: Math.max(...pages.map((p) => p.base.h)) });
  function layout() {
    pagesEl.style.setProperty('--scale-factor', st.scale * PT);
    for (const p of pages) { p.slot.style.width = `${p.base.w * st.scale}px`; p.slot.style.height = `${p.base.h * st.scale}px`; }
    syncZoomUi();
  }
  function fitScale(kind) {
    const m = maxBase(); const w = scroller.clientWidth - PAD * 2; const hh = scroller.clientHeight - PAD * 2;
    if (w < 40 || hh < 40) return st.scale; // not laid out yet
    return kind === 'page' ? Math.min(w / m.w, hh / m.h) : w / m.w;
  }
  function syncZoomUi() {
    const pct = Math.round(st.scale * 100);
    const opts = [['width', 'Fit width'], ['page', 'Fit page'], ...PRESETS.map((s) => [String(s), `${Math.round(s * 100)}%`])];
    if (!st.fit && !PRESETS.some((s) => Math.round(s * 100) === pct)) opts.push(['custom', `${pct}%`]);
    zoomSel.replaceChildren(...opts.map(([v, l]) => h('option', { value: v, selected: st.fit ? v === st.fit : (v === 'custom' || Math.round(+v * 100) === pct) }, l)));
  }
  /** Sets the zoom keeping the document point under (cx, cy) — relative to the scroller — exactly in place. */
  function setScale(next, cx = scroller.clientWidth / 2, cy = scroller.clientHeight / 2, fit = null) {
    next = Math.min(MAX, Math.max(MIN, next));
    if (Math.abs(next - st.scale) < 1e-4 && fit === st.fit) return;
    const y = scroller.scrollTop + cy, x = scroller.scrollLeft + cx;
    let i = pages.findIndex((p) => p.slot.offsetTop + p.slot.offsetHeight + GAP / 2 > y); if (i < 0) i = pages.length - 1;
    const s0 = pages[i].slot;
    const fy = (y - s0.offsetTop) / s0.offsetHeight, fx = (x - s0.offsetLeft) / s0.offsetWidth;
    st.scale = next; st.fit = fit; layout();
    scroller.scrollTop = s0.offsetTop + fy * s0.offsetHeight - cy;
    scroller.scrollLeft = s0.offsetLeft + fx * s0.offsetWidth - cx;
    scheduleRender();
  }
  const zoomBy = (f, cx, cy) => setScale(st.scale * f, cx, cy);
  function applyFit(keepPage) { if (!st.fit) return; const p = st.page; setScale(fitScale(st.fit), undefined, undefined, st.fit); if (keepPage) gotoPage(p, false); }
  zoomSel.addEventListener('change', () => {
    const v = zoomSel.value;
    if (v === 'width' || v === 'page') { st.fit = v; applyFit(true); } else if (v !== 'custom') setScale(+v);
    scroller.focus();
  });
  new ResizeObserver(() => { if (st.fit) applyFit(true); }).observe(scroller);

  // Ctrl/⌘ + wheel and trackpad pinch (browsers send ctrlKey wheel events for pinch)
  scroller.addEventListener('wheel', (e) => {
    if (!(e.ctrlKey || e.metaKey)) return;
    e.preventDefault();
    const r = scroller.getBoundingClientRect();
    zoomBy(Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0025)), e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });

  // Two-finger pinch on touch screens; one finger keeps native scrolling
  const touches = new Map(); let pinch = null;
  scroller.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'touch') return;
    touches.set(e.pointerId, e);
    if (touches.size === 2) { const [a, b] = [...touches.values()]; pinch = { d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), s: st.scale }; }
  });
  scroller.addEventListener('pointermove', (e) => {
    if (!touches.has(e.pointerId)) return; touches.set(e.pointerId, e);
    if (pinch && touches.size === 2) {
      const [a, b] = [...touches.values()]; const r = scroller.getBoundingClientRect();
      const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      setScale(pinch.s * d / pinch.d, (a.clientX + b.clientX) / 2 - r.left, (a.clientY + b.clientY) / 2 - r.top);
    }
  });
  const endTouch = (e) => { touches.delete(e.pointerId); if (touches.size < 2) pinch = null; };
  scroller.addEventListener('pointerup', endTouch); scroller.addEventListener('pointercancel', endTouch);

  // ---------- panning ----------
  let space = false, drag = null;
  function setHand(on) { st.hand = on; handBtn.classList.toggle('on', on); scroller.classList.toggle('hand', on); window.getSelection()?.removeAllRanges(); }
  scroller.addEventListener('mousedown', (e) => {
    const pan = e.button === 1 || (e.button === 0 && (st.hand || space));
    if (!pan) return;
    e.preventDefault();
    drag = { x: e.clientX, y: e.clientY, l: scroller.scrollLeft, t: scroller.scrollTop };
    scroller.classList.add('grabbing');
  });
  window.addEventListener('mousemove', (e) => { if (!drag) return; scroller.scrollLeft = drag.l - (e.clientX - drag.x); scroller.scrollTop = drag.t - (e.clientY - drag.y); });
  window.addEventListener('mouseup', () => { if (drag) { drag = null; scroller.classList.remove('grabbing'); } });

  // ---------- rendering ----------
  let renderTimer = 0;
  function scheduleRender(delay = 90) { clearTimeout(renderTimer); renderTimer = setTimeout(renderVisible, delay); }
  function visibleRange(margin = 1) {
    const top = scroller.scrollTop - scroller.clientHeight * margin, bottom = scroller.scrollTop + scroller.clientHeight * (1 + margin);
    const out = [];
    for (let i = 0; i < pages.length; i++) { const s = pages[i].slot; if (s.offsetTop + s.offsetHeight >= top && s.offsetTop <= bottom) out.push(i); }
    return out;
  }
  async function renderVisible() {
    const dpr = Math.min(3, devicePixelRatio || 1);
    for (const i of visibleRange()) {
      const p = pages[i]; let scale = st.scale * dpr;
      const area = p.base.w * p.base.h * scale * scale; if (area > MAX_PX) scale *= Math.sqrt(MAX_PX / area);
      if (Math.abs(p.rendered - scale) < 0.01) { buildText(p); continue; }
      p.task?.cancel();
      const vp = viewportFor(p, scale);
      const c = document.createElement('canvas'); c.width = Math.floor(vp.width); c.height = Math.floor(vp.height); c.className = 'vw-canvas';
      const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
      p.task = p.page.render({ canvasContext: g, viewport: vp });
      p.task.promise.then(() => {
        p.task = null; p.rendered = scale;
        if (p.canvas) p.canvas.replaceWith(c); else p.slot.prepend(c);
        p.canvas = c; buildText(p);
      }).catch((err) => { if (err?.name !== 'RenderingCancelledException') console.warn(err); });
    }
    // free far-away pages to keep memory low on long documents
    const keep = new Set(visibleRange(4));
    pages.forEach((p, i) => { if (!keep.has(i) && p.canvas) { p.canvas.remove(); p.canvas = null; p.rendered = 0; } });
  }

  const textCache = new Map();
  async function textOf(i) { if (!textCache.has(i)) textCache.set(i, pages[i].page.getTextContent()); return textCache.get(i); }
  async function buildText(p) {
    if (p.textBuilt) return; p.textBuilt = true;
    const i = pages.indexOf(p);
    const div = h('div', { class: 'textLayer' });
    p.slot.append(div);
    const tl = new lib.TextLayer({ textContentSource: await textOf(i), container: div, viewport: viewportFor(p, 1) });
    await tl.render();
    p.textDiv = div; p.textItems = tl.textContentItemsStr; p.textDivs = tl.textDivs;
    highlightPage(i);
  }

  // ---------- navigation ----------
  function updateCurrent() {
    const mid = scroller.scrollTop + scroller.clientHeight * 0.35; let cur = 0;
    for (let i = 0; i < pages.length; i++) { if (pages[i].slot.offsetTop <= mid) cur = i; else break; }
    if (cur !== st.page) { st.page = cur; markThumb(); }
    if (document.activeElement !== pageInput) pageInput.value = String(st.page + 1);
  }
  function gotoPage(i, smooth = true) {
    i = Math.max(0, Math.min(N - 1, i));
    scroller.scrollTo({ top: pages[i].slot.offsetTop - PAD / 2, behavior: smooth ? 'smooth' : 'auto' });
    st.page = i; pageInput.value = String(i + 1); markThumb();
  }
  pageInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { const n = parseInt(pageInput.value, 10); if (n >= 1 && n <= N) gotoPage(n - 1); else pageInput.value = String(st.page + 1); scroller.focus(); } });
  pageInput.addEventListener('focus', () => pageInput.select());
  scroller.addEventListener('scroll', () => { updateCurrent(); scheduleRender(60); }, { passive: true });

  function rotate() {
    const p = st.page; st.rot = (st.rot + 90) % 360; buildSlots(); layout();
    if (st.fit) st.scale = fitScale(st.fit), layout();
    requestAnimationFrame(() => { gotoPage(p, false); scheduleRender(0); });
    renderThumbs(true);
  }

  // ---------- thumbnails ----------
  let thumbIO = null;
  function renderThumbs(reset) {
    if (reset) thumbIO?.disconnect();
    sideEl.replaceChildren(...pages.map((p, i) => h('button', { class: 'vw-thumb', 'data-i': i, 'aria-label': `Page ${i + 1}`, onclick: () => gotoPage(i) },
      h('div', { class: 'vw-thumbimg', style: { aspectRatio: `${p.base.w} / ${p.base.h}` } }), h('span', {}, i + 1))));
    markThumb(); if (st.thumbs) observeThumbs();
  }
  function observeThumbs() {
    thumbIO?.disconnect();
    thumbIO = new IntersectionObserver((entries) => entries.forEach(async (en) => {
      if (!en.isIntersecting) return; thumbIO.unobserve(en.target);
      const i = +en.target.dataset.i; const p = pages[i]; const box = en.target.querySelector('.vw-thumbimg');
      const vp = viewportFor(p, 140 / p.base.w * 1.5); const c = document.createElement('canvas'); c.width = vp.width; c.height = vp.height;
      await p.page.render({ canvasContext: c.getContext('2d'), viewport: vp }).promise.catch(() => {});
      box.replaceChildren(c);
    }), { root: sideEl, rootMargin: '300px' });
    sideEl.querySelectorAll('.vw-thumb').forEach((t) => thumbIO.observe(t));
  }
  function markThumb() {
    sideEl.querySelectorAll('.vw-thumb.on').forEach((t) => t.classList.remove('on'));
    const t = sideEl.querySelector(`.vw-thumb[data-i="${st.page}"]`);
    if (t) { t.classList.add('on'); if (st.thumbs) t.scrollIntoView({ block: 'nearest' }); }
  }
  renderThumbs();

  // ---------- search ----------
  function toggleSearch(open) {
    st.search.open = open; searchBar.hidden = !open;
    if (open) { searchInput.focus(); searchInput.select(); } else { st.search = { open: false, q: '', hits: [], i: -1 }; searchCount.textContent = ''; pages.forEach((_, i) => highlightPage(i)); scroller.focus(); }
  }
  async function runSearch(q) {
    st.search.q = q; st.search.hits = []; st.search.i = -1;
    if (!q.trim()) { searchCount.textContent = ''; pages.forEach((_, i) => highlightPage(i)); return; }
    const needle = q.toLowerCase();
    for (let i = 0; i < N; i++) {
      const tc = await textOf(i); const strs = tc.items.map((it) => it.str ?? '');
      strs.forEach((s, k) => { let from = 0, at; const low = s.toLowerCase(); while ((at = low.indexOf(needle, from)) >= 0) { st.search.hits.push({ page: i, item: k, at }); from = at + needle.length; } });
    }
    searchCount.textContent = st.search.hits.length ? '' : 'No matches';
    pages.forEach((_, i) => highlightPage(i));
    if (st.search.hits.length) gotoHit(0);
  }
  function gotoHit(n) {
    const hits = st.search.hits; if (!hits.length) return;
    st.search.i = (n + hits.length) % hits.length; const hit = hits[st.search.i];
    searchCount.textContent = `${st.search.i + 1} of ${hits.length}`;
    pages.forEach((_, i) => highlightPage(i));
    const p = pages[hit.page];
    const yInPage = (() => { const div = p.textDivs?.[hit.item]; return div ? div.offsetTop : 0; })();
    scroller.scrollTo({ top: p.slot.offsetTop + yInPage - scroller.clientHeight / 3, behavior: 'smooth' });
    setTimeout(() => { if (p.textDivs) highlightPage(hit.page); else scheduleRender(0); }, 350);
  }
  function highlightPage(i) {
    const p = pages[i]; if (!p.textDivs) return;
    p.textDivs.forEach((d) => d.classList.remove('vw-hit', 'vw-hit-on'));
    const cur = st.search.hits[st.search.i];
    for (const hit of st.search.hits) if (hit.page === i) p.textDivs[hit.item]?.classList.add(cur === hit ? 'vw-hit-on' : 'vw-hit');
  }
  let searchTimer = 0;
  searchInput.addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => runSearch(searchInput.value), 250); });
  searchInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); gotoHit(st.search.i + (e.shiftKey ? -1 : 1)); } if (e.key === 'Escape') toggleSearch(false); });

  // ---------- keyboard ----------
  const onKey = (e) => {
    if (!root.isConnected) { window.removeEventListener('keydown', onKey); window.removeEventListener('keyup', onKeyUp); return; }
    const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName);
    const mod = e.ctrlKey || e.metaKey;
    if (mod && (e.key === '+' || e.key === '=')) { e.preventDefault(); zoomBy(1.2); }
    else if (mod && (e.key === '-' || e.key === '_')) { e.preventDefault(); zoomBy(1 / 1.2); }
    else if (mod && e.key === '0') { e.preventDefault(); st.fit = 'width'; applyFit(true); }
    else if (mod && e.key.toLowerCase() === 'f') { e.preventDefault(); toggleSearch(true); }
    else if (typing) return;
    else if (e.key === ' ' && !space) { space = true; scroller.classList.add('hand'); e.preventDefault(); }
    else if (e.key === 'PageDown' || (e.key === 'ArrowRight' && !mod && scroller.scrollWidth <= scroller.clientWidth)) { e.preventDefault(); gotoPage(st.page + 1); }
    else if (e.key === 'PageUp' || (e.key === 'ArrowLeft' && !mod && scroller.scrollWidth <= scroller.clientWidth)) { e.preventDefault(); gotoPage(st.page - 1); }
    else if (e.key === 'Home') { e.preventDefault(); gotoPage(0); }
    else if (e.key === 'End') { e.preventDefault(); gotoPage(N - 1); }
    else if (e.key.toLowerCase() === 'h') setHand(!st.hand);
    else if (e.key.toLowerCase() === 'r') rotate();
  };
  const onKeyUp = (e) => { if (e.key === ' ') { space = false; if (!st.hand) scroller.classList.remove('hand'); } };
  window.addEventListener('keydown', onKey); window.addEventListener('keyup', onKeyUp);

  // ---------- start ----------
  requestAnimationFrame(() => { st.scale = fitScale('width'); layout(); scheduleRender(0); scroller.focus(); });
  window.__pdfViewer = { setScale, gotoPage, get state() { return st; } };
}
