// OCR with Tesseract.js (WebAssembly). Language data downloads once per language and is then cached for offline use.
import { tesseract, base } from './lib.js';
import { OCR_LANGS } from './tools.js';

export const langName = (code) => (OCR_LANGS.find(([c]) => c === code)?.[1] ?? code).split(' ·')[0];

/** Opens an OCR worker for `lang` (+English for mixed text). */
export async function openOcr(lang, status) {
  const T = await tesseract();
  const langs = lang === 'eng' ? 'eng' : `${lang}+eng`;
  let loading = true;
  const worker = await T.createWorker(langs, 1, {
    workerPath: base + 'vendor/tesseract/worker.min.js',
    corePath: base + 'vendor/tesseract/',
    workerBlobURL: false,
    logger: (m) => {
      if (loading && /loading language|loading lang/i.test(m.status)) status?.(`Downloading ${langName(lang)} language data (one time)… ${Math.round((m.progress || 0) * 100)}%`);
      else if (/recognizing/i.test(m.status)) status?.(`Reading ${langName(lang)} text… ${Math.round((m.progress || 0) * 100)}%`);
    },
  });
  loading = false;
  return {
    /** Returns lines with normalised boxes {text,l,t,r,b}. */
    async lines(canvas) {
      const { data } = await worker.recognize(canvas, {}, { blocks: true, text: true });
      const out = [];
      for (const b of data.blocks ?? []) for (const p of b.paragraphs ?? []) for (const l of p.lines ?? []) {
        const text = (l.text ?? '').replace(/[‌‍](?=\s|$)/g, '').trim();
        if (!text || (l.confidence ?? 100) < 20) continue;
        const { x0, y0, x1, y1 } = l.bbox;
        out.push({ text, l: x0 / canvas.width, t: y0 / canvas.height, r: x1 / canvas.width, b: y1 / canvas.height });
      }
      return { lines: out, text: data.text ?? out.map((o) => o.text).join('\n') };
    },
    close: () => worker.terminate(),
  };
}
