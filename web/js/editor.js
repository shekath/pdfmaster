// Page editor: Edit text (text layer or OCR), add text, draw, highlight, white-out, shapes, images, sign, redact, crop.
import { h, icon, iconBtn, toast, dialog, pickFiles } from './ui.js';
import { openPdf, renderPage, textLines, canvasBytes } from './render.js';
import { openOcr, langName } from './ocr.js';
import { OCR_LANGS } from './tools.js';
import * as P from './pdfops.js';

const PALETTE = ['#111111', '#1E4FD8', '#D32F2F', '#1E9E6A', '#F5B400', '#FFFFFF'];
const MODES = {
  edit: [['edittext', 'Edit text', 'edit'], ['text', 'Text', 'text'], ['draw', 'Draw', 'brush'], ['highlight', 'Highlight', 'highlight'], ['whiteout', 'White-out', 'square'], ['box', 'Box', 'square'], ['image', 'Image', 'image'], ['move', 'Move', 'hand']],
  sign: [['sign', 'Signature', 'sign'], ['text', 'Text', 'text'], ['move', 'Move', 'hand']],
  redact: [['redact', 'Redact', 'square']],
  crop: [['crop', 'Crop', 'crop']],
};

export async function mountEditor(root, ctx) {
  const { tool, file } = ctx;
  const bytes = new Uint8Array(await file.arrayBuffer());
  let pdf;
  try { pdf = await openPdf(bytes); } catch (e) { root.replaceChildren(h('div', { class: 'page' }, h('div', { class: 'error' }, e.message))); return; }
  const st = { page: 0, mode: MODES[tool.id][0][0], items: [], sel: null, color: tool.id === 'sign' ? '#1E3A8A' : PALETTE[0], size: 0.03, stroke: 0.004, cropAll: true, detected: new Map(), detecting: false, signature: null, pendingImage: null, layer: null };
  const pageCanvas = h('canvas'); const overlay = h('canvas'); const stage = h('div', { class: 'ed-page' }, pageCanvas, overlay);
  const pill = h('div', { class: 'pill', hidden: true });
  let pageImg = null, W = 0, H = 0;
  const info = (msg, ms = 2600) => { pill.textContent = msg; pill.hidden = !msg; clearTimeout(info.t); if (msg && ms) info.t = setTimeout(() => { pill.hidden = true; }, ms); };

  async function loadPage() {
    const vp = (await pdf.getPage(st.page + 1)).getViewport({ scale: 1 });
    const maxW = Math.min(root.clientWidth - 24, 900), maxH = window.innerHeight - 250;
    let w = maxW, hh = w * vp.height / vp.width; if (hh > maxH) { hh = maxH; w = hh * vp.width / vp.height; }
    W = Math.round(w); H = Math.round(hh); stage.style.width = W + 'px'; stage.style.height = H + 'px';
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    overlay.width = W * dpr; overlay.height = H * dpr;
    pageImg = await renderPage(pdf, st.page, Math.round(W * dpr));
    pageCanvas.width = pageImg.width; pageCanvas.height = pageImg.height; pageCanvas.getContext('2d').drawImage(pageImg, 0, 0);
    draw(); renderBar();
    if (st.mode === 'edittext') detect();
  }

  // ---------- existing text detection ----------
  async function detect(force = false) {
    if (st.detected.has(st.page) && !force) { draw(); return; }
    st.detecting = true; renderBar();
    try {
      let lines = [];
      if (!force) { const tl = await textLines(pdf, st.page); lines = tl.lines.filter((l) => l.text.trim()).map((l) => ({ l: l.nl, t: l.nt, r: l.nr, b: l.nb, text: l.text, size: l.nsize, bold: l.bold, ocr: false })); }
      if (lines.length) info('Tap any highlighted line to edit it');
      else {
        const lang = ctx.ocrLang(); info(`Reading the page with OCR (${langName(lang)})…`, 0);
        const ocr = await openOcr(lang, (m) => info(m, 0));
        try {
          const vp = (await pdf.getPage(st.page + 1)).getViewport({ scale: 1 });
          const c = await renderPage(pdf, st.page, Math.min(3000, Math.max(1600, Math.round(vp.width * 4))));
          lines = (await ocr.lines(c)).lines.map((l) => ({ ...l, size: (l.b - l.t) * c.height / 1.2 / c.width, bold: false, ocr: true }));
        } finally { await ocr.close(); }
        info(lines.length ? `Found ${lines.length} lines with OCR. Tap one to edit it` : 'No text found. Tap “OCR page” to choose the document language');
      }
      st.detected.set(st.page, lines);
    } catch (e) { info(e.message); } finally { st.detecting = false; renderBar(); draw(); }
  }

  function sampleColors(b) {
    const c = pageImg; const g = c.getContext('2d', { willReadFrequently: true });
    const x0 = Math.max(0, Math.floor(b.l * c.width)), x1 = Math.min(c.width, Math.ceil(b.r * c.width)), y0 = Math.max(0, Math.floor(b.t * c.height)), y1 = Math.min(c.height, Math.ceil(b.b * c.height));
    if (x1 - x0 < 1 || y1 - y0 < 1) return ['#FFFFFF', '#000000'];
    const d = g.getImageData(x0, y0, x1 - x0, y1 - y0).data; const counts = new Map(); const px = [];
    for (let i = 0; i < d.length; i += 4 * Math.max(1, Math.floor((x1 - x0) * (y1 - y0) / 4000))) { const q = ((d[i] >> 4) << 8) | ((d[i + 1] >> 4) << 4) | (d[i + 2] >> 4); counts.set(q, (counts.get(q) ?? 0) + 1); px.push([d[i], d[i + 1], d[i + 2]]); }
    const bq = [...counts.entries()].sort((a, b2) => b2[1] - a[1])[0][0]; const bg = [((bq >> 8) & 15) * 16 + 8, ((bq >> 4) & 15) * 16 + 8, (bq & 15) * 16 + 8];
    const dist = (a, b2) => Math.abs(a[0] - b2[0]) + Math.abs(a[1] - b2[1]) + Math.abs(a[2] - b2[2]);
    const bgPx = px.filter((p) => dist(p, bg) < 40); const avg = (l) => l.reduce((a, p) => [a[0] + p[0], a[1] + p[1], a[2] + p[2]], [0, 0, 0]).map((v) => Math.round(v / l.length));
    const bgc = bgPx.length ? avg(bgPx) : bg; const ink = px.filter((p) => dist(p, bgc) > 110).sort((a, b2) => dist(b2, bgc) - dist(a, bgc));
    const fg = ink.length > 3 ? avg(ink.slice(0, Math.max(3, ink.length / 3 | 0))) : (bgc[0] + bgc[1] + bgc[2] > 380 ? [0, 0, 0] : [255, 255, 255]);
    const hx = (c3) => '#' + c3.map((v) => v.toString(16).padStart(2, '0')).join('');
    return [hx(bgc), hx(fg)];
  }

  // ---------- drawing ----------
  function draw() {
    const g = overlay.getContext('2d'); const k = overlay.width / W; g.setTransform(k, 0, 0, k, 0, 0); g.clearRect(0, 0, W, H);
    st.items.forEach((it, i) => { if (it.page === st.page) drawItem(g, it, i === st.sel); });
    if (st.mode === 'edittext') {
      const done = st.items.filter((it) => it.type === 'replace' && it.page === st.page);
      for (const d of st.detected.get(st.page) ?? []) {
        if (done.some((x) => Math.abs(x.l - d.l) < 0.002 && Math.abs(x.t - d.t) < 0.002)) continue;
        g.fillStyle = 'rgba(47,91,234,.08)'; g.fillRect(d.l * W, d.t * H, (d.r - d.l) * W, (d.b - d.t) * H);
        g.strokeStyle = 'rgba(47,91,234,.6)'; g.lineWidth = 1; g.strokeRect(d.l * W, d.t * H, (d.r - d.l) * W, (d.b - d.t) * H);
      }
    }
    if (drag?.rect) { const r = drag.rect; g.fillStyle = { highlight: 'rgba(255,225,77,.4)', whiteout: '#fff', redact: '#000', crop: 'rgba(47,91,234,.15)', box: st.color }[st.mode] ?? st.color; g.fillRect(r.l * W, r.t * H, (r.r - r.l) * W, (r.b - r.t) * H); g.setLineDash([6, 5]); g.strokeStyle = '#2F5BEA'; g.strokeRect(r.l * W, r.t * H, (r.r - r.l) * W, (r.b - r.t) * H); g.setLineDash([]); }
    if (drag?.ink?.length > 1) inkPath(g, drag.ink, st.stroke, st.color);
    if (st.mode === 'crop') { const b = st.items.find((x) => x.type === 'box'); if (b) { g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, 0, W, b.t * H); g.fillRect(0, b.b * H, W, H - b.b * H); g.fillRect(0, b.t * H, b.l * W, (b.b - b.t) * H); g.fillRect(b.r * W, b.t * H, W - b.r * W, (b.b - b.t) * H); } }
  }
  function inkPath(g, pts, w, color) { g.beginPath(); pts.forEach(([x, y], i) => (i ? g.lineTo(x * W, y * H) : g.moveTo(x * W, y * H))); g.strokeStyle = color; g.lineWidth = w * W; g.lineCap = g.lineJoin = 'round'; g.stroke(); }
  function textBounds(it, g) { g.font = `${it.bold ? '700 ' : ''}${it.size * W}px -apple-system, Roboto, "Noto Sans", sans-serif`; const lines = it.text.split('\n'); return { w: Math.max(...lines.map((l) => g.measureText(l).width)) / W, h: lines.length * it.size * W * 1.2 / H }; }
  function drawItem(g, it, sel) {
    const dash = (x, y, w, hh) => { g.setLineDash([7, 5]); g.strokeStyle = '#2F5BEA'; g.lineWidth = 2; g.strokeRect(x - 3, y - 3, w + 6, hh + 6); g.setLineDash([]); };
    if (it.type === 'box') { g.globalAlpha = it.alpha; g.fillStyle = it.color; g.fillRect(it.l * W, it.t * H, (it.r - it.l) * W, (it.b - it.t) * H); g.globalAlpha = 1; }
    else if (it.type === 'ink') inkPath(g, it.points, it.width, it.color);
    else if (it.type === 'text' || it.type === 'replace') {
      const b = textBounds(it, g); g.fillStyle = it.color; g.textBaseline = 'alphabetic';
      if (it.type === 'replace') { g.fillStyle = it.bg; g.fillRect(it.l * W - 1, it.t * H - 1, Math.max(it.r - it.l, b.w + it.size * 0.2) * W + 2, (it.b - it.t) * H + 2); g.fillStyle = it.color; }
      const x = (it.type === 'replace' ? it.l : it.x) * W, y = (it.type === 'replace' ? it.t : it.y) * H;
      it.text.split('\n').forEach((l, k) => g.fillText(l, x, y + it.size * W * 0.93 + k * it.size * W * 1.2));
      if (sel) dash(x, y, b.w * W, Math.max(b.h * H, it.type === 'replace' ? (it.b - it.t) * H : 0));
    } else if (it.type === 'picture') { if (it.img) g.drawImage(it.img, it.x * W, it.y * H, it.w * W, it.h * H); if (sel) dash(it.x * W, it.y * H, it.w * W, it.h * H); }
  }
  function hit(nx, ny) {
    const g = overlay.getContext('2d');
    for (let i = st.items.length - 1; i >= 0; i--) {
      const it = st.items[i]; if (it.page !== st.page) continue; let r;
      if (it.type === 'text') { const b = textBounds(it, g); r = [it.x, it.y, it.x + b.w, it.y + b.h]; }
      else if (it.type === 'picture') r = [it.x, it.y, it.x + it.w, it.y + it.h];
      else if (it.type === 'replace') r = [it.l, it.t, Math.max(it.r, it.l + 0.02), it.b];
      else continue;
      if (nx >= r[0] - 0.015 && nx <= r[2] + 0.015 && ny >= r[1] - 0.015 && ny <= r[3] + 0.015) return i;
    }
    return null;
  }

  // ---------- pointer input ----------
  let drag = null;
  const norm = (e) => { const r = overlay.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
  overlay.addEventListener('pointerdown', (e) => {
    overlay.setPointerCapture(e.pointerId); const [x, y] = norm(e);
    const movable = ['move', 'text', 'sign', 'image'].includes(st.mode) ? hit(x, y) : null;
    drag = { x, y, sx: e.clientX, sy: e.clientY, moved: false, item: movable, ink: st.mode === 'draw' ? [[x, y]] : null };
    if (movable != null) st.sel = movable;
  });
  overlay.addEventListener('pointermove', (e) => {
    if (!drag) return; const [x, y] = norm(e);
    if (Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) > 6) drag.moved = true;
    if (!drag.moved) return;
    if (drag.item != null) { const it = st.items[drag.item]; const dx = x - drag.x, dy = y - drag.y; drag.x = x; drag.y = y; if (it.type === 'text' || it.type === 'picture') { it.x += dx; it.y += dy; } }
    else if (drag.ink) drag.ink.push([x, y]);
    else if (['highlight', 'whiteout', 'box', 'redact', 'crop'].includes(st.mode)) drag.rect = { l: Math.min(drag.x, x), t: Math.min(drag.y, y), r: Math.max(drag.x, x), b: Math.max(drag.y, y) };
    draw();
  });
  overlay.addEventListener('pointerup', (e) => {
    const d = drag; drag = null; if (!d) return; const [x, y] = norm(e);
    if (!d.moved) { tap(x, y); draw(); renderBar(); return; }
    if (d.ink?.length > 1) st.items.push({ type: 'ink', page: st.page, points: d.ink, width: st.stroke, color: st.color });
    const r = d.rect; const big = r && r.r - r.l > 0.01 && r.b - r.t > 0.005;
    if (big) {
      const clamp = (v) => Math.min(1, Math.max(0, v)); const box = { type: 'box', page: st.page, l: clamp(r.l), t: clamp(r.t), r: clamp(r.r), b: clamp(r.b) };
      if (st.mode === 'highlight') st.items.push({ ...box, color: '#FFE14D', alpha: 0.4 });
      if (st.mode === 'whiteout') st.items.push({ ...box, color: '#FFFFFF', alpha: 1 });
      if (st.mode === 'box') st.items.push({ ...box, color: st.color, alpha: 1 });
      if (st.mode === 'redact') st.items.push({ ...box, color: '#000000', alpha: 1 });
      if (st.mode === 'crop') { st.items = st.items.filter((i) => i.type !== 'box'); st.items.push({ ...box, color: '#2F5BEA', alpha: 0.15 }); }
    }
    draw(); renderBar();
  });

  function tap(x, y) {
    if (st.mode === 'edittext') {
      const ex = st.items.findIndex((it) => it.type === 'replace' && it.page === st.page && x >= it.l && x <= Math.max(it.r, it.l + 0.02) && y >= it.t && y <= it.b);
      if (ex >= 0) return replaceDialog(st.items[ex], ex);
      const pad = 0.006; const line = (st.detected.get(st.page) ?? []).filter((d) => x >= d.l - pad && x <= d.r + pad && y >= d.t - pad && y <= d.b + pad).sort((a, b) => (a.b - a.t) * (a.r - a.l) - (b.b - b.t) * (b.r - b.l))[0];
      if (line) replaceDialog(line, null); else info(st.detecting ? 'Still reading the page…' : 'No text there. Tap a highlighted line');
      return;
    }
    const hitI = ['redact', 'crop'].includes(st.mode) ? null : hit(x, y);
    if (hitI != null) { st.sel = hitI; return; }
    st.sel = null;
    if (st.mode === 'text') textDialog(x, y);
    else if (st.mode === 'sign') { if (st.signature) place(st.signature, x, y, 0.3); else signatureDialog(); }
    else if (st.mode === 'image') { if (st.pendingImage) { place(st.pendingImage, x, y, 0.4); st.pendingImage = null; info(''); } else chooseImage(); }
  }

  function place(sig, x, y, wFrac) {
    const aspect = H / W; const w = wFrac; const hh = w * sig.h / sig.w / aspect;
    st.items.push({ type: 'picture', page: st.page, x: Math.min(Math.max(0, x - w / 2), 1 - w), y: Math.min(Math.max(0, y - hh / 2), Math.max(0, 1 - hh)), w, h: hh, png: sig.png, img: sig.img });
    st.sel = st.items.length - 1; draw(); renderBar();
  }
  async function imageFrom(canvas) { const png = await canvasBytes(canvas, 'image/png'); const img = new Image(); img.src = URL.createObjectURL(new Blob([png], { type: 'image/png' })); await img.decode(); return { png, img, w: canvas.width, h: canvas.height }; }
  async function chooseImage() {
    const [f] = await pickFiles('image/*', false); if (!f) return;
    const bmp = await createImageBitmap(f, { imageOrientation: 'from-image' }); const s = Math.min(1, 1600 / Math.max(bmp.width, bmp.height));
    const c = h('canvas', { width: Math.round(bmp.width * s), height: Math.round(bmp.height * s) }); c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
    st.pendingImage = await imageFrom(c); st.mode = 'image'; renderBar(); info('Tap the page to place the image', 0);
  }

  // ---------- dialogs ----------
  function textDialog(x, y) {
    const ta = h('textarea', { class: 'field', placeholder: 'Type here', rows: 3 });
    dialog('Add text', ta, [['Cancel'], ['Add', () => { if (ta.value.trim()) { st.items.push({ type: 'text', page: st.page, x, y, text: ta.value, size: st.size, color: st.color }); st.sel = st.items.length - 1; draw(); renderBar(); } }, 'primary']]);
    setTimeout(() => ta.focus(), 50);
  }
  function replaceDialog(line, index) {
    const ta = h('textarea', { class: 'field', rows: 2 }, line.text); const bold = h('input', { type: 'checkbox', checked: !!line.bold });
    const body = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '12px' } }, ta, h('label', { class: 'switch' }, h('span', { class: 'grow' }, 'Bold'), bold),
      line.ocr && h('div', { class: 'small muted' }, 'Recognised with OCR: check the spelling before saving.'));
    const actions = [['Cancel'], ['Apply', () => {
      const prev = index != null ? st.items[index] : null; const [bg, fg] = prev ? [prev.bg, prev.color] : sampleColors(line);
      const it = { type: 'replace', page: st.page, l: line.l, t: line.t, r: line.r, b: line.b, text: ta.value.replace(/\n/g, ' '), size: prev?.size ?? line.size, color: fg, bg, bold: bold.checked, ocr: line.ocr };
      if (index != null) st.items[index] = it; else st.items.push(it); st.sel = index ?? st.items.length - 1; draw(); renderBar();
    }, 'primary']];
    if (index != null) actions.unshift(['Restore original', () => { st.items.splice(index, 1); st.sel = null; draw(); renderBar(); }]);
    dialog('Edit text', body, actions); setTimeout(() => ta.focus(), 50);
  }
  function signatureDialog() {
    const pad = h('canvas', { style: { width: '100%', height: '180px', background: '#fff', borderRadius: '12px', border: '1px solid var(--line2)', touchAction: 'none' } });
    const name = h('input', { class: 'field', placeholder: 'Full name' });
    const preview = h('div', { style: { height: '80px', background: '#fff', borderRadius: '12px', display: 'grid', placeItems: 'center', font: '40px "Snell Roundhand","Segoe Script","Brush Script MT",cursive', color: st.color } }, 'Your Name');
    name.addEventListener('input', () => { preview.textContent = name.value || 'Your Name'; });
    let tab = 'draw'; const strokes = []; let cur = null;
    const drawPad = h('div', {}, pad, h('button', { class: 'btn outline', style: { padding: '6px 12px', marginTop: '8px' }, onclick: () => { strokes.length = 0; repaint(); } }, 'Clear'));
    const typePad = h('div', { hidden: true, style: { display: 'flex', flexDirection: 'column', gap: '10px' } }, name, preview);
    const tabs = h('div', { class: 'seg', style: { marginBottom: '12px' } });
    const setTab = (t) => { tab = t; drawPad.hidden = t !== 'draw'; typePad.hidden = t !== 'type'; tabs.replaceChildren(...[['draw', 'Draw'], ['type', 'Type']].map(([k, l]) => h('button', { class: 'chip' + (tab === k ? ' on' : ''), onclick: () => setTab(k) }, l))); };
    const repaint = () => { const g = pad.getContext('2d'); g.clearRect(0, 0, pad.width, pad.height); g.strokeStyle = st.color; g.lineWidth = 3 * devicePixelRatio; g.lineCap = g.lineJoin = 'round'; for (const s of [...strokes, cur].filter(Boolean)) { g.beginPath(); s.forEach(([x, y], i) => (i ? g.lineTo(x, y) : g.moveTo(x, y))); g.stroke(); } };
    const pos = (e) => { const r = pad.getBoundingClientRect(); return [(e.clientX - r.left) * pad.width / r.width, (e.clientY - r.top) * pad.height / r.height]; };
    pad.addEventListener('pointerdown', (e) => { pad.setPointerCapture(e.pointerId); cur = [pos(e)]; });
    pad.addEventListener('pointermove', (e) => { if (cur) { cur.push(pos(e)); repaint(); } });
    pad.addEventListener('pointerup', () => { if (cur?.length > 1) strokes.push(cur); cur = null; repaint(); });
    setTab('draw');
    const d = dialog('Your signature', h('div', {}, tabs, drawPad, typePad), [['Cancel'], ['Use signature', () => {
      let c;
      if (tab === 'draw') {
        if (!strokes.length) { toast('Draw your signature first'); return false; }
        const pts = strokes.flat(); const p = 10 * devicePixelRatio; const minX = Math.min(...pts.map((q) => q[0])) - p, minY = Math.min(...pts.map((q) => q[1])) - p, maxX = Math.max(...pts.map((q) => q[0])) + p, maxY = Math.max(...pts.map((q) => q[1])) + p;
        c = h('canvas', { width: Math.ceil(maxX - minX), height: Math.ceil(maxY - minY) }); c.getContext('2d').drawImage(pad, minX, minY, c.width, c.height, 0, 0, c.width, c.height);
      } else {
        if (!name.value.trim()) { toast('Type your name first'); return false; }
        c = h('canvas'); const g = c.getContext('2d'); const font = '120px "Snell Roundhand","Segoe Script","Brush Script MT",cursive'; g.font = font; const m = g.measureText(name.value);
        c.width = Math.ceil(m.width) + 40; c.height = 180; g.font = font; g.fillStyle = st.color; g.fillText(name.value, 20, 130);
      }
      imageFrom(c).then((sig) => { st.signature = sig; st.mode = 'sign'; place(sig, 0.5, 0.8, 0.3); info('Drag to move it. Tap elsewhere to add it again.'); });
    }, 'primary']]);
    requestAnimationFrame(() => { pad.width = pad.clientWidth * devicePixelRatio; pad.height = pad.clientHeight * devicePixelRatio; repaint(); });
    return d;
  }
  function findDialog() {
    const inp = h('input', { class: 'field', placeholder: 'Word or phrase' });
    dialog('Redact matching text', inp, [['Cancel'], ['Mark all', () => {
      const term = inp.value.trim().toLowerCase(); if (!term) return false;
      (async () => {
        let count = 0; const pages = new Set();
        for (let i = 0; i < pdf.numPages; i++) {
          const { w, h: ph, lines } = await textLines(pdf, i);
          for (const l of lines) {
            const hay = l.text.toLowerCase(); let k = hay.indexOf(term);
            while (k >= 0) {
              const cs = l.chars.slice(k, k + term.length); const pad = 1.5;
              st.items.push({ type: 'box', page: i, l: Math.max(0, (Math.min(...cs.map((c) => c.l)) - pad) / w), r: Math.min(1, (Math.max(...cs.map((c) => c.r)) + pad) / w), t: Math.max(0, (l.top - pad) / ph), b: Math.min(1, (l.bottom + pad) / ph), color: '#000000', alpha: 1 });
              count++; pages.add(i); k = hay.indexOf(term, k + term.length);
            }
          }
        }
        info(count ? `Marked ${count} match(es) across ${pages.size} page(s)` : `No matches for “${inp.value}”. Scanned pages need OCR first.`); draw(); renderBar();
      })();
    }, 'primary']]);
    setTimeout(() => inp.focus(), 50);
  }
  function langDialog() {
    const sel = h('select', { class: 'field' }, ...OCR_LANGS.map(([c, n]) => h('option', { value: c, selected: ctx.ocrLang() === c }, n)));
    dialog('Document language', h('div', {}, sel, h('p', { class: 'small muted' }, 'Indian languages download their language data once (about 10 MB).')), [['Cancel'], ['Read page', () => { ctx.setOcrLang(sel.value); detect(true); }, 'primary']]);
  }

  // ---------- toolbar ----------
  const bar = h('div', { class: 'bottom', style: { display: 'flex', flexDirection: 'column', gap: '6px' } });
  function renderBar() {
    const selIt = st.sel != null ? st.items[st.sel] : null;
    const showStyle = ['text', 'draw', 'box', 'sign', 'move'].includes(st.mode) && tool.id !== 'redact' || selIt?.type === 'replace';
    bar.replaceChildren(...[
      h('div', { class: 'row', style: { flexWrap: 'wrap' } },
        iconBtn('left', 'Previous page', () => { st.page--; st.sel = null; loadPage(); }, { disabled: st.page === 0 }),
        h('span', { class: 'small' }, `Page ${st.page + 1} of ${pdf.numPages}`),
        iconBtn('right', 'Next page', () => { st.page++; st.sel = null; loadPage(); }, { disabled: st.page >= pdf.numPages - 1 }),
        h('div', { class: 'grow' }),
        selIt && iconBtn('trash', 'Delete selected', () => { st.items.splice(st.sel, 1); st.sel = null; draw(); renderBar(); }),
        st.mode === 'edittext' && h('button', { class: 'chip', disabled: st.detecting, onclick: langDialog }, st.detecting ? 'Reading…' : 'OCR page'),
        tool.id === 'redact' && h('button', { class: 'chip', onclick: findDialog }, 'Find text'),
        tool.id === 'crop' && h('label', { class: 'switch small' }, 'All pages', h('input', { type: 'checkbox', checked: st.cropAll, onchange: (e) => { st.cropAll = e.target.checked; } })),
      ),
      MODES[tool.id].length > 1 && h('div', { class: 'ed-tools' }, ...MODES[tool.id].map(([k, label, ic]) => h('button', { class: 'chip row' + (st.mode === k ? ' on' : ''), style: { gap: '6px' }, onclick: () => {
        st.mode = k; st.sel = null;
        if (k === 'image') chooseImage(); if (k === 'sign' && !st.signature) signatureDialog(); if (k === 'edittext') detect();
        draw(); renderBar();
      } }, icon(ic, 16), label)),
      tool.id === 'sign' && h('button', { class: 'chip', onclick: signatureDialog }, 'New signature'),
      tool.id === 'sign' && h('button', { class: 'chip', onclick: () => { st.items.push({ type: 'text', page: st.page, x: 0.1, y: 0.85, text: new Date().toLocaleDateString(), size: 0.025, color: st.color }); st.sel = st.items.length - 1; draw(); renderBar(); } }, 'Date')),
      showStyle && h('div', { class: 'row' }, ...PALETTE.map((c) => h('button', { class: 'swatch' + (st.color === c ? ' on' : ''), style: { background: c }, 'aria-label': 'Colour ' + c, onclick: () => { st.color = c; if (selIt && 'color' in selIt) selIt.color = c; draw(); renderBar(); } })),
        h('input', { type: 'range', class: 'grow', min: st.mode === 'draw' ? 1 : 10, max: st.mode === 'draw' ? 20 : 120, value: st.mode === 'draw' ? st.stroke * 1000 : (selIt?.size ?? st.size) * 1000, 'aria-label': 'Size', oninput: (e) => {
          const v = e.target.value / 1000; if (st.mode === 'draw') st.stroke = v; else st.size = v;
          if (selIt?.type === 'text' || selIt?.type === 'replace') selIt.size = v;
          if (selIt?.type === 'picture') { const nw = Math.min(1, Math.max(0.05, v * 8)); selIt.h = selIt.h * nw / selIt.w; selIt.w = nw; }
          draw();
        } })),
    ].filter(Boolean));
  }

  async function saveEdits() {
    const t = tool; const base = file.name.replace(/\.[^.]+$/, '');
    ctx.runJob(async () => {
      let out, msg;
      if (t.id === 'redact') {
        const boxes = st.items.filter((i) => i.type === 'box'); if (!boxes.length) throw new Error('Mark at least one area to redact');
        out = await P.redact(bytes, boxes, (p) => ctx.progress(p, 'Redacting…')); msg = `Redacted ${boxes.length} area(s) on ${new Set(boxes.map((b) => b.page)).size} page(s). The covered content has been removed from the file.`;
      } else if (t.id === 'crop') {
        const b = st.items.find((i) => i.type === 'box'); if (!b) throw new Error('Drag on the page to select the area to keep');
        out = await P.crop(bytes, b, st.cropAll ? null : b.page); msg = st.cropAll ? 'Cropped every page.' : `Cropped page ${b.page + 1}.`;
      } else {
        if (!st.items.length) throw new Error(t.id === 'sign' ? 'Add your signature to the page first' : 'Add something to the page first');
        out = await P.applyEdits(bytes, st.items.map(({ img, ...rest }) => rest)); msg = t.id === 'sign' ? 'Signed and saved.' : 'Saved your changes.';
      }
      const name = `${base}_${{ redact: 'redacted', crop: 'cropped', sign: 'signed' }[t.id] ?? 'edited'}.pdf`;
      return { outputs: [{ name, blob: new Blob([out], { type: 'application/pdf' }) }], message: msg };
    });
  }

  root.replaceChildren(
    h('div', { class: 'topbar' }, iconBtn('back', 'Back', ctx.back), h('h2', {}, tool.title),
      iconBtn('undo', 'Undo', () => { st.items.pop(); st.sel = null; draw(); renderBar(); }),
      h('button', { class: 'btn primary', style: { padding: '9px 18px' }, onclick: saveEdits }, 'Save')),
    h('div', { class: 'ed-stage', style: { paddingBottom: '190px' } }, stage), pill, bar);
  await loadPage();
}
