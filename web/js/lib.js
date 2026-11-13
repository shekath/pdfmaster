// Lazy loaders for the vendored libraries (all served from this site, so the app works offline).
const loaded = new Map();
export const base = new URL('../', import.meta.url).href;

function script(src) {
  if (!loaded.has(src)) loaded.set(src, new Promise((res, rej) => {
    const s = document.createElement('script'); s.src = base + src; s.onload = res; s.onerror = () => rej(new Error('Could not load ' + src)); document.head.appendChild(s);
  }));
  return loaded.get(src);
}

export async function pdfLib() { await script('vendor/pdf-lib.min.js'); await script('vendor/regenerator-runtime.js'); await script('vendor/fontkit.umd.min.js'); return window.PDFLib; }
export async function fontkit() { await pdfLib(); return window.fontkit; }
export async function jszip() { await script('vendor/jszip.min.js'); return window.JSZip; }
export async function mammoth() { await script('vendor/mammoth.browser.min.js'); return window.mammoth; }
export async function xlsx() { await script('vendor/xlsx.full.min.js'); return window.XLSX; }
export async function html2canvas() { await script('vendor/html2canvas.min.js'); return window.html2canvas; }
export async function tesseract() { await script('vendor/tesseract/tesseract.min.js'); return window.Tesseract; }

let pdfjsP;
export function pdfjs() {
  pdfjsP ??= import(base + 'vendor/pdfjs/pdf.min.mjs').then((m) => { m.GlobalWorkerOptions.workerSrc = base + 'vendor/pdfjs/pdf.worker.min.mjs'; return m; });
  return pdfjsP;
}

let qpdfP;
/** Runs qpdf with args; input/output via its virtual file system. Returns { code, out?: Uint8Array, log }. */
export async function qpdf(args, inputs, outputName) {
  await script('vendor/qpdf.js');
  const log = [];
  // a fresh module per call keeps state clean (qpdf calls exit())
  const q = await window.Module({ noInitialRun: true, locateFile: () => base + 'vendor/qpdf.wasm', print: (t) => log.push(t), printErr: (t) => log.push(t) });
  for (const [name, bytes] of Object.entries(inputs)) q.FS.writeFile('/' + name, bytes);
  let code;
  // this qpdf build reports errors through console.error/warn; capture them while it runs
  const ce = console.error, cw = console.warn, cl = console.log;
  console.error = console.warn = console.log = (...a) => log.push(a.join(' '));
  try { code = q.callMain(args); } catch (e) { code = typeof e?.status === 'number' ? e.status : 2; log.push(String(e?.message ?? e)); }
  finally { console.error = ce; console.warn = cw; console.log = cl; }
  let out;
  if (outputName) { try { out = q.FS.readFile('/' + outputName); } catch { /* none */ } }
  return { code, out, log: log.join('\n') };
}

const fontCache = new Map();
export async function fontBytes(name) {
  if (!fontCache.has(name)) fontCache.set(name, fetch(base + 'fonts/' + name + '-Regular.ttf').then((r) => r.arrayBuffer()));
  return fontCache.get(name);
}
export async function assetBytes(path) { return (await fetch(base + path)).arrayBuffer(); }
