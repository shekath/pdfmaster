// Organize pages: reorder, rotate, delete, duplicate, blank pages, insert pages from other PDFs.
import { h, icon, iconBtn, pickFiles, toast } from './ui.js';
import { openPdf, renderPage } from './render.js';
import * as P from './pdfops.js';

export async function mountOrganizer(root, ctx) {
  const sources = []; const docs = []; let pages = []; let nextId = 0;
  async function addSource(file) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const doc = await openPdf(bytes); sources.push(bytes); docs.push(doc);
    const src = sources.length - 1;
    for (let i = 0; i < doc.numPages; i++) pages.push({ id: nextId++, src, index: i, rot: 0 });
  }
  const thumbs = new Map();
  async function thumb(p) {
    const key = p.src + ':' + p.index; if (p.index < 0) return null;
    if (!thumbs.has(key)) thumbs.set(key, renderPage(docs[p.src], p.index, 260).then((c) => c.toDataURL('image/jpeg', 0.8)));
    return thumbs.get(key);
  }
  const grid = h('div', { class: 'thumbs' });
  function renderGrid() {
    grid.replaceChildren(...pages.map((p, i) => {
      const img = h('img', { alt: `Page ${i + 1}`, style: { transform: `rotate(${p.rot}deg)` } });
      if (p.index >= 0) thumb(p).then((u) => { img.src = u; }); else { img.style.cssText += ';width:70%;aspect-ratio:.77'; img.alt = 'Blank page'; }
      return h('div', { class: 'card thumb' },
        h('div', { class: 'img' }, img, h('span', { class: 'n' }, i + 1)),
        h('div', { class: 'acts' },
          iconBtn('left', 'Move earlier', () => { if (i > 0) { [pages[i - 1], pages[i]] = [pages[i], pages[i - 1]]; renderGrid(); } }, { disabled: i === 0 }),
          iconBtn('rotateR', 'Rotate', () => { p.rot = (p.rot + 90) % 360; renderGrid(); }),
          iconBtn('dup', 'Duplicate', () => { pages.splice(i + 1, 0, { ...p, id: nextId++ }); renderGrid(); }),
          iconBtn('trash', 'Delete', () => { pages.splice(i, 1); renderGrid(); }),
          iconBtn('right', 'Move later', () => { if (i < pages.length - 1) { [pages[i + 1], pages[i]] = [pages[i], pages[i + 1]]; renderGrid(); } }, { disabled: i === pages.length - 1 })));
    }));
  }
  await addSource(ctx.file);
  const save = () => {
    if (!pages.length) { toast('The document has no pages left'); return; }
    const base = ctx.file.name.replace(/\.[^.]+$/, '');
    ctx.runJob(async () => {
      ctx.progress(-1, 'Saving…');
      const out = await P.organize(sources, pages.map(({ src, index, rot }) => ({ src, index, rot })));
      return { outputs: [{ name: base + '_organized.pdf', blob: new Blob([out], { type: 'application/pdf' }) }], message: `Saved ${pages.length} page(s) in the new order.` };
    });
  };
  root.replaceChildren(
    h('div', { class: 'topbar' }, iconBtn('back', 'Back', ctx.back), h('h2', {}, 'Organize PDF'), h('button', { class: 'btn primary', style: { padding: '9px 18px' }, onclick: save }, 'Save')),
    h('div', { class: 'page', style: { maxWidth: '980px' } }, grid),
    h('div', { class: 'bottom' }, h('div', { class: 'row' },
      h('button', { class: 'btn outline grow', onclick: async () => { const [f] = await pickFiles('.pdf,application/pdf', false); if (f) { try { await addSource(f); renderGrid(); } catch (e) { toast(e.message, 5000); } } } }, icon('plus'), 'Add PDF'),
      h('button', { class: 'btn outline grow', onclick: () => { pages.push({ id: nextId++, src: 0, index: -1, rot: 0 }); renderGrid(); } }, icon('pdf'), 'Blank page'))));
  renderGrid();
}
