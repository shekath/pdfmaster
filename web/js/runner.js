// Runs a tool on the chosen files. Returns { outputs:[{name, blob}], message, text?, diff? }.
import * as P from './pdfops.js';
import * as O from './office.js';
import { openPdf, renderPage, pageSize, textLines, canvasBytes, paragraphs } from './render.js';
import { openOcr, langName } from './ocr.js';
import { summarize, summaryHtml, diff, diffHtml, translate } from './smart.js';
import { jszip } from './lib.js';
import { mimeOf } from './ui.js';
import { TRANSLATE_LANGS } from './tools.js';

const bytesOf = async (f) => new Uint8Array(await f.arrayBuffer());
const baseName = (f) => (f?.name ?? 'document').replace(/\.[^.]+$/, '') || 'document';
const out = (name, data) => ({ name, blob: data instanceof Blob ? data : new Blob([data], { type: mimeOf(name) }) });

async function zipIfMany(list, zipName, msg) {
  if (list.length <= 1) return { outputs: list, message: msg };
  const JSZip = await jszip(); const z = new JSZip(); const used = new Set();
  for (const o of list) { let n = o.name, k = 2; while (used.has(n)) n = o.name.replace(/(\.[^.]+)$/, ` (${k++})$1`); used.add(n); z.file(n, await o.blob.arrayBuffer()); }
  return { outputs: [out(zipName + '.zip', await z.generateAsync({ type: 'uint8array' }))], message: msg + ' Packed into a ZIP file.' };
}

export async function run(tool, files, o, progress) {
  const f = files[0]; const base = baseName(f); const p = (label) => (x) => progress(x, label);
  switch (tool.id) {
    case 'merge': return { outputs: [out('merged.pdf', await P.merge(await Promise.all(files.map(bytesOf)), p('Merging…')))], message: `Merged ${files.length} files into one PDF.` };
    case 'split': {
      const bytes = await bytesOf(f); const n = await P.pageCount(bytes); let groups;
      if (o.mode === 'every') groups = [...Array(n).keys()].map((i) => [i]);
      else if (o.mode === 'fixed') { const k = parseInt(o.n, 10); if (!(k > 0)) throw new Error('Enter how many pages each file should have'); groups = []; for (let i = 0; i < n; i += k) groups.push([...Array(Math.min(k, n - i)).keys()].map((j) => i + j)); }
      else groups = P.parseRanges(o.ranges ?? '', n).map(([a, b]) => Array.from({ length: b - a + 1 }, (_, j) => a - 1 + j));
      const res = await P.extractGroups(bytes, groups, p('Splitting…'));
      return zipIfMany(res.map((r, i) => out(`${base}_${groups[i].length === 1 ? 'page_' + (groups[i][0] + 1) : `pages_${groups[i][0] + 1}-${groups[i].at(-1) + 1}`}.pdf`, r)), base + '_split', `Split into ${res.length} files.`);
    }
    case 'remove': {
      if (!o.pages?.trim()) throw new Error('Enter the pages to remove');
      const bytes = await bytesOf(f); const n = await P.pageCount(bytes); const rm = P.pageSet(o.pages, n);
      const keep = [...Array(n).keys()].filter((i) => !rm.has(i)); if (!keep.length) throw new Error('You can’t remove every page');
      return { outputs: [out(base + '_edited.pdf', (await P.extractGroups(bytes, [keep]))[0])], message: `Removed ${rm.size} page(s). ${keep.length} left.` };
    }
    case 'extract': {
      if (!o.pages?.trim()) throw new Error('Enter the pages to extract');
      const bytes = await bytesOf(f); const n = await P.pageCount(bytes);
      const sel = [...new Set(P.parseRanges(o.pages, n).flatMap(([a, b]) => Array.from({ length: b - a + 1 }, (_, j) => a - 1 + j)))];
      if (o.output === 'separate') return zipIfMany((await P.extractGroups(bytes, sel.map((i) => [i]), p('Extracting…'))).map((r, k) => out(`${base}_page_${sel[k] + 1}.pdf`, r)), base + '_pages', `Extracted ${sel.length} page(s).`);
      return { outputs: [out(base + '_extracted.pdf', (await P.extractGroups(bytes, [sel]))[0])], message: `Extracted ${sel.length} page(s) into a new PDF.` };
    }
    case 'scan': {
      let pdf = await P.imagesToPdf(files, { size: 'fit', margin: 0, enhance: o.enhance }, p('Building PDF…'));
      if (o.ocr) pdf = await ocrPdf(pdf, o.lang, progress);
      const name = 'scan_' + new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '') + '.pdf';
      return { outputs: [out(name, pdf)], message: `Scanned ${files.length} page(s)${o.ocr ? ' with searchable text' : ''}.` };
    }
    case 'compress': {
      const bytes = await bytesOf(f); const r = await P.compress(bytes, o.level, p('Compressing…'));
      const msg = r.smaller ? `Reduced from ${hs(bytes.length)} to ${hs(r.bytes.length)} (−${100 - Math.round(r.bytes.length * 100 / bytes.length)}%).` : 'This PDF is already well optimised; it couldn’t be made smaller.';
      return { outputs: [out(base + '_compressed.pdf', r.bytes)], message: msg };
    }
    case 'repair': { const r = await P.repair(await bytesOf(f), p('Repairing…')); return { outputs: [out(base + '_repaired.pdf', r.bytes)], message: r.msg }; }
    case 'ocr': {
      const bytes = await bytesOf(f); const res = await ocrPdf(bytes, o.lang, progress, true);
      const outs = []; if (o.output !== 'txt') outs.push(out(base + '_ocr.pdf', res.pdf)); if (o.output !== 'pdf') outs.push(out(base + '_text.txt', res.text));
      return { outputs: outs, message: res.lines ? `Recognised ${res.lines} lines of ${langName(o.lang)} text.` : 'No text was recognised. Check the document language.' };
    }
    case 'jpg2pdf': return { outputs: [out((files.length === 1 ? base : 'images') + '.pdf', await P.imagesToPdf(files, o, p('Creating PDF…')))], message: `Created a ${files.length}-page PDF.` };
    case 'word2pdf': case 'excel2pdf': case 'ppt2pdf': {
      const fn = { word2pdf: O.wordToPdf, excel2pdf: O.excelToPdf, ppt2pdf: O.pptToPdf }[tool.id]; const outs = [];
      for (let i = 0; i < files.length; i++) outs.push(out(baseName(files[i]) + '.pdf', await fn(files[i], (x) => progress((i + x) / files.length, 'Converting…'))));
      return { outputs: outs, message: outs.length === 1 ? 'Converted to PDF.' : `Converted ${outs.length} files to PDF.` };
    }
    case 'html2pdf': {
      let html, name;
      if (f) { html = await f.text(); name = base; }
      else {
        let url = (o.url ?? '').trim(); if (!url) throw new Error('Enter a web address or choose an HTML file');
        if (!/^https?:\/\//.test(url)) url = 'https://' + url;
        progress(-1, 'Loading page…');
        try { const r = await fetch(url); html = await r.text(); } catch { throw new Error('This website doesn’t allow other sites to load it. In Safari, open the page, tap Share → Options → “Web Archive” or save as HTML, then choose that file here.'); }
        html = html.replace(/<head([^>]*)>/i, `<head$1><base href="${url}">`); name = new URL(url).hostname;
      }
      html = html.replace(/<script[\s\S]*?<\/script>/gi, '');
      return { outputs: [out(name + '.pdf', await O.htmlToPdf(html, { paper: o.paper, progress: p('Rendering…') }))], message: 'Saved the page as a PDF.' };
    }
    case 'pdf2jpg': {
      const bytes = await bytesOf(f);
      if (o.mode === 'extract') {
        const imgs = await P.extractImages(bytes); if (!imgs.length) throw new Error('No images found in this PDF.');
        return zipIfMany(imgs.map((im, i) => out(`${base}_image_${i + 1}.${im.ext}`, im.bytes)), base + '_images', `Extracted ${imgs.length} image(s).`);
      }
      const pdf = await openPdf(bytes); const res = []; const png = o.format === 'png';
      for (let i = 0; i < pdf.numPages; i++) {
        const s = await pageSize(pdf, i); const c = await renderPage(pdf, i, Math.round(s.w / 72 * (+o.quality || 150)));
        res.push(out(`${base}_page_${i + 1}.${png ? 'png' : 'jpg'}`, await canvasBytes(c, png ? 'image/png' : 'image/jpeg', 0.92)));
        progress((i + 1) / pdf.numPages, 'Rendering pages…');
      }
      return zipIfMany(res, base + '_images', `Created ${res.length} image(s).`);
    }
    case 'pdf2word': return { outputs: [out(base + '.docx', await O.pdfToWord(await openPdf(await bytesOf(f)), o.mode === 'layout', p('Converting to Word…')))], message: 'Converted to Word.' };
    case 'pdf2ppt': return { outputs: [out(base + '.pptx', await O.pdfToPowerPoint(await openPdf(await bytesOf(f)), o.mode === 'text', p('Converting to PowerPoint…')))], message: 'Converted to PowerPoint.' };
    case 'pdf2excel': return { outputs: [out(base + '.xlsx', await O.pdfToExcel(await openPdf(await bytesOf(f)), o.sheets !== 'single', p('Converting to Excel…')))], message: 'Converted to Excel. Check the columns, as complex tables may need tidying.' };
    case 'pdfa': return { outputs: [out(base + '_pdfa.pdf', await P.pdfa(await bytesOf(f), +o.part || 2, base))], message: 'Added PDF/A identification, metadata and an sRGB output intent. For guaranteed compliance, check it with a PDF/A validator.' };
    case 'rotate': { const b = await bytesOf(f); const set = P.pageSet(o.pages, await P.pageCount(b)); return { outputs: [out(base + '_rotated.pdf', await P.rotate(b, +o.angle, set))], message: `Rotated ${set ? set.size : 'all'} page(s).` }; }
    case 'numbers': return { outputs: [out(base + '_numbered.pdf', await P.pageNumbers(await bytesOf(f), { position: o.position, format: o.format, start: parseInt(o.start, 10) || 1, size: +o.size, skipFirst: o.skipFirst }))], message: 'Added page numbers.' };
    case 'watermark': {
      const b = await bytesOf(f); const set = P.pageSet(o.pages, await P.pageCount(b)); let res;
      if (o.type === 'image') { if (!o.image) throw new Error('Choose an image for the watermark'); const ib = await bytesOf(o.image); res = await P.watermarkImage(b, ib, /png$/i.test(o.image.type || o.image.name), { scale: o.scale / 100, opacity: o.opacity / 100, tile: o.layout === 'tile', pages: set }); }
      else { if (!o.text?.trim()) throw new Error('Enter the watermark text'); res = await P.watermarkText(b, { text: o.text, size: +o.size, opacity: o.opacity / 100, angle: +o.angle, color: o.color, tile: o.layout === 'tile', pages: set }); }
      return { outputs: [out(base + '_watermarked.pdf', res)], message: 'Added the watermark.' };
    }
    case 'unlock': return { outputs: [out(base + '_unlocked.pdf', await P.unlock(await bytesOf(f), o.password ?? ''))], message: 'Password removed.' };
    case 'protect': {
      if (!o.password) throw new Error('Enter a password'); if (o.password !== o.confirm) throw new Error('The passwords don’t match');
      return { outputs: [out(base + '_protected.pdf', await P.protect(await bytesOf(f), o.password, { print: o.print, copy: o.copy, modify: o.modify }))], message: 'Protected with AES-256 encryption. Keep your password safe: it can’t be recovered.' };
    }
    case 'compare': {
      if (files.length !== 2) throw new Error('Choose exactly two PDF files'); progress(-1, 'Comparing…');
      const lines = async (file) => { const pdf = await openPdf(await bytesOf(file)); const res = []; for (let i = 0; i < pdf.numPages; i++) (await textLines(pdf, i)).lines.forEach((l) => l.text.trim() && res.push({ text: l.text.trim(), page: i + 1 })); return res; };
      const r = diff(await lines(files[0]), await lines(files[1]));
      const report = await O.htmlToPdf(diffHtml(files[0].name, files[1].name, r));
      return { outputs: [out('comparison_report.pdf', report)], message: r.added + r.removed ? `${r.removed} line(s) removed and ${r.added} line(s) added.` : 'No text differences found.', diff: r };
    }
    case 'summarize': {
      progress(-1, 'Reading…'); const pdf = await openPdf(await bytesOf(f)); let text = '';
      for (let i = 0; i < pdf.numPages; i++) text += paragraphs((await textLines(pdf, i)).lines).join('\n') + '\n';
      const s = summarize(text, o.length);
      return { outputs: [out(base + '_summary.pdf', await O.htmlToPdf(summaryHtml(f.name, s)))], message: `Picked the ${s.sentences.length} most important sentences from ${s.wordCount} words.`, text: s.sentences.map((x) => '• ' + x).join('\n\n') + (s.keywords.length ? '\n\nKey terms: ' + s.keywords.join(', ') : '') };
    }
    case 'translate': {
      const pdf = await openPdf(await bytesOf(f)); const pages = [];
      for (let i = 0; i < pdf.numPages; i++) pages.push(paragraphs((await textLines(pdf, i)).lines));
      if (!pages.flat().join('').trim()) throw new Error('No text found. If this is a scanned document, run OCR first.');
      let html = ''; const total = pages.flat().length; let done = 0;
      for (const [i, paras] of pages.entries()) {
        if (i) html += '<div style="page-break-before:always;height:24px"></div>';
        for (const para of paras) { html += `<p>${O.esc(await translate(para, o.source, o.target))}</p>`; done++; progress(done / total, `Translating… ${Math.round(done * 100 / total)}%`); }
      }
      const name = TRANSLATE_LANGS.find(([c]) => c === o.target)?.[1] ?? o.target;
      return { outputs: [out(`${base}_${o.target}.pdf`, await O.htmlToPdf(html))], message: `Translated to ${name}. Layout is simplified to flowing text.` };
    }
    default: throw new Error('Unknown tool');
  }
}

const hs = (n) => n < 1048576 ? Math.round(n / 1024) + ' KB' : (n / 1048576).toFixed(1) + ' MB';

/** OCR every page and add an invisible text layer. */
export async function ocrPdf(bytes, lang, progress, withText = false) {
  const pdf = await openPdf(bytes); const ocr = await openOcr(lang, (s) => progress(-1, s));
  const pagesText = []; let all = ''; let count = 0;
  try {
    for (let i = 0; i < pdf.numPages; i++) {
      progress(i / pdf.numPages, `Reading ${langName(lang)} text… page ${i + 1} of ${pdf.numPages}`);
      const s = await pageSize(pdf, i); const c = await renderPage(pdf, i, Math.min(3000, Math.max(1600, Math.round(s.w * 4))));
      const r = await ocr.lines(c); pagesText.push({ index: i, lines: r.lines }); count += r.lines.length; all += (i ? '\n\n' : '') + r.lines.map((l) => l.text).join('\n');
    }
  } finally { await ocr.close(); }
  progress(0.98, 'Saving…');
  const out2 = await P.addTextLayer(bytes, pagesText);
  return withText ? { pdf: out2, text: all, lines: count } : out2;
}
