// Summarise (offline, extractive), compare (LCS line diff) and translate (MyMemory online service).
import { esc } from './office.js';

const STOP = new Set('a about above after again against all am an and any are as at be because been before being below between both but by can could did do does doing down during each few for from further had has have having he her here hers herself him himself his how i if in into is it its itself just me more most my myself no nor not now of off on once only or other our ours ourselves out over own same she should so some such than that the their theirs them themselves then there these they this those through to too under until up very was we were what when where which while who whom why will with would you your yours yourself yourselves also may might must shall one two us via per page'.split(' '));

export function summarize(text, length) {
  const clean = text.replace(/-\n(\p{Ll})/gu, '$1').replace(/\s+/g, ' ').trim();
  if (clean.length < 40) throw new Error('Not enough text to summarise. If this is a scan, run OCR first.');
  const seg = typeof Intl.Segmenter === 'function' ? [...new Intl.Segmenter(undefined, { granularity: 'sentence' }).segment(clean)].map((s) => s.segment.trim()) : clean.split(/(?<=[.!?।])\s+/);
  const sentences = seg.filter((s) => s.length >= 25 && s.length <= 600);
  const words = clean.toLowerCase().match(/\p{L}[\p{L}\p{M}\p{N}'-]+/gu) ?? [];
  const freq = new Map(); words.filter((w) => w.length > 2 && !STOP.has(w)).forEach((w) => freq.set(w, (freq.get(w) ?? 0) + 1));
  const maxF = Math.max(1, ...freq.values());
  const keywords = [...freq.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10).map(([w]) => w);
  if (!sentences.length) return { sentences: [clean.slice(0, 600)], keywords, wordCount: words.length };
  const scores = sentences.map((s, i) => {
    const ws = (s.toLowerCase().match(/\p{L}[\p{L}\p{M}\p{N}'-]+/gu) ?? []).filter((w) => !STOP.has(w) && w.length > 2);
    const base = ws.length ? ws.reduce((a, w) => a + (freq.get(w) ?? 0) / maxF, 0) / Math.sqrt(ws.length) : 0;
    return base * (1 + 0.4 * (1 - Math.min(1, i / Math.max(1, sentences.length * 0.2)))) * (1 + 0.1 * Math.log(1 + ws.length));
  });
  const ratio = { short: 0.08, long: 0.3 }[length] ?? 0.16, maxN = { short: 5, long: 25 }[length] ?? 12;
  const n = Math.max(Math.min(3, sentences.length), Math.min(maxN, Math.round(sentences.length * ratio)));
  const chosen = scores.map((s, i) => [s, i]).sort((a, b) => b[0] - a[0]).slice(0, n).map(([, i]) => i).sort((a, b) => a - b).map((i) => sentences[i]);
  return { sentences: chosen, keywords, wordCount: words.length };
}

export function summaryHtml(title, s) {
  return `<h1>Summary of ${esc(title)}</h1><p style="color:#666">${s.wordCount} words in the original · ${s.sentences.length} key sentences</p><ul>${s.sentences.map((x) => `<li style="margin-bottom:8px">${esc(x)}</li>`).join('')}</ul>${s.keywords.length ? `<h3>Key terms</h3><p>${s.keywords.map(esc).join(' · ')}</p>` : ''}`;
}

/** a, b: arrays of { text, page } */
export function diff(a, b) {
  const norm = (s) => s.replace(/\s+/g, ' ').trim();
  const run = (A, B) => {
    const n = A.length, m = B.length; const dp = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i][j] = norm(A[i].text) === norm(B[j].text) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    const out = []; let i = 0, j = 0;
    while (i < n && j < m) {
      if (norm(A[i].text) === norm(B[j].text)) { out.push({ kind: 'same', ...B[j] }); i++; j++; }
      else if (dp[i + 1][j] >= dp[i][j + 1]) out.push({ kind: 'rem', ...A[i++] });
      else out.push({ kind: 'add', ...B[j++] });
    }
    while (i < n) out.push({ kind: 'rem', ...A[i++] }); while (j < m) out.push({ kind: 'add', ...B[j++] });
    return out;
  };
  let lines;
  if (a.length * b.length > 6e6) { lines = []; const pages = [...new Set([...a, ...b].map((x) => x.page))].sort((x, y) => x - y); for (const p of pages) lines.push(...run(a.filter((x) => x.page === p), b.filter((x) => x.page === p))); }
  else lines = run(a, b);
  return { lines, added: lines.filter((l) => l.kind === 'add').length, removed: lines.filter((l) => l.kind === 'rem').length };
}

export function diffHtml(nameA, nameB, r) {
  const rows = r.lines.filter((l) => l.kind !== 'same').map((l) => `<div style="background:${l.kind === 'add' ? '#d6f5de' : '#fddede'};padding:4px 8px;margin:3px 0;border-radius:4px;font-size:13px">${l.kind === 'add' ? '+' : '−'} ${esc(l.text)} <span style="color:#777">(p. ${l.page})</span></div>`).join('');
  return `<h1>Comparison report</h1><p style="color:#555">Original: ${esc(nameA)}<br>Changed: ${esc(nameB)}<br>${r.removed} line(s) removed · ${r.added} line(s) added</p>${rows || '<p>No text differences found.</p>'}`;
}

/** Translates text with the MyMemory API in ≤450-character chunks. */
export async function translate(text, from, to, status) {
  const chunks = []; let cur = '';
  for (const part of text.split(/(?<=[.!?।\n])\s+/)) {
    if ((cur + ' ' + part).length > 450 && cur) { chunks.push(cur); cur = ''; }
    if (part.length > 450) { for (let i = 0; i < part.length; i += 450) chunks.push(part.slice(i, i + 450)); } else cur = cur ? cur + ' ' + part : part;
  }
  if (cur) chunks.push(cur);
  const out = [];
  for (let i = 0; i < chunks.length; i++) {
    status?.((i) / chunks.length, `Translating… ${Math.round(i * 100 / chunks.length)}%`);
    const url = `https://api.mymemory.translated.net/get?q=${encodeURIComponent(chunks[i])}&langpair=${from === 'auto' ? 'Autodetect' : from}|${to}`;
    let res;
    try { res = await (await fetch(url)).json(); } catch { throw new Error('Translation needs an internet connection.'); }
    if (res.responseStatus !== 200 && res.responseStatus !== '200') throw new Error(`The translation service refused the request: ${res.responseDetails || res.responseStatus}`);
    out.push(res.responseData.translatedText);
  }
  return out.join(' ');
}
