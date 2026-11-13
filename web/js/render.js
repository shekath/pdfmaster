// Rendering and text extraction (pdf.js). Coordinates in "display" space: page rotation applied, origin top-left, points.
import { pdfjs } from './lib.js';

export async function openPdf(bytes, password) {
  const lib = await pdfjs();
  const task = lib.getDocument({ data: bytes.slice(0), password, isEvalSupported: false, disableFontFace: false });
  try { return await task.promise; } catch (e) {
    if (e?.name === 'PasswordException') { const err = new Error(password ? 'Incorrect password.' : 'This PDF is protected with a password. Use “Unlock PDF” first.'); err.locked = true; throw err; }
    throw e;
  }
}

export async function pageSize(doc, i) {
  const page = await doc.getPage(i + 1);
  const v = page.getViewport({ scale: 1 });
  return { w: v.width, h: v.height };
}

const MAX_PX = 12_000_000; // iOS Safari canvas limit is ~16.7 MP

/** Renders page i (0-based) to a canvas `widthPx` wide on white. */
export async function renderPage(doc, i, widthPx) {
  const page = await doc.getPage(i + 1);
  const base = page.getViewport({ scale: 1 });
  let scale = widthPx / base.width;
  if (base.width * base.height * scale * scale > MAX_PX) scale = Math.sqrt(MAX_PX / (base.width * base.height));
  const vp = page.getViewport({ scale });
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.floor(vp.width)); c.height = Math.max(1, Math.floor(vp.height));
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  await page.render({ canvasContext: ctx, viewport: vp }).promise;
  return c;
}

export const canvasBlob = (c, type = 'image/jpeg', q = 0.9) => new Promise((res) => c.toBlob(res, type, q));
export async function canvasBytes(c, type = 'image/jpeg', q = 0.9) { return new Uint8Array(await (await canvasBlob(c, type, q)).arrayBuffer()); }

/**
 * Text lines of page i. Each line: { text, x, top, bottom, size, bold, chars:[{l,r}], cells:[{x0,x1,text}] } in points,
 * plus normalised box { nl, nt, nr, nb } and nsize (fraction of page width).
 */
export async function textLines(doc, i) {
  const lib = await pdfjs();
  const page = await doc.getPage(i + 1);
  const vp = page.getViewport({ scale: 1 });
  const tc = await page.getTextContent();
  const items = [];
  for (const it of tc.items) {
    if (!it.str || !it.str.trim()) continue;
    const tx = lib.Util.transform(vp.transform, it.transform);
    const size = Math.hypot(tx[2], tx[3]);
    if (size < 1) continue;
    // only roughly horizontal text
    if (Math.abs(tx[1]) > Math.abs(tx[0]) * 0.3) continue;
    const width = Math.abs(it.width * (vp.transform[0] || vp.transform[1] ? Math.hypot(vp.transform[0], vp.transform[1]) : 1)) || it.str.length * size * 0.5;
    let fontName = '';
    try { if (page.commonObjs.has(it.fontName)) fontName = page.commonObjs.get(it.fontName)?.name || ''; } catch { /* not loaded */ }
    items.push({ str: it.str, x: tx[4], y: tx[5], size, width, bold: /bold|black|heavy/i.test(fontName) });
  }
  items.sort((a, b) => a.y - b.y || a.x - b.x);
  const groups = [];
  for (const it of items) {
    const g = groups[groups.length - 1];
    if (g && Math.abs(it.y - g.y) <= Math.max(2, Math.min(it.size, g.size) * 0.45)) g.items.push(it);
    else groups.push({ y: it.y, size: it.size, items: [it] });
  }
  const lines = groups.map((g) => {
    const its = g.items.sort((a, b) => a.x - b.x);
    let text = ''; const chars = []; const cells = []; let cell = { x0: its[0].x, x1: its[0].x, text: '' };
    let prevEnd = null;
    for (const it of its) {
      if (prevEnd !== null) {
        const gap = it.x - prevEnd;
        if (gap > Math.max(it.size * 1.2, 6)) { cells.push({ ...cell, text: cell.text.trim() }); cell = { x0: it.x, x1: it.x, text: '' }; }
        if (gap > it.size * 0.2 && !text.endsWith(' ') && !it.str.startsWith(' ')) { text += ' '; chars.push({ l: prevEnd, r: it.x }); cell.text += ' '; }
      }
      const cw = it.width / Math.max(1, it.str.length);
      [...it.str].forEach((ch, k) => { chars.push({ l: it.x + k * cw, r: it.x + (k + 1) * cw }); });
      // keep chars aligned with UTF-16 length for substring search
      const extra = it.str.length - [...it.str].length; for (let k = 0; k < extra; k++) chars.push(chars[chars.length - 1]);
      text += it.str; cell.text += it.str; cell.x1 = it.x + it.width;
      prevEnd = it.x + it.width;
    }
    cells.push({ ...cell, text: cell.text.trim() });
    const size = its.map((i) => i.size).sort((a, b) => a - b)[Math.floor(its.length / 2)];
    const x = its[0].x, right = prevEnd, top = g.y - size * 0.92, bottom = g.y + size * 0.24;
    return {
      text, x, top, bottom, size, bold: its.filter((i) => i.bold).length > its.length / 2, chars, cells, baseline: g.y,
      nl: Math.max(0, x / vp.width), nt: Math.max(0, top / vp.height), nr: Math.min(1, right / vp.width), nb: Math.min(1, bottom / vp.height), nsize: size / vp.width,
    };
  });
  return { w: vp.width, h: vp.height, lines };
}

export async function allText(doc) {
  const out = [];
  for (let i = 0; i < doc.numPages; i++) out.push((await textLines(doc, i)).lines.map((l) => l.text).join('\n'));
  return out;
}

/** Groups lines into paragraphs. */
export function paragraphs(lines) {
  const out = []; let cur = ''; let prev = null;
  for (const l of lines) {
    const brk = !prev || (l.top - prev.bottom) > Math.max(prev.size, l.size) * 0.9 || Math.abs(l.size - prev.size) > 1.5;
    if (brk && cur) { out.push(cur); cur = ''; }
    if (cur) cur = cur.endsWith('-') ? cur.slice(0, -1) : cur + ' ';
    cur += l.text; prev = l;
  }
  if (cur) out.push(cur);
  return out;
}

/** Aligns each line's cells to shared columns (simple table detection). */
export function tableRows(lines) {
  const starts = lines.flatMap((l) => l.cells.map((c) => c.x0)).sort((a, b) => a - b);
  const cols = [];
  for (const s of starts) if (!cols.length || s - cols[cols.length - 1] > 12) cols.push(s);
  return lines.map((l) => {
    const row = cols.map(() => '');
    for (const c of l.cells) {
      let idx = -1; cols.forEach((x, k) => { if (x <= c.x0 + 6) idx = k; });
      if (idx < 0) idx = 0;
      row[idx] = row[idx] ? row[idx] + ' ' + c.text : c.text;
    }
    while (row.length && !row[row.length - 1]) row.pop();
    return row;
  });
}
