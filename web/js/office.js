// Office ⇄ PDF. Office → PDF renders HTML with the browser (correct shaping for every script) and adds an
// invisible text layer so results stay searchable. PDF → Office writes OOXML directly.
import { pdfLib, jszip, mammoth, xlsx, html2canvas } from './lib.js';
import { canvasBytes, renderPage, pageSize, textLines, paragraphs, tableRows } from './render.js';
import { drawRuns, textWidth } from './pdfops.js';

export const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '');

const BASE_CSS = `body{margin:0;padding:48px 52px;font:15px/1.5 -apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif;color:#111;background:#fff;word-wrap:break-word}
img{max-width:100%;height:auto}table{border-collapse:collapse;margin:8px 0;max-width:100%}td,th{border:1px solid #bbb;padding:3px 6px;vertical-align:top;font-size:13px}
th{background:#eef1f6}h1{font-size:26px}h2{font-size:21px}h3{font-size:18px}p{margin:0 0 9px}pre{white-space:pre-wrap;font:14px/1.5 ui-monospace,Menlo,monospace}`;

/** Renders an HTML string to PDF pages (A4/Letter portrait or landscape). */
export async function htmlToPdf(html, { paper = 'a4', landscape = false, css = '', progress } = {}) {
  const h2c = await html2canvas(); const L = await pdfLib();
  const [pw0, ph0] = paper === 'letter' ? [612, 792] : [595.28, 841.89];
  const [pw, ph] = landscape ? [ph0, pw0] : [pw0, ph0];
  const cssW = Math.round(pw / 0.75); // 1pt = 1/72in, 1css px = 1/96in
  const frame = document.createElement('iframe');
  frame.setAttribute('sandbox', 'allow-same-origin');
  frame.style.cssText = `position:fixed;left:-20000px;top:0;width:${cssW}px;height:1200px;border:0;visibility:hidden`;
  document.body.appendChild(frame);
  try {
    await new Promise((res) => { frame.onload = res; frame.srcdoc = `<!doctype html><html><head><meta charset="utf-8"><style>${BASE_CSS}${css}</style></head><body>${html}</body></html>`; });
    const doc = frame.contentDocument;
    await Promise.all([...doc.images].map((im) => im.complete ? 0 : new Promise((r) => { im.onload = im.onerror = r; })));
    await (doc.fonts?.ready ?? Promise.resolve());
    const fullH = Math.max(doc.documentElement.scrollHeight, doc.body.scrollHeight);
    frame.style.height = fullH + 'px';
    const scale = Math.min(2, Math.sqrt(11_000_000 / (cssW * fullH)) || 1);
    const words = collectText(doc);
    progress?.(0.3);
    const canvas = await h2c(doc.documentElement, { scale, backgroundColor: '#ffffff', windowWidth: cssW, width: cssW, height: fullH, logging: false, useCORS: true });
    progress?.(0.7);
    // slice into pages, preferring cuts on blank rows
    const pageHpx = Math.round(cssW * scale * ph / pw);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const cuts = [0];
    while (canvas.height - cuts[cuts.length - 1] > pageHpx) {
      let cut = cuts[cuts.length - 1] + pageHpx;
      for (let y = cut; y > cut - pageHpx * 0.18; y--) {
        const row = ctx.getImageData(0, y, canvas.width, 1).data; let blank = true;
        for (let x = 0; x < row.length; x += 16) if (row[x] < 245 || row[x + 1] < 245 || row[x + 2] < 245) { blank = false; break; }
        if (blank) { cut = y; break; }
      }
      cuts.push(cut);
    }
    cuts.push(canvas.height);
    const pdf = await L.PDFDocument.create();
    for (let i = 0; i < cuts.length - 1; i++) {
      const y0 = cuts[i], y1 = cuts[i + 1]; if (y1 - y0 < 2) continue;
      const slice = document.createElement('canvas'); slice.width = canvas.width; slice.height = pageHpx;
      const sctx = slice.getContext('2d'); sctx.fillStyle = '#fff'; sctx.fillRect(0, 0, slice.width, slice.height);
      sctx.drawImage(canvas, 0, y0, canvas.width, y1 - y0, 0, 0, canvas.width, y1 - y0);
      const page = pdf.addPage([pw, ph]);
      page.drawImage(await pdf.embedJpg(await canvasBytes(slice, 'image/jpeg', 0.9)), { x: 0, y: 0, width: pw, height: ph });
      // invisible text for search/copy
      const k = pw / cssW;
      for (const w of words) {
        const top = w.y * scale; if (top < y0 || top >= y1) continue;
        const unit = await textWidth(pdf, w.text, 1, true); if (unit <= 0) continue;
        const fs = Math.min(Math.max(1, (w.w * k) / unit), w.h * k * 1.4);
        await drawRuns(pdf, page, w.text, w.x * k, ph - ((top - y0) / scale + w.h * 0.8) * k, fs, undefined, 0);
      }
    }
    progress?.(1);
    return pdf.save({ useObjectStreams: true });
  } finally { frame.remove(); }
}

/** Line fragments of visible text with their positions (css px). */
function collectText(doc) {
  const out = []; const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT); let n; let count = 0;
  const range = doc.createRange();
  while ((n = walker.nextNode()) && count < 30000) {
    const text = n.nodeValue; if (!text.trim()) continue;
    const re = /\S+/g; let m; let cur = null;
    while ((m = re.exec(text))) {
      count++;
      range.setStart(n, m.index); range.setEnd(n, m.index + m[0].length);
      const r = range.getBoundingClientRect(); if (!r.width) continue;
      if (cur && Math.abs(r.top - cur.y) < r.height * 0.5) { cur.text += ' ' + m[0]; cur.w = r.right - cur.x; }
      else { if (cur) out.push(cur); cur = { text: m[0], x: r.left, y: r.top, w: r.width, h: r.height }; }
    }
    if (cur) out.push(cur);
  }
  const sy = doc.defaultView.scrollY || 0;
  out.forEach((o) => { o.y += sy; });
  return out;
}

// ---------- Office → PDF ----------
export async function wordToPdf(file, progress) {
  const ext = file.name.split('.').pop().toLowerCase();
  let html;
  if (ext === 'docx') html = (await (await mammoth()).convertToHtml({ arrayBuffer: await file.arrayBuffer() })).value;
  else if (ext === 'doc') throw new Error('Old .doc files aren’t supported. Save the file as .docx and try again.');
  else if (ext === 'rtf') html = `<pre>${esc((await file.text()).replace(/\\par[d]?/g, '\n').replace(/\{\\\*[^{}]*\}/g, '').replace(/\\'[0-9a-f]{2}/gi, '').replace(/\\[a-z]+-?\d* ?/gi, '').replace(/[{}]/g, ''))}</pre>`;
  else html = (await file.text()).split(/\n{2,}/).map((p) => `<p>${esc(p).replace(/\n/g, '<br>')}</p>`).join('');
  return htmlToPdf(html || '<p></p>', { progress });
}

export async function excelToPdf(file, progress) {
  const X = await xlsx();
  const wb = X.read(await file.arrayBuffer(), { type: 'array', cellDates: true });
  const html = wb.SheetNames.map((n, i) => `<h2 style="margin-top:${i ? 28 : 0}px">${esc(n)}</h2>` + X.utils.sheet_to_html(wb.Sheets[n], { header: '', footer: '' }).replace(/<\/?(html|head|body|meta)[^>]*>/g, '')).join('');
  return htmlToPdf(html, { landscape: true, css: 'body{padding:32px}td{font-size:12px;white-space:nowrap}', progress });
}

export async function pptToPdf(file, progress) {
  const JSZip = await jszip(); const L = await pdfLib(); const h2c = await html2canvas();
  const zip = await JSZip.loadAsync(await file.arrayBuffer());
  const xml = async (p) => { const f = zip.file(p); return f ? new DOMParser().parseFromString(await f.async('text'), 'application/xml') : null; };
  const rels = async (p) => { const dir = p.substring(0, p.lastIndexOf('/')); const r = await xml(`${dir}/_rels/${p.split('/').pop()}.rels`); const m = {}; if (r) for (const e of r.getElementsByTagName('Relationship')) m[e.getAttribute('Id')] = resolve(dir, e.getAttribute('Target')); return m; };
  const pres = await xml('ppt/presentation.xml'); if (!pres) throw new Error('This is not a valid PowerPoint (.pptx) file');
  const sz = pres.getElementsByTagName('p:sldSz')[0]; const cx = +(sz?.getAttribute('cx') ?? 9144000), cy = +(sz?.getAttribute('cy') ?? 6858000);
  const prel = await rels('ppt/presentation.xml');
  const ids = [...pres.getElementsByTagName('p:sldId')].map((e) => e.getAttribute('r:id'));
  const pw = cx / 12700, ph = cy / 12700; const W = 960, k = W / cx; const H = Math.round(cy * k);
  const pdf = await L.PDFDocument.create();
  for (let s = 0; s < ids.length; s++) {
    const path = prel[ids[s]]; const doc = await xml(path); if (!doc) continue;
    const srels = await rels(path); let html = '';
    for (const sp of [...doc.getElementsByTagName('p:sp'), ...doc.getElementsByTagName('p:pic')]) {
      const off = sp.getElementsByTagName('a:off')[0], ext = sp.getElementsByTagName('a:ext')[0];
      const ph_ = sp.getElementsByTagName('p:ph')[0]; const isTitle = /title|ctrTitle/i.test(ph_?.getAttribute('type') ?? '');
      let x, y, w, h;
      if (off && ext) { x = +off.getAttribute('x') * k; y = +off.getAttribute('y') * k; w = +ext.getAttribute('cx') * k; h = +ext.getAttribute('cy') * k; }
      else if (isTitle) { x = W * 0.06; y = H * 0.05; w = W * 0.88; h = H * 0.17; } else { x = W * 0.06; y = H * 0.25; w = W * 0.88; h = H * 0.67; }
      const blip = sp.getElementsByTagName('a:blip')[0];
      if (blip) {
        const target = srels[blip.getAttribute('r:embed')]; const f = target && zip.file(target);
        if (f) html += `<img src="data:image/${target.split('.').pop()};base64,${await f.async('base64')}" style="position:absolute;left:${x}px;top:${y}px;width:${w}px;height:${h}px;object-fit:fill">`;
        continue;
      }
      const fill = [...sp.getElementsByTagName('p:spPr')].flatMap((e) => [...e.children]).find((e) => e.tagName === 'a:solidFill')?.getElementsByTagName('a:srgbClr')[0]?.getAttribute('val');
      const paras = [...sp.getElementsByTagName('a:p')].map((p) => {
        const algn = p.getElementsByTagName('a:pPr')[0]?.getAttribute('algn');
        const runs = [...p.childNodes].filter((n) => n.nodeName === 'a:r' || n.nodeName === 'a:fld' || n.nodeName === 'a:br').map((r) => {
          if (r.nodeName === 'a:br') return '<br>';
          const pr = r.getElementsByTagName('a:rPr')[0]; const sz2 = pr?.getAttribute('sz'); const col = pr?.getElementsByTagName('a:srgbClr')[0]?.getAttribute('val');
          const st = [sz2 ? `font-size:${(+sz2 / 100) * 12700 * k}px` : '', pr?.getAttribute('b') === '1' ? 'font-weight:700' : '', pr?.getAttribute('i') === '1' ? 'font-style:italic' : '', col ? `color:#${col}` : ''].join(';');
          return `<span style="${st}">${esc(r.getElementsByTagName('a:t')[0]?.textContent ?? '')}</span>`;
        }).join('');
        return `<div style="text-align:${algn === 'ctr' ? 'center' : algn === 'r' ? 'right' : 'left'};min-height:1em">${runs || '&nbsp;'}</div>`;
      }).join('');
      html += `<div style="position:absolute;left:${x}px;top:${y}px;width:${w}px;height:${h}px;${fill ? `background:#${fill};` : ''}font-size:${isTitle ? H * 0.075 : H * 0.042}px;${isTitle ? 'font-weight:700;' : ''}line-height:1.2;overflow:hidden;padding:4px">${paras}</div>`;
    }
    const frame = document.createElement('iframe'); frame.setAttribute('sandbox', 'allow-same-origin');
    frame.style.cssText = `position:fixed;left:-20000px;top:0;width:${W}px;height:${H}px;border:0`;
    document.body.appendChild(frame);
    await new Promise((res) => { frame.onload = res; frame.srcdoc = `<!doctype html><meta charset="utf-8"><body style="margin:0;width:${W}px;height:${H}px;position:relative;overflow:hidden;background:#fff;font-family:-apple-system,Roboto,'Noto Sans',sans-serif;color:#111">${html}</body>`; });
    const fd = frame.contentDocument; await Promise.all([...fd.images].map((im) => im.complete ? 0 : new Promise((r) => { im.onload = im.onerror = r; })));
    const words = collectText(fd);
    const c = await h2c(fd.body, { scale: 2, backgroundColor: '#ffffff', width: W, height: H, windowWidth: W, logging: false });
    frame.remove();
    const page = pdf.addPage([pw, ph]);
    page.drawImage(await pdf.embedJpg(await canvasBytes(c, 'image/jpeg', 0.9)), { x: 0, y: 0, width: pw, height: ph });
    const kk = pw / W;
    for (const w of words) { const unit = await textWidth(pdf, w.text, 1, true); if (unit > 0) await drawRuns(pdf, page, w.text, w.x * kk, ph - (w.y + w.h * 0.8) * kk, Math.min(Math.max(1, w.w * kk / unit), w.h * kk * 1.4), undefined, 0); }
    progress?.((s + 1) / ids.length);
  }
  if (!pdf.getPageCount()) pdf.addPage([pw, ph]);
  return pdf.save({ useObjectStreams: true });
}

function resolve(dir, target) {
  if (target.startsWith('/')) return target.slice(1);
  const parts = dir ? dir.split('/') : [];
  for (const seg of target.split('/')) { if (seg === '..') parts.pop(); else if (seg && seg !== '.') parts.push(seg); }
  return parts.join('/');
}

// ---------- PDF → Office ----------
const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
const PKG = 'http://schemas.openxmlformats.org/package/2006/relationships';
const RNS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

export async function pdfToWord(pdf, exactLook, progress) {
  const JSZip = await jszip(); const body = []; const media = [];
  const para = (text, bold, size) => `<w:p><w:r>${bold || size ? `<w:rPr>${bold ? '<w:b/>' : ''}${size ? `<w:sz w:val="${Math.round(size * 2)}"/>` : ''}</w:rPr>` : ''}<w:t xml:space="preserve">${esc(text)}</w:t></w:r></w:p>`;
  const brk = '<w:p><w:r><w:br w:type="page"/></w:r></w:p>';
  for (let i = 0; i < pdf.numPages; i++) {
    if (i) body.push(brk);
    if (exactLook) {
      const s = await pageSize(pdf, i); const c = await renderPage(pdf, i, Math.round(s.w / 72 * 150));
      media.push(await canvasBytes(c, 'image/jpeg', 0.85)); const n = media.length;
      const f = Math.min(555 / s.w, 798 / s.h); const cx = Math.round(s.w * f * 12700), cy = Math.round(s.h * f * 12700);
      body.push(`<w:p><w:pPr><w:spacing w:before="0" w:after="0"/><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${cx}" cy="${cy}"/><wp:docPr id="${n}" name="Page ${n}"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${n}" name="image${n}.jpeg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdImg${n}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>`);
    } else {
      const { lines } = await textLines(pdf, i);
      if (!lines.length) body.push(para(`(No text found on page ${i + 1}. If this is a scan, run OCR first.)`));
      let prev = null, buf = '', bs = 11, bb = false;
      const flush = () => { if (buf) body.push(para(buf, bb, Math.abs(bs - 11) > 1 ? bs : null)); buf = ''; };
      for (const l of lines) {
        const np = !prev || (l.top - prev.bottom) > Math.max(prev.size, l.size) * 0.9 || Math.abs(l.size - prev.size) > 1.5 || l.bold !== prev.bold;
        if (np) { flush(); bs = l.size; bb = l.bold; } else if (buf) buf = buf.endsWith('-') ? buf.slice(0, -1) : buf + ' ';
        buf += l.text; prev = l;
      }
      flush();
    }
    progress?.((i + 1) / pdf.numPages);
  }
  const m = exactLook ? 400 : 1134; const z = new JSZip();
  z.file('[Content_Types].xml', XML + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>');
  z.file('_rels/.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/officeDocument" Target="word/document.xml"/></Relationships>`);
  z.file('word/_rels/document.xml.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rIdStyles" Type="${RNS}/styles" Target="styles.xml"/>${media.map((_, i) => `<Relationship Id="rIdImg${i + 1}" Type="${RNS}/image" Target="media/image${i + 1}.jpeg"/>`).join('')}</Relationships>`);
  z.file('word/styles.xml', XML + '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>');
  media.forEach((b, i) => z.file(`word/media/image${i + 1}.jpeg`, b));
  z.file('word/document.xml', XML + `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="${RNS}" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>${body.join('')}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="${m}" w:right="${m}" w:bottom="${m}" w:left="${m}" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>`);
  return z.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
}

export async function pdfToExcel(pdf, onePerPage, progress) {
  const sheets = []; const all = [];
  for (let i = 0; i < pdf.numPages; i++) {
    const rows = tableRows((await textLines(pdf, i)).lines);
    if (onePerPage) sheets.push([`Page ${i + 1}`, rows]); else { all.push(...rows); if (rows.length) all.push([]); }
    progress?.((i + 1) / pdf.numPages);
  }
  if (!onePerPage) sheets.push(['Data', all]);
  return writeXlsx(sheets);
}

export async function writeXlsx(sheets) {
  const JSZip = await jszip(); const z = new JSZip();
  const col = (i) => { let n = i + 1, s = ''; while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26); } return s; };
  z.file('[Content_Types].xml', XML + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')}</Types>`);
  z.file('_rels/.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/officeDocument" Target="xl/workbook.xml"/></Relationships>`);
  z.file('xl/workbook.xml', XML + `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="${RNS}"><sheets>${sheets.map(([n], i) => `<sheet name="${esc(n.replace(/[\[\]:*?/\\]/g, ' ').slice(0, 31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`);
  z.file('xl/_rels/workbook.xml.rels', XML + `<Relationships xmlns="${PKG}">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${RNS}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rIdStyles" Type="${RNS}/styles" Target="styles.xml"/></Relationships>`);
  z.file('xl/styles.xml', XML + '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>');
  sheets.forEach(([, rows], si) => {
    const out = rows.map((row, ri) => `<row r="${ri + 1}">${row.map((v, ci) => {
      if (v === '' || v == null) return '';
      const ref = col(ci) + (ri + 1); const num = String(v).replace(/,/g, '');
      return /^-?\d+(\.\d+)?$/.test(num) && num.length < 15 ? `<c r="${ref}"><v>${num}</v></c>` : `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`;
    }).join('')}</row>`).join('');
    z.file(`xl/worksheets/sheet${si + 1}.xml`, XML + `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${out}</sheetData></worksheet>`);
  });
  return z.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
}

export async function pdfToPowerPoint(pdf, editable, progress) {
  const JSZip = await jszip(); const z = new JSZip(); const E = 12700;
  const first = await pageSize(pdf, 0); const fit = Math.min(1, 4032 / Math.max(first.w, first.h));
  const cx = Math.round(first.w * fit * E), cy = Math.round(first.h * fit * E);
  const ns = `xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="${RNS}" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"`;
  const grp = '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>';
  const n = pdf.numPages; let media = 0;
  for (let i = 0; i < n; i++) {
    const s = await pageSize(pdf, i); const k = Math.min(cx / (s.w * E), cy / (s.h * E));
    const pw = Math.round(s.w * E * k), ph = Math.round(s.h * E * k), ox = Math.round((cx - pw) / 2), oy = Math.round((cy - ph) / 2);
    let shapes = ''; let rels = `<Relationship Id="rId1" Type="${RNS}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>`; let id = 2;
    if (editable) {
      for (const l of (await textLines(pdf, i)).lines) {
        const kk = E * k; const w = Math.max(10, (l.chars.at(-1)?.r ?? l.x + 10) - l.x);
        shapes += `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="Text ${id++}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="${Math.round(ox + l.x * kk)}" y="${Math.round(oy + l.top * kk)}"/><a:ext cx="${Math.round(w * kk * 1.08)}" cy="${Math.max(E, Math.round((l.bottom - l.top) * kk * 1.3))}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="none" lIns="0" tIns="0" rIns="0" bIns="0"><a:noAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="${Math.max(100, Math.round(l.size * k * 100))}"${l.bold ? ' b="1"' : ''} dirty="0"/><a:t>${esc(l.text)}</a:t></a:r></a:p></p:txBody></p:sp>`;
      }
    } else {
      const c = await renderPage(pdf, i, Math.round(s.w / 72 * 160)); media++;
      z.file(`ppt/media/image${media}.jpeg`, await canvasBytes(c, 'image/jpeg', 0.85));
      rels += `<Relationship Id="rIdImg${media}" Type="${RNS}/image" Target="../media/image${media}.jpeg"/>`;
      shapes = `<p:pic><p:nvPicPr><p:cNvPr id="2" name="Page ${i + 1}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdImg${media}"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="${ox}" y="${oy}"/><a:ext cx="${pw}" cy="${ph}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>`;
    }
    z.file(`ppt/slides/slide${i + 1}.xml`, XML + `<p:sld ${ns}><p:cSld><p:spTree>${grp}${shapes}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>`);
    z.file(`ppt/slides/_rels/slide${i + 1}.xml.rels`, XML + `<Relationships xmlns="${PKG}">${rels}</Relationships>`);
    progress?.((i + 1) / n);
  }
  const idx = [...Array(n).keys()];
  z.file('[Content_Types].xml', XML + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>${idx.map((i) => `<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>`).join('')}</Types>`);
  z.file('_rels/.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/officeDocument" Target="ppt/presentation.xml"/></Relationships>`);
  z.file('ppt/presentation.xml', XML + `<p:presentation ${ns} saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>${idx.map((i) => `<p:sldId id="${256 + i}" r:id="rId${i + 3}"/>`).join('')}</p:sldIdLst><p:sldSz cx="${cx}" cy="${cy}"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr></p:defaultTextStyle></p:presentation>`);
  z.file('ppt/_rels/presentation.xml.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="${RNS}/theme" Target="theme/theme1.xml"/>${idx.map((i) => `<Relationship Id="rId${i + 3}" Type="${RNS}/slide" Target="slides/slide${i + 1}.xml"/>`).join('')}</Relationships>`);
  z.file('ppt/slideMasters/slideMaster1.xml', XML + `<p:sldMaster ${ns}><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>${grp}</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>`);
  z.file('ppt/slideMasters/_rels/slideMaster1.xml.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="${RNS}/theme" Target="../theme/theme1.xml"/></Relationships>`);
  z.file('ppt/slideLayouts/slideLayout1.xml', XML + `<p:sldLayout ${ns} type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>${grp}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>`);
  z.file('ppt/slideLayouts/_rels/slideLayout1.xml.rels', XML + `<Relationships xmlns="${PKG}"><Relationship Id="rId1" Type="${RNS}/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>`);
  const sf = '<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>';
  z.file('ppt/theme/theme1.xml', XML + `<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme"><a:themeElements><a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme><a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="Office"><a:fillStyleLst>${sf.repeat(3)}</a:fillStyleLst><a:lnStyleLst>${'<a:ln w="6350" cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/><a:miter lim="800000"/></a:ln>'.repeat(3)}</a:lnStyleLst><a:effectStyleLst>${'<a:effectStyle><a:effectLst/></a:effectStyle>'.repeat(3)}</a:effectStyleLst><a:bgFillStyleLst>${sf.repeat(3)}</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>`);
  return z.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
}

export { paragraphs };
