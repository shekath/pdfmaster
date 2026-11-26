// PDF viewer: scrolls through pages (rendered as they come into view) with one-tap shortcuts to the main tools.
import { h, icon, iconBtn, toast } from './ui.js';
import { openPdf, renderPage } from './render.js';
import { byId, CATEGORIES } from './tools.js';

const QUICK = ['edit', 'sign', 'compress', 'organize', 'pdf2word', 'merge', 'protect', 'ocr'];

export async function mountViewer(root, ctx) {
  const file = ctx.S.viewFile;
  let zoom = 1;
  const pagesEl = h('div', { class: 'vw-pages' });
  const status = h('span', { class: 'small muted' }, 'Opening…');
  const quick = h('div', { class: 'vw-quick' }, ...QUICK.map((id) => {
    const t = byId(id); const c = CATEGORIES.find((x) => x.id === t.cat);
    return h('button', { class: 'chip row', style: { gap: '6px' }, onclick: () => ctx.useTool(t, file) }, h('span', { style: { color: c.tint, display: 'flex' } }, icon(t.id, 16)), t.title.replace(' PDF', '').replace('PDF to ', 'To '));
  }), h('button', { class: 'chip', onclick: () => ctx.useTool(null, file) }, 'All tools…'));

  root.replaceChildren(
    h('div', { class: 'topbar' }, iconBtn('back', 'Back', ctx.back), h('h2', { title: file.name }, file.name),
      iconBtn('minus', 'Zoom out', () => setZoom(zoom / 1.25)), iconBtn('plus', 'Zoom in', () => setZoom(zoom * 1.25))),
    h('div', { class: 'vw-wrap' }, pagesEl),
    h('div', { class: 'bottom vw-bar' }, h('div', { class: 'row', style: { marginBottom: '8px' } }, status), quick),
  );

  let pdf;
  try { pdf = await openPdf(new Uint8Array(await file.arrayBuffer())); }
  catch (e) {
    pagesEl.replaceChildren(h('div', { class: 'error', style: { margin: '24px auto', maxWidth: '520px' } }, e.message,
      e.locked && h('div', { style: { marginTop: '10px' } }, h('button', { class: 'btn primary', onclick: () => ctx.useTool(byId('unlock'), file) }, 'Unlock this PDF'))));
    status.textContent = ''; return;
  }
  status.textContent = `${pdf.numPages} page${pdf.numPages === 1 ? '' : 's'} · tap a tool to work on this file`;

  // placeholders sized to each page, rendered lazily
  const sizes = [];
  for (let i = 0; i < pdf.numPages; i++) { const v = (await pdf.getPage(i + 1)).getViewport({ scale: 1 }); sizes.push([v.width, v.height]); }
  const slots = sizes.map(([w, hh], i) => h('div', { class: 'vw-page', 'data-i': i, style: { aspectRatio: `${w} / ${hh}` } }, h('span', { class: 'vw-num' }, i + 1)));
  pagesEl.replaceChildren(...slots);
  const rendered = new Map();
  const draw = async (slot) => {
    const i = +slot.dataset.i; const width = Math.round(slot.clientWidth * Math.min(2.5, devicePixelRatio || 1));
    if (rendered.get(i) >= width) return; rendered.set(i, width);
    try { const c = await renderPage(pdf, i, width); c.className = 'vw-canvas'; slot.querySelector('canvas')?.remove(); slot.prepend(c); } catch (e) { toast(e.message); }
  };
  const io = new IntersectionObserver((entries) => entries.forEach((en) => { if (en.isIntersecting) draw(en.target); }), { rootMargin: '600px 0px' });
  slots.forEach((s) => io.observe(s));
  function setZoom(z) {
    zoom = Math.min(4, Math.max(0.5, z)); const base = Math.min(820, innerWidth - 32); pagesEl.style.setProperty('--vw-w', `${Math.round(base * zoom)}px`);
    requestAnimationFrame(() => slots.forEach((s) => { const r = s.getBoundingClientRect(); if (r.bottom > -600 && r.top < innerHeight + 600) draw(s); }));
  }
}
