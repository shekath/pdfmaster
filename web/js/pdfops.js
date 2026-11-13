// PDF operations with pdf-lib (editing) and qpdf (encryption, structure). Everything runs in the browser.
import { pdfLib, fontkit, qpdf, fontBytes, assetBytes } from './lib.js';
import { renderPage, canvasBytes, openPdf, pageSize } from './render.js';

export class LockedError extends Error { constructor(m) { super(m); this.locked = true; } }

/** Loads a PDF for editing. Owner-restricted files are decrypted with qpdf; password-protected ones throw LockedError. */
export async function load(bytes, password = '') {
  const { PDFDocument } = await pdfLib();
  let doc;
  try { doc = await PDFDocument.load(bytes, { updateMetadata: false }); } catch (e) {
    if (!/encrypt/i.test(String(e?.message))) throw new Error('This file is not a valid PDF or is badly damaged. Try “Repair PDF”.');
  }
  if (doc && !doc.isEncrypted) return doc;
  const r = await qpdf(['--password=' + password, '--decrypt', '/in.pdf', '/out.pdf'], { 'in.pdf': bytes }, 'out.pdf');
  if (!r.out || r.code === 2) {
    if (/password/i.test(r.log)) throw new LockedError(password ? 'Incorrect password.' : 'This PDF is protected with a password. Use “Unlock PDF” first.');
    throw new Error('Could not read this PDF: ' + r.log.split('\n')[0]);
  }
  return PDFDocument.load(r.out, { updateMetadata: false });
}

export async function isLocked(bytes) {
  try { await load(bytes); return false; } catch (e) { return !!e.locked; }
}

export async function pageCount(bytes) { return (await load(bytes)).getPageCount(); }

const save = (doc) => doc.save({ useObjectStreams: true });

// ---------- coordinates ----------
/** Displayed page size and the matrix mapping displayed coords (origin bottom-left) to user space. */
export function visual(page) {
  const b = page.getCropBox(); const rot = ((page.getRotation().angle % 360) + 360) % 360;
  const { x, y, width: w, height: h } = b;
  if (rot === 90) return { w: h, h: w, m: [0, 1, -1, 0, x + w, y], rot };
  if (rot === 180) return { w, h, m: [-1, 0, 0, -1, x + w, y + h], rot };
  if (rot === 270) return { w: h, h: w, m: [0, -1, 1, 0, x, y + h], rot };
  return { w, h, m: [1, 0, 0, 1, x, y], rot: 0 };
}
async function inVisual(page, fn) {
  const L = await pdfLib(); const v = visual(page);
  page.pushOperators(L.pushGraphicsState(), L.concatTransformationMatrix(...v.m));
  await fn(v.w, v.h);
  page.pushOperators(L.popGraphicsState());
}
const hex = async (c) => { const L = await pdfLib(); const n = typeof c === 'string' ? parseInt(c.replace('#', ''), 16) : c; return L.rgb(((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255); };

// ---------- fonts & scripts ----------
const SCRIPTS = [
  [/[ऀ-ॿ꣠-ꣿ᳐-᳿]/, 'NotoSansDevanagari'], [/[ঀ-৿]/, 'NotoSansBengali'], [/[਀-੿]/, 'NotoSansGurmukhi'],
  [/[઀-૿]/, 'NotoSansGujarati'], [/[଀-୿]/, 'NotoSansOriya'], [/[஀-௿]/, 'NotoSansTamil'], [/[ఀ-౿]/, 'NotoSansTelugu'],
  [/[ಀ-೿]/, 'NotoSansKannada'], [/[ഀ-ൿ]/, 'NotoSansMalayalam'], [/[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]/, 'NotoNaskhArabic'],
];
const NEUTRAL = /[\s0-9.,;:!?'"()\[\]{}\-–—_/\\@#%&*+=<>|~`^$‌‍◌]/;
export const needsShaping = (t) => SCRIPTS.some(([re]) => re.test(t));
const fontCache = new WeakMap();
async function font(doc, name) {
  if (!fontCache.has(doc)) { doc.registerFontkit(await fontkit()); fontCache.set(doc, new Map()); }
  const m = fontCache.get(doc);
  if (!m.has(name)) {
    const L = await pdfLib();
    // Basic Latin uses the built-in Helvetica (no embedding); other scripts embed the whole Noto font
    // (font subsetting in fontkit is unreliable for these fonts).
    const p = name === 'Helvetica' ? doc.embedFont(L.StandardFonts.Helvetica) : doc.embedFont(await fontBytes(name), { subset: false }).then((f) => {
      // Text drawn with these fonts is only ever the invisible search layer (visible Indic/Arabic text is drawn as
      // a browser-shaped image). Map characters straight to glyphs, so copy/search returns the original text.
      const fk = f.embedder?.font;
      if (fk && name !== 'NotoSans') fk.layout = (text) => ({ glyphs: Array.from(text, (ch) => fk.glyphForCodePoint(ch.codePointAt(0))) });
      return f;
    });
    m.set(name, p);
  }
  return m.get(name);
}
const WINANSI_EXTRA = '€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ';
function scriptOf(ch) {
  for (const [re, f] of SCRIPTS) if (re.test(ch)) return f;
  if (NEUTRAL.test(ch)) return null;
  const c = ch.codePointAt(0);
  return (c >= 0x20 && c <= 0x7e) || (c >= 0xa0 && c <= 0xff) || WINANSI_EXTRA.includes(ch) ? 'Helvetica' : 'NotoSans';
}
async function runs(doc, text) {
  const out = []; let cur = null; let buf = '';
  for (const ch of text.replace(/\t/g, ' ')) {
    const s = scriptOf(ch) ?? cur ?? 'Helvetica';
    if (cur && s !== cur && buf) { out.push([cur, buf]); buf = ''; }
    cur = s; buf += ch;
  }
  if (buf) out.push([cur ?? 'Helvetica', buf]);
  const res = [];
  for (const [name, t] of out) { const f = await font(doc, name); res.push([f, t]); }
  return res;
}
export async function textWidth(doc, text, size, perChar = false) { let w = 0; for (const [f, t] of await runs(doc, text)) { try { if (perChar && needsShaping(t)) for (const ch of t) w += f.widthOfTextAtSize(ch, size); else w += f.widthOfTextAtSize(t, size); } catch { /* glyph missing */ } } return w; }
/** Draws text of any script at baseline (x,y). opacity 0 = invisible searchable text. */
export async function drawRuns(doc, page, text, x, y, size, color, opacity = 1) {
  const L = await pdfLib(); let cx = x;
  for (const [f, t] of await runs(doc, text)) {
    try {
      page.drawText(t, { x: cx, y, size, font: f, color: color ?? L.rgb(0, 0, 0), opacity }); cx += f.widthOfTextAtSize(t, size);
    } catch { /* skip unsupported */ }
  }
  return cx - x;
}
/** Correctly shaped text (Indic, Arabic…) drawn as an image rendered by the browser, plus an invisible searchable layer. */
async function drawShaped(doc, page, text, x, top, size, colorHex, bold) {
  const scale = 4; const c = document.createElement('canvas'); const ctx = c.getContext('2d');
  const fontCss = `${bold ? '700 ' : ''}${size * scale}px "Noto Sans", system-ui, sans-serif`;
  ctx.font = fontCss; const m = ctx.measureText(text);
  const asc = m.fontBoundingBoxAscent ?? size * scale * 0.95; const desc = m.fontBoundingBoxDescent ?? size * scale * 0.3;
  c.width = Math.ceil(m.width) + 8; c.height = Math.ceil(asc + desc) + 4;
  ctx.font = fontCss; ctx.fillStyle = '#' + (colorHex >>> 0 & 0xffffff).toString(16).padStart(6, '0'); ctx.textBaseline = 'alphabetic';
  ctx.fillText(text, 4, asc + 2);
  const img = await doc.embedPng(await canvasBytes(c, 'image/png'));
  const w = c.width / scale, h = c.height / scale;
  const baseline = top - size * 0.93;
  page.drawImage(img, { x: x - 1, y: baseline - (c.height - asc - 2) / scale, width: w, height: h });
  const unit = await textWidth(doc, text, 1, true);
  await drawRuns(doc, page, text, x, baseline, unit > 0 ? Math.min(size * 1.6, (w - 2) / unit) : size, undefined, 0);
  return w;
}

// ---------- organise ----------
export async function merge(list, progress) {
  const { PDFDocument } = await pdfLib(); const out = await PDFDocument.create();
  for (let i = 0; i < list.length; i++) {
    const src = await load(list[i]);
    (await out.copyPages(src, src.getPageIndices())).forEach((p) => out.addPage(p));
    progress?.((i + 1) / list.length);
  }
  return save(out);
}

export async function extractGroups(bytes, groups, progress) {
  const { PDFDocument } = await pdfLib(); const src = await load(bytes); const outs = [];
  for (let g = 0; g < groups.length; g++) {
    const d = await PDFDocument.create();
    (await d.copyPages(src, groups[g])).forEach((p) => d.addPage(p));
    outs.push(await save(d)); progress?.((g + 1) / groups.length);
  }
  return outs;
}

export async function rotate(bytes, deg, set) {
  const { degrees } = await pdfLib(); const d = await load(bytes);
  d.getPages().forEach((p, i) => { if (!set || set.has(i)) p.setRotation(degrees((p.getRotation().angle + deg) % 360)); });
  return save(d);
}

/** refs: [{ src, index (-1 = blank), rot }] */
export async function organize(sources, refs) {
  const { PDFDocument, degrees, PageSizes } = await pdfLib();
  const docs = []; for (const s of sources) docs.push(await load(s));
  const out = await PDFDocument.create(); let last = PageSizes.A4;
  for (const r of refs) {
    if (r.index < 0) { const p = out.addPage(last); p.setRotation(degrees(r.rot % 360)); continue; }
    const [p] = await out.copyPages(docs[r.src], [r.index]);
    const base = p.getRotation().angle; p.setRotation(degrees((base + r.rot) % 360));
    const mb = p.getMediaBox(); last = [mb.width, mb.height];
    out.addPage(p);
  }
  return save(out);
}

// ---------- stamps ----------
export async function watermarkText(bytes, o) {
  const L = await pdfLib(); const d = await load(bytes); const color = await hex(o.color);
  const rad = (o.angle * Math.PI) / 180;
  for (const [i, page] of d.getPages().entries()) {
    if (o.pages && !o.pages.has(i)) continue;
    await inVisual(page, async (w, h) => {
      const tw = await textWidth(d, o.text, o.size);
      const centers = [];
      if (o.tile) { const sx = tw + o.size * 3, sy = o.size * 5; for (let y = sy / 2, r = 0; y < h + sy; y += sy, r++) for (let x = r % 2 ? sx * 0.75 : sx * 0.25; x < w + sx; x += sx) centers.push([x, y]); }
      else centers.push([w / 2, h / 2]);
      for (const [cx, cy] of centers) {
        const dx = -tw / 2, dy = -o.size / 3;
        const x = cx + dx * Math.cos(rad) - dy * Math.sin(rad), y = cy + dx * Math.sin(rad) + dy * Math.cos(rad);
        for (const [f, t] of await runs(d, o.text)) page.drawText(t, { x, y, size: o.size, font: f, color, opacity: o.opacity, rotate: L.degrees(o.angle) });
      }
    });
  }
  return save(d);
}

export async function watermarkImage(bytes, imgBytes, isPng, o) {
  const d = await load(bytes); const img = isPng ? await d.embedPng(imgBytes) : await d.embedJpg(imgBytes);
  for (const [i, page] of d.getPages().entries()) {
    if (o.pages && !o.pages.has(i)) continue;
    await inVisual(page, (w, h) => {
      const iw = w * o.scale, ih = iw * img.height / img.width;
      if (!o.tile) page.drawImage(img, { x: (w - iw) / 2, y: (h - ih) / 2, width: iw, height: ih, opacity: o.opacity });
      else for (let y = ih * 0.5; y < h; y += ih * 1.8) for (let x = iw * 0.25; x < w; x += iw * 1.6) page.drawImage(img, { x, y, width: iw, height: ih, opacity: o.opacity });
    });
  }
  return save(d);
}

export async function pageNumbers(bytes, o) {
  const d = await load(bytes); const color = await hex(0x333333);
  const total = d.getPageCount() - (o.skipFirst ? 1 : 0); let n = o.start;
  for (const [i, page] of d.getPages().entries()) {
    if (o.skipFirst && i === 0) continue;
    const last = total + o.start - 1;
    const label = { page_n: `Page ${n}`, n_of_t: `${n} / ${last}`, page_n_of_t: `Page ${n} of ${last}` }[o.format] ?? String(n);
    n++;
    await inVisual(page, async (w, h) => {
      const tw = await textWidth(d, label, o.size); const m = Math.max(18, Math.min(w, h) * 0.04);
      const x = o.position[1] === 'l' ? m : o.position[1] === 'r' ? w - m - tw : (w - tw) / 2;
      const y = o.position[0] === 't' ? h - m - o.size * 0.8 : m;
      await drawRuns(d, page, label, x, y, o.size, color);
    });
  }
  return save(d);
}

/** Crop with a normalised displayed box {l,t,r,b}; pageIndex null = all pages. */
export async function crop(bytes, box, pageIndex) {
  const d = await load(bytes);
  d.getPages().forEach((page, i) => {
    if (pageIndex != null && i !== pageIndex) return;
    const v = visual(page); const [a, b, c, dd, e, f] = v.m;
    const tp = (x, y) => [a * x + c * y + e, b * x + dd * y + f];
    const p1 = tp(box.l * v.w, v.h - box.t * v.h), p2 = tp(box.r * v.w, v.h - box.b * v.h);
    const x = Math.min(p1[0], p2[0]), y = Math.min(p1[1], p2[1]), w = Math.abs(p1[0] - p2[0]), h = Math.abs(p1[1] - p2[1]);
    page.setCropBox(x, y, w, h); page.setMediaBox(x, y, w, h);
  });
  return save(d);
}

// ---------- editor overlays ----------
/** items: Text{x,y,text,size,color} Ink{points,width,color} Box{l,t,r,b,color,alpha} Replace{l,t,r,b,text,size,color,bg,bold} Picture{x,y,w,h,png} */
export async function applyEdits(bytes, items) {
  const L = await pdfLib(); const d = await load(bytes); const pages = d.getPages(); const imgs = new Map();
  const byPage = new Map(); items.forEach((it) => { if (!byPage.has(it.page)) byPage.set(it.page, []); byPage.get(it.page).push(it); });
  for (const [pi, list] of byPage) {
    const page = pages[pi]; if (!page) continue;
    await inVisual(page, async (w, h) => {
      for (const it of list) {
        if (it.type === 'box') page.drawRectangle({ x: it.l * w, y: h - it.b * h, width: (it.r - it.l) * w, height: (it.b - it.t) * h, color: await hex(it.color), opacity: it.alpha });
        else if (it.type === 'ink' && it.points.length > 1) {
          const path = it.points.map(([x, y], k) => `${k ? 'L' : 'M'}${(x * w).toFixed(2)} ${(y * h).toFixed(2)}`).join(' ');
          page.drawSvgPath(path, { x: 0, y: h, borderColor: await hex(it.color), borderWidth: it.width * w, borderLineCap: L.LineCapStyle.Round });
        } else if (it.type === 'text') {
          const size = it.size * w; const lines = it.text.split('\n');
          for (let k = 0; k < lines.length; k++) {
            const top = h - it.y * h - k * size * 1.2;
            if (needsShaping(lines[k])) await drawShaped(d, page, lines[k], it.x * w, top, size, it.color, false);
            else await drawRuns(d, page, lines[k], it.x * w, top - size * 0.93, size, await hex(it.color));
          }
        } else if (it.type === 'replace') {
          const size = it.size * w; const text = it.text.replace(/\n/g, ' ');
          const tw = await textWidth(d, text, size) * (needsShaping(text) ? 1.05 : 1);
          const bw = Math.max((it.r - it.l) * w, tw + size * 0.2);
          page.drawRectangle({ x: it.l * w - 0.5, y: h - it.b * h - 0.5, width: bw + 1, height: (it.b - it.t) * h + 1, color: await hex(it.bg) });
          if (needsShaping(text) || it.bold) await drawShaped(d, page, text, it.l * w, h - it.t * h, size, it.color, it.bold);
          else await drawRuns(d, page, text, it.l * w, h - it.t * h - size * 0.93, size, await hex(it.color));
        } else if (it.type === 'picture') {
          if (!imgs.has(it.png)) imgs.set(it.png, await d.embedPng(it.png));
          page.drawImage(imgs.get(it.png), { x: it.x * w, y: h - (it.y + it.h) * h, width: it.w * w, height: it.h * h });
        }
      }
    });
  }
  return save(d);
}

/** True redaction: pages with boxes are re-rendered as images with the boxes burned in. */
export async function redact(bytes, boxes, progress) {
  const { PDFDocument } = await pdfLib(); const src = await load(bytes); const pdf = await openPdf(bytes);
  const out = await PDFDocument.create(); const byPage = new Map();
  boxes.forEach((b) => { if (!byPage.has(b.page)) byPage.set(b.page, []); byPage.get(b.page).push(b); });
  for (let i = 0; i < src.getPageCount(); i++) {
    const list = byPage.get(i);
    if (!list) { const [p] = await out.copyPages(src, [i]); out.addPage(p); continue; }
    const size = await pageSize(pdf, i); const c = await renderPage(pdf, i, Math.round(size.w / 72 * 200));
    const ctx = c.getContext('2d'); ctx.fillStyle = '#000';
    list.forEach((b) => ctx.fillRect(b.l * c.width, b.t * c.height, (b.r - b.l) * c.width, (b.b - b.t) * c.height));
    const img = await out.embedJpg(await canvasBytes(c, 'image/jpeg', 0.9));
    out.addPage([size.w, size.h]).drawImage(img, { x: 0, y: 0, width: size.w, height: size.h });
    progress?.((i + 1) / src.getPageCount());
  }
  out.setProducer('PDF Master'); out.setCreator('PDF Master');
  return save(out);
}

/** Adds invisible OCR text. pages: [{ index, lines:[{text,l,t,r,b}] }] in normalised display coords. */
export async function addTextLayer(bytes, pagesText) {
  const d = await load(bytes); const pages = d.getPages();
  for (const pt of pagesText) {
    const page = pages[pt.index]; if (!page) continue;
    await inVisual(page, async (w, h) => {
      for (const ln of pt.lines) {
        const bw = (ln.r - ln.l) * w, bh = (ln.b - ln.t) * h;
        const unit = await textWidth(d, ln.text, 1, true); if (unit <= 0) continue;
        const fs = Math.min(Math.max(1, bw / unit), Math.max(2, bh * 1.6));
        await drawRuns(d, page, ln.text, ln.l * w, h - ln.b * h + bh * 0.2, fs, undefined, 0);
      }
    });
  }
  return save(d);
}

// ---------- images → PDF ----------
async function decodeImage(file, maxDim = 3000) {
  const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' }).catch(() => null);
  if (!bmp) throw new Error(`“${file.name}” is not a supported image`);
  const s = Math.min(1, maxDim / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas'); c.width = Math.round(bmp.width * s); c.height = Math.round(bmp.height * s);
  const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height); ctx.drawImage(bmp, 0, 0, c.width, c.height);
  return c;
}
export function enhance(c, mode) {
  if (mode === 'color') return c;
  const ctx = c.getContext('2d'); const img = ctx.getImageData(0, 0, c.width, c.height); const p = img.data;
  // estimate paper brightness, then stretch contrast
  let sum = 0; for (let i = 0; i < p.length; i += 16) sum += (p[i] + p[i + 1] + p[i + 2]) / 3; const paper = Math.max(120, sum / (p.length / 16));
  for (let i = 0; i < p.length; i += 4) {
    const g = 0.299 * p[i] + 0.587 * p[i + 1] + 0.114 * p[i + 2];
    let v = Math.min(255, Math.max(0, (g - 40) * 255 / (paper - 40)));
    if (mode === 'bw') v = v > 150 ? 255 : 0;
    p[i] = p[i + 1] = p[i + 2] = v;
  }
  ctx.putImageData(img, 0, 0); return c;
}
export async function imagesToPdf(files, o, progress) {
  const { PDFDocument, PageSizes } = await pdfLib(); const d = await PDFDocument.create();
  for (let i = 0; i < files.length; i++) {
    const c = enhance(await decodeImage(files[i]), o.enhance ?? 'color');
    const img = await d.embedJpg(await canvasBytes(c, 'image/jpeg', 0.88));
    const m = Number(o.margin ?? 0); let pw, ph;
    if (o.size === 'a4' || o.size === 'letter') {
      const [bw, bh] = o.size === 'a4' ? PageSizes.A4 : PageSizes.Letter;
      const land = o.orientation === 'landscape' || (o.orientation !== 'portrait' && c.width > c.height);
      [pw, ph] = land ? [bh, bw] : [bw, bh];
    } else { let w = c.width * 72 / 150, h = c.height * 72 / 150; const s = Math.min(1, 1008 / Math.max(w, h)); pw = w * s + 2 * m; ph = h * s + 2 * m; }
    const s = Math.min((pw - 2 * m) / c.width, (ph - 2 * m) / c.height); const dw = c.width * s, dh = c.height * s;
    d.addPage([pw, ph]).drawImage(img, { x: (pw - dw) / 2, y: (ph - dh) / 2, width: dw, height: dh });
    progress?.((i + 1) / files.length);
  }
  return save(d);
}

// ---------- optimise ----------
const LEVELS = { low: { q: 0.82, max: 2400 }, recommended: { q: 0.65, max: 1600 }, extreme: { q: 0.45, max: 1100 } };
export async function compress(bytes, level, progress) {
  const L = await pdfLib(); const d = await load(bytes); const ctx = d.context; const cfg = LEVELS[level] ?? LEVELS.recommended;
  const N = (n) => L.PDFName.of(n);
  const objs = ctx.enumerateIndirectObjects(); let k = 0;
  for (const [ref, obj] of objs) {
    k++; if (k % 20 === 0) progress?.(0.8 * k / objs.length);
    if (!(obj instanceof L.PDFRawStream)) continue;
    const dict = obj.dict;
    if (String(dict.get(N('Subtype'))) !== '/Image') continue;
    if (dict.get(N('ImageMask')) || dict.get(N('Mask'))) continue;
    const width = dict.get(N('Width'))?.asNumber?.() ?? 0, height = dict.get(N('Height'))?.asNumber?.() ?? 0;
    if (width * height < 128 * 128) continue;
    const filter = String(dict.get(N('Filter')) ?? '');
    const cs = String(dict.get(N('ColorSpace')) ?? '');
    let canvas = null;
    try {
      if (filter === '/DCTDecode' && (cs === '/DeviceRGB' || cs === '/DeviceGray' || cs === '')) {
        const bmp = await createImageBitmap(new Blob([obj.contents], { type: 'image/jpeg' }));
        canvas = document.createElement('canvas'); canvas.width = bmp.width; canvas.height = bmp.height; canvas.getContext('2d').drawImage(bmp, 0, 0);
      } else if (filter === '/FlateDecode' && !dict.get(N('DecodeParms')) && (cs === '/DeviceRGB' || cs === '/DeviceGray') && dict.get(N('BitsPerComponent'))?.asNumber?.() === 8) {
        const raw = L.decodePDFRawStream(obj).decode(); const ch = cs === '/DeviceRGB' ? 3 : 1;
        if (raw.length < width * height * ch) continue;
        canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
        const c2 = canvas.getContext('2d'); const id = c2.createImageData(width, height);
        for (let i = 0, j = 0; i < width * height; i++, j += ch) { const o = i * 4; id.data[o] = raw[j]; id.data[o + 1] = raw[j + (ch === 3 ? 1 : 0)]; id.data[o + 2] = raw[j + (ch === 3 ? 2 : 0)]; id.data[o + 3] = 255; }
        c2.putImageData(id, 0, 0);
      }
    } catch { canvas = null; }
    if (!canvas) continue;
    const s = Math.min(1, cfg.max / Math.max(canvas.width, canvas.height));
    const out = document.createElement('canvas'); out.width = Math.max(1, Math.round(canvas.width * s)); out.height = Math.max(1, Math.round(canvas.height * s));
    out.getContext('2d').drawImage(canvas, 0, 0, out.width, out.height);
    const jpg = await canvasBytes(out, 'image/jpeg', cfg.q);
    if (jpg.length >= obj.contents.length * 0.9) continue;
    const nd = { Type: 'XObject', Subtype: 'Image', Width: out.width, Height: out.height, ColorSpace: 'DeviceRGB', BitsPerComponent: 8, Filter: 'DCTDecode' };
    const stream = ctx.stream(jpg, nd);
    const sm = dict.get(N('SMask')); if (sm) stream.dict.set(N('SMask'), sm);
    ctx.assign(ref, stream);
  }
  let outBytes = await save(d);
  const q = await qpdf(['--object-streams=generate', '--compress-streams=y', '--recompress-flate', '--compression-level=9', '/in.pdf', '/out.pdf'], { 'in.pdf': outBytes }, 'out.pdf');
  if (q.out && q.code !== 2 && q.out.length < outBytes.length) outBytes = q.out;
  progress?.(1);
  return outBytes.length < bytes.length ? { bytes: outBytes, smaller: true } : { bytes, smaller: false };
}

export async function repair(bytes, progress) {
  const q = await qpdf(['/in.pdf', '/out.pdf'], { 'in.pdf': bytes }, 'out.pdf');
  if (q.out && q.code !== 2) { try { await load(q.out); return { bytes: q.out, msg: 'Rebuilt the document structure.' }; } catch { /* fall through */ } }
  // pdf.js is very tolerant: recover readable pages as images
  const { PDFDocument } = await pdfLib(); const pdf = await openPdf(bytes).catch(() => null);
  if (!pdf) throw new Error('The file is too damaged to recover.');
  const out = await PDFDocument.create();
  for (let i = 0; i < pdf.numPages; i++) {
    try {
      const size = await pageSize(pdf, i); const c = await renderPage(pdf, i, Math.round(size.w / 72 * 200));
      out.addPage([size.w, size.h]).drawImage(await out.embedJpg(await canvasBytes(c)), { x: 0, y: 0, width: size.w, height: size.h });
    } catch { /* skip unreadable page */ }
    progress?.((i + 1) / pdf.numPages);
  }
  if (!out.getPageCount()) throw new Error('The file is too damaged to recover.');
  return { bytes: await save(out), msg: 'The structure couldn’t be rebuilt, so the readable pages were recovered as images.' };
}

export async function pdfa(bytes, part, title) {
  const L = await pdfLib(); const d = await load(bytes); const ctx = d.context; const S = L.PDFString.of;
  const now = new Date(); d.setTitle(d.getTitle() || title); d.setProducer('PDF Master'); d.setCreator('PDF Master'); d.setModificationDate(now); if (!d.getCreationDate()) d.setCreationDate(now);
  const iso = now.toISOString().replace(/\.\d+Z$/, 'Z');
  const esc = (s) => s.replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const xmp = `<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/"><pdfaid:part>${part}</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(d.getTitle() || title)}</rdf:li></rdf:Alt></dc:title></rdf:Description>
<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/"><xmp:CreateDate>${iso}</xmp:CreateDate><xmp:ModifyDate>${iso}</xmp:ModifyDate><xmp:CreatorTool>PDF Master</xmp:CreatorTool></rdf:Description>
<rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/"><pdf:Producer>PDF Master</pdf:Producer></rdf:Description>
</rdf:RDF></x:xmpmeta><?xpacket end="w"?>`;
  const meta = ctx.stream(new TextEncoder().encode(xmp), { Type: 'Metadata', Subtype: 'XML' });
  d.catalog.set(L.PDFName.of('Metadata'), ctx.register(meta));
  const icc = ctx.stream(new Uint8Array(await assetBytes('srgb.icc')), { N: 3 });
  const oi = ctx.obj({ Type: 'OutputIntent', S: 'GTS_PDFA1', OutputConditionIdentifier: S('sRGB IEC61966-2.1'), Info: S('sRGB IEC61966-2.1'), RegistryName: S('http://www.color.org'), DestOutputProfile: ctx.register(icc) });
  d.catalog.set(L.PDFName.of('OutputIntents'), ctx.obj([ctx.register(oi)]));
  return d.save({ useObjectStreams: false });
}

// ---------- security (qpdf) ----------
export async function protect(bytes, password, allow) {
  const src = await save(await load(bytes));
  const r = await qpdf(['--encrypt', password, crypto.randomUUID(), '256', `--print=${allow.print ? 'full' : 'none'}`, `--extract=${allow.copy ? 'y' : 'n'}`, `--modify=${allow.modify ? 'all' : 'none'}`, '--', '/in.pdf', '/out.pdf'], { 'in.pdf': src }, 'out.pdf');
  if (!r.out || r.code === 2) throw new Error('Could not encrypt: ' + r.log);
  return r.out;
}
export async function unlock(bytes, password) {
  const r = await qpdf(['--password=' + password, '--decrypt', '/in.pdf', '/out.pdf'], { 'in.pdf': bytes }, 'out.pdf');
  if (!r.out || r.code === 2) throw new LockedError(/password/i.test(r.log) || !r.log.trim() ? 'Incorrect password.' : 'Could not unlock: ' + r.log.split('\n')[0]);
  return r.out;
}

// ---------- images out ----------
export async function extractImages(bytes) {
  const L = await pdfLib(); const d = await load(bytes); const N = (n) => L.PDFName.of(n); const out = [];
  for (const [, obj] of d.context.enumerateIndirectObjects()) {
    if (!(obj instanceof L.PDFRawStream) || String(obj.dict.get(N('Subtype'))) !== '/Image') continue;
    const w = obj.dict.get(N('Width'))?.asNumber?.() ?? 0, h = obj.dict.get(N('Height'))?.asNumber?.() ?? 0;
    if (w < 16 || h < 16) continue;
    const filter = String(obj.dict.get(N('Filter')) ?? ''); const cs = String(obj.dict.get(N('ColorSpace')) ?? '');
    if (filter === '/DCTDecode') { out.push({ ext: 'jpg', bytes: obj.contents }); continue; }
    if (filter === '/FlateDecode' && (cs === '/DeviceRGB' || cs === '/DeviceGray') && !obj.dict.get(N('DecodeParms'))) {
      try {
        const raw = L.decodePDFRawStream(obj).decode(); const ch = cs === '/DeviceRGB' ? 3 : 1; if (raw.length < w * h * ch) continue;
        const c = document.createElement('canvas'); c.width = w; c.height = h; const ctx = c.getContext('2d'); const id = ctx.createImageData(w, h);
        for (let i = 0, j = 0; i < w * h; i++, j += ch) { id.data[i * 4] = raw[j]; id.data[i * 4 + 1] = raw[j + (ch === 3 ? 1 : 0)]; id.data[i * 4 + 2] = raw[j + (ch === 3 ? 2 : 0)]; id.data[i * 4 + 3] = 255; }
        ctx.putImageData(id, 0, 0); out.push({ ext: 'png', bytes: await canvasBytes(c, 'image/png') });
      } catch { /* unsupported */ }
    }
  }
  return out;
}

export function parseRanges(spec, count) {
  const parts = spec.split(/[,; ]+/).map((s) => s.trim()).filter(Boolean);
  if (!parts.length) throw new Error('Enter at least one page or range, e.g. 1-3, 5');
  return parts.map((p) => {
    const m = /^(\d*)\s*-\s*(\d*)$/.exec(p); let a, b;
    if (m) { a = m[1] ? +m[1] : 1; b = m[2] ? +m[2] : count; } else { if (!/^\d+$/.test(p)) throw new Error(`“${p}” is not a page number`); a = b = +p; }
    if (a < 1 || b > count || a > count || b < 1) throw new Error(`Page range “${p}” is outside this document (1-${count})`);
    if (a > b) throw new Error(`Range “${p}” is backwards`);
    return [a, b];
  });
}
export const pageSet = (spec, count) => spec?.trim() ? new Set(parseRanges(spec, count).flatMap(([a, b]) => Array.from({ length: b - a + 1 }, (_, k) => a - 1 + k))) : null;
